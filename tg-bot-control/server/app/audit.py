"""Audit log helper — every mutating admin action must call this."""
from __future__ import annotations

from fastapi import Request
from sqlalchemy.ext.asyncio import AsyncSession

from .models import AuditLog


async def audit(
    db: AsyncSession,
    request: Request | None,
    user: str,
    action: str,
    target: str = "",
    details: str = "",
) -> None:
    ip = ""
    if request is not None and request.client is not None:
        ip = request.client.host
    db.add(AuditLog(user=user, action=action, target=target, details=details[:2000], ip=ip))
    await db.commit()
