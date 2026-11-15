"""Security fix 8.4, closure (stage 1): aggregate output bounds, and what could have happened to the external action.

Real child processes (adversarial server in tests/support) for the bounds and the timeout/crash cases; unit tests for the pure
classification; the tool loop with a scripted Core API for the reporting rules.
"""

import asyncio
import contextlib
import json
import sys
from pathlib import Path
from types import SimpleNamespace

import httpx
import pytest
import respx
from mcp import MCPError, StdioServerParameters

from app.mcp import bounded_stdio, executor
from app.mcp.bounded_stdio import McpOutputLimits
from app.mcp.configuration import McpServerConfig
from app.mcp.executor import McpServerManager, ToolExecutionError, ToolOutputLimitError
from app.orchestration import tool_loop
from tests.support.processes import wait_gone
from tests.test_tool_execution import CoreScript, Env, FakeManager, Provider, final, run, tool_use

SERVER = str(Path(__file__).parent / "support" / "mcp_test_server.py")
BOUND = 60
LIMIT = 65_536
CHUNK = 65_536


def limits(**overrides) -> McpOutputLimits:
    base = {"max_message_bytes": LIMIT, "stderr_capture_bytes": 1_024, "stderr_flood_bytes": 262_144, "stdout_flood_bytes": 1_073_741_824,
            "stdout_flood_messages": 10_000_000, "max_call_bytes": 268_435_456}
    return McpOutputLimits(**{**base, **overrides})


@pytest.fixture
def launch(monkeypatch, tmp_path):
    state = {"n": 0, "pids": []}

    def params(_config):
        state["n"] += 1
        pid_file = tmp_path / f"pid-{state['n']}"
        state["pids"].append(pid_file)
        return StdioServerParameters(command=sys.executable, args=[SERVER, "normal", str(pid_file)])

    monkeypatch.setattr(executor, "launch_parameters", params)
    return state


def pids(launch) -> list[int]:
    return [int(p.read_text()) for p in launch["pids"] if p.exists() and p.read_text().strip()]


@pytest.fixture
async def manager(launch):
    created: list[McpServerManager] = []

    def make(lim=None, timeout=10.0):
        m = McpServerManager([McpServerConfig(name="diagnostic", server_id="diagnostic", timeout_seconds=timeout)], limits=lim or limits())
        created.append(m)
        return m

    yield make
    for m in created:
        await asyncio.wait_for(m.aclose(), BOUND)
    for pid in pids(launch):
        assert wait_gone(pid), f"orphan MCP process {pid}"


async def call(m, tool, arguments=None, *, timeout=10.0):
    return await asyncio.wait_for(m.call("diagnostic", tool, arguments or {}, timeout_seconds=timeout, max_output_bytes=4096), BOUND)


def monitor_of(m):
    return m._connections["diagnostic"].monitor  # noqa: SLF001


async def violation_of(m, tool, arguments, **kwargs):
    last = {}
    original = McpServerManager._discard  # noqa: SLF001

    async def discard(self, server, connection):
        last["monitor"] = connection.monitor
        await original(self, server, connection)

    McpServerManager._discard = discard  # noqa: SLF001
    try:
        with pytest.raises(ToolOutputLimitError) as info:
            await call(m, tool, arguments, **kwargs)
    finally:
        McpServerManager._discard = original  # noqa: SLF001
    return info.value, last["monitor"]


# ---- aggregate bounds ------------------------------------------------------------------------------------------------------


async def test_many_small_valid_messages_are_cut_by_the_message_rate_limit(manager, launch):
    m = manager(limits(stdout_flood_messages=100))
    error, monitor = await violation_of(m, "chatter", {"count": 5_000, "size": 50})        # each message is tiny and valid
    assert error.reason == "stdout-message-flood" and error.error_code == "OUTCOME_UNKNOWN"
    assert monitor.stdout_peak_retained_bytes <= LIMIT + CHUNK
    assert all(wait_gone(pid) for pid in pids(launch))


async def test_repeated_large_valid_messages_are_cut_by_the_window_byte_limit(manager, launch):
    m = manager(limits(stdout_flood_bytes=262_144))                                         # 20 x 40 kB: each below the 64 KiB message limit
    error, _ = await violation_of(m, "chatter", {"count": 20, "size": 40_000})
    assert error.reason == "stdout-flood"
    assert all(wait_gone(pid) for pid in pids(launch))


async def test_everything_sent_during_one_call_is_bounded_by_the_per_call_budget(manager, launch):
    m = manager(limits(max_call_bytes=262_144))                                             # the window is unlimited: only the call budget acts
    error, monitor = await violation_of(m, "chatter", {"count": 20, "size": 40_000})
    assert error.reason == "stdout-call-limit" and monitor.call_bytes > 262_144
    assert all(wait_gone(pid) for pid in pids(launch))


