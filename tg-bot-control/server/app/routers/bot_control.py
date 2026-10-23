"""POST /bot/start|stop|restart, POST /plugins/{id}/reload lives in plugins router."""
from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Request
from sqlalchemy.ext.asyncio import AsyncSession

from ..audit import audit
from ..database import get_db
from ..schemas import MessageResponse
from ..security import get_current_user, require_role

router = APIRouter(prefix="/bot", tags=["bot"])
needs_operator = require_role("operator", "admin")


@router.post("/start", response_model=MessageResponse)
async def bot_start(
    request: Request,
    user: Annotated[dict, Depends(needs_operator)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    bot = request.app.state.bot
    try:
        await bot.start()
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(500, f"start failed: {exc}") from exc
    await audit(db, request, user["username"], "bot.start")
    return MessageResponse(message="Bot started")


@router.post("/stop", response_model=MessageResponse)
async def bot_stop(
    request: Request,
    user: Annotated[dict, Depends(needs_operator)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    await request.app.state.bot.stop()
    await audit(db, request, user["username"], "bot.stop")
    return MessageResponse(message="Bot stopped")


@router.post("/restart", response_model=MessageResponse)
async def bot_restart(
    request: Request,
    user: Annotated[dict, Depends(needs_operator)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    bot = request.app.state.bot
    try:
        await bot.restart()
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(500, f"restart failed: {exc}") from exc
    await audit(db, request, user["username"], "bot.restart")
    return MessageResponse(message="Bot restarted")
