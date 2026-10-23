"""GET /status — online, uptime, version, plugins, errors, events."""
from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, Request
from sqlalchemy import desc, select
from sqlalchemy.ext.asyncio import AsyncSession

from ..database import get_db
from ..models import BotEvent
from ..schemas import StatusResponse
from ..security import get_current_user
from ..services.log_bus import log_bus

router = APIRouter(tags=["status"])


def _bot_manager(request) -> object:
    return request.app.state.bot


@router.get("/status", response_model=StatusResponse)
async def status(
    request: Request, db: Annotated[AsyncSession, Depends(get_db)],
    _user: Annotated[dict, Depends(get_current_user)],
):
    bot = _bot_manager(request)
    result = await db.execute(select(BotEvent).order_by(desc(BotEvent.id)).limit(20))
    events = [
        {"level": e.level, "source": e.source, "message": e.message,
         "created_at": e.created_at.isoformat() if e.created_at else None}
        for e in result.scalars().all()
    ]
    if not events:
        events = log_bus.history(limit=20)
    return StatusResponse(
        online=bot.online,
        uptime_seconds=bot.uptime_seconds,
        version=bot.version,
        active_plugins=bot.active_plugins,
        last_errors=bot.last_errors,
        recent_events=events,
    )