async def test_legitimate_multi_message_exchanges_are_not_affected_by_the_aggregate_bounds(manager):
    m = manager(limits(stdout_flood_messages=100, stdout_flood_bytes=262_144, max_call_bytes=262_144))
    await m.discover("diagnostic")                                                          # handshake + tool list: several messages
    out = await call(m, "chatter", {"count": 20, "size": 1_000})                            # a call with 20 progress/log notifications
    assert out.text == "chatted" and monitor_of(m).violation is None
    for i in range(5):
        assert (await call(m, "echo", {"text": str(i)})).text == str(i)
    assert monitor_of(m).violation is None


async def test_the_per_call_budget_only_counts_bytes_received_during_a_call(manager):
    m = manager(limits(max_call_bytes=65_536))
    tools = await m.discover("diagnostic")
    assert tools and monitor_of(m).violation is None                                        # discovery is not a call
    assert (await call(m, "echo", {"text": "small"})).text == "small"
    assert monitor_of(m).call_active is False


async def test_stderr_is_a_rate_not_a_lifetime_total_and_a_burst_above_the_rate_is_cut(manager):
    m = manager(limits(stderr_flood_bytes=65_536, stderr_flood_window_seconds=0.5))
    for _ in range(3):                                                                      # 3 windows x 40 kB: each below 64 KiB per window
        await call(m, "stderr_burst", {"total": 40_000})
        await asyncio.sleep(0.7)
    monitor = monitor_of(m)
    assert monitor.violation is None and monitor.stderr.total_bytes >= 120_000
    assert monitor.stderr.retained_bytes <= 1_024                                           # volume never turns into memory
    with contextlib.suppress(ToolOutputLimitError):                                         # 150 kB inside one window: cut
        await call(m, "stderr_burst", {"total": 150_000})
    deadline = asyncio.get_event_loop().time() + 10
    while monitor.violation is None and asyncio.get_event_loop().time() < deadline:
        await asyncio.sleep(0.05)
    assert monitor.violation is not None and monitor.violation.kind == "stderr-flood"


# ---- what could have happened to the external action --------------------------------------------------------------------------


async def test_a_timeout_after_the_request_was_sent_is_an_unknown_outcome_and_the_effect_is_not_repeated(manager, tmp_path, launch):
    counter = tmp_path / "effects.txt"
    m = manager()
    with pytest.raises(ToolExecutionError) as info:
        await call(m, "effect_then_hang", {"counter": str(counter)}, timeout=1.5)
    assert info.value.error_code == "TOOL_TIMEOUT" and info.value.outcome_unknown is True
    assert counter.read_text().splitlines() == ["1"]                                        # the action happened, its result never came
    assert all(wait_gone(pid) for pid in pids(launch))
    assert (await call(m, "echo", {"text": "next"})).text == "next" and counter.read_text().splitlines() == ["1"]


async def test_a_crash_during_a_call_is_an_unknown_outcome(manager):
    m = manager()
    with pytest.raises(ToolExecutionError) as info:
        await call(m, "crash")
    assert info.value.error_code == "MCP_UNAVAILABLE" and info.value.outcome_unknown is True


async def test_a_tool_result_flagged_as_an_error_is_a_known_failure(manager):
    m = manager()
    with pytest.raises(ToolExecutionError) as info:
        await call(m, "fail")                                                               # the tool itself reported the failure: a known result
    assert info.value.error_code == "TOOL_ERROR" and info.value.outcome_unknown is False


def synthetic(requests_before: int, requests_now: int, violation=None):
    monitor = bounded_stdio.TransportMonitor(limits())
    monitor.stdin_started = requests_now
    monitor.violation = violation
    return SimpleNamespace(monitor=monitor), requests_before


@pytest.mark.parametrize("exc,dispatched,unknown,code", [
    (TimeoutError(), False, False, "TOOL_TIMEOUT"),           # never sent: provably nothing ran
    (TimeoutError(), True, True, "TOOL_TIMEOUT"),             # sent, no answer: unknown
    (RuntimeError("closed"), False, False, "MCP_UNAVAILABLE"),
    (RuntimeError("closed"), True, True, "MCP_UNAVAILABLE"),
    (OSError("broken pipe"), True, True, "MCP_UNAVAILABLE"),
])
def test_the_failure_is_classified_from_the_evidence_of_what_was_sent(exc, dispatched, unknown, code):
    connection, before = synthetic(5, 6 if dispatched else 5)
    error = McpServerManager._classify_failure(connection, before, exc)  # noqa: SLF001
    assert (error.error_code, error.outcome_unknown) == (code, unknown)


