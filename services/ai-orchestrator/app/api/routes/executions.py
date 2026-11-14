"""Entry point used by the Core API to hand over an execution (service token of an allowed caller only)."""

from __future__ import annotations

from typing import Literal
from uuid import UUID

from fastapi import APIRouter, Request, status
from pydantic import BaseModel, ConfigDict

from app.api.dependencies.auth import Caller
from app.core.errors import UnavailableError
from app.core.logging import get_logger

router = APIRouter(prefix="/api/v1/executions", tags=["executions"])
log = get_logger("executions-api")


class ExecutionRequest(BaseModel):
    """Identifiers only. The instruction, the agent and the credential are fetched from the Core API,
    which re-validates the organization/execution pair: nothing in this body widens any permission."""

    model_config = ConfigDict(extra="forbid")
    organizationId: UUID  # noqa: N815 - wire name
    executionId: UUID  # noqa: N815 - wire name


class Accepted(BaseModel):
    status: Literal["ACCEPTED"] = "ACCEPTED"


@router.post("", status_code=status.HTTP_202_ACCEPTED, response_model=Accepted)
async def submit_execution(request: Request, body: ExecutionRequest, caller: Caller) -> Accepted:
    runner = request.app.state.executor
    if runner is None:
        raise UnavailableError("Execution is not available: the runtime is not configured.")
    runner.submit(body.organizationId, body.executionId)
    log.info("execution_accepted", execution_id=str(body.executionId), caller=caller.client_id)
    return Accepted()
