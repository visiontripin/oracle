"""FastAPI Admin API — entrypoint.

Run:  uvicorn app.main:app --host 0.0.0.0 --port 8000
"""
from __future__ import annotations

from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from loguru import logger

from .bot.core import BotManager
from .config import get_settings
from . import database
from .database import init_db
from .routers import auth, backup_router, bot_control, config_router, llm, logs, plugins, status
from .services.backup_service import BackupService
from .services.llm_jobs import JobQueue
from .services.llm_service import LLMService, seed_from_env
from .services.log_bus import setup_logging

settings = get_settings()


@asynccontextmanager
async def lifespan(app: FastAPI):
    setup_logging(settings.LOG_LEVEL)
    Path("data").mkdir(exist_ok=True)
    settings.PLUGINS_DIR.mkdir(parents=True, exist_ok=True)
    settings.BACKUP_DIR.mkdir(parents=True, exist_ok=True)
    await init_db()
    llm = LLMService(database.async_session_factory)
    await seed_from_env(llm, settings)
    app.state.llm = llm
    app.state.bot = BotManager(
        token=settings.BOT_TOKEN, version=settings.BOT_VERSION,
        plugins_dir=settings.PLUGINS_DIR, llm=llm,
    )
    app.state.backups = BackupService(
        plugins_dir=settings.PLUGINS_DIR, backup_dir=settings.BACKUP_DIR,
        data_dir=Path("data"),
    )
    logger.bind(source="api").info(f"Admin API ready, bot v{settings.BOT_VERSION}")
    yield
    try:
        await app.state.bot.stop()
    except Exception:  # noqa: BLE001
        pass


app = FastAPI(title="Telegram Bot Admin API", version=settings.BOT_VERSION, lifespan=lifespan)

if settings.cors_origins:
    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.cors_origins,
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

app.include_router(auth.router)
app.include_router(status.router)
app.include_router(bot_control.router)
app.include_router(plugins.router)
app.include_router(logs.router)
app.include_router(config_router.router)
app.include_router(backup_router.router)
app.include_router(llm.router)


@app.get("/health")
async def health():
    return {"ok": True, "version": settings.BOT_VERSION}
