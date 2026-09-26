"""LLM integration tests: binding matcher, provider CRUD via API,
profile, bindings, test-chat against a mocked OpenAI-compatible server."""
import asyncio
import json
import os
import tempfile

_tmp = tempfile.mkdtemp(prefix="botctrl-llm-")

os.environ.setdefault("SECRET_KEY", "test-secret-key-0123456789abcdef-test")
os.environ.setdefault("ADMIN_PASSWORD", "test-admin-pass-123")
os.environ.setdefault("DATABASE_URL", f"sqlite+aiosqlite:///{_tmp}/test.db")
os.environ.setdefault("BOT_TOKEN", "")

import httpx  # noqa: E402
import pytest  # noqa: E402
from httpx import ASGITransport, AsyncClient  # noqa: E402

from app.main import app  # noqa: E402
from app.services.llm_service import (  # noqa: E402
    LLMService,
    binding_matches,
    normalize_base_url,
)

CAPTURED: list[httpx.Request] = []


def _handler(request: httpx.Request) -> httpx.Response:
    CAPTURED.append(request)
    if request.url.path.endswith("/models"):
        return httpx.Response(200, json={"data": [{"id": "llama3.1:8b"}, {"id": "qwen2.5:7b"}]})
    if request.url.path.endswith("/chat/completions"):
        return httpx.Response(200, json={"choices": [{"message": {"content": "TEST-REPLY"}}]})
    return httpx.Response(404, json={"error": "nope"})


@pytest.fixture(scope="module", autouse=True)
def _llm_setup():
    """ASGITransport skips lifespan — init DB + attach mock-backed LLMService."""
    from app.database import async_session_factory, init_db

    asyncio.run(init_db())
    app.state.llm = LLMService(async_session_factory, transport=httpx.MockTransport(_handler))
    CAPTURED.clear()
    yield


async def _client():
    return AsyncClient(transport=ASGITransport(app=app), base_url="http://test")


async def _auth_headers():
    async with await _client() as c:
        r = await c.post("/auth/login", json={"username": "admin", "password": "test-admin-pass-123"})
        assert r.status_code == 200, r.text
        return {"Authorization": f"Bearer {r.json()['access_token']}"}


# ---------- pure logic ----------
def test_normalize_base_url():
    assert normalize_base_url("http://localhost:11434") == "http://localhost:11434/v1"
    assert normalize_base_url("http://h:1234/v1/") == "http://h:1234/v1"


def test_binding_matcher():
    cmd = {"scope": "command", "pattern": "/ask", "enabled": True}
    assert binding_matches(cmd, "/ask hello", 1, "private")
    assert binding_matches(cmd, "/ask@mybot hi", 1, "private")
    assert not binding_matches(cmd, "hello", 1, "private")
    assert not binding_matches({**cmd, "enabled": False}, "/ask", 1, "private")

    chat = {"scope": "chat", "pattern": "-100123", "enabled": True}
    assert binding_matches(chat, "any text", -100123, "supergroup")
    assert not binding_matches(chat, "any text", 42, "private")

    chan = {"scope": "channel", "pattern": "@mychannel", "enabled": True}
    assert binding_matches(chan, "post", -100999, "channel", "mychannel")
    assert not binding_matches(chan, "post", -100999, "private", "mychannel")
    assert not binding_matches(chan, "post", -100999, "channel", "other")

    assert binding_matches({"scope": "all", "pattern": "", "enabled": True}, "x", 1, "private")


# ---------- API ----------
async def test_provider_crud_and_status():
    h = await _auth_headers()
    async with await _client() as c:
        # anonymous is rejected
        assert (await c.get("/llm/status")).status_code in (401, 403)

        r = await c.post("/llm/providers", headers=h, json={
            "name": "Ollama local", "kind": "ollama",
            "base_url": "http://fake-llm:11434", "model": "llama3.1:8b",
            "api_key": "sk-secret",
        })
        assert r.status_code == 201, r.text
        provider = r.json()
        assert provider["api_key_set"] is True  # masked, key never returned
        assert "sk-secret" not in r.text
        pid = provider["id"]

        # default URL filled from kind when blank
        r2 = await c.post("/llm/providers", headers=h, json={"name": "LM", "kind": "lmstudio"})
        assert r2.status_code == 201 and ":1234" in r2.json()["base_url"]
        await c.delete(f"/llm/providers/{r2.json()['id']}", headers=h)

        assert (await c.post(f"/llm/providers/{pid}/activate", headers=h)).status_code == 200
        status = (await c.get("/llm/status", headers=h)).json()
        assert status["configured"] is True and status["provider"]["id"] == pid

        upd = await c.put(f"/llm/providers/{pid}", headers=h, json={
            "name": "Ollama local", "kind": "ollama",
            "base_url": "http://fake-llm:11434", "model": "qwen2.5:7b", "api_key": "",
        })
        assert upd.status_code == 200 and upd.json()["model"] == "qwen2.5:7b"
        assert upd.json()["api_key_set"] is True  # empty key = keep existing


