"""JWT auth + password hashing + RBAC dependencies."""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Annotated

import bcrypt
import jwt
from fastapi import Depends, HTTPException, WebSocket, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from .config import get_settings
from .database import get_db

bearer_scheme = HTTPBearer(auto_error=False)

# role -> allowed? hierarchy: viewer < operator < admin
ROLE_RANK = {"viewer": 0, "operator": 1, "admin": 2}


def hash_password(password: str) -> str:
    return bcrypt.hashpw(password.encode(), bcrypt.gensalt()).decode()


def verify_password(password: str, password_hash: str) -> bool:
    try:
        return bcrypt.checkpw(password.encode(), password_hash.encode())
    except ValueError:
        return False


def create_access_token(username: str, role: str) -> str:
    settings = get_settings()
    now = datetime.now(timezone.utc)
    payload = {
        "sub": username,
        "role": role,
        "iat": int(now.timestamp()),
        "exp": int((now + timedelta(minutes=settings.JWT_EXPIRE_MINUTES)).timestamp()),
    }
    return jwt.encode(payload, settings.SECRET_KEY, algorithm=settings.JWT_ALGORITHM)


def decode_token(token: str) -> dict:
    settings = get_settings()
    try:
        return jwt.decode(token, settings.SECRET_KEY, algorithms=[settings.JWT_ALGORITHM])
    except jwt.ExpiredSignatureError as exc:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Token expired") from exc
    except jwt.InvalidTokenError as exc:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Invalid token") from exc


async def get_current_user(
    creds: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer_scheme)],
    db: Annotated[AsyncSession, Depends(get_db)],
) -> dict:
    """Return {'username': ..., 'role': ...} or raise 401."""
    from .models import User  # deferred to avoid circular import

    if creds is None or not creds.credentials:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Not authenticated")
    payload = decode_token(creds.credentials)
    username: str | None = payload.get("sub")
    if not username:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Invalid token payload")
    result = await db.execute(select(User).where(User.username == username, User.is_active.is_(True)))
    user = result.scalar_one_or_none()
    if user is None:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "User not found or disabled")
    return {"username": user.username, "role": user.role}


def require_role(*allowed: str):
    """Dependency factory enforcing RBAC rank (allowed = minimum roles)."""
    min_rank = min(ROLE_RANK[r] for r in allowed)

    async def _dep(user: Annotated[dict, Depends(get_current_user)]) -> dict:
        if ROLE_RANK.get(user["role"], -1) < min_rank:
            raise HTTPException(status.HTTP_403_FORBIDDEN, "Insufficient permissions")
        return user

    return _dep


async def get_ws_user(websocket: WebSocket) -> dict:
    """Authenticate websocket via ?token= query param. Closes socket on failure."""
    from .database import async_session_factory
    from .models import User

    token = websocket.query_params.get("token")
    if not token:
        await websocket.close(code=4401)
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "WS token required")
    try:
        payload = decode_token(token)
    except HTTPException:
        await websocket.close(code=4401)
        raise
    async with async_session_factory() as db:
        result = await db.execute(
            select(User).where(User.username == payload.get("sub"), User.is_active.is_(True))
        )
        user = result.scalar_one_or_none()
        if user is None:
            await websocket.close(code=4401)
            raise HTTPException(status.HTTP_401_UNAUTHORIZED, "User not found")
        return {"username": user.username, "role": user.role}
