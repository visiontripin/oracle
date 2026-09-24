"""Admin API — local LLM management (/llm/*).

RBAC: GET = any authenticated user; provider CRUD / profile / bindings /
activate / test / chat: operator+; provider create/delete: admin.
Every mutation is written to the audit log.
"""
from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Request
from sqlalchemy.ext.asyncio import AsyncSession

from ..audit import audit
from ..database import get_db
from ..schemas import (
    LlmAgentStatus,
    LlmBinding,
    LlmBindingCreate,
    LlmChatRequest,
    LlmChatResponse,
    LlmClaimResponse,
    LlmJobDto,
    LlmJobFailRequest,
    LlmJobResultRequest,
    LlmModelsResponse,
    LlmProfile,
    LlmProvider,
    LlmProviderCreate,
    LlmStatus,
    LlmTestResponse,
    MessageResponse,
)
from ..security import require_role
from ..services.llm_service import LLMConfigError, LLMError

router = APIRouter(prefix="/llm", tags=["llm"])

needs_admin = require_role("admin")
needs_operator = require_role("operator", "admin")
needs_viewer = require_role("viewer")


def _svc(request: Request):
    svc = getattr(request.app.state, "llm", None)
    if svc is None:
        raise HTTPException(503, "LLM service is not initialized")
    return svc


@router.get("/status", response_model=LlmStatus)
async def llm_status(
    request: Request,
    _u: Annotated[dict, Depends(needs_viewer)],
):
    svc = _svc(request)
    provider = await svc.active_provider()
    return LlmStatus(
        configured=provider is not None,
        provider=LlmProvider(**svc.masked(provider)) if provider else None,
        bindings=[LlmBinding(**b) for b in await svc.bindings()],
        last_error=svc.last_error,
    )


# ---------- providers ----------
@router.get("/providers", response_model=list[LlmProvider])
async def list_providers(
    request: Request,
    _u: Annotated[dict, Depends(needs_viewer)],
):
    svc = _svc(request)
    return [LlmProvider(**svc.masked(p)) for p in await svc.providers()]


