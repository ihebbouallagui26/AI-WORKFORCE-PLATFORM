"""Parallel multi-agent execution (C3.4) at the orchestrator boundary: concurrent isolated agent turns and multi-delegation.

STUBBED Core API and SCRIPTED provider adapters. What is proven: independent executions really overlap (a barrier that only opens
when all of them are in flight at the same moment), nothing is shared between them, a coordinator may forward several delegations in
one turn (bounded), and a failure reports the provider usage already consumed. Real Anthropic behaviour is NOT VERIFIED.
"""

import asyncio
import uuid

import pytest
from langgraph.checkpoint.memory import InMemorySaver

from app.clients.contracts import ExecutionStarted
from app.core.config import Settings
from app.orchestration.executor import ExecutionRunner
from app.orchestration.graphs import execution as graph_module
from app.orchestration.tool_loop import LoopDeps, LoopFailure, initial_state, platform_alias, run_loop
from app.providers.base import ProviderScope, TextBlock, Usage

from .test_workflow_actions import (
    AGENT,
    CONNECTION,
    DELEGATE,
    FINISH,
    ORG,
    ScriptedAdapter,
    StubCore,
    accepted,
    rejected,
    run_graph,
    started,
    turn,
    use,
)


class DistinctTasksCore(StubCore):
    """Every execution gets its OWN instruction; completions are recorded per execution id."""

    def __init__(self):
        super().__init__()
        self.by_execution = {}
        self.failed_by_execution = {}

    async def start_execution(self, organization_id, execution_id):
        return ExecutionStarted(execution_id=execution_id, agent_id=AGENT, task=f"instruction-{execution_id}")

    async def complete_execution(self, organization_id, execution_id, **kwargs):
        self.by_execution[execution_id] = kwargs

    async def fail_execution(self, organization_id, execution_id, *, error_code, message, input_tokens=None, output_tokens=None):
        self.failed_by_execution[execution_id] = (error_code, input_tokens, output_tokens)


class BarrierAdapter(ScriptedAdapter):
    """A provider that answers only once ``parties`` calls are in flight AT THE SAME TIME: proof of genuine overlap."""

    def __init__(self, parties):
        super().__init__()
        self.barrier = asyncio.Barrier(parties)
        self.in_flight = 0
        self.peak = 0

    async def generate(self, scope, request):
        self.in_flight += 1
        self.peak = max(self.peak, self.in_flight)
        try:
            async with asyncio.timeout(5):
                await self.barrier.wait()  # blocks until every party has arrived: sequential execution would deadlock here
        finally:
            self.in_flight -= 1
        text = request.messages[-1].content  # the instruction of THIS execution, echoed back
        return type("R", (), {"text": f"result-of::{text}", "model": "model-x", "usage": Usage(input_tokens=7, output_tokens=3)})()


class FakeProviders:
    def __init__(self, adapter):
        self._adapter = adapter

    def adapter_with(self, provider, resolver):
        return self._adapter


def runner_for(core, adapter, parties):
    settings = Settings(environment="test", max_concurrent_executions=parties, run_timeout_seconds=10)
    return ExecutionRunner(settings, core, FakeProviders(adapter), InMemorySaver())


# ---------------------------------------------------------------- concurrent, isolated agent turns


async def test_independent_executions_really_overlap_and_share_nothing():
    core = DistinctTasksCore()
    adapter = BarrierAdapter(parties=3)
    runner = runner_for(core, adapter, parties=3)
    ids = [uuid.uuid4() for _ in range(3)]
    for execution_id in ids:
        runner.submit(ORG, execution_id)
    assert runner.active == 3
    for _ in range(100):
        if len(core.by_execution) == 3 or core.failed_by_execution:
            break
        await asyncio.sleep(0.05)
    assert core.failed_by_execution == {}
    assert adapter.peak == 3                                  # all three provider calls were in flight at the same moment
    for execution_id in ids:                                  # each completion carries ITS OWN instruction: no cross-contamination
        assert core.by_execution[execution_id]["result"] == f"result-of::instruction-{execution_id}"
        assert core.by_execution[execution_id]["input_tokens"] == 7
    await runner.shutdown()
    assert runner.active == 0


