"""Pydantic request/response schemas for the Admin API."""
from __future__ import annotations

from datetime import datetime
from typing import Any

from pydantic import BaseModel, Field


# ---------- Auth ----------
class LoginRequest(BaseModel):
    username: str
    password: str


class LoginResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"
    role: str
    expires_in_minutes: int


# ---------- Status ----------
class StatusResponse(BaseModel):
    online: bool
    uptime_seconds: int
    version: str
    active_plugins: list[str]
    last_errors: list[str]
    recent_events: list[dict[str, Any]]


# ---------- Plugins ----------
class PluginTrigger(BaseModel):
    type: str = Field(pattern="^(command|contains|startswith|regex)$")
    pattern: str
    response_text: str = ""
    description: str = ""


class PluginCommand(BaseModel):
    name: str
    description: str = ""


class PluginManifest(BaseModel):
    id: str = Field(pattern=r"^[a-z0-9_]{2,64}$")
    name: str = Field(min_length=1, max_length=128)
    version: str = Field(pattern=r"^\d+\.\d+\.\d+$")
    description: str = ""
    enabled: bool = False
    permissions: list[str] = []
    entrypoint: str = "main.py"
    commands: list[PluginCommand] = []
    triggers: list[PluginTrigger] = []
    dependencies: list[str] = []
    min_bot_version: str = "1.0.0"


class PluginCreate(BaseModel):
    id: str = Field(pattern=r"^[a-z0-9_]{2,64}$")
    name: str = Field(min_length=1, max_length=128)
    description: str = ""
    version: str = Field(default="0.1.0", pattern=r"^\d+\.\d+\.\d+$")
    permissions: list[str] = []
    commands: list[PluginCommand] = []
    triggers: list[PluginTrigger] = []
    code: str = ""  # optional main.py content (validated in sandbox)
    config: dict[str, Any] = {}


class PluginUpdate(BaseModel):
    manifest: dict[str, Any] | None = None
    code: str | None = None  # new main.py (validated before save)
    config: dict[str, Any] | None = None


class PluginInfo(BaseModel):
    id: str
    name: str
    version: str
    description: str
    enabled: bool
    permissions: list[str]
    commands: list[PluginCommand]
    triggers: list[PluginTrigger]
    last_error: str = ""
    has_code: bool = False


class PluginDetail(PluginInfo):
    manifest: dict[str, Any]
    code: str = ""
    config: dict[str, Any] = {}


# ---------- Logs / audit ----------
class LogEntry(BaseModel):
    id: int | None = None
    level: str
    source: str
    message: str
    created_at: datetime | None = None


class AuditEntry(BaseModel):
    id: int
    user: str
    action: str
    target: str
    details: str
    ip: str
    created_at: datetime


# ---------- Config / backup ----------
class ConfigUpdate(BaseModel):
    values: dict[str, str]


class BackupInfo(BaseModel):
    name: str
    path: str
    size_bytes: int
    created_at: datetime


class MessageResponse(BaseModel):
    ok: bool = True
    message: str = ""


# ---------- LLM ----------
class LlmProviderCreate(BaseModel):
    name: str = Field(min_length=1, max_length=64)
    kind: str = Field(default="ollama", pattern="^(ollama|lmstudio|llamacpp|vllm|custom)$")
    base_url: str = ""
    api_key: str = ""  # write-only: never returned to clients
    model: str = ""
    enabled: bool = True


class LlmProvider(BaseModel):
    id: str
    name: str
    kind: str
    base_url: str
    model: str
    enabled: bool
    api_key_set: bool


class LlmProfile(BaseModel):
    system_prompt: str = Field(default="", max_length=16000)
    temperature: float = Field(default=0.7, ge=0.0, le=2.0)
    top_p: float = Field(default=0.9, ge=0.0, le=1.0)
    max_tokens: int = Field(default=512, ge=16, le=8192)
    seed: int | None = None
    timeout_sec: int = Field(default=120, ge=5, le=600)
    context_chars: int = Field(default=4000, ge=200, le=64000)


class LlmBindingCreate(BaseModel):
    scope: str = Field(pattern="^(all|command|chat|channel)$")
    pattern: str = Field(default="", max_length=256)
    enabled: bool = True


class LlmBinding(BaseModel):
    id: str
    scope: str
    pattern: str
    enabled: bool


class LlmChatRequest(BaseModel):
    message: str = Field(min_length=1, max_length=8000)


class LlmChatResponse(BaseModel):
    reply: str


class LlmModelsResponse(BaseModel):
    models: list[str]


class LlmTestResponse(BaseModel):
    ok: bool = True
    models: list[str] = []
    message: str = ""


class LlmStatus(BaseModel):
    configured: bool
    provider: LlmProvider | None = None
    bindings: list[LlmBinding] = []
    last_error: str = ""

class LlmJobDto(BaseModel):
    id: int
    chat_id: str
    message: str
    system_prompt: str
    temperature: float
    top_p: float
    max_tokens: int
    status: str
    reply: str = ""
    error: str = ""
    claimed_by: str = ""
    created_at: datetime | None = None


class LlmClaimResponse(BaseModel):
    job: LlmJobDto | None = None


class LlmJobResultRequest(BaseModel):
    reply: str = Field(min_length=1, max_length=8000)


class LlmJobFailRequest(BaseModel):
    error: str = Field(min_length=1, max_length=2000)


class LlmAgentStatus(BaseModel):
    online: bool
    last_seen_ago_sec: int
    pending: int
    running: int

