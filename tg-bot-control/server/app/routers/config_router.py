"""GET /config, PUT /config — safe runtime settings subset."""
from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Request
from sqlalchemy.ext.asyncio import AsyncSession

from ..audit import audit
from ..database import get_db
from ..schemas import ConfigUpdate
from ..security import get_current_user, require_role
from ..services.config_service import get_all, update_many

router = APIRouter(prefix="/config", tags=["config"])
needs_operator = require_role("operator", "admin")


@router.get("")
async def get_config(
    _u: Annotated[dict, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    return {"values": await get_all(db)}


@router.put("")
async def put_config(
    payload: ConfigUpdate, request: Request,
    user: Annotated[dict, Depends(needs_operator)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    try:
        values = await update_many(db, payload.values)
    except ValueError as exc:
        raise HTTPException(400, str(exc)) from exc
    await audit(db, request, user["username"], "config.update", ",".join(payload.values.keys()))
    return {"values": values}