@router.post("/providers", response_model=LlmProvider, status_code=201)
async def create_provider(
    payload: LlmProviderCreate, request: Request,
    user: Annotated[dict, Depends(needs_admin)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    svc = _svc(request)
    try:
        provider = await svc.save_provider(payload.model_dump())
    except LLMConfigError as exc:
        raise HTTPException(400, str(exc)) from exc
    await audit(db, request, user["username"], "llm.provider.create",
                provider["id"], f"{provider['kind']} {provider['base_url']} model={provider['model']!r}")
    return LlmProvider(**svc.masked(provider))


@router.put("/providers/{provider_id}", response_model=LlmProvider)
async def update_provider(
    provider_id: str, payload: LlmProviderCreate, request: Request,
    user: Annotated[dict, Depends(needs_admin)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    svc = _svc(request)
    try:
        provider = await svc.update_provider(provider_id, payload.model_dump())
    except LLMConfigError as exc:
        raise HTTPException(404, str(exc)) from exc
    await audit(db, request, user["username"], "llm.provider.update", provider_id)
    return LlmProvider(**svc.masked(provider))


@router.delete("/providers/{provider_id}", response_model=MessageResponse)
async def delete_provider(
    provider_id: str, request: Request,
    user: Annotated[dict, Depends(needs_admin)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    svc = _svc(request)
    try:
        await svc.delete_provider(provider_id)
    except LLMConfigError as exc:
        raise HTTPException(404, str(exc)) from exc
    await audit(db, request, user["username"], "llm.provider.delete", provider_id)
    return MessageResponse(message=f"Provider '{provider_id}' deleted")


@router.post("/providers/{provider_id}/activate", response_model=MessageResponse)
async def activate_provider(
    provider_id: str, request: Request,
    user: Annotated[dict, Depends(needs_operator)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    svc = _svc(request)
    try:
        await svc.activate(provider_id)
    except LLMConfigError as exc:
        raise HTTPException(404, str(exc)) from exc
    await audit(db, request, user["username"], "llm.provider.activate", provider_id)
    return MessageResponse(message=f"Provider '{provider_id}' activated")


@router.get("/providers/{provider_id}/models", response_model=LlmModelsResponse)
async def provider_models(
    provider_id: str, request: Request,
    _u: Annotated[dict, Depends(needs_viewer)],
):
    svc = _svc(request)
    provider = await svc.get_provider(provider_id)
    if provider is None:
        raise HTTPException(404, f"provider '{provider_id}' not found")
    try:
        models = await svc.list_models(provider["base_url"], provider.get("api_key") or "")
    except LLMError as exc:
        raise HTTPException(502, str(exc)) from exc
    return LlmModelsResponse(models=models)


@router.post("/providers/{provider_id}/test", response_model=LlmTestResponse)
async def test_provider(
    provider_id: str, request: Request,
    _u: Annotated[dict, Depends(needs_operator)],
):
    svc = _svc(request)
    try:
        return LlmTestResponse(**await svc.test_provider(provider_id))
    except LLMConfigError as exc:
        raise HTTPException(404, str(exc)) from exc
    except LLMError as exc:
        raise HTTPException(502, str(exc)) from exc


# ---------- profile ----------
@router.get("/profile", response_model=LlmProfile)
async def get_profile(
    request: Request,
    _u: Annotated[dict, Depends(needs_viewer)],
):
    return LlmProfile(**await _svc(request).profile())


@router.put("/profile", response_model=LlmProfile)
async def put_profile(
    payload: LlmProfile, request: Request,
    user: Annotated[dict, Depends(needs_operator)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    profile = await _svc(request).set_profile(payload.model_dump())
    await audit(db, request, user["username"], "llm.profile.update", "profile",
                f"temperature={profile['temperature']} max_tokens={profile['max_tokens']}")
    return LlmProfile(**profile)


# ---------- bindings ----------
@router.get("/bindings", response_model=list[LlmBinding])
async def list_bindings(
    request: Request,
    _u: Annotated[dict, Depends(needs_viewer)],
):
    return [LlmBinding(**b) for b in await _svc(request).bindings()]


@router.post("/bindings", response_model=LlmBinding, status_code=201)
async def add_binding(
    payload: LlmBindingCreate, request: Request,
    user: Annotated[dict, Depends(needs_operator)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    svc = _svc(request)
    try:
        binding = await svc.add_binding(payload.scope, payload.pattern, payload.enabled)
    except LLMConfigError as exc:
        raise HTTPException(400, str(exc)) from exc
    await audit(db, request, user["username"], "llm.binding.create",
                binding["id"], f"{binding['scope']}:{binding['pattern']}")
    return LlmBinding(**binding)


@router.put("/bindings/{binding_id}", response_model=LlmBinding)
async def toggle_binding(
    binding_id: str, payload: LlmBindingCreate, request: Request,
    user: Annotated[dict, Depends(needs_operator)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    svc = _svc(request)
    try:
        binding = await svc.update_binding(binding_id, payload.enabled)
    except LLMConfigError as exc:
        raise HTTPException(404, str(exc)) from exc
    await audit(db, request, user["username"], "llm.binding.update",
                binding_id, f"enabled={payload.enabled}")
    return LlmBinding(**binding)


@router.delete("/bindings/{binding_id}", response_model=MessageResponse)
async def delete_binding(
    binding_id: str, request: Request,
    user: Annotated[dict, Depends(needs_operator)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    await _svc(request).delete_binding(binding_id)
    await audit(db, request, user["username"], "llm.binding.delete", binding_id)
    return MessageResponse(message=f"Binding '{binding_id}' deleted")


# ---------- test chat ----------
@router.post("/chat", response_model=LlmChatResponse)
async def llm_chat(
    payload: LlmChatRequest, request: Request,
    _u: Annotated[dict, Depends(needs_operator)],
):
    svc = _svc(request)
    try:
        reply = await svc.chat_once(payload.message)
    except LLMConfigError as exc:
        raise HTTPException(400, str(exc)) from exc
    except LLMError as exc:
        raise HTTPException(502, str(exc)) from exc
    return LlmChatResponse(reply=reply)

# ---------- on-device jobs (agent = phone running the model locally) ----------
def _jobs(request: Request):
    jobs = getattr(request.app.state, "llm_jobs", None)
    if jobs is None:
        raise HTTPException(503, "Job queue is not initialized")
    return jobs


def _job_dto(job) -> LlmJobDto:
    import time as _time

    return LlmJobDto(
        id=job.id, chat_id=job.chat_id, message=job.message,
        system_prompt=job.system_prompt, temperature=job.temperature,
        top_p=job.top_p, max_tokens=job.max_tokens, status=job.status,
        reply=job.reply, error=job.error, claimed_by=job.claimed_by,
        created_at=job.created_at.isoformat() if job.created_at else None,
    )


@router.post("/jobs/claim", response_model=LlmClaimResponse)
async def claim_job(
    request: Request,
    user: Annotated[dict, Depends(needs_operator)],
):
    """Agent polls this: takes the oldest pending job for local generation."""
    jobs = _jobs(request)
    job = await jobs.claim(agent=user["username"])
    return LlmClaimResponse(job=_job_dto(job) if job else None)


@router.post("/jobs/{job_id}/result", response_model=MessageResponse)
async def job_result(
    job_id: int, payload: LlmJobResultRequest, request: Request,
    _u: Annotated[dict, Depends(needs_operator)],
):
    jobs = _jobs(request)
    jobs.agent_seen(_u["username"])
    if not await jobs.finish(job_id, payload.reply):
        raise HTTPException(409, f"job {job_id} not claimable (done/expired?)")
    return MessageResponse(message="result accepted")


@router.post("/jobs/{job_id}/fail", response_model=MessageResponse)
async def job_fail(
    job_id: int, payload: LlmJobFailRequest, request: Request,
    _u: Annotated[dict, Depends(needs_operator)],
):
    jobs = _jobs(request)
    jobs.agent_seen(_u["username"])
    if not await jobs.fail(job_id, payload.error):
        raise HTTPException(409, f"job {job_id} not claimable (done/expired?)")
    return MessageResponse(message="failure recorded")


@router.get("/jobs", response_model=list[LlmJobDto])
async def recent_jobs(
    request: Request,
    _u: Annotated[dict, Depends(needs_viewer)],
    limit: int = 30,
):
    return [_job_dto(j) for j in await _jobs(request).recent(limit)]


@router.get("/agent/status", response_model=LlmAgentStatus)
async def agent_status(
    request: Request,
    _u: Annotated[dict, Depends(needs_viewer)],
):
    import time as _time

    jobs = _jobs(request)
    counts = await jobs.counts()
    ago = int(_time.time() - jobs.agent_last_seen) if jobs.agent_last_seen else -1
    return LlmAgentStatus(online=jobs.agent_online, last_seen_ago_sec=ago,
                          pending=counts["pending"], running=counts["running"])

