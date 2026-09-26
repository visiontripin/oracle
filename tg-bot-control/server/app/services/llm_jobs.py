"""On-device LLM job queue.

The server enqueues one job per incoming message matched by an LLM binding;
the Android agent (running the model on the phone) claims jobs, generates
locally and posts the result back. The bot waits for the result with a
timeout; if the agent is offline or too slow, the job expires and the bot
falls back to silence/server provider.

Pure asyncio + SQLAlchemy, no HTTP — fully unit-testable.
"""
from __future__ import annotations

import asyncio
import time
from typing import Any

from sqlalchemy import desc, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from ..models import LlmJob

AGENT_ONLINE_TTL_SEC = 60  # agent considered online if it claimed within this window


class JobQueue:
    def __init__(self, session_factory) -> None:
        self._factory = session_factory
        self._events: dict[int, asyncio.Event] = {}
        self.agent_last_seen: float = 0.0
        self.claimed_by: str = ""

    # ---------- agent presence ----------
    @property
    def agent_online(self) -> bool:
        return self.agent_last_seen > 0 and (time.time() - self.agent_last_seen) < AGENT_ONLINE_TTL_SEC

    def agent_seen(self, who: str) -> None:
        self.agent_last_seen = time.time()
        self.claimed_by = who

    # ---------- stats ----------
    async def counts(self) -> dict[str, int]:
        async with self._factory() as db:
            rows = await db.execute(select(LlmJob.status, func.count(LlmJob.id)).group_by(LlmJob.status))
            by_status = {status: n for status, n in rows.all()}
        return {
            "pending": by_status.get("pending", 0),
            "running": by_status.get("running", 0),
            "done": by_status.get("done", 0),
            "failed": by_status.get("failed", 0),
            "expired": by_status.get("expired", 0),
        }

    # ---------- lifecycle ----------
    async def enqueue(
        self, chat_id: int | str, message: str, system_prompt: str,
        params: dict[str, Any] | None = None,
    ) -> LlmJob:
        params = params or {}
        job = LlmJob(
            chat_id=str(chat_id), message=message[:8000],
            system_prompt=(system_prompt or "")[:16000],
            temperature=float(params.get("temperature", 0.7)),
            top_p=float(params.get("top_p", 0.9)),
            max_tokens=int(params.get("max_tokens", 512)),
        )
        async with self._factory() as db:
            db.add(job)
            await db.commit()
            await db.refresh(job)
        self._events[job.id] = asyncio.Event()
        return job

    async def claim(self, agent: str) -> LlmJob | None:
        """Oldest pending -> running. Also marks the agent as online."""
        async with self._factory() as db:
            result = await db.execute(
                select(LlmJob).where(LlmJob.status == "pending").order_by(LlmJob.id).limit(1))
            job = result.scalar_one_or_none()
            if job is None:
                return None
            job.status = "running"
            job.claimed_by = agent[:64]
            await db.commit()
            await db.refresh(job)
        self.agent_seen(agent)
        self._events.setdefault(job.id, asyncio.Event())
        return job

    async def finish(self, job_id: int, reply: str) -> bool:
        """Agent posted a reply. False if the job is gone/expired (late reply)."""
        async with self._factory() as db:
            job = await db.get(LlmJob, job_id)
            if job is None or job.status in ("done", "expired"):
                return False
            job.status = "done"
            job.reply = reply[:8000]
            job.error = ""
            await db.commit()
        self._events.setdefault(job_id, asyncio.Event()).set()
        return True

    async def fail(self, job_id: int, error: str) -> bool:
        async with self._factory() as db:
            job = await db.get(LlmJob, job_id)
            if job is None or job.status in ("done", "expired"):
                return False
            job.status = "failed"
            job.error = (error or "agent failure")[:2000]
            await db.commit()
        self._events.setdefault(job_id, asyncio.Event()).set()
        return True

    async def wait_result(self, job_id: int, timeout_sec: float) -> str | None:
        """Bot-side wait. Returns the reply, or None on timeout/agent failure."""
        event = self._events.setdefault(job_id, asyncio.Event())
        try:
            await asyncio.wait_for(event.wait(), timeout=timeout_sec)
        except asyncio.TimeoutError:
            await self._expire(job_id)
            return None
        async with self._factory() as db:
            job = await db.get(LlmJob, job_id)
            if job is not None and job.status == "done":
                return job.reply
            return None  # failed by agent
        # note: expired jobs handled above

    async def _expire(self, job_id: int) -> None:
        async with self._factory() as db:
            job = await db.get(LlmJob, job_id)
            if job is not None and job.status in ("pending", "running"):
                job.status = "expired"
                job.error = "agent did not answer in time"
                await db.commit()
        self._events.setdefault(job_id, asyncio.Event()).set()

    async def get(self, job_id: int) -> LlmJob | None:
        async with self._factory() as db:
            return await db.get(LlmJob, job_id)

    async def recent(self, limit: int = 30) -> list[LlmJob]:
        async with self._factory() as db:
            result = await db.execute(
                select(LlmJob).order_by(desc(LlmJob.id)).limit(limit))
            return list(result.scalars().all())

    async def purge_finished(self) -> int:
        """Housekeeping: delete done/failed/expired rows older than 1h."""
        cutoff = time.time() - 3600
        async with self._factory() as db:
            result = await db.execute(select(LlmJob).where(
                LlmJob.status.in_(("done", "failed", "expired"))))
            jobs = [j for j in result.scalars().all()
                    if j.created_at and j.created_at.timestamp() < cutoff]
            for j in jobs:
                await db.delete(j)
            await db.commit()
            return len(jobs)
