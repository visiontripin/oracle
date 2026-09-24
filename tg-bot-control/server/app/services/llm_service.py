"""Local LLM integration — OpenAI-compatible providers.

Works with any local OpenAI-compatible server: Ollama (11434), LM Studio
(1234), llama.cpp server (8080), vLLM (8000) or a custom endpoint.

Config lives in the `settings` table as JSON (keys `llm:*`):
  llm:providers  -> list of provider dicts
  llm:active     -> active provider id | None
  llm:profile    -> system prompt + sampling parameters
  llm:bindings   -> rules connecting the LLM to the bot / channels

Security: the LLM runs on the server host (not in the sandboxed plugin
runtime) and is reachable only via declarative bindings or the admin-only
`POST /llm/chat` test endpoint. API keys, if any, are stored server-side
and never returned to clients (masked as `api_key_set`).
"""
from __future__ import annotations

import json
import uuid
from typing import Any

import httpx
from sqlalchemy.ext.asyncio import AsyncSession

from ..models import Setting

K_DEFAULT_URLS: dict[str, str] = {
    "ollama": "http://localhost:11434/v1",
    "lmstudio": "http://localhost:1234/v1",
    "llamacpp": "http://localhost:8080/v1",
    "vllm": "http://localhost:8000/v1",
    "custom": "",
}

DEFAULT_PROFILE: dict[str, Any] = {
    "system_prompt": "You are a helpful assistant inside a Telegram bot. Answer concisely.",
    "temperature": 0.7,
    "top_p": 0.9,
    "max_tokens": 512,
    "seed": None,
    "timeout_sec": 120,
    "context_chars": 4000,
}

_SCOPES = ("all", "command", "chat", "channel")


class LLMError(RuntimeError):
    """Provider/runtime failure."""


class LLMConfigError(LLMError):
    """Misconfiguration (no active provider, no model selected, ...)."""


def normalize_base_url(url: str) -> str:
    base = (url or "").strip().rstrip("/")
    if not base:
        raise LLMConfigError("base_url is empty")
    if not base.endswith("/v1"):
        base = f"{base}/v1"
    return base


def binding_matches(
    binding: dict[str, Any], text: str, chat_id: int | str,
    chat_type: str, username: str = "",
) -> bool:
    """Pure matching logic for scope/pattern (unit-tested)."""
    scope = binding.get("scope")
    pattern = (binding.get("pattern") or "").strip()
    if not binding.get("enabled", True):
        return False
    if scope == "all":
        return True
    if scope == "command":
        first = (text or "").split()
        if not first:
            return False
        cmd = first[0].split("@")[0]
        want = pattern if pattern.startswith("/") else f"/{pattern}"
        return cmd == want
    if scope == "chat":
        return str(chat_id) == pattern
    if scope == "channel":
        if chat_type != "channel":
            return False
        return str(chat_id) == pattern or (username and f"@{username}" == pattern)
    return False


