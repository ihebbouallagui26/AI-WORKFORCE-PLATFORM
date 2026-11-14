import asyncio
import uuid

import pytest
from langgraph.checkpoint.memory import InMemorySaver

from app.core.errors import NotFoundError, RunTimeoutError
from app.orchestration.graphs.diagnostic import build_diagnostic_graph, checked
from app.orchestration.runtime import OrchestrationRuntime
from app.orchestration.state.models import (
    ActorContext,
    ExecutionContext,
    StateSafetyError,
    assert_state_safe,
    thread_id_for,
)

ACTOR = ActorContext("service", "core-api")


def context(org=None) -> ExecutionContext:
    return ExecutionContext.create(org or uuid.uuid4(), uuid.uuid4(), ACTOR)


def test_execution_ids_and_thread_ids_are_generated_server_side():
    a, b = context(), context()
    assert a.execution_id != b.execution_id
    assert a.thread_id == f"{a.organization_id}:{a.execution_id}"
    assert thread_id_for(a.organization_id, a.execution_id) == a.thread_id


async def test_diagnostic_graph_runs_deterministically_and_makes_no_external_call():
    runtime = OrchestrationRuntime(InMemorySaver())
    snapshot = await runtime.run_diagnostic(context(), target=3)
    assert snapshot.status == "completed" and snapshot.counter == 3 and snapshot.result == "counter=3"
    assert snapshot.steps == ("begin", "work-1", "work-2", "work-3", "finish") and snapshot.next_nodes == ()


async def test_zero_target_skips_the_work_node():
    snapshot = await OrchestrationRuntime(InMemorySaver()).run_diagnostic(context(), target=0)
    assert snapshot.steps == ("begin", "finish")


async def test_state_is_checkpointed_at_every_step():
    saver = InMemorySaver()
    runtime = OrchestrationRuntime(saver)
    ctx = context()
    await runtime.run_diagnostic(ctx, target=2)
    history = [c async for c in saver.alist({"configurable": {"thread_id": ctx.thread_id}})]
    assert len(history) >= 4


async def test_failure_leaves_a_checkpoint_and_the_run_can_be_resumed():
    runtime = OrchestrationRuntime(InMemorySaver())
    ctx = context()
    with pytest.raises(RuntimeError):
        await runtime.run_diagnostic(ctx, target=4, fail_after=2)
    partial = await runtime.get_snapshot(ctx.organization_id, ctx.execution_id)
    assert partial.status == "running" and partial.counter == 2 and partial.result is None
    recovered = await runtime.resume(ctx.organization_id, ctx.execution_id, clear_fault=True)
    assert recovered.status == "completed" and recovered.counter == 4


async def test_human_in_the_loop_pause_and_resume():
    runtime = OrchestrationRuntime(InMemorySaver(), pause_before_finish=True)
    ctx = context()
    paused = await runtime.run_diagnostic(ctx, target=1)
    assert paused.status == "running" and paused.next_nodes == ("finish",)
    resumed = await runtime.resume(ctx.organization_id, ctx.execution_id)
    assert resumed.status == "completed" and resumed.next_nodes == ()


async def test_another_organization_cannot_read_or_resume_an_execution():
    runtime = OrchestrationRuntime(InMemorySaver())
    ctx = context()
    await runtime.run_diagnostic(ctx)
    intruder = uuid.uuid4()
    with pytest.raises(NotFoundError):
        await runtime.get_snapshot(intruder, ctx.execution_id)
    with pytest.raises(NotFoundError):
        await runtime.resume(intruder, ctx.execution_id)
    assert not await runtime.cancel(intruder, ctx.execution_id)


async def test_a_state_claiming_another_organization_is_refused_even_on_the_right_thread():
    saver = InMemorySaver()
    runtime = OrchestrationRuntime(saver)
    victim, attacker = uuid.uuid4(), uuid.uuid4()
    ctx = context(victim)
    # Forge a checkpoint on the victim's thread whose state belongs to someone else.
    graph = build_diagnostic_graph(saver)
    await graph.ainvoke(
        {"organization_id": str(attacker), "agent_id": "x", "execution_id": str(ctx.execution_id), "target": 0, "steps": []},
        {"configurable": {"thread_id": ctx.thread_id}},
    )
    with pytest.raises(NotFoundError):
        await runtime.get_snapshot(victim, ctx.execution_id)


@pytest.mark.parametrize(
    "update",
    [
        {"api_key": "x"},
        {"note": "Authorization: Bearer abcdefghijklmnop"},
        {"note": "sk-abcdefghijklmnop"},
        {"token": "t"},
        {"nested": {"password": "p"}},
        {"jwt": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig"},
    ],
)
def test_sensitive_values_cannot_enter_state(update):
    with pytest.raises(StateSafetyError):
        assert_state_safe(update)


def test_ordinary_state_is_accepted():
    assert_state_safe({"status": "running", "counter": 2, "steps": ["work-2"], "result": "counter=2"})


async def test_a_node_that_tries_to_checkpoint_a_secret_fails():
    @checked
    async def leaky(state):
        return {"result": "token=Bearer abcdefghijklmnop"}

    with pytest.raises(StateSafetyError):
        await leaky({})


async def test_runtime_timeout_cancels_the_run():
    runtime = OrchestrationRuntime(InMemorySaver(), run_timeout_seconds=0.05)
    slow = runtime._graph  # noqa: SLF001

    async def never(*_args, **_kwargs):
        await asyncio.sleep(10)

    runtime._graph = type("G", (), {"ainvoke": staticmethod(never), "aget_state": slow.aget_state})()  # noqa: SLF001
    with pytest.raises(RunTimeoutError):
        await runtime.run_diagnostic(context())


async def test_a_running_execution_can_be_cancelled():
    runtime = OrchestrationRuntime(InMemorySaver())
    ctx = context()
    started = asyncio.Event()

    async def slow(*_args, **_kwargs):
        started.set()
        await asyncio.sleep(10)

    runtime._graph = type("G", (), {"ainvoke": staticmethod(slow)})()  # noqa: SLF001
    task = asyncio.create_task(runtime._execute(ctx.thread_id, {}))  # noqa: SLF001
    await started.wait()
    assert await runtime.cancel(ctx.organization_id, ctx.execution_id)
    with pytest.raises(asyncio.CancelledError):
        await task
