"""Plugin discovery, validation, install/update/delete, aiogram wiring."""
from __future__ import annotations

import hashlib
import json
import re
import shutil
from datetime import datetime, timezone
from pathlib import Path

from aiogram import Dispatcher, F, Router
from aiogram.filters import Command
from aiogram.types import Message
from loguru import logger

from ..schemas import PluginCreate, PluginInfo, PluginManifest, PluginTrigger
from .rule_engine import Trigger
from .sandbox import MAX_CODE_SIZE, run_handler, validate_source

MANIFEST_FILE = "manifest.json"
CONFIG_FILE = "config.json"

SAFE_ID = re.compile(r"^[a-z0-9_]{2,64}$")


class PluginError(Exception):
    pass


def _read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def _write_json(path: Path, data: dict) -> None:
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


class PluginManager:
    def __init__(self, plugins_dir: Path) -> None:
        self.dir = plugins_dir
        self._router = Router(name="plugins")
        self._mounted = False
        self._errors: dict[str, str] = {}

    # ---------- filesystem helpers ----------
    def plugin_path(self, plugin_id: str) -> Path:
        if not SAFE_ID.match(plugin_id):
            raise PluginError("invalid plugin id")
        return (self.dir / plugin_id).resolve()

    def _ensure_inside(self, path: Path) -> Path:
        resolved = path.resolve()
        if resolved != self.dir.resolve() and self.dir.resolve() not in resolved.parents:
            raise PluginError("path escapes plugins directory")
        return resolved

    def discover(self) -> list[PluginManifest]:
        manifests: list[PluginManifest] = []
        if not self.dir.exists():
            return manifests
        for child in sorted(self.dir.iterdir()):
            if not child.is_dir() or child.name.startswith(("_", ".")):
                continue
            manifest_file = child / MANIFEST_FILE
            if not manifest_file.exists():
                continue
            try:
                data = _read_json(manifest_file)
                manifests.append(PluginManifest(**data))
            except Exception as exc:  # noqa: BLE001
                logger.bind(source="plugin_manager").warning(
                    f"skip broken manifest {child.name}: {exc}"
                )
                self._errors[child.name] = f"broken manifest: {exc}"
        return manifests

    def get_manifest(self, plugin_id: str) -> PluginManifest:
        path = self._ensure_inside(self.plugin_path(plugin_id) / MANIFEST_FILE)
        if not path.exists():
            raise PluginError("plugin not found")
        return PluginManifest(**_read_json(path))

    def get_code(self, plugin_id: str) -> str:
        manifest = self.get_manifest(plugin_id)
        entry = self._ensure_inside(self.plugin_path(plugin_id) / manifest.entrypoint)
        if not entry.exists():
            return ""
        return entry.read_text(encoding="utf-8")

    def get_config(self, plugin_id: str) -> dict:
        path = self.plugin_path(plugin_id) / CONFIG_FILE
        if not path.exists():
            return {}
        return _read_json(path)

    def set_enabled(self, plugin_id: str, enabled: bool) -> PluginManifest:
        manifest = self.get_manifest(plugin_id)
        manifest.enabled = enabled
        if enabled:
            self._errors.pop(plugin_id, None)
        _write_json(self.plugin_path(plugin_id) / MANIFEST_FILE, manifest.model_dump())
        logger.bind(source="plugin_manager").info(
            f"plugin '{plugin_id}' {'enabled' if enabled else 'disabled'}"
        )
        return manifest

    def save_config(self, plugin_id: str, config: dict) -> dict:
        if not isinstance(config, dict):
            raise PluginError("config must be an object")
        raw = json.dumps(config, ensure_ascii=False)
        if len(raw.encode()) > 64 * 1024:
            raise PluginError("config too large (max 64 KiB)")
        _write_json(self.plugin_path(plugin_id) / CONFIG_FILE, config)
        return config

    # ---------- create / update / delete ----------
    def create(self, payload: PluginCreate, author: str = "") -> PluginManifest:
        target = self._ensure_inside(self.plugin_path(payload.id))
        if target.exists():
            raise PluginError("plugin id already exists")
        manifest = PluginManifest(
            id=payload.id,
            name=payload.name,
            version=payload.version,
            description=payload.description,
            enabled=False,
            permissions=list(payload.permissions),
            entrypoint="main.py",
            commands=list(payload.commands),
            triggers=list(payload.triggers),
            dependencies=[],
            min_bot_version="1.0.0",
        )
        if payload.code:
            result = validate_source(payload.code)
            if not result.ok:
                raise PluginError("code rejected: " + "; ".join(result.errors))
        target.mkdir(parents=True)
        (target / "handlers").mkdir(exist_ok=True)
        (target / "assets").mkdir(exist_ok=True)
        (target / "logs").mkdir(exist_ok=True)
        _write_json(target / MANIFEST_FILE, manifest.model_dump())
        _write_json(target / CONFIG_FILE, dict(payload.config or {}))
        code = payload.code or _default_template_code(payload.id)
        (target / "main.py").write_text(code, encoding="utf-8")
        logger.bind(source="plugin_manager").info(f"plugin '{payload.id}' created by {author}")
        return manifest

    def update(
        self,
        plugin_id: str,
        manifest_patch: dict | None,
        code: str | None,
        config: dict | None,
    ) -> PluginManifest:
        manifest = self.get_manifest(plugin_id)
        if manifest_patch:
            merged = manifest.model_dump()
            merged.update(manifest_patch)
            merged["id"] = manifest.id  # id is immutable
            manifest = PluginManifest(**merged)  # raises ValidationError -> 422
            _write_json(self.plugin_path(plugin_id) / MANIFEST_FILE, manifest.model_dump())
        if code is not None:
            if len(code.encode()) > MAX_CODE_SIZE:
                raise PluginError("code too large")
            result = validate_source(code)
            if not result.ok:
                raise PluginError("code rejected: " + "; ".join(result.errors))
            entry = self._ensure_inside(self.plugin_path(plugin_id) / manifest.entrypoint)
            entry.write_text(code, encoding="utf-8")
        if config is not None:
            self.save_config(plugin_id, config)
        self._errors.pop(plugin_id, None)
        return manifest

    def delete(self, plugin_id: str) -> None:
        target = self._ensure_inside(self.plugin_path(plugin_id))
        if not target.exists() or not target.is_dir():
            raise PluginError("plugin not found")
        shutil.rmtree(target)
        self._errors.pop(plugin_id, None)
        logger.bind(source="plugin_manager").info(f"plugin '{plugin_id}' deleted")

    def snapshot(self, plugin_id: str) -> tuple[dict, str]:
        """Return (manifest_dict, sha256 of code) for version history."""
        manifest = self.get_manifest(plugin_id)
        code = self.get_code(plugin_id)
        digest = hashlib.sha256(code.encode()).hexdigest()
        return manifest.model_dump(), digest

    def restore_snapshot(self, plugin_id: str, manifest_data: dict, code: str) -> PluginManifest:
        manifest_data = dict(manifest_data)
        manifest_data["id"] = plugin_id
        manifest = PluginManifest(**manifest_data)
        target = self._ensure_inside(self.plugin_path(plugin_id))
        target.mkdir(parents=True, exist_ok=True)
        _write_json(target / MANIFEST_FILE, manifest.model_dump())
        if code:
            result = validate_source(code)
            if not result.ok:
                raise PluginError("snapshot code no longer passes validation")
            (target / manifest.entrypoint).write_text(code, encoding="utf-8")
        return manifest

    def to_info(self, manifest: PluginManifest) -> PluginInfo:
        code = ""
        try:
            code = self.get_code(manifest.id)
        except PluginError:
            code = ""
        return PluginInfo(
            id=manifest.id,
            name=manifest.name,
            version=manifest.version,
            description=manifest.description,
            enabled=manifest.enabled,
            permissions=manifest.permissions,
            commands=manifest.commands,
            triggers=manifest.triggers,
            last_error=self._errors.get(manifest.id, ""),
            has_code=bool(code.strip()),
        )

    def last_error(self, plugin_id: str) -> str:
        return self._errors.get(plugin_id, "")

    def record_error(self, plugin_id: str, message: str) -> None:
        self._errors[plugin_id] = message[:2000]

    # ---------- aiogram wiring ----------
    def build_router(self) -> Router:
        """Rebuild a fresh Router from all enabled plugins (hot reload)."""
        router = Router(name=f"plugins-{datetime.now(timezone.utc).timestamp()}")

        @router.message()
        async def _dispatch(message: Message) -> bool | None:
            text = message.text or message.caption or ""
            for manifest in self.discover():
                if not manifest.enabled:
                    continue
                try:
                    handled = await self._handle_with_plugin(manifest, message, text)
                except Exception as exc:  # noqa: BLE001
                    self.record_error(manifest.id, str(exc))
                    logger.bind(source=f"plugin:{manifest.id}").error(f"handler crash: {exc}")
                    continue
                if handled:
                    return True
            return None

        # Explicit command handlers for /help-like discovery (best effort).
        for manifest in self.discover():
            if not manifest.enabled:
                continue
            for trig in manifest.triggers:
                if trig.type == "command" and trig.response_text:
                    _register_static_command(router, manifest.id, trig, self)
        return router

    async def _handle_with_plugin(self, manifest: PluginManifest, message: Message, text: str) -> bool:
        # 1) Declarative triggers first (no code execution at all).
        for t in manifest.triggers:
            trigger = Trigger(type=t.type, pattern=t.pattern,
                              response_text=t.response_text, description=t.description)
            if trigger.matches(text):
                if trigger.response_text:
                    user = (message.from_user.first_name if message.from_user else "")
                    await message.answer(trigger.render(text, user or ""))
                    return True
                # trigger matched but codeless response empty -> fall through to code
                break

        # 2) Sandboxed main.py — only if the message is a declared command
        #    (or the plugin declares no triggers at all, i.e. code-driven).
        wants_code = False
        if manifest.commands:
            first = text.split()[0].split("@")[0] if text.split() else ""
            declared = {f"/{c.name.lstrip('/')}" for c in manifest.commands}
            wants_code = first in declared
        elif not manifest.triggers:
            wants_code = bool(text.startswith("/"))

        if not wants_code:
            return False

        code = self.get_code(manifest.id)
        if not code.strip():
            return False

        replies: list[str] = []

        async def _reply(reply_text: str) -> None:
            replies.append(str(reply_text)[:4000])

        ctx = {
            "plugin_id": manifest.id,
            "text": text,
            "user": message.from_user.first_name if message.from_user else "",
            "user_id": message.from_user.id if message.from_user else 0,
            "chat_id": message.chat.id,
            "config": self.get_config(manifest.id),
            "reply": _reply,
        }
        result = await run_handler(code, "handle", ctx, timeout=5.0)
        if result != "ok":
            assert not isinstance(result, str)
            self.record_error(manifest.id, "; ".join(result.errors))
            logger.bind(source=f"plugin:{manifest.id}").error("; ".join(result.errors))
            return False
        for r in replies:
            await message.answer(r)
        return bool(replies)


def _register_static_command(router: Router, plugin_id: str, trig: PluginTrigger, mgr: PluginManager) -> None:
    cmd = trig.pattern.lstrip("/").split()[0]

    @router.message(Command(cmd))
    async def _static(message: Message) -> None:
        user = message.from_user.first_name if message.from_user else ""
        text = message.text or ""
        trigger = Trigger(type="command", pattern=trig.pattern,
                          response_text=trig.response_text, description=trig.description)
        try:
            await message.answer(trigger.render(text, user or ""))
        except Exception as exc:  # noqa: BLE001
            mgr.record_error(plugin_id, str(exc))


def _default_template_code(plugin_id: str) -> str:
    return f'''"""Plugin '{plugin_id}' — sandboxed handler.

Available in ctx: plugin_id, text, user, user_id, chat_id, config, reply().
Only safe builtins + re/math/json are available. Network, files, OS and
aiogram objects are NOT accessible here.
"""


async def handle(ctx):
    text = ctx["text"]
    await ctx["reply"]("Echo from {plugin_id}: " + text)
'''
