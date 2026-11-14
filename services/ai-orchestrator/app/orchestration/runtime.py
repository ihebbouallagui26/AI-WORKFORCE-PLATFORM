"""Runtime that runs graphs for an execution context, with tenant isolation, timeouts and cancellation."""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
from typing import Any
from uuid import UUID

from langgraph.checkpoint.base import BaseCheckpointSaver

from app.core.errors import NotFoundError, RunTimeoutError
from app.core.logging import get_logger
from app.orchestration.graphs.diagnostic import build_diagnostic_graph
from app.orchestration.state.models import ExecutionContext, thread_id_for

log = get_logger("runtime")


@dataclass(frozen=True)
class RunSnapshot:
    organization_id: UUID
    execution_id: UUID
    status: str
    counter: int
    steps: tuple[str, ...]
    next_nodes: tuple[str, ...]
    result: str | None


class OrchestrationRuntime:
    def __init__(
        self,
        checkpointer: BaseCheckpointSaver[Any],
        *,
        run_timeout_seconds: float = 60.0,
        pause_before_finish: bool = False,
    ) -> None:
        self._timeout = run_timeout_seconds
        self._graph = build_diagnostic_graph(
            checkpointer, interrupt_before=("finish",) if pause_before_finish else ()
        )
        self._running: dict[str, asyncio.Task[Any]] = {}

    @staticmethod
    def _config(thread_id: str) -> dict[str, Any]:
        # The thread id is always derived server-side from (organization, execution).
        return {"configurable": {"thread_id": thread_id}}

    async def run_diagnostic(
        self, context: ExecutionContext, *, target: int = 3, fail_after: int | None = None
    ) -> RunSnapshot:
        initial = {
            "organization_id": str(context.organization_id),
            "agent_id": str(context.agent_id),
            "execution_id": str(context.execution_id),
            "status": "pending",
            "target": target,
            "fail_after": fail_after,
            "steps": [],
        }
        await self._execute(context.thread_id, initial)
        return await self.get_snapshot(context.organization_id, context.execution_id)

    async def resume(self, organization_id: UUID, execution_id: UUID, *, clear_fault: bool = False) -> RunSnapshot:
        """Continues from the last checkpoint (after a failure or a human-in-the-loop pause)."""
        await self.get_snapshot(organization_id, execution_id)  # tenant check before anything else
        thread_id = thread_id_for(organization_id, execution_id)
        if clear_fault:
            await self._graph.aupdate_state(self._config(thread_id), {"fail_after": None})
        await self._execute(thread_id, None)
        return await self.get_snapshot(organization_id, execution_id)

    async def get_snapshot(self, organization_id: UUID, execution_id: UUID) -> RunSnapshot:
        """Reads state through the caller's own organization: another tenant's thread is simply not found."""
        thread_id = thread_id_for(organization_id, execution_id)
        snapshot = await self._graph.aget_state(self._config(thread_id))
        values = snapshot.values
        if not values or values.get("organization_id") != str(organization_id):
            raise NotFoundError("Execution not found.")
        return RunSnapshot(
            organization_id=organization_id,
            execution_id=execution_id,
            status=values.get("status", "unknown"),
            counter=values.get("counter", 0),
            steps=tuple(values.get("steps", ())),
            next_nodes=tuple(snapshot.next),
            result=values.get("result"),
        )

    async def cancel(self, organization_id: UUID, execution_id: UUID) -> bool:
        task = self._running.get(thread_id_for(organization_id, execution_id))
        if task is None or task.done():
            return False
        task.cancel()
        return True

    async def _execute(self, thread_id: str, initial: dict[str, Any] | None) -> None:
        task = asyncio.ensure_future(self._graph.ainvoke(initial, self._config(thread_id)))
        self._running[thread_id] = task
        try:
            async with asyncio.timeout(self._timeout):
                await task
        except TimeoutError as exc:
            task.cancel()
            log.warning("run_timeout")
            raise RunTimeoutError from exc
        finally:
            self._running.pop(thread_id, None)
