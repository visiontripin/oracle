"""Runtime bot settings stored in DB (safe subset, editable from the app)."""
from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..models import Setting

# Only these keys may be changed from Android — never secrets/tokens here.
ALLOWED_KEYS = {
    "bot_name": "My Bot",
    "default_language": "ru",
    "timezone": "Europe/Moscow",
    "welcome_enabled": "true",
    "log_level": "INFO",
    "maintenance_mode": "false",
}


async def get_all(db: AsyncSession) -> dict[str, str]:
    result = await db.execute(select(Setting))
    stored = {row.key: row.value for row in result.scalars().all()}
    return {key: stored.get(key, default) for key, default in ALLOWED_KEYS.items()}


async def update_many(db: AsyncSession, values: dict[str, str]) -> dict[str, str]:
    for key in values:
        if key not in ALLOWED_KEYS:
            raise ValueError(f"setting '{key}' is not editable")
    result = await db.execute(select(Setting))
    existing = {row.key: row for row in result.scalars().all()}
    for key, value in values.items():
        if key in existing:
            existing[key].value = str(value)[:2000]
        else:
            db.add(Setting(key=key, value=str(value)[:2000]))
    await db.commit()
    return await get_all(db)
