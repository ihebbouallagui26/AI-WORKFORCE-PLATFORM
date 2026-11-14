"""Runs supervised executions in the background of the service, bounded and cancellable.

Design (documented trade-off): the Core API asks for a run with a short HTTP call that is answered
``202`` immediately; the run itself is an in-process asyncio task. That avoids a queue or broker for
the first execution and never holds a request open for the duration of a model call. Consequences:
runs do not survive an orchestrator restart (the Core API's reaper fails them with ``TIMEOUT`` after
``app.executions.timeout``) and capacity is bounded by ``max_concurrent_executions`` (a full runtime
answers 503, which the Core API turns into a safe ``ORCHESTRATOR_UNAVAILABLE`` failure).
"""

from __future__ import annotations

import asyncio
from typing import Any
from uuid import UUID

from langgraph.checkpoint.base import BaseCheckpointSaver

from app.clients.core_api_client import CoreApiClient
from app.core.config import Settings
from app.core.errors import OrchestratorError, UnavailableError
from app.core.logging import get_logger
from app.orchestration.graphs.execution import (
    FAILURES,
    RunContext,
    build_execution_graph,
    classify,
)
from app.orchestration.state.models import thread_id_for
from app.providers.registry import ProviderRegistry

log = get_logger("executor")


class DuplicateExecution(OrchestratorError):
    status = 409
    code = "duplicate-execution"
    default_message = "This execution is already being processed."


class ExecutionRunner:
    def __init__(
        self,
        settings: Settings,
        core: CoreApiClient,
        providers: ProviderRegistry,
        checkpointer: BaseCheckpointSaver[Any],
    ) -> None:
        self._settings = settings
        self._core = core
        self._providers = providers
        self._checkpointer = checkpointer
        self._tasks: dict[str, asyncio.Task[None]] = {}

    @property
    def active(self) -> int:
        return len(self._tasks)

    def submit(self, organization_id: UUID, execution_id: UUID) -> None:
        """Starts the run in the background. The ids are NOT trusted: the Core API validates the pair
        in its first call (``start``), and answers 404 for any organization that does not own it."""
        key = thread_id_for(organization_id, execution_id)
        if key in self._tasks:
            raise DuplicateExecution
        if len(self._tasks) >= self._settings.max_concurrent_executions:
            raise UnavailableError("The orchestrator is at capacity.")
        task = asyncio.create_task(self._run(organization_id, execution_id), name=f"execution-{execution_id}")
        self._tasks[key] = task
        task.add_done_callback(lambda _t, k=key: self._tasks.pop(k, None))

    async def _run(self, organization_id: UUID, execution_id: UUID) -> None:
        run = RunContext(organization_id=organization_id, execution_id=execution_id)
        graph = build_execution_graph(
            self._checkpointer,
            run,
            core=self._core,
            adapter_factory=self._providers.adapter_with,
            enabled_providers=self._settings.execution_enabled_providers,
            max_output_tokens=self._settings.execution_max_output_tokens,
        )
        initial = {
            "organization_id": str(organization_id),
            "execution_id": str(execution_id),
            "status": "pending",
            "steps": [],
        }
        config = {"configurable": {"thread_id": thread_id_for(organization_id, execution_id)}}
        try:
            async with asyncio.timeout(self._settings.run_timeout_seconds):
                await graph.ainvoke(initial, config)
        except TimeoutError:
            log.warning("execution_timeout", execution_id=str(execution_id))
            await self._report_failure(organization_id, execution_id, "EXECUTION_TIMEOUT")
        except asyncio.CancelledError:
            await asyncio.shield(self._report_failure(organization_id, execution_id, "EXECUTION_FAILED"))
            raise
        except Exception as exc:  # noqa: BLE001 - the graph handles stage errors; this is the last resort
            code = classify(exc)
            log.error("execution_crashed", execution_id=str(execution_id), code=code, error=type(exc).__name__)
            await self._report_failure(organization_id, execution_id, code)
        finally:
            # Drop the instruction, prompt and result as soon as the run ends.
            run.task, run.messages, run.result = "", [], ""

    async def _report_failure(self, organization_id: UUID, execution_id: UUID, code: str) -> None:
        try:
            await self._core.fail_execution(organization_id, execution_id, error_code=code, message=FAILURES[code])
        except OrchestratorError as exc:
            log.warning("failure_report_failed", code=code, error=type(exc).__name__)

    async def shutdown(self) -> None:
        for task in list(self._tasks.values()):
            task.cancel()
        await asyncio.gather(*self._tasks.values(), return_exceptions=True)
