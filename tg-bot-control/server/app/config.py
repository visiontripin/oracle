"""Application configuration via pydantic-settings.

All secrets come from environment / .env file. Nothing is hardcoded.
"""
from __future__ import annotations

from functools import lru_cache
from pathlib import Path

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    SECRET_KEY: str = Field(min_length=32, description="JWT signing key, min 32 chars")
    JWT_ALGORITHM: str = "HS256"
    JWT_EXPIRE_MINUTES: int = 120

    BOT_TOKEN: str = Field(default="", description="Telegram bot token from @BotFather")
    BOT_VERSION: str = "1.0.0"

    ADMIN_USERNAME: str = "admin"
    ADMIN_PASSWORD: str = Field(default="change-me-strong-password", min_length=8)

    DATABASE_URL: str = "sqlite+aiosqlite:///./data/bot.db"
    PLUGINS_DIR: Path = Path("./plugins")
    BACKUP_DIR: Path = Path("./backups")
    LOG_LEVEL: str = "INFO"

    # Optional bootstrap for a local LLM provider (OpenAI-compatible).
    # Example: LLM_BASE_URL=http://localhost:11434/v1  LLM_MODEL=llama3.1:8b
    LLM_BASE_URL: str = ""
    LLM_MODEL: str = ""

    API_HOST: str = "0.0.0.0"
    API_PORT: int = 8000
    CORS_ORIGINS: str = ""

    @field_validator("PLUGINS_DIR", "BACKUP_DIR", mode="before")
    @classmethod
    def _as_path(cls, v: object) -> Path:
        return v if isinstance(v, Path) else Path(str(v))

    @property
    def cors_origins(self) -> list[str]:
        return [o.strip() for o in self.CORS_ORIGINS.split(",") if o.strip()]


@lru_cache
def get_settings() -> Settings:
    return Settings()  # type: ignore[call-arg]
