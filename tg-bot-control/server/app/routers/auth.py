"""POST /auth/login, POST /auth/logout."""
from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..audit import audit
from ..config import get_settings
from ..database import get_db
from ..models import User
from ..schemas import LoginRequest, LoginResponse, MessageResponse
from ..security import create_access_token, get_current_user, verify_password

router = APIRouter(prefix="/auth", tags=["auth"])


@router.post("/login", response_model=LoginResponse)
async def login(payload: LoginRequest, request: Request, db: Annotated[AsyncSession, Depends(get_db)]):
    result = await db.execute(select(User).where(User.username == payload.username))
    user = result.scalar_one_or_none()
    if user is None or not user.is_active or not verify_password(payload.password, user.password_hash):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Invalid username or password")
    token = create_access_token(user.username, user.role)
    await audit(db, request, user.username, "auth.login", user.username)
    return LoginResponse(
        access_token=token, role=user.role,
        expires_in_minutes=get_settings().JWT_EXPIRE_MINUTES,
    )


@router.post("/logout", response_model=MessageResponse)
async def logout(
    request: Request,
    user: Annotated[dict, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    # Stateless JWT: logout = client discards token; we keep an audit trail.
    await audit(db, request, user["username"], "auth.logout", user["username"])
    return MessageResponse(ok=True, message="Logged out. Discard the token on the client.")
