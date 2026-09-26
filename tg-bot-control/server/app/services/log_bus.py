"""In-memory broadcast bus for live logs (WebSocket fan-out) + ring buffer."""
from __future__ import annotations

import asyncio
from collections import deque
from datetime import datetime, timezone

from loguru import logger


class LogBus:
    def __init__(self, history_size: int = 500) -> None:
        self._subs: set[asyncio.Queue] = set()
        self._history: deque[dict] = deque(maxlen=history_size)
        self._lock = asyncio.Lock()

    async def publish(self, level: str, source: str, message: str) -> None:
        entry = {
            "level": level,
            "source": source,
            "message": message,
            "created_at": datetime.now(timezone.utc).isoformat(),
        }
        self._history.append(entry)
        async with self._lock:
            subs = list(self._subs)
        for q in subs:
            try:
                q.put_nowait(entry)
            except asyncio.QueueFull:
                pass

    async def subscribe(self) -> asyncio.Queue:
        q: asyncio.Queue = asyncio.Queue(maxsize=1000)
        async with self._lock:
            self._subs.add(q)
        return q

    async def unsubscribe(self, q: asyncio.Queue) -> None:
        async with self._lock:
            self._subs.discard(q)

    def history(self, level: str | None = None, search: str | None = None, limit: int = 200) -> list[dict]:
        items = list(self._history)
        if level:
            items = [e for e in items if e["level"] == level.upper()]
        if search:
            s = search.lower()
            items = [e for e in items if s in e["message"].lower() or s in e["source"].lower()]
        return items[-limit:]


log_bus = LogBus()


def _loguru_sink(message) -> None:
    record = message.record
    level = record["level"].name
    source = record.get("extra", {}).get("source", record["name"])
    try:
        loop = asyncio.get_running_loop()
    except RuntimeError:
        return
    loop.create_task(log_bus.publish(level, str(source), record["message"].rstrip()))


def setup_logging(level: str = "INFO") -> None:
    logger.remove()
    logger.add(
        "data/bot.log",
        rotation="5 MB",
        retention=5,
        level=level,
        encoding="utf-8",
        backtrace=False,
        diagnose=False,
    )
    logger.add(_loguru_sink, level=level, backtrace=False, diagnose=False)
    logger.add(lambda m: print(m, end=""), level=level, backtrace=False, diagnose=False)
