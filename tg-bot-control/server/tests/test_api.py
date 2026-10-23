"""API contract tests: auth, status, plugins, config, audit, backup."""
import asyncio
import os
import tempfile
from pathlib import Path

_tmp = tempfile.mkdtemp(prefix="botctrl-test-")

os.environ.setdefault("SECRET_KEY", "test-secret-key-0123456789abcdef-test")
os.environ.setdefault("ADMIN_PASSWORD", "test-admin-pass-123")
os.environ.setdefault("DATABASE_URL", f"sqlite+aiosqlite:///{_tmp}/test.db")
os.environ.setdefault("BOT_TOKEN", "")

import pytest  # noqa: E402
from httpx import ASGITransport, AsyncClient  # noqa: E402

from app.main import app  # noqa: E402


@pytest.fixture(scope="session", autouse=True)
def _setup():
    """ASGITransport skips lifespan — init DB + app.state manually."""
    from app.bot.core import BotManager
    from app.database import init_db
    from app.services.backup_service import BackupService

    asyncio.run(init_db())
    plugins_dir = Path(_tmp) / "plugins"
    backups_dir = Path(_tmp) / "backups"
    plugins_dir.mkdir(exist_ok=True)
    backups_dir.mkdir(exist_ok=True)
    app.state.bot = BotManager(token="", version="test", plugins_dir=plugins_dir)
    app.state.backups = BackupService(
        plugins_dir=plugins_dir, backup_dir=backups_dir, data_dir=Path(_tmp))
    yield



async def _client():
    return AsyncClient(transport=ASGITransport(app=app), base_url="http://test")


async def _auth_headers():
    async with await _client() as c:
        r = await c.post("/auth/login", json={"username": "admin", "password": "test-admin-pass-123"})
        assert r.status_code == 200, r.text
        return {"Authorization": f"Bearer {r.json()['access_token']}"}


async def test_login_rejects_bad_password():
    async with await _client() as c:
        r = await c.post("/auth/login", json={"username": "admin", "password": "wrong"})
        assert r.status_code == 401


async def test_protected_requires_token():
    async with await _client() as c:
        assert (await c.get("/status")).status_code in (401, 403)


async def test_status_shape():
    h = await _auth_headers()
    async with await _client() as c:
        r = await c.get("/status", headers=h)
        assert r.status_code == 200, r.text
        body = r.json()
        for key in ("online", "uptime_seconds", "version", "active_plugins", "last_errors", "recent_events"):
            assert key in body


async def test_plugin_crud_roundtrip(tmp_path=None):
    h = await _auth_headers()
    async with await _client() as c:
        pid = "apitest"
        await c.delete(f"/plugins/{pid}", headers=h)  # cleanup from previous runs
        r = await c.post("/plugins", headers=h, json={
            "id": pid, "name": "API Test", "description": "t",
            "triggers": [{"type": "command", "pattern": "/ping", "response_text": "pong"}],
        })
        assert r.status_code == 201, r.text
        assert (await c.post(f"/plugins/{pid}/enable", headers=h)).status_code == 200
        assert (await c.post(f"/plugins/{pid}/disable", headers=h)).status_code == 200
        bad = await c.put(f"/plugins/{pid}", headers=h, json={"code": "import os\n"})
        assert bad.status_code == 400  # sandbox must reject
        assert (await c.get(f"/plugins/{pid}/logs", headers=h)).status_code == 200
        assert (await c.delete(f"/plugins/{pid}", headers=h)).status_code == 200


async def test_config_and_audit_and_logs():
    h = await _auth_headers()
    async with await _client() as c:
        assert (await c.get("/config", headers=h)).status_code == 200
        bad = await c.put("/config", headers=h, json={"values": {"BOT_TOKEN": "x"}})
        assert bad.status_code == 400  # secrets not editable
        ok = await c.put("/config", headers=h, json={"values": {"bot_name": "TestBot"}})
        assert ok.status_code == 200
        assert (await c.get("/audit", headers=h)).status_code == 200
        assert (await c.get("/logs", headers=h)).status_code == 200
        assert (await c.get("/backups", headers=h)).status_code == 200
