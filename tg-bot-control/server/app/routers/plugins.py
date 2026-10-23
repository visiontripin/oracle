"""Plugin CRUD + enable/disable/reload + per-plugin logs.

Every mutating call: sandbox validation -> snapshot version -> write -> audit.
"""
from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import ValidationError
from sqlalchemy import desc, select
from sqlalchemy.ext.asyncio import AsyncSession

from ..audit import audit
from ..bot.plugin_manager import PluginError
from ..database import get_db
from ..models import BotEvent, PluginRecord, PluginVersion
from ..schemas import (MessageResponse, PluginCreate, PluginDetail, PluginInfo,
                       PluginUpdate)
from ..security import get_current_user, require_role

router = APIRouter(prefix="/plugins", tags=["plugins"])
needs_operator = require_role("operator", "admin")
needs_admin = require_role("admin")


def _pm(request: Request):
    return request.app.state.bot.plugins


async def _sync_record(db: AsyncSession, request: Request, plugin_id: str) -> None:
    pm = _pm(request)
    try:
        manifest = pm.get_manifest(plugin_id)
    except PluginError:
        result = await db.execute(select(PluginRecord).where(PluginRecord.id == plugin_id))
        rec = result.scalar_one_or_none()
        if rec is not None:
            await db.delete(rec)
            await db.commit()
        return
    result = await db.execute(select(PluginRecord).where(PluginRecord.id == plugin_id))
    rec = result.scalar_one_or_none()
    if rec is None:
        db.add(PluginRecord(id=manifest.id, name=manifest.name, version=manifest.version,
                            description=manifest.description, enabled=manifest.enabled,
                            last_error=pm.last_error(plugin_id)))
    else:
        rec.name, rec.version, rec.description = manifest.name, manifest.version, manifest.description
        rec.enabled = manifest.enabled
        rec.last_error = pm.last_error(plugin_id)
    await db.commit()


async def _snapshot(db: AsyncSession, request: Request, plugin_id: str, author: str) -> None:
    pm = _pm(request)
    manifest_dict, digest = pm.snapshot(plugin_id)
    db.add(PluginVersion(plugin_id=plugin_id, version=manifest_dict.get("version", "?"),
                         manifest_json=manifest_dict, code_hash=digest,
                         backup_path="", author=author))
    await db.commit()


@router.get("", response_model=list[PluginInfo])
async def list_plugins(request: Request, _u: Annotated[dict, Depends(get_current_user)]):
    return [_pm(request).to_info(m) for m in _pm(request).discover()]


