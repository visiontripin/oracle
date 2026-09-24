"""BotManager — owns the aiogram Bot/Dispatcher lifecycle.

Single polling task, explicit start/stop/restart, hot plugin reload by
rebuilding the plugin router. Safe to call from API handlers.

LLM: when an LLMService is attached, messages not handled by any plugin
(including channel posts) are checked against LLM bindings and answered
by the configured local model.
"""
from __future__ import annotations

import asyncio
import time
from pathlib import Path
from typing import TYPE_CHECKING

from aiogram import Bot, Dispatcher
from aiogram.types import Message
from loguru import logger

from .plugin_manager import PluginManager

if TYPE_CHECKING:  # pragma: no cover
    from ..services.llm_service import LLMService


class BotManager:
    def __init__(self, token: str, version: str, plugins_dir: Path,
                 llm: "LLMService | None" = None) -> None:
        self._token = token
        self.version = version
        self.plugins = PluginManager(plugins_dir)
        self._llm = llm
        self._bot: Bot | None = None
        self._dp: Dispatcher | None = None
        self._task: asyncio.Task | None = None
        self._started_at: float | None = None
        self._errors: list[str] = []
        self._lock = asyncio.Lock()

    # ---------- state ----------
    @property
    def online(self) -> bool:
        return self._task is not None and not self._task.done()

    @property
    def uptime_seconds(self) -> int:
        if not self.online or self._started_at is None:
            return 0
        return int(time.time() - self._started_at)

    @property
    def active_plugins(self) -> list[str]:
        return [m.id for m in self.plugins.discover() if m.enabled]

    @property
    def last_errors(self) -> list[str]:
        return self._errors[-10:]

    def record_error(self, message: str) -> None:
        self._errors.append(message[:1000])
        self._errors = self._errors[-50:]

    async def _maybe_llm_reply(self, message: Message) -> None:
        """Answer via LLM when an enabled binding matches.

        Priority 1: on-device agent (model runs on the user's phone).
        Priority 2: server-side OpenAI-compatible provider (if configured).
        """
        if self._llm is None:
            return
        text = message.text or message.caption or ""
        chat = message.chat
        try:
            binding = await self._llm.match(
                text, chat.id, getattr(chat, "type", "private") or "private",
                getattr(chat, "username", "") or "",
            )
            if binding is None:
                return
            logger.bind(source="llm").info(
                f"match {binding['scope']}:{binding['pattern']} chat={chat.id}")
            if self._jobs is not None and self._jobs.agent_online:
                await self._reply_via_device(message, chat.id, text)
                return
            reply = await self._llm.generate(chat.id, text)
            await message.answer(reply[:4000])
            logger.bind(source="llm").info(f"replied chat={chat.id} chars={len(reply)} (server provider)")
        except Exception as exc:  # noqa: BLE001 — LLM failure must not crash polling
            self.record_error(f"llm: {exc}")
            logger.bind(source="llm").error(f"generation failed: {exc}")

    async def _reply_via_device(self, message: Message, chat_id: int, text: str) -> None:
        assert self._jobs is not None and self._llm is not None
        profile = await self._llm.profile()
        job = await self._jobs.enqueue(
            chat_id, text, profile["system_prompt"],
            params={"temperature": profile["temperature"], "top_p": profile["top_p"],
                    "max_tokens": profile["max_tokens"]},
        )
        logger.bind(source="llm").info(f"job #{job.id} -> device agent chat={chat_id}")
        reply = await self._jobs.wait_result(job.id, timeout_sec=AGENT_WAIT_SEC)
        if reply:
            await message.answer(reply[:4000])
            logger.bind(source="llm").info(f"job #{job.id} answered from device")
        else:
            self.record_error(f"llm job #{job.id}: agent did not answer in {AGENT_WAIT_SEC}s")
            logger.bind(source="llm").warning(f"job #{job.id} expired/failed")

    # ---------- lifecycle ----------
    async def start(self) -> None:
        async with self._lock:
            if self.online:
                return
            if not self._token:
                raise RuntimeError("BOT_TOKEN is not configured")
            self._bot = Bot(token=self._token)
            self._dp = Dispatcher()
            self._dp.include_router(self.plugins.build_router())

            @self._dp.message()
            async def _fallback(message: Message) -> None:
                # Only reached when no plugin handled the update.
                if (message.text or "").startswith("/start"):
                    await message.answer("Bot is online. No plugin handled this message.")
                    return
                await self._maybe_llm_reply(message)

            @self._dp.channel_post()
            async def _channel_llm(post: Message) -> None:
                # Channels: only LLM bindings answer (scope=channel / all).
                await self._maybe_llm_reply(post)

            async def _poll() -> None:
                assert self._dp is not None and self._bot is not None
                try:
                    logger.bind(source="bot").info("polling started")
                    await self._dp.start_polling(self._bot, handle_signals=False)
                except asyncio.CancelledError:
                    logger.bind(source="bot").info("polling stopped")
                    raise
                except Exception as exc:  # noqa: BLE001
                    self.record_error(str(exc))
                    logger.bind(source="bot").error(f"polling crashed: {exc}")

            self._task = asyncio.create_task(_poll())
            self._started_at = time.time()
            logger.bind(source="bot").info(f"bot v{self.version} started")

    async def stop(self) -> None:
        async with self._lock:
            task, self._task = self._task, None
            self._started_at = None
            if task is not None:
                task.cancel()
                try:
                    await task
                except asyncio.CancelledError:
                    pass
            if self._bot is not None:
                try:
                    await self._bot.session.close()
                except Exception:  # noqa: BLE001
                    pass
            self._bot = None
            self._dp = None
            logger.bind(source="bot").info("bot stopped")

    async def restart(self) -> None:
        await self.stop()
        await self.start()

    async def reload_plugins(self) -> list[str]:
        """Hot-reload: rebuild router if online, else just re-validate manifests."""
        manifests = self.plugins.discover()
        enabled = [m.id for m in manifests if m.enabled]
        if self.online and self._dp is not None:
            # aiogram 3: safest hot reload is full polling restart with new router.
            await self.restart()
        logger.bind(source="bot").info(f"plugins reloaded: {enabled}")
        return enabled

    def set_token(self, token: str) -> None:
        if self.online:
            raise RuntimeError("stop the bot before changing token")
        self._token = token
