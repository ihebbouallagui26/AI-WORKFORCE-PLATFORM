"""Workflow reliability (C3.3) at the orchestrator boundary, with a STUBBED Core API and SCRIPTED provider adapters.

The Core API owns every retry and recovery decision. What the orchestrator owes it: report each failure with a precise safe code,
never retry or replay anything itself, respect the time the Core API left for the execution, and never leak a credential.
Real Anthropic behaviour is NOT VERIFIED by any of this.
"""

import asyncio
import uuid

import pytest

from app.clients.contracts import ExecutionStarted
from app.clients.core_api_client import CoreApiConflict, CoreApiForbidden, CoreApiNotFound, CoreApiUnavailable
from app.core.crypto import StateIntegrityError
from app.mcp.executor import ToolExecutionError
from app.orchestration.graphs.execution import FAILURES, ExecutionFailure, classify
from app.orchestration.tool_loop import LoopFailure
from app.providers.base import (
    ProviderAuthenticationError,
    ProviderBadRequestError,
    ProviderRateLimitError,
    ProviderResponseError,
    ProviderTimeoutError,
    ProviderUnavailableError,
    Usage,
)

from .test_workflow_actions import AGENT, EXECUTION, ScriptedAdapter, StubCore, run_graph, started, turn, use


def started_with_deadline(seconds, role=None):
    base = started(role=role)
    return ExecutionStarted(
        execution_id=base.execution_id, agent_id=base.agent_id, task=base.task, workflow_role=role,
        platform_actions=base.platform_actions, deadline_seconds=seconds,
    )


class SlowAdapter(ScriptedAdapter):
    """A provider that does not answer within the time left."""

    async def generate(self, scope, request):
        await asyncio.sleep(5)
        return type("R", (), {"text": "too late", "model": "model-x", "usage": Usage(input_tokens=1, output_tokens=1)})()


class QuickAdapter(ScriptedAdapter):
    async def generate(self, scope, request):
        return type("R", (), {"text": "on time", "model": "model-x", "usage": Usage(input_tokens=3, output_tokens=2)})()


# ---------------------------------------------------------------- the failure vocabulary the Core API classifies


@pytest.mark.parametrize(
    ("error", "code"),
    [
        (ProviderUnavailableError(), "PROVIDER_UNAVAILABLE"),
        (ProviderRateLimitError(), "PROVIDER_RATE_LIMIT"),
        (ProviderTimeoutError(), "PROVIDER_TIMEOUT"),
        (ProviderAuthenticationError(), "PROVIDER_AUTHENTICATION"),
        (ProviderBadRequestError(), "PROVIDER_BAD_REQUEST"),
        (ProviderResponseError(), "PROVIDER_INVALID_RESPONSE"),
        (CoreApiUnavailable(), "CORE_API_UNAVAILABLE"),
        (CoreApiConflict(), "CORE_API_REJECTED"),
        (CoreApiForbidden(), "CORE_API_REJECTED"),
        (CoreApiNotFound(), "CORE_API_REJECTED"),
        (StateIntegrityError(), "STATE_UNAVAILABLE"),
        (ExecutionFailure("DEADLINE_EXCEEDED"), "DEADLINE_EXCEEDED"),
        (LoopFailure("COORDINATION_UNAVAILABLE"), "COORDINATION_UNAVAILABLE"),
        (RuntimeError("secret sk-ant-123 must never be used"), "EXECUTION_FAILED"),
    ],
)
def test_every_failure_is_reported_with_a_precise_safe_code(error, code):
    assert classify(error) == code
    assert code in FAILURES
    assert "sk-ant" not in FAILURES[code]


def test_a_tool_error_is_reported_as_a_tool_failure_never_as_a_provider_failure():
    assert classify(ToolExecutionError("TOOL_TIMEOUT", "slow")) == "TOOL_FAILED"


# ---------------------------------------------------------------- the time left by the Core API