@router.post("", response_model=PluginDetail, status_code=201)
async def create_plugin(
    payload: PluginCreate, request: Request,
    user: Annotated[dict, Depends(needs_admin)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    pm = _pm(request)
    try:
        manifest = pm.create(payload, author=user["username"])
    except PluginError as exc:
        raise HTTPException(400, str(exc)) from exc
    except ValidationError as exc:
        raise HTTPException(422, str(exc)) from exc
    await _snapshot(db, request, manifest.id, user["username"])
    await _sync_record(db, request, manifest.id)
    await audit(db, request, user["username"], "plugin.create", manifest.id, manifest.name)
    return PluginDetail(**pm.to_info(manifest).model_dump(), manifest=manifest.model_dump(),
                        code=pm.get_code(manifest.id), config=pm.get_config(manifest.id))


@router.get("/{plugin_id}", response_model=PluginDetail)
async def get_plugin(plugin_id: str, request: Request,
                     _u: Annotated[dict, Depends(get_current_user)]):
    pm = _pm(request)
    try:
        manifest = pm.get_manifest(plugin_id)
    except PluginError as exc:
        raise HTTPException(404, str(exc)) from exc
    return PluginDetail(**pm.to_info(manifest).model_dump(), manifest=manifest.model_dump(),
                        code=pm.get_code(plugin_id), config=pm.get_config(plugin_id))


@router.put("/{plugin_id}", response_model=PluginDetail)
async def update_plugin(
    plugin_id: str, payload: PluginUpdate, request: Request,
    user: Annotated[dict, Depends(needs_operator)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    pm = _pm(request)
    try:
        manifest = pm.update(plugin_id, payload.manifest, payload.code, payload.config)
    except PluginError as exc:
        raise HTTPException(400, str(exc)) from exc
    except ValidationError as exc:
        raise HTTPException(422, f"manifest invalid: {exc}") from exc
    await _snapshot(db, request, plugin_id, user["username"])
    await _sync_record(db, request, plugin_id)
    await audit(db, request, user["username"], "plugin.update", plugin_id,
                f"manifest={payload.manifest is not None} code={payload.code is not None} "
                f"config={payload.config is not None}")
    return PluginDetail(**pm.to_info(manifest).model_dump(), manifest=manifest.model_dump(),
                        code=pm.get_code(plugin_id), config=pm.get_config(plugin_id))


@router.delete("/{plugin_id}", response_model=MessageResponse)
async def delete_plugin(
    plugin_id: str, request: Request,
    user: Annotated[dict, Depends(needs_admin)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    pm = _pm(request)
    try:
        await _snapshot(db, request, plugin_id, user["username"])  # keep last copy for rollback
        pm.delete(plugin_id)
    except PluginError as exc:
        raise HTTPException(404, str(exc)) from exc
    await _sync_record(db, request, plugin_id)
    await audit(db, request, user["username"], "plugin.delete", plugin_id)
    return MessageResponse(message=f"Plugin '{plugin_id}' deleted")


@router.post("/{plugin_id}/enable", response_model=MessageResponse)
async def enable_plugin(
    plugin_id: str, request: Request,
    user: Annotated[dict, Depends(needs_operator)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    pm = _pm(request)
    try:
        pm.set_enabled(plugin_id, True)
    except PluginError as exc:
        raise HTTPException(404, str(exc)) from exc
    await _sync_record(db, request, plugin_id)
    await audit(db, request, user["username"], "plugin.enable", plugin_id)
    return MessageResponse(message=f"Plugin '{plugin_id}' enabled")


@router.post("/{plugin_id}/disable", response_model=MessageResponse)
async def disable_plugin(
    plugin_id: str, request: Request,
    user: Annotated[dict, Depends(needs_operator)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    pm = _pm(request)
    try:
        pm.set_enabled(plugin_id, False)
    except PluginError as exc:
        raise HTTPException(404, str(exc)) from exc
    await _sync_record(db, request, plugin_id)
    await audit(db, request, user["username"], "plugin.disable", plugin_id)
    return MessageResponse(message=f"Plugin '{plugin_id}' disabled")


@router.post("/{plugin_id}/reload", response_model=MessageResponse)
async def reload_plugin(
    plugin_id: str, request: Request,
    user: Annotated[dict, Depends(needs_operator)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    pm = _pm(request)
    try:
        pm.get_manifest(plugin_id)  # validate it still parses
    except PluginError as exc:
        raise HTTPException(404, str(exc)) from exc
    except ValidationError as exc:
        raise HTTPException(422, f"manifest invalid: {exc}") from exc
    enabled = await request.app.state.bot.reload_plugins()
    await audit(db, request, user["username"], "plugin.reload", plugin_id)
    return MessageResponse(message=f"Reloaded. Enabled: {', '.join(enabled) or 'none'}")


@router.get("/{plugin_id}/logs")
async def plugin_logs(
    plugin_id: str, request: Request,
    _u: Annotated[dict, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
    level: str | None = None, limit: int = 200,
):
    from ..services.log_bus import log_bus

    items = [e for e in log_bus.history(limit=1000) if e["source"] == f"plugin:{plugin_id}"]
    if level:
        items = [e for e in items if e["level"] == level.upper()]
    q = (select(BotEvent).where(BotEvent.source == f"plugin:{plugin_id}")
          .order_by(desc(BotEvent.id)).limit(limit))
    if level:
        q = q.where(BotEvent.level == level.upper())
    result = await db.execute(q)
    db_items = [{"level": e.level, "source": e.source, "message": e.message,
                 "created_at": e.created_at.isoformat() if e.created_at else None}
                for e in result.scalars().all()]
    return {"live": items[-limit:], "persisted": db_items,
            "last_error": _pm(request).last_error(plugin_id)}


@router.get("/{plugin_id}/versions")
async def plugin_versions(
    plugin_id: str, _u: Annotated[dict, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)], limit: int = 20,
):
    result = await db.execute(
        select(PluginVersion).where(PluginVersion.plugin_id == plugin_id)
        .order_by(desc(PluginVersion.id)).limit(limit))
    return [
        {"id": v.id, "plugin_id": v.plugin_id, "version": v.version,
         "code_hash": v.code_hash, "author": v.author,
         "created_at": v.created_at.isoformat() if v.created_at else None}
        for v in result.scalars().all()
    ]
