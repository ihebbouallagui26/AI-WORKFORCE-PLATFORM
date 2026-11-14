from __future__ import annotations

from fastapi import APIRouter, Request
from pydantic import BaseModel

from app import __version__
from app.api.dependencies.auth import Caller
from app.providers.registry import PROVIDER_KINDS

router = APIRouter(prefix="/api/v1/runtime", tags=["runtime"])


class RuntimeInfo(BaseModel):
    service: str
    version: str
    environment: str
    providers: list[str]
    mcpServers: list[str]
    features: dict[str, bool]


@router.get("/info", response_model=RuntimeInfo)
async def runtime_info(request: Request, caller: Caller) -> RuntimeInfo:
    """Capabilities of this build. C0 is a foundation: nothing here executes an agent."""
    settings = request.app.state.settings
    return RuntimeInfo(
        service="ai-orchestrator",
        version=__version__,
        environment=settings.environment,
        providers=list(PROVIDER_KINDS),
        mcpServers=["diagnostic"],
        features={
            "agentExecution": request.app.state.executor is not None,
            "toolExecution": False,
            "diagnosticGraph": True,
            "checkpointPersistence": request.app.state.checkpoint_probe is not None,
        },
    )