async def test_models_and_test_endpoint():
    h = await _auth_headers()
    async with await _client() as c:
        r = await c.post("/llm/providers", headers=h, json={
            "name": "t", "kind": "ollama", "base_url": "http://fake-llm:11434",
            "model": "llama3.1:8b",
        })
        pid = r.json()["id"]
        models = (await c.get(f"/llm/providers/{pid}/models", headers=h)).json()
        assert models["models"] == ["llama3.1:8b", "qwen2.5:7b"]
        test = (await c.post(f"/llm/providers/{pid}/test", headers=h)).json()
        assert test["ok"] is True and "reachable" in test["message"]


async def test_profile_bindings_and_chat():
    h = await _auth_headers()
    async with await _client() as c:
        pid = (await c.post("/llm/providers", headers=h, json={
            "name": "chatprov", "kind": "ollama",
            "base_url": "http://fake-llm:11434", "model": "llama3.1:8b",
        })).json()["id"]
        assert (await c.post(f"/llm/providers/{pid}/activate", headers=h)).status_code == 200

        prof = await c.put("/llm/profile", headers=h, json={
            "system_prompt": "You are a test bot.", "temperature": 0.33,
            "top_p": 0.8, "max_tokens": 100, "seed": 7,
        })
        assert prof.status_code == 200 and prof.json()["temperature"] == 0.33
        bad = await c.put("/llm/profile", headers=h, json={"temperature": 99})
        assert bad.status_code == 422  # out of range

        b = await c.post("/llm/bindings", headers=h, json={"scope": "command", "pattern": "/ask"})
        assert b.status_code == 201
        bid = b.json()["id"]
        no_pattern = await c.post("/llm/bindings", headers=h, json={"scope": "chat", "pattern": ""})
        assert no_pattern.status_code == 400

        chat = await c.post("/llm/chat", headers=h, json={"message": "hi"})
        assert chat.status_code == 200 and chat.json()["reply"] == "TEST-REPLY"
        sent = json.loads(CAPTURED[-1].read().decode())
        assert sent["model"] == "llama3.1:8b"
        assert sent["temperature"] == 0.33 and sent["max_tokens"] == 100 and sent["seed"] == 7
        assert sent["messages"][0] == {"role": "system", "content": "You are a test bot."}

        # toggle + delete binding; audit trail recorded
        assert (await c.put(f"/llm/bindings/{bid}", headers=h,
                            json={"scope": "command", "pattern": "/ask", "enabled": False})).status_code == 200
        assert (await c.delete(f"/llm/bindings/{bid}", headers=h)).status_code == 200
        audit = (await c.get("/audit", headers=h)).json()
        assert any(a["action"].startswith("llm.") for a in audit)


async def test_chat_without_active_provider_is_400():
    h = await _auth_headers()
    async with await _client() as c:
        # isolated empty store (own sqlite file) — no providers configured
        from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

        from app.database import init_db
        from app.models import Base

        engine = create_async_engine(f"sqlite+aiosqlite:///{_tmp}/bare.db")
        factory = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)

        async def _create() -> None:
            async with engine.begin() as conn:
                await conn.run_sync(Base.metadata.create_all)

        await _create()
        bare = LLMService(factory, transport=httpx.MockTransport(_handler))
        saved, app.state.llm = app.state.llm, bare
        try:
            r = await c.post("/llm/chat", headers=h, json={"message": "hi"})
        finally:
            app.state.llm = saved
        assert r.status_code == 400


async def test_generate_history_trim():
    from app.database import async_session_factory

    svc = LLMService(async_session_factory, transport=httpx.MockTransport(_handler))
    provider = await svc.save_provider({"name": "g", "kind": "ollama",
                                        "base_url": "http://fake-llm:11434", "model": "m"})
    await svc.activate(provider["id"])
    assert await svc.match("hello", 1, "private") is None  # no bindings yet
    await svc.add_binding("all", "")
    assert await svc.match("hello", 1, "private") is not None
    reply = await svc.generate(555, "hello")
    assert reply == "TEST-REPLY"
    history = svc._history["555"]
    assert [m["role"] for m in history] == ["user", "assistant"]
    svc.reset_history(555)
    assert svc._history.get("555") is None
    await svc.delete_provider(provider["id"])