class LLMService:
    """Provider store + OpenAI-compatible chat client + bot bindings."""

    def __init__(self, session_factory, transport: httpx.BaseTransport | None = None) -> None:
        self._factory = session_factory
        self._transport = transport  # injectable httpx.MockTransport for tests
        self.last_error: str = ""
        self._history: dict[str, list[dict[str, str]]] = {}

    # ---------- settings KV ----------
    async def _kv_get(self, key: str, default: Any) -> Any:
        async with self._factory() as db:  # type: AsyncSession
            row = await db.get(Setting, key)
            if row is None or not row.value:
                return default
            try:
                return json.loads(row.value)
            except (ValueError, TypeError):
                return default

    async def _kv_set(self, key: str, value: Any) -> None:
        payload = json.dumps(value, ensure_ascii=False)
        async with self._factory() as db:
            row = await db.get(Setting, key)
            if row is None:
                db.add(Setting(key=key, value=payload))
            else:
                row.value = payload
            await db.commit()

    # ---------- providers ----------
    async def providers(self) -> list[dict[str, Any]]:
        return list(await self._kv_get("llm:providers", []))

    async def get_provider(self, provider_id: str) -> dict[str, Any] | None:
        for p in await self.providers():
            if p["id"] == provider_id:
                return p
        return None

    async def active_provider(self) -> dict[str, Any] | None:
        active_id = await self._kv_get("llm:active", None)
        if not active_id:
            return None
        provider = await self.get_provider(str(active_id))
        if provider is None or not provider.get("enabled", True):
            return None
        return provider

    @staticmethod
    def masked(provider: dict[str, Any]) -> dict[str, Any]:
        return {
            "id": provider["id"], "name": provider.get("name", ""),
            "kind": provider.get("kind", "custom"),
            "base_url": provider.get("base_url", ""),
            "model": provider.get("model", ""),
            "enabled": bool(provider.get("enabled", True)),
            "api_key_set": bool(provider.get("api_key")),
        }

    async def save_provider(self, data: dict[str, Any]) -> dict[str, Any]:
        kind = data.get("kind", "custom")
        if kind not in K_DEFAULT_URLS:
            raise LLMConfigError(f"unknown provider kind: {kind}")
        base_url = (data.get("base_url") or "").strip() or K_DEFAULT_URLS[kind]
        if not base_url:
            raise LLMConfigError("custom provider requires base_url")
        provider = {
            "id": data.get("id") or uuid.uuid4().hex[:8],
            "name": (data.get("name") or kind)[:64],
            "kind": kind,
            "base_url": base_url,
            "api_key": (data.get("api_key") or "").strip(),
            "model": (data.get("model") or "").strip(),
            "enabled": bool(data.get("enabled", True)),
        }
        providers = await self.providers()
        providers = [p for p in providers if p["id"] != provider["id"]]
        providers.append(provider)
        await self._kv_set("llm:providers", providers)
        return provider

    async def update_provider(self, provider_id: str, patch: dict[str, Any]) -> dict[str, Any]:
        provider = await self.get_provider(provider_id)
        if provider is None:
            raise LLMConfigError(f"provider '{provider_id}' not found")
        api_key = (patch.get("api_key") or "").strip()
        for field in ("name", "kind", "base_url", "model"):
            if field in patch and patch[field] is not None and patch[field] != "":
                provider[field] = str(patch[field]).strip() if field in ("base_url", "model", "name") else patch[field]
        if "enabled" in patch and patch["enabled"] is not None:
            provider["enabled"] = bool(patch["enabled"])
        if api_key:  # empty string = keep existing key
            provider["api_key"] = api_key
        return await self.save_provider(provider)

    async def delete_provider(self, provider_id: str) -> None:
        providers = [p for p in await self.providers() if p["id"] != provider_id]
        await self._kv_set("llm:providers", providers)
        active_id = await self._kv_get("llm:active", None)
        if active_id == provider_id:
            await self._kv_set("llm:active", None)

    async def activate(self, provider_id: str) -> None:
        if await self.get_provider(provider_id) is None:
            raise LLMConfigError(f"provider '{provider_id}' not found")
        await self._kv_set("llm:active", provider_id)

    # ---------- profile / bindings ----------
    async def profile(self) -> dict[str, Any]:
        stored = await self._kv_get("llm:profile", {})
        profile = dict(DEFAULT_PROFILE)
        profile.update({k: v for k, v in stored.items() if k in DEFAULT_PROFILE})
        return profile

    async def set_profile(self, values: dict[str, Any]) -> dict[str, Any]:
        current = await self.profile()
        current.update({k: v for k, v in values.items() if k in DEFAULT_PROFILE})
        await self._kv_set("llm:profile", current)
        return current

    async def bindings(self) -> list[dict[str, Any]]:
        return list(await self._kv_get("llm:bindings", []))

    async def add_binding(self, scope: str, pattern: str, enabled: bool = True) -> dict[str, Any]:
        if scope not in _SCOPES:
            raise LLMConfigError(f"scope must be one of {_SCOPES}")
        if scope in ("command", "chat", "channel") and not pattern.strip():
            raise LLMConfigError(f"scope '{scope}' requires a pattern")
        binding = {"id": uuid.uuid4().hex[:8], "scope": scope,
                   "pattern": pattern.strip(), "enabled": bool(enabled)}
        all_bindings = await self.bindings()
        all_bindings.append(binding)
        await self._kv_set("llm:bindings", all_bindings)
        return binding

    async def update_binding(self, binding_id: str, enabled: bool) -> dict[str, Any]:
        all_bindings = await self.bindings()
        for b in all_bindings:
            if b["id"] == binding_id:
                b["enabled"] = bool(enabled)
                await self._kv_set("llm:bindings", all_bindings)
                return b
        raise LLMConfigError(f"binding '{binding_id}' not found")

    async def delete_binding(self, binding_id: str) -> None:
        remaining = [b for b in await self.bindings() if b["id"] != binding_id]
        await self._kv_set("llm:bindings", remaining)

    # ---------- matching & generation ----------
    async def match(
        self, text: str, chat_id: int | str,
        chat_type: str = "private", username: str = "",
    ) -> dict[str, Any] | None:
        """First enabled binding matching this message, if LLM is usable."""
        provider = await self.active_provider()
        if provider is None:
            return None
        for binding in await self.bindings():
            if not binding.get("enabled", True):
                continue
            try:
                if binding_matches(binding, text, chat_id, chat_type, username):
                    return binding
            except Exception:  # noqa: BLE001 — broken binding must not crash dispatch
                continue
        return None

    def _headers(self, provider: dict[str, Any]) -> dict[str, str]:
        key = provider.get("api_key") or ""
        return {"Authorization": f"Bearer {key}"} if key else {}

    async def _chat(
        self, provider: dict[str, Any], messages: list[dict[str, str]],
        profile: dict[str, Any],
    ) -> str:
        payload: dict[str, Any] = {
            "model": provider["model"],
            "messages": messages,
            "temperature": float(profile["temperature"]),
            "top_p": float(profile["top_p"]),
            "max_tokens": int(profile["max_tokens"]),
        }
        if profile.get("seed") is not None:
            payload["seed"] = int(profile["seed"])
        url = f"{normalize_base_url(provider['base_url'])}/chat/completions"
        try:
            async with httpx.AsyncClient(
                timeout=float(profile["timeout_sec"]), transport=self._transport,
            ) as client:
                resp = await client.post(url, json=payload, headers=self._headers(provider))
        except httpx.HTTPError as exc:
            self.last_error = f"provider unreachable: {exc}"
            raise LLMError(self.last_error) from exc
        if resp.status_code >= 400:
            self.last_error = f"provider HTTP {resp.status_code}: {resp.text[:200]}"
            raise LLMError(self.last_error)
        try:
            content = resp.json()["choices"][0]["message"]["content"]
        except (ValueError, KeyError, IndexError, TypeError) as exc:
            self.last_error = "unexpected provider response shape"
            raise LLMError(self.last_error) from exc
        return content or ""

    async def chat_once(
        self, text: str, system: str | None = None, provider_id: str | None = None,
    ) -> str:
        """Stateless completion — used by the admin test endpoint."""
        provider = (
            await self.get_provider(provider_id) if provider_id
            else await self.active_provider()
        )
        if provider is None:
            raise LLMConfigError("no active LLM provider")
        if not provider.get("model"):
            raise LLMConfigError("provider has no model selected")
        profile = await self.profile()
        messages: list[dict[str, str]] = []
        system_text = system if system is not None else profile["system_prompt"]
        if system_text:
            messages.append({"role": "system", "content": system_text})
        messages.append({"role": "user", "content": text})
        return await self._chat(provider, messages, profile)

    async def generate(self, chat_id: int | str, text: str) -> str:
        """Completion with per-chat in-memory history (context window capped)."""
        provider = await self.active_provider()
        if provider is None:
            raise LLMConfigError("no active LLM provider")
        if not provider.get("model"):
            raise LLMConfigError("provider has no model selected")
        profile = await self.profile()
        key = str(chat_id)
        history = self._history.setdefault(key, [])
        messages: list[dict[str, str]] = []
        if profile["system_prompt"]:
            messages.append({"role": "system", "content": profile["system_prompt"]})
        messages += history + [{"role": "user", "content": text}]
        reply = await self._chat(provider, messages, profile)
        history += [{"role": "user", "content": text}, {"role": "assistant", "content": reply}]
        # Trim oldest pairs beyond the configured character budget.
        budget = int(profile["context_chars"])
        while history and sum(len(m["content"]) for m in history) > budget:
            history.pop(0)
        self._history[key] = history[-40:]
        return reply

    def reset_history(self, chat_id: int | str | None = None) -> None:
        if chat_id is None:
            self._history.clear()
        else:
            self._history.pop(str(chat_id), None)

    async def list_models(self, base_url: str, api_key: str = "") -> list[str]:
        url = f"{normalize_base_url(base_url)}/models"
        try:
            async with httpx.AsyncClient(timeout=15, transport=self._transport) as client:
                resp = await client.get(url, headers={"Authorization": f"Bearer {api_key}"} if api_key else {})
        except httpx.HTTPError as exc:
            self.last_error = f"provider unreachable: {exc}"
            raise LLMError(self.last_error) from exc
        if resp.status_code >= 400:
            self.last_error = f"provider HTTP {resp.status_code}: {resp.text[:200]}"
            raise LLMError(self.last_error)
        try:
            data = resp.json().get("data", [])
        except ValueError as exc:
            raise LLMError("unexpected /models response") from exc
        return [m.get("id", "") for m in data if isinstance(m, dict) and m.get("id")]

    async def test_provider(self, provider_id: str) -> dict[str, Any]:
        provider = await self.get_provider(provider_id)
        if provider is None:
            raise LLMConfigError(f"provider '{provider_id}' not found")
        models = await self.list_models(provider["base_url"], provider.get("api_key") or "")
        return {"ok": True, "models": models, "message": f"reachable, {len(models)} model(s)"}


async def seed_from_env(service: LLMService, settings) -> None:
    """Optionally bootstrap one provider from LLM_BASE_URL / LLM_MODEL env."""
    if not getattr(settings, "LLM_BASE_URL", ""):
        return
    if await service.providers():
        return
    url = settings.LLM_BASE_URL
    kind = "ollama" if ":11434" in url else "lmstudio" if ":1234" in url else "custom"
    provider = await service.save_provider({
        "name": "Local LLM (env)", "kind": kind, "base_url": url,
        "model": getattr(settings, "LLM_MODEL", "") or "",
    })
    if provider.get("model"):
        await service.activate(provider["id"])
