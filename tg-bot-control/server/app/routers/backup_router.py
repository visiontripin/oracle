"""POST /backup/create, GET /backups, POST /rollback/{version}."""
from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Request
from sqlalchemy.ext.asyncio import AsyncSession

from ..audit import audit
from ..database import get_db
from ..schemas import BackupInfo, MessageResponse
from ..security import require_role

router = APIRouter(tags=["backup"])
needs_admin = require_role("admin")


@router.post("/backup/create", response_model=BackupInfo)
async def backup_create(
    request: Request,
    user: Annotated[dict, Depends(needs_admin)],
    db: Annotated[AsyncSession, Depends(get_db)],
    label: str = "manual",
):
    svc = request.app.state.backups
    path = svc.create(label=label[:32])
    size = sum(f.stat().st_size for f in path.rglob("*") if f.is_file())
    from datetime import datetime, timezone

    info = BackupInfo(name=path.name, path=str(path), size_bytes=size,
                      created_at=datetime.now(timezone.utc))
    await audit(db, request, user["username"], "backup.create", path.name)
    return info


@router.get("/backups", response_model=list[BackupInfo])
async def backup_list(request: Request, _u: Annotated[dict, Depends(needs_admin)]):
    return [BackupInfo(**b) for b in request.app.state.backups.list()]


@router.post("/rollback/{version}", response_model=MessageResponse)
async def rollback(
    version: str, request: Request,
    user: Annotated[dict, Depends(needs_admin)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    """Rollback plugins to a backup (name) or plugin snapshot (pv:<id>).

    - /rollback/backup-20240101-120000-manual -> restore plugins dir
    - /rollback/pv:42 -> restore single plugin from PluginVersion row
    """
    if version.startswith("pv:"):
        from sqlalchemy import select

        from ..models import PluginVersion

        try:
            row_id = int(version[3:])
        except ValueError as exc:
            raise HTTPException(400, "bad snapshot id") from exc
        result = await db.execute(select(PluginVersion).where(PluginVersion.id == row_id))
        row = result.scalar_one_or_none()
        if row is None:
            raise HTTPException(404, "snapshot not found")
        pm = request.app.state.bot.plugins
        # Code itself is re-read from current file only if hash matches;
        # otherwise restore manifest and require manual code review.
        from ..bot.plugin_manager import PluginError

        current_code = ""
        try:
            current_code = pm.get_code(row.plugin_id)
        except PluginError:
            current_code = ""
        import hashlib

        code = current_code if hashlib.sha256(current_code.encode()).hexdigest() == row.code_hash else ""
        try:
            pm.restore_snapshot(row.plugin_id, dict(row.manifest_json), code)
        except Exception as exc:  # noqa: BLE001
            raise HTTPException(400, f"restore failed: {exc}") from exc
        await audit(db, request, user["username"], "rollback.snapshot", version,
                    f"plugin={row.plugin_id} code_restored={bool(code)}")
        return MessageResponse(message=f"Plugin '{row.plugin_id}' manifest rolled back to v{row.version}")

    try:
        path = request.app.state.backups.rollback(version)
    except ValueError as exc:
        raise HTTPException(404, str(exc)) from exc
    await request.app.state.bot.reload_plugins()
    await audit(db, request, user["username"], "rollback.backup", version)
    return MessageResponse(message=f"Rolled back plugins from '{path.name}'")
