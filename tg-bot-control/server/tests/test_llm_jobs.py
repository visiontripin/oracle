"""On-device job queue: service-level lifecycle + agent API endpoints."""
import asyncio
import os
import tempfile

_tmp = tempfile.mkdtemp(prefix="botctrl-jobs-")

os.environ.setdefault("SECRET_KEY", "test-secret-key-0123456789abcdef-test")
os.environ.setdefault("ADMIN_PASSWORD", "test-admin-pass-123")
os.environ.setdefault("DATABASE_URL", f"sqlite+aiosqlite:///{_tmp}/test.db")
os.environ.setdefault("BOT_TOKEN", "")

import pytest  # noqa: E402
from httpx import ASGITransport, AsyncClient  # noqa: E402

from app.main import app  # noqa: E402
from app.services.llm_jobs import JobQueue  # noqa: E402


@pytest.fixture(scope="module", autouse=True)
def _setup():
    """ASGITransport skips lifespan — init DB + attach a fresh JobQueue."""
    from app.database import async_session_factory, init_db

    asyncio.run(init_db())
    app.state.llm_jobs = JobQueue(async_session_factory)
    yield


async def _client():
    return AsyncClient(transport=ASGITransport(app=app), base_url="http://test")


async def _auth_headers():
    async with await _client() as c:
        r = await c.post("/auth/login", json={"username": "admin", "password": "test-admin-pass-123"})
        assert r.status_code == 200, r.text
        return {"Authorization": f"Bearer {r.json()['access_token']}"}


async def test_queue_lifecycle_done():
    queue = JobQueue((await _engine_factory()))
    job = await queue.enqueue(42, "hello", "be nice", {"temperature": 0.5, "max_tokens": 64})
    assert job.status == "pending" and job.temperature == 0.5

    waiter = asyncio.create_task(queue.wait_result(job.id, timeout_sec=5))
    await asyncio.sleep(0.05)
    claimed = await queue.claim("phone-agent")
    assert claimed is not None and claimed.id == job.id and claimed.status == "running"
    assert queue.agent_online is True

    ok = await queue.finish(job.id, "DEVICE-REPLY")
    assert ok is True
    assert await waiter == "DEVICE-REPLY"
    done = await queue.get(job.id)
    assert done.status == "done" and done.reply == "DEVICE-REPLY"
    # late duplicate result -> rejected
    assert await queue.finish(job.id, "again") is False


async def test_queue_fail_and_empty_claim():
    queue = JobQueue((await _engine_factory()))
    assert await queue.claim("nobody") is None  # empty
    job = await queue.enqueue(1, "q", "", None)
    waiter = asyncio.create_task(queue.wait_result(job.id, timeout_sec=5))
    await asyncio.sleep(0.05)
    await queue.claim("agent")
    await queue.fail(job.id, "OOM on device")
    assert await waiter is None  # failed -> None, not exception
    failed = await queue.get(job.id)
    assert failed.status == "failed" and "OOM" in failed.error


async def test_queue_timeout_expires():
    queue = JobQueue((await _engine_factory()))
    job = await queue.enqueue(2, "q", "", None)
    reply = await queue.wait_result(job.id, timeout_sec=0.05)
    assert reply is None
    expired = await queue.get(job.id)
    assert expired.status == "expired"
    assert await queue.finish(job.id, "late") is False  # late answers rejected


async def _engine_factory():
    from app.database import async_session_factory

    return async_session_factory


async def test_agent_api_flow():
    h = await _auth_headers()
    async with await _client() as c:
        # anonymous rejected
        assert (await c.post("/llm/jobs/claim")).status_code in (401, 403)

        # enqueue via service directly (simulates bot core)
        queue = app.state.llm_jobs
        await queue.enqueue(777, "say hi", "sys prompt", {"temperature": 0.4})

        r = await c.post("/llm/jobs/claim", headers=h)
        assert r.status_code == 200, r.text
        job = r.json()["job"]
        assert job is not None and job["chat_id"] == "777" and job["status"] == "running"
        jid = job["id"]

        status = (await c.get("/llm/agent/status", headers=h)).json()
        assert status["online"] is True and status["running"] >= 1

        # second claim while one is running returns the job only if another pending exists
        r2 = await c.post("/llm/jobs/claim", headers=h)
        assert r2.status_code == 200

        res = await c.post(f"/llm/jobs/{jid}/result", headers=h, json={"reply": "hi from phone"})
        assert res.status_code == 200
        jobs = (await c.get("/llm/jobs", headers=h)).json()
        target = next(j for j in jobs if j["id"] == jid)
        assert target["status"] == "done" and target["reply"] == "hi from phone"


async def test_job_result_409_on_unknown():
    h = await _auth_headers()
    async with await _client() as c:
        r = await c.post("/llm/jobs/999999/result", headers=h, json={"reply": "x"})
        assert r.status_code == 409
        f = await c.post("/llm/jobs/999999/fail", headers=h, json={"error": "x"})
        assert f.status_code == 409