async def test_a_failing_execution_does_not_disturb_its_concurrent_siblings():
    class Mixed(BarrierAdapter):
        async def generate(self, scope, request):
            if "FAIL" in request.messages[-1].content:
                await self.barrier.wait()
                from app.providers.base import ProviderBadRequestError

                raise ProviderBadRequestError()
            return await super().generate(scope, request)

    core = DistinctTasksCore()
    good, bad = uuid.uuid4(), uuid.uuid4()

    async def start(organization_id, execution_id):
        task = "FAIL" if execution_id == bad else f"instruction-{execution_id}"
        return ExecutionStarted(execution_id=execution_id, agent_id=AGENT, task=task)

    core.start_execution = start
    runner = runner_for(core, Mixed(parties=2), parties=2)
    runner.submit(ORG, good)
    runner.submit(ORG, bad)
    for _ in range(100):
        if good in core.by_execution and bad in core.failed_by_execution:
            break
        await asyncio.sleep(0.05)
    assert core.failed_by_execution[bad][0] == "PROVIDER_BAD_REQUEST"
    assert core.by_execution[good]["result"].startswith("result-of::")
    await runner.shutdown()


# ---------------------------------------------------------------- multi-delegation


def deps(core, adapter, max_delegations):
    return LoopDeps(
        core=core, adapter=adapter, scope=ProviderScope(ORG, CONNECTION), organization_id=ORG, execution_id=uuid.uuid4(),
        model="model-x", max_output_tokens=300, offered={}, manager=None, max_rounds=3, max_tool_calls=0, output_max_chars=0,
        platform={platform_alias(a.name): a for a in (DELEGATE, FINISH)}, max_delegations=max_delegations,
    )


def state():
    return initial_state("system", "task", "model-x")


async def test_a_coordinator_turn_forwards_up_to_the_allowed_number_of_independent_delegations():
    core = StubCore(accepted(), accepted(), accepted())
    adapter = ScriptedAdapter(
        turn(
            use("toolu_1", "workflow_delegate", participantRef="worker_1", task="competitors"),
            use("toolu_2", "workflow_delegate", participantRef="worker_2", task="pricing"),
            use("toolu_3", "workflow_delegate", participantRef="worker_3", task="trends"),
        )
    )
    outcome = await run_loop(deps(core, adapter, 3), state())
    assert outcome.text == "3 workflow.delegate actions accepted."
    assert [a[0] for a in core.actions] == ["toolu_1", "toolu_2", "toolu_3"]      # each forwarded with ITS OWN idempotency key
    assert len(adapter.requests) == 1                                               # one model round: the turn ends after the batch


async def test_delegations_beyond_the_bound_and_a_finish_after_a_delegation_are_refused_without_a_request():
    core = StubCore(accepted(), accepted())
    adapter = ScriptedAdapter(
        turn(
            use("toolu_1", "workflow_delegate", participantRef="worker_1", task="one"),
            use("toolu_2", "workflow_delegate", participantRef="worker_2", task="two"),
            use("toolu_3", "workflow_delegate", participantRef="worker_3", task="three"),   # beyond the bound of 2
            use("toolu_4", "workflow_finish", result="done"),                                 # never combined with delegations
        )
    )
    outcome = await run_loop(deps(core, adapter, 2), state())
    assert outcome.text == "2 workflow.delegate actions accepted."
    assert [a[0] for a in core.actions] == ["toolu_1", "toolu_2"]


async def test_a_sequential_coordinator_keeps_its_one_action_per_turn_contract():
    core = StubCore(accepted())
    adapter = ScriptedAdapter(
        turn(use("toolu_1", "workflow_delegate", participantRef="worker_1", task="one"),
             use("toolu_2", "workflow_delegate", participantRef="worker_2", task="two"))
    )
    outcome = await run_loop(deps(core, adapter, 1), state())
    assert outcome.text == "workflow.delegate accepted."
    assert [a[0] for a in core.actions] == ["toolu_1"]


