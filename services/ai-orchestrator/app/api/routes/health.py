"""Liveness and readiness. Responses never expose hostnames, credentials or error details."""

from __future__ import annotations

from typing import Literal

from fastapi import APIRouter, Request, Response
from pydantic import BaseModel

router = APIRouter(prefix="/health", tags=["health"])


class LiveResponse(BaseModel):
    status: Literal["UP"] = "UP"


class ReadyResponse(BaseModel):
    status: Literal["UP", "DOWN"]
    checks: dict[str, Literal["UP", "DOWN", "NOT_CONFIGURED"]]


@router.get("/live", response_model=LiveResponse)
async def live() -> LiveResponse:
    return LiveResponse()


@router.get("/ready", response_model=ReadyResponse, responses={503: {"model": ReadyResponse}})
async def ready(request: Request, response: Response) -> ReadyResponse:
    probe = request.app.state.checkpoint_probe  # async callable returning bool, or None when unconfigured
    checks: dict[str, Literal["UP", "DOWN", "NOT_CONFIGURED"]] = {}
    checks["checkpointStore"] = "NOT_CONFIGURED" if probe is None else ("UP" if await probe() else "DOWN")
    healthy = all(value == "UP" for value in checks.values())
    if not healthy:
        response.status_code = 503
    return ReadyResponse(status="UP" if healthy else "DOWN", checks=checks)
