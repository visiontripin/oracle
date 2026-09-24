"""Static validation + restricted execution of plugin code.

Security model (defense in depth):
1. AST static analysis — deny dangerous imports/names/attributes BEFORE save.
2. Restricted builtins at exec time (no open/exec/eval/__import__ etc.).
3. No direct aiogram objects are exposed — plugins get a minimal `ctx` API.
4. Execution timeout via asyncio.wait_for.
5. (Production hardening) run handlers in a separate process — see
   `run_isolated` docstring; default in-process mode is safe for trusted admins
   but process isolation is recommended for untrusted code.

If validation fails, the plugin is NOT saved and the API returns 422 with
the list of violations. Rule-based (codeless) triggers never execute Python
at all and are always preferred for simple plugins.
"""
from __future__ import annotations

import ast
import asyncio
import math
import re
from dataclasses import dataclass

ALLOWED_IMPORTS = frozenset(
    {"re", "json", "math", "datetime", "random", "string", "collections",
     "itertools", "functools", "typing", "dataclasses", "enum"}
)

DENIED_NAMES = frozenset(
    {"eval", "exec", "open", "__import__", "compile", "globals", "locals",
     "vars", "dir", "getattr", "setattr", "delattr", "hasattr", "callable",
     "help", "input", "breakpoint", "memoryview", "exit", "quit",
     "os", "sys", "subprocess", "socket", "shutil", "pathlib", "importlib",
     "ctypes", "multiprocessing", "threading", "asyncio", "aiohttp",
     "aiogram", "sqlalchemy", "pickle", "marshal", "shelve", "pty",
     "ptyprocess", "signal", "inspect", "ast"}
)

DENIED_ATTRS = frozenset(
    {"system", "popen", "spawn", "exec", "eval", "remove", "unlink",
     "rmdir", "mkdir", "rename", "replace", "chdir", "chmod", "chown",
     "environ", "putenv", "getenv"}
)

MAX_CODE_SIZE = 64 * 1024  # 64 KiB per file


@dataclass
class ValidationResult:
    ok: bool
    errors: list[str]


class _Visitor(ast.NodeVisitor):
    def __init__(self) -> None:
        self.errors: list[str] = []

    def visit_Import(self, node: ast.Import) -> None:  # noqa: N802
        for alias in node.names:
            root = alias.name.split(".")[0]
            if root in DENIED_NAMES or root not in ALLOWED_IMPORTS:
                self.errors.append(f"line {node.lineno}: import '{alias.name}' is not allowed")
        self.generic_visit(node)

    def visit_ImportFrom(self, node: ast.ImportFrom) -> None:  # noqa: N802
        root = (node.module or "").split(".")[0]
        if root in DENIED_NAMES or root not in ALLOWED_IMPORTS:
            self.errors.append(f"line {node.lineno}: import from '{node.module}' is not allowed")
        self.generic_visit(node)

    def visit_Name(self, node: ast.Name) -> None:  # noqa: N802
        if node.id in DENIED_NAMES:
            self.errors.append(f"line {node.lineno}: name '{node.id}' is forbidden")
        if node.id.startswith("__") and node.id.endswith("__"):
            self.errors.append(f"line {node.lineno}: dunder '{node.id}' is forbidden")
        self.generic_visit(node)

    def visit_Attribute(self, node: ast.Attribute) -> None:  # noqa: N802
        if node.attr.startswith("_"):
            self.errors.append(f"line {node.lineno}: private attribute '{node.attr}' is forbidden")
        if node.attr in DENIED_ATTRS:
            self.errors.append(f"line {node.lineno}: attribute '{node.attr}' is forbidden")
        self.generic_visit(node)

    def visit_Call(self, node: ast.Call) -> None:  # noqa: N802
        func = node.func
        if isinstance(func, ast.Name) and func.id in DENIED_NAMES:
            self.errors.append(f"line {node.lineno}: call '{func.id}(...)' is forbidden")
        self.generic_visit(node)


def validate_source(source: str) -> ValidationResult:
    """Statically validate plugin Python source. Never executes it."""
    errors: list[str] = []
    if len(source.encode("utf-8")) > MAX_CODE_SIZE:
        errors.append(f"code exceeds {MAX_CODE_SIZE} bytes")
        return ValidationResult(ok=False, errors=errors)
    try:
        tree = ast.parse(source)
    except SyntaxError as exc:
        return ValidationResult(ok=False, errors=[f"syntax error at line {exc.lineno}: {exc.msg}"])
    visitor = _Visitor()
    visitor.visit(tree)
    errors.extend(visitor.errors)
    return ValidationResult(ok=len(errors) == 0, errors=errors)


SAFE_BUILTINS = {
    "abs": abs, "min": min, "max": max, "sum": sum, "len": len,
    "range": range, "enumerate": enumerate, "zip": zip, "sorted": sorted,
    "str": str, "int": int, "float": float, "bool": bool, "list": list,
    "dict": dict, "set": set, "tuple": tuple, "round": round,
    "isinstance": isinstance, "reversed": reversed, "any": any,
    "all": all, "print": print, "ord": ord, "chr": chr,
}

SAFE_MODULES = {"re": re, "math": math, "json": __import__("json")}


def build_handler_namespace() -> dict:
    ns: dict = {"__builtins__": dict(SAFE_BUILTINS)}
    ns.update(SAFE_MODULES)
    return ns


async def run_handler(
    source: str,
    handler_name: str,
    ctx: dict,
    timeout: float = 5.0,
) -> ValidationResult | str:
    """Validate + exec plugin source and await `handler_name(ctx)`.

    Returns "ok" on success or ValidationResult with errors.
    NOTE: for untrusted third-party code, prefer `run_isolated` (separate
    process) — see below.
    """
    validation = validate_source(source)
    if not validation.ok:
        return validation
    ns = build_handler_namespace()
    try:
        compiled = compile(source, filename="<plugin>", mode="exec")
        exec(compiled, ns)  # noqa: S102 — source was statically validated
    except Exception as exc:  # noqa: BLE001
        return ValidationResult(ok=False, errors=[f"exec failed: {exc}"])
    handler = ns.get(handler_name)
    if handler is None:
        return ValidationResult(ok=False, errors=[f"handler '{handler_name}' not defined"])
    if not callable(handler):
        return ValidationResult(ok=False, errors=[f"'{handler_name}' is not callable"])
    try:
        result = handler(ctx)
        if asyncio.iscoroutine(result):
            await asyncio.wait_for(result, timeout=timeout)
        return "ok"
    except asyncio.TimeoutError:
        return ValidationResult(ok=False, errors=["handler timed out"])
    except Exception as exc:  # noqa: BLE001
        return ValidationResult(ok=False, errors=[f"handler error: {exc}"])


def run_isolated(*args, **kwargs) -> str:
    """Placeholder for process-isolated execution (production hardening).

    Recommended implementation: spawn a `multiprocessing` worker with a
    strict resource limit, pass the event payload over a Pipe as JSON, run
    `run_handler` there with a wall-clock timeout, kill the worker on
    timeout. Only JSON-serializable `ctx` may cross the boundary, so plugin
    code can never touch aiogram objects even if validation is bypassed.
    """
    raise NotImplementedError(
        "Process isolation is an optional hardening step; "
        "use run_handler with validated code or implement a worker pool here."
    )