@pytest.mark.parametrize("rpc_code,unknown", [(-32602, False), (-32601, False), (-32600, False), (-32700, False), (-32603, True), (-32000, True)])
def test_a_json_rpc_error_is_known_only_when_it_is_a_refusal_before_any_tool_code_runs(rpc_code, unknown):
    connection, before = synthetic(5, 6)
    error = McpServerManager._classify_failure(connection, before, MCPError(code=rpc_code, message="x"))  # noqa: SLF001
    assert error.outcome_unknown is unknown


def test_an_enforced_limit_is_unknown_even_if_the_request_counter_did_not_move():
    connection, before = synthetic(5, 5, violation=bounded_stdio.OutputViolation("stdout-flood", 1))
    error = McpServerManager._classify_failure(connection, before, RuntimeError("x"))  # noqa: SLF001
    assert isinstance(error, ToolOutputLimitError) and error.outcome_unknown is True


# ---- tool loop: every unknown outcome ends the execution; Core API unavailable while reporting ------------------------------


@respx.mock
@pytest.mark.parametrize("code", ["TOOL_TIMEOUT", "MCP_UNAVAILABLE"])
async def test_any_post_dispatch_failure_is_reported_as_unknown_outcome_and_ends_the_execution_without_replay(code):
    e = Env(manager=FakeManager())
    runner = await e.build()
    core = CoreScript(respx.mock)
    e.manager.failure = ToolExecutionError(code, "The tool did not answer: unknown.", outcome_unknown=True)
    provider = Provider(tool_use("diagnostic_echo", "toolu_1", {"text": "x"}), final("again"))
    await run(runner)
    assert [c["errorCode"] for c in core.completes] == ["OUTCOME_UNKNOWN"] and core.completes[0]["success"] is False
    assert core.posted(core.fail)["errorCode"] == "TOOL_FAILED" and len(provider.requests) == 1
    assert len(e.manager.calls) == 1 and len(core.begins) == 1                              # never replayed
    await e.close()


@respx.mock
async def test_a_pre_dispatch_failure_is_not_labelled_unknown_and_the_agent_continues():
    e = Env(manager=FakeManager())
    runner = await e.build()
    core = CoreScript(respx.mock)
    e.manager.failure = ToolExecutionError("MCP_UNAVAILABLE", "The tool server failed before the call could be sent.")
    Provider(tool_use("diagnostic_echo", "toolu_1", {"text": "x"}), final("done"))
    await run(runner)
    assert core.completes[0]["errorCode"] == "MCP_UNAVAILABLE"                              # known: nothing could have been executed
    assert core.posted(core.complete)["result"] == "done"
    await e.close()


class FlakyCore(CoreScript):
    """The Core API is unreachable for the first ``outages`` attempts to complete a tool call, then answers."""

    def __init__(self, *args, outages: int = 2, **kwargs):
        self.outages = outages
        super().__init__(*args, **kwargs)

    def _complete(self, request):
        if self.outages > 0:
            self.outages -= 1
            self.completes.append(json.loads(request.content))
            return httpx.Response(503, json={"detail": "down"})
        return super()._complete(request)


@respx.mock
async def test_when_the_core_api_is_briefly_unavailable_the_unknown_outcome_is_still_reported(monkeypatch):
    monkeypatch.setattr(tool_loop, "UNKNOWN_REPORT_PAUSE_SECONDS", 0.0)
    e = Env(manager=FakeManager())
    runner = await e.build()
    core = FlakyCore(respx.mock, outages=2)
    e.manager.failure = ToolExecutionError("TOOL_TIMEOUT", "unknown", outcome_unknown=True)
    Provider(tool_use("diagnostic_echo", "toolu_1", {"text": "x"}), final("x"))
    await run(runner)
    assert len(core.completes) == 3 and core.completes[-1]["errorCode"] == "OUTCOME_UNKNOWN"   # retried until the Core API answered
    assert len(e.manager.calls) == 1                                                          # the tool itself was never run again
    assert core.posted(core.fail)["errorCode"] == "TOOL_FAILED"
    await e.close()


@respx.mock
async def test_when_the_core_api_stays_unavailable_nothing_is_replayed_and_the_core_recovery_remains_the_safety_net(monkeypatch):
    monkeypatch.setattr(tool_loop, "UNKNOWN_REPORT_PAUSE_SECONDS", 0.0)
    e = Env(manager=FakeManager())
    runner = await e.build()
    core = CoreScript(respx.mock)
    core.complete_status = 503
    e.manager.failure = ToolExecutionError("TOOL_TIMEOUT", "unknown", outcome_unknown=True)
    Provider(tool_use("diagnostic_echo", "toolu_1", {"text": "x"}), final("x"))
    await run(runner)
    assert len(core.completes) == tool_loop.UNKNOWN_REPORT_ATTEMPTS and len(e.manager.calls) == 1
    assert core.posted(core.fail)["errorCode"] == "CORE_API_UNAVAILABLE"                    # the call stays RUNNING in the Core API's database
    await e.close()
