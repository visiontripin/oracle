"""GET /logs, WebSocket /logs/stream, GET /audit."""
from __future__ import annotations

import json
from typing import Annotated

from fastapi import APIRouter, Depends, Query, WebSocket, WebSocketDisconnect
from sqlalchemy import desc, select
from sqlalchemy.ext.asyncio import AsyncSession

from ..database import get_db
from ..models import AuditLog, BotEvent
from ..schemas import AuditEntry
from ..security import get_current_user, get_ws_user
from ..services.log_bus import log_bus

router = APIRouter(tags=["logs"])


@router.get("/logs")
async def get_logs(
    _u: Annotated[dict, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
    level: str | None = Query(default=None, description="INFO|WARNING|ERROR"),
    search: str | None = Query(default=None),
    limit: int = Query(default=200, le=1000),
):
    live = log_bus.history(level=level, search=search, limit=limit)
    q = select(BotEvent).order_by(desc(BotEvent.id)).limit(limit)
    if level:
        q = q.where(BotEvent.level == level.upper())
    if search:
        q = q.where(BotEvent.message.contains(search))
    result = await db.execute(q)
    persisted = [
        {"id": e.id, "level": e.level, "source": e.source, "message": e.message,
         "created_at": e.created_at.isoformat() if e.created_at else None}
        for e in result.scalars().all()
    ]
    return {"live": live, "persisted": persisted}


@router.websocket("/logs/stream")
async def logs_stream(websocket: WebSocket):
    await websocket.accept()
    try:
        user = await get_ws_user(websocket)
    except Exception:  # noqa: BLE001 — get_ws_user already closed the socket
        return
    queue = await log_bus.subscribe()
    await websocket.send_text(json.dumps({"type": "hello", "user": user["username"]}))
    try:
        while True:
            entry = await queue.get()
            await websocket.send_text(json.dumps({"type": "log", **entry}))
    except WebSocketDisconnect:
        pass
    finally:
        await log_bus.unsubscribe(queue)


@router.get("/audit", response_model=list[AuditEntry])
async def get_audit(
    _u: Annotated[dict, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
    limit: int = Query(default=200, le=1000),
    action: str | None = None,
):
    q = select(AuditLog).order_by(desc(AuditLog.id)).limit(limit)
    if action:
        q = q.where(AuditLog.action.contains(action))
    result = await db.execute(q)
    return [
        AuditEntry(id=a.id, user=a.user, action=a.action, target=a.target,
                   details=a.details, ip=a.ip, created_at=a.created_at)
        for a in result.scalars().all()
    ]