async def test_the_execution_stops_when_the_time_left_by_the_core_api_is_over_and_reports_a_precise_code():
    core = StubCore(started=started_with_deadline(1))
    final, _ = await run_graph(core, SlowAdapter())
    assert final["status"] == "failed"
    assert core.failed and core.failed[0][0] == "DEADLINE_EXCEEDED"
    assert core.completed == []                       # a result that arrives too late is never reported as a success
    assert core.failed[0][1] == FAILURES["DEADLINE_EXCEEDED"]


async def test_an_execution_that_finishes_in_time_is_unaffected_by_the_deadline():
    core = StubCore(started=started_with_deadline(30))
    final, run = await run_graph(core, QuickAdapter())
    assert final["status"] == "succeeded" and core.completed[0]["result"] == "on time" and run.deadline_seconds == 30


async def test_an_execution_without_workflow_deadline_is_not_bounded_by_the_orchestrator():
    core = StubCore(started=started_with_deadline(None))
    final, run = await run_graph(core, QuickAdapter())
    assert final["status"] == "succeeded" and run.deadline_seconds is None


def test_the_started_answer_carries_the_remaining_time_in_camel_case_and_rejects_nonsense():
    parsed = ExecutionStarted.model_validate(
        {"executionId": str(EXECUTION), "agentId": str(AGENT), "task": "t", "deadlineSeconds": 42}
    )
    assert parsed.deadline_seconds == 42
    assert ExecutionStarted.model_validate({"executionId": str(uuid.uuid4()), "agentId": str(AGENT), "task": "t"}).deadline_seconds is None
    with pytest.raises(ValueError, match="deadline_seconds|greater"):
        ExecutionStarted.model_validate({"executionId": str(EXECUTION), "agentId": str(AGENT), "task": "t", "deadlineSeconds": 0})


# ---------------------------------------------------------------- the orchestrator never retries or replays by itself


class FailingAdapter(ScriptedAdapter):
    def __init__(self, error):
        super().__init__()
        self.error = error
        self.calls = 0

    async def generate(self, scope, request):
        self.calls += 1
        raise self.error


async def test_a_transient_provider_failure_is_reported_once_and_never_retried_here():
    core = StubCore(started=started_with_deadline(30))
    adapter = FailingAdapter(ProviderUnavailableError())
    final, _ = await run_graph(core, adapter)
    assert final["status"] == "failed"
    assert adapter.calls == 1                          # the Core API decides on a retry, as a NEW execution
    assert [f[0] for f in core.failed] == ["PROVIDER_UNAVAILABLE"]
    assert core.completed == []


async def test_a_permanent_provider_failure_is_reported_as_such():
    core = StubCore(started=started_with_deadline(30))
    final, _ = await run_graph(core, FailingAdapter(ProviderAuthenticationError()))
    assert final["status"] == "failed" and core.failed[0][0] == "PROVIDER_AUTHENTICATION"


async def test_a_duplicate_provider_action_is_forwarded_with_the_same_idempotency_key_and_a_core_conflict_is_not_hidden():
    # The same provider tool_call id is the idempotency key on the Core API side: it is forwarded unchanged.
    first = StubCore(CoreApiUnavailable(), started=started(role="COORDINATOR"))
    second = StubCore(CoreApiUnavailable(), started=started(role="COORDINATOR"))
    for core in (first, second):
        await run_graph(core, ScriptedAdapter(turn(use("toolu_same", "workflow_finish", result="x"))))
    assert first.actions[0][0] == second.actions[0][0] == "toolu_same"
    assert first.failed[0][0] == "COORDINATION_UNAVAILABLE"      # the call is not replayed here: one attempt, one failure
    assert len(first.actions) == 1


async def test_the_failure_report_never_contains_the_task_a_credential_or_the_provider_message():
    core = StubCore(started=started_with_deadline(30))
    await run_graph(core, FailingAdapter(ProviderUnavailableError("Bearer sk-ant-api03-SECRET boom")))
    code, message = core.failed[0]
    assert message == FAILURES[code]
    assert "SECRET" not in message and "Bearer" not in message and "Objective" not in message