async def test_accepted_and_rejected_delegations_are_reported_individually_and_the_accepted_ones_stand():
    core = StubCore(accepted(), rejected("UNKNOWN_PARTICIPANT"))
    adapter = ScriptedAdapter(
        turn(use("toolu_1", "workflow_delegate", participantRef="worker_1", task="one"),
             use("toolu_2", "workflow_delegate", participantRef="worker_9", task="two"))
    )
    outcome = await run_loop(deps(core, adapter, 3), state())
    assert outcome.text == "workflow.delegate accepted."
    assert [a[0] for a in core.actions] == ["toolu_1", "toolu_2"]


async def test_when_every_delegation_is_rejected_the_model_sees_each_reason_and_may_choose_again():
    core = StubCore(rejected("UNKNOWN_PARTICIPANT"), rejected("SELF_DELEGATION"), accepted("workflow.finish"))
    adapter = ScriptedAdapter(
        turn(use("toolu_1", "workflow_delegate", participantRef="worker_9", task="a"),
             use("toolu_2", "workflow_delegate", participantRef="coordinator", task="b")),
        turn(use("toolu_3", "workflow_finish", result="done")),
    )
    outcome = await run_loop(deps(core, adapter, 3), state())
    assert outcome.text == "workflow.finish accepted."
    blocks = adapter.requests[1].messages[-1].content
    assert len(blocks) == 2 and all(b.is_error for b in blocks)
    assert "UNKNOWN_PARTICIPANT" in blocks[0].content and "SELF_DELEGATION" in blocks[1].content


async def test_the_start_answer_bounds_the_delegations_per_turn():
    parsed = ExecutionStarted.model_validate(
        {"executionId": str(uuid.uuid4()), "agentId": str(AGENT), "task": "t", "maxDelegationsPerTurn": 3}
    )
    assert parsed.max_delegations_per_turn == 3
    with pytest.raises(ValueError, match="max_delegations_per_turn|less than|greater"):
        ExecutionStarted.model_validate({"executionId": str(uuid.uuid4()), "agentId": str(AGENT), "task": "t", "maxDelegationsPerTurn": 50})


async def test_the_graph_hands_the_bound_to_the_loop():
    core = StubCore(accepted(), accepted(), started=ExecutionStarted(
        execution_id=uuid.uuid4(), agent_id=AGENT, task="Objective", workflow_role="COORDINATOR",
        platform_actions=[DELEGATE, FINISH], max_delegations_per_turn=2,
    ))
    adapter = ScriptedAdapter(
        turn(use("toolu_1", "workflow_delegate", participantRef="worker_1", task="a"),
             use("toolu_2", "workflow_delegate", participantRef="worker_2", task="b"),
             use("toolu_3", "workflow_delegate", participantRef="worker_3", task="c"))
    )
    final, run = await run_graph(core, adapter)
    assert final["status"] == "succeeded" and run.max_delegations == 2
    assert [a[0] for a in core.actions] == ["toolu_1", "toolu_2"]


# ---------------------------------------------------------------- the usage of a failed turn is reported


async def test_a_failed_coordinator_turn_reports_the_provider_usage_it_already_consumed():
    core = StubCore(*[rejected() for _ in range(10)], started=started(role="COORDINATOR"))
    turns = [turn(use(f"toolu_{i}", "workflow_delegate", participantRef="worker_9", task="x"), tokens=(100, 10)) for i in range(10)]
    final, _ = await run_graph(core, ScriptedAdapter(*turns))
    assert final["status"] == "failed"
    assert core.failed[0][0] == "TOOL_ROUND_LIMIT"
    assert core.failed_usage == (600, 60)               # six rounds were paid for: a failed attempt still counts toward the budget
    assert isinstance(graph_module.FAILURES["TOOL_ROUND_LIMIT"], str)


async def test_the_loop_failure_carries_a_safe_code_only():
    core = StubCore(*[rejected() for _ in range(10)])
    turns = [turn(use(f"toolu_{i}", "workflow_delegate", participantRef="worker_9", task="x")) for i in range(10)]
    with pytest.raises(LoopFailure) as failure:
        await run_loop(deps(core, ScriptedAdapter(*turns), 3), state())
    assert failure.value.error_code == "TOOL_ROUND_LIMIT"
    assert TextBlock(text="x").text == "x"
