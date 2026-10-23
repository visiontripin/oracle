"""Sandbox validation + execution tests."""
import asyncio

from app.bot.sandbox import run_handler, validate_source


def test_allows_safe_code():
    src = "async def handle(ctx):\n    await ctx['reply']('hi ' + ctx['user'])\n"
    assert validate_source(src).ok


def test_allows_safe_imports():
    assert validate_source("import re\nimport math\n").ok


def test_blocks_os_import():
    r = validate_source("import os\n")
    assert not r.ok and any("os" in e for e in r.errors)


def test_blocks_subprocess_from_import():
    r = validate_source("from subprocess import Popen\n")
    assert not r.ok


def test_blocks_eval_open():
    assert not validate_source("eval('1')\n").ok
    assert not validate_source("open('/etc/passwd').read()\n").ok


def test_blocks_dunder_and_private_attr():
    assert not validate_source("x = obj.__class__\n").ok
    assert not validate_source("x = obj._secret\n").ok


def test_blocks_syntax_error():
    r = validate_source("def broken(:\n")
    assert not r.ok and any("syntax" in e for e in r.errors)


def test_run_handler_replies():
    src = "async def handle(ctx):\n    await ctx['reply']('echo:' + ctx['text'])\n"
    replies = []

    async def reply(t):
        replies.append(t)

    ctx = {"text": "/echo hi", "user": "Bob", "config": {}, "reply": reply}
    assert asyncio.run(run_handler(src, "handle", ctx)) == "ok"
    assert replies == ["echo:/echo hi"]


def test_run_handler_missing_handler():
    assert not asyncio.run(run_handler("x = 1\n", "handle", {})).ok
    # run_handler returns ValidationResult on failure
    res = asyncio.run(run_handler("x = 1\n", "handle", {}))
    assert hasattr(res, "errors")
