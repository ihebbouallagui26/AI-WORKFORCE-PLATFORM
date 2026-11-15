"""Security fix 8.4: MCP stdout/stderr memory limits, against REAL child processes (adversarial server in tests/support).

Everything here runs the production ``McpServerManager`` + the bounded stdio transport + the real MCP SDK client over a real
pipe to a real subprocess. Only ``launch_parameters`` is redirected (as in the lifecycle tests) to the adversarial test server;
the production allowlist is untouched. Limits are small so that an "attack" takes milliseconds, but the logic is the production one.
"""

import asyncio
import contextlib
import inspect
import os
import sys
import tracemalloc
from pathlib import Path

import httpx
import pytest
import respx
from mcp import StdioServerParameters
from pydantic import ValidationError

from app.core.config import Settings
from app.mcp import bounded_stdio, executor
from app.mcp.bounded_stdio import McpOutputLimits, StderrCapture
from app.mcp.configuration import McpServerConfig
from app.mcp.executor import McpServerManager, ToolExecutionError, ToolOutputLimitError
from tests.support.processes import pid_alive, wait_gone
from tests.test_tool_execution import ECHO, EXECUTION, CoreScript, Env, Provider, final, run, tool_use

SERVER = str(Path(__file__).parent / "support" / "mcp_test_server.py")
BOUND = 60  # hard upper bound for any awaited operation: a hang fails the test instead of the CI job
LIMIT = 65_536  # the smallest legal message limit
CHUNK = 65_536  # the transport's read size: how far past the limit a buffer can briefly be
SMALL = McpOutputLimits(max_message_bytes=LIMIT, stderr_capture_bytes=1_024, stderr_flood_bytes=262_144, stderr_flood_window_seconds=10.0)


def config(timeout=10.0) -> McpServerConfig:
    return McpServerConfig(name="diagnostic", server_id="diagnostic", timeout_seconds=timeout)


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


def started_pids(launch) -> list[int]:
    return [int(p.read_text()) for p in launch["pids"] if p.exists() and p.read_text().strip()]


@pytest.fixture
async def manager(launch):
    created: list[McpServerManager] = []

    def make(timeout=10.0, limits=SMALL, **kwargs):
        m = McpServerManager([config(timeout)], limits=limits, **kwargs)
        created.append(m)
        return m

    yield make
    for m in created:
        await asyncio.wait_for(m.aclose(), BOUND)
    for pid in started_pids(launch):  # whatever happened in the test, NO child process may survive it
        assert wait_gone(pid), f"orphan MCP process {pid}"


async def call(m, tool, arguments=None, *, timeout=10.0, limit=4096):
    return await asyncio.wait_for(m.call("diagnostic", tool, arguments or {}, timeout_seconds=timeout, max_output_bytes=limit), BOUND)


def monitor_of(m: McpServerManager):
    return m._connections["diagnostic"].monitor  # noqa: SLF001


async def violated(m, tool, arguments=None, **kwargs) -> tuple[ToolOutputLimitError, bounded_stdio.TransportMonitor]:
    with pytest.raises(ToolOutputLimitError) as info:
        await call(m, tool, arguments, **kwargs)
    return info.value, m._last_monitor  # noqa: SLF001


@pytest.fixture(autouse=True)
def remember_monitor(monkeypatch):
    """The failed connection is discarded; keep its monitor so a test can inspect what the transport observed."""
    original = McpServerManager._discard  # noqa: SLF001

    async def discard(self, server, connection):
        self._last_monitor = connection.monitor
        await original(self, server, connection)

    monkeypatch.setattr(McpServerManager, "_discard", discard)


# ---- 1. the SDK internals this transport relies on are still there ---------------------------------------------------------


def test_the_pinned_sdk_internals_used_by_the_bounded_transport_still_exist():
    import importlib.metadata

    assert importlib.metadata.version("mcp") == "2.3.0", "the SDK was upgraded: re-audit app/mcp/bounded_stdio.py against its stdio_client"
    from mcp.client import stdio

    for name in ("_aclose_all", "_close_pipe", "_drain_stdout", "_get_executable_command", "_stop_server_process", "_terminate_process_tree"):
        assert inspect.iscoroutinefunction(getattr(stdio, name)), name
    assert list(inspect.signature(stdio._stop_server_process).parameters) == ["process"]  # noqa: SLF001
    assert list(inspect.signature(stdio._terminate_process_tree).parameters) == ["process"]  # noqa: SLF001


# ---- 2. configuration ------------------------------------------------------------------------------------------------------


def test_the_defaults_are_far_above_any_legitimate_payload_and_inside_the_hard_bounds():
    limits = McpOutputLimits()
    assert limits.max_message_bytes == 1_048_576 >= 16 * 65_536    # 16x the largest tool result the platform ever keeps
    assert limits.stderr_capture_bytes == 16_384 and limits.stderr_flood_bytes == 8_388_608


@pytest.mark.parametrize("field,value", [
    ("max_message_bytes", 65_535), ("max_message_bytes", 16_777_217), ("stderr_capture_bytes", 1_023), ("stderr_capture_bytes", 1_048_577),
    ("stderr_flood_bytes", 65_535), ("stderr_flood_bytes", 268_435_457), ("read_chunk_bytes", 100), ("stderr_flood_window_seconds", 0.1),
])
def test_limits_outside_their_bounds_are_refused(field, value):
    with pytest.raises(ValueError):
        McpOutputLimits(**{field: value})


@pytest.mark.parametrize("field,value", [("mcp_max_message_bytes", 1000), ("mcp_stderr_capture_bytes", 10), ("mcp_stderr_flood_bytes", 5)])
def test_the_application_settings_validate_the_limits(field, value):
    with pytest.raises(ValidationError):
        Settings(environment="test", **{field: value})
    settings = Settings(environment="test")
    assert (settings.mcp_max_message_bytes, settings.mcp_stderr_capture_bytes, settings.mcp_stderr_flood_bytes) == (1_048_576, 16_384, 8_388_608)


def test_the_stderr_capture_keeps_the_head_marks_the_truncation_and_redacts_credentials():
    capture = StderrCapture(64)
    capture.feed(b"warning sk-ant-api03-STDERR-SECRET-0123456789abcdef\x00\x1b[31m")
    capture.feed(b"Z" * 10_000)
    text = capture.text()
    assert capture.retained_bytes == 64 and capture.total_bytes > 10_000 and capture.truncated
    assert "sk-ant-api03-STDERR-SECRET" not in text and "\x00" not in text and "\x1b" not in text
    assert "stderr truncated" in text


# ---- 3. normal operation is unchanged ------------------------------------------------------------------------------------


async def test_a_normal_handshake_discovery_and_call_work_through_the_bounded_transport(manager, launch):
    m = manager()
    tools = {t.name for t in await m.discover("diagnostic")}
    assert {"echo", "big", "flood_stdout"} <= tools
    out = await call(m, "echo", {"text": "hello"})
    assert out.text == "hello" and monitor_of(m).violation is None and monitor_of(m).stdout_messages >= 3


async def test_a_large_stdout_response_below_the_limit_is_accepted_then_truncated_by_the_normal_rules(manager):
    m = manager()
    out = await call(m, "big", {"size": 20_000}, limit=1000)         # ~40 kB on the wire (text + structured content): below the 64 KiB limit
    assert out.truncated is True and len(out.text.encode()) <= 1000
    assert monitor_of(m).violation is None


# ---- 4. stdout over the limit -----------------------------------------------------------------------------------------------


async def test_a_response_over_the_message_limit_is_refused_while_it_is_read_and_reported_as_unknown_outcome(manager, launch):
    m = manager()
    error, monitor = await violated(m, "huge_line", {"size": 500_000})
    assert error.error_code == "OUTCOME_UNKNOWN" and error.reason == "stdout-message-limit"
    assert monitor.violation.limit_bytes == LIMIT and monitor.stdout_peak_retained_bytes <= LIMIT + CHUNK
    assert "AAAA" not in str(error) and len(str(error)) < 300       # no payload in the exception
    assert all(wait_gone(pid) for pid in started_pids(launch))        # the offending process was terminated


async def test_a_single_huge_chunk_is_refused_without_ever_holding_it(manager, launch):
    m = manager()
    error, monitor = await violated(m, "flood_stdout", {"total": 50_000_000, "chunk": 50_000_000})
    assert error.reason == "stdout-message-limit" and monitor.stdout_peak_retained_bytes <= LIMIT + CHUNK
    assert all(wait_gone(pid) for pid in started_pids(launch))


async def test_a_response_without_any_line_break_cannot_grow_the_buffer(manager, launch):
    m = manager()
    error, monitor = await violated(m, "flood_stdout", {"total": -1})   # endless, no newline: the SDK's own reader would grow for ever
    assert error.reason == "stdout-message-limit" and monitor.stdout_peak_retained_bytes <= LIMIT + CHUNK
    assert monitor.stdout_messages >= 1                                  # only the framed messages before the flood were delivered
    assert all(wait_gone(pid) for pid in started_pids(launch))


async def test_a_truncated_message_is_never_accepted_as_a_response(manager):
    m = manager()
    # The partial line is exactly the kind of fragment a truncating reader would hand to the JSON parser: it must never be parsed.
    with pytest.raises(ToolOutputLimitError):
        await call(m, "flood_stdout", {"total": -1})
    assert m._last_monitor.violation is not None and m._last_monitor.stdout_messages >= 1  # noqa: SLF001
    # a fresh connection answers normally: nothing of the aborted message leaked into the session
    assert (await call(m, "echo", {"text": "clean"})).text == "clean"


# ---- 5. stderr ------------------------------------------------------------------------------------------------------------


async def test_a_little_stderr_is_kept_redacted_and_the_call_is_unaffected(manager, capsys):
    m = manager()
    out = await call(m, "stderr_burst", {"total": 300})
    assert out.text == "stderr written"
    capture = monitor_of(m).stderr
    assert capture.total_bytes >= 300 and not capture.truncated
    text = capture.text()
    assert "sk-ant-api03-STDERR-SECRET" not in text and "startup warning" in text


async def test_stderr_beyond_the_capture_limit_is_drained_and_discarded_not_accumulated(manager):
    m = manager()
    out = await call(m, "stderr_burst", {"total": 150_000})              # more than the 1 KiB capture, less than the flood limit
    assert out.text == "stderr written"                                  # a full pipe never blocked the server: drained concurrently
    capture = monitor_of(m).stderr
    assert capture.retained_bytes <= 1_024 and capture.truncated and capture.total_bytes >= 150_000
    assert "stderr truncated" in capture.text() and monitor_of(m).violation is None


async def test_continuous_stderr_is_cut_off_as_a_flood_and_the_manager_recovers(manager, launch):
    m = manager()
    assert (await call(m, "stderr_forever")).text == "started"
    monitor = monitor_of(m)
    deadline = asyncio.get_event_loop().time() + BOUND
    while monitor.violation is None and asyncio.get_event_loop().time() < deadline:
        await asyncio.sleep(0.05)
    assert monitor.violation is not None and monitor.violation.kind == "stderr-flood"
    assert monitor.stderr.retained_bytes <= 1_024                         # a flood never accumulates beyond the capture limit
    assert wait_gone(started_pids(launch)[0])
    assert (await call(m, "echo", {"text": "again"})).text == "again"     # the next call reconnects: the manager stays usable


async def test_simultaneous_stdout_and_stderr_pressure_terminates_the_server_without_deadlock(manager, launch):
    m = manager()
    error, monitor = await violated(m, "pressure", {"stdout_total": -1})
    assert error.error_code == "OUTCOME_UNKNOWN" and monitor.violation.kind in {"stdout-message-limit", "stderr-flood"}
    assert monitor.stdout_peak_retained_bytes <= LIMIT + CHUNK and monitor.stderr.retained_bytes <= 1_024
    assert all(wait_gone(pid) for pid in started_pids(launch))


async def test_a_stall_in_the_middle_of_an_output_ends_at_the_call_timeout_with_the_partial_bytes_discarded(manager, launch):
    m = manager()
    with pytest.raises(ToolExecutionError) as info:
        await call(m, "drip", {"partial": 1000}, timeout=1.5)
    assert info.value.error_code == "TOOL_TIMEOUT" and not isinstance(info.value, ToolOutputLimitError)
    assert m._last_monitor.stdout_peak_retained_bytes < 10_000 and m._last_monitor.violation is None  # noqa: SLF001
    assert all(wait_gone(pid) for pid in started_pids(launch))


# ---- 6. memory ------------------------------------------------------------------------------------------------------------


async def test_peak_python_memory_does_not_grow_with_the_size_of_the_attack(manager):
    m = manager()
    await call(m, "echo", {"text": "warm up"})
    tracemalloc.start()
    try:
        await call(m, "echo", {"text": "baseline"})
        _, baseline = tracemalloc.get_traced_memory()
        tracemalloc.reset_peak()
        with pytest.raises(ToolOutputLimitError):
            await call(m, "flood_stdout", {"total": 268_435_456, "chunk": 268_435_456})   # the server tries to send 256 MiB
        _, attacked = tracemalloc.get_traced_memory()
        peak = tracemalloc.get_traced_memory()[1]
    finally:
        tracemalloc.stop()
    # A 256 MiB attack must not leave a trace anywhere near its size: the retained buffer is capped at the 64 KiB limit.
    assert peak < baseline + 4 * 1024 * 1024, f"peak {peak} vs baseline {baseline}"
    assert m._last_monitor.stdout_peak_retained_bytes <= LIMIT + CHUNK  # noqa: SLF001


# ---- 7. process lifecycle ------------------------------------------------------------------------------------------------


def handles() -> int:
    if sys.platform == "win32":
        import ctypes

        count = ctypes.c_ulong()
        ctypes.windll.kernel32.GetProcessHandleCount(ctypes.windll.kernel32.GetCurrentProcess(), ctypes.byref(count))
        return count.value
    return len(os.listdir("/proc/self/fd"))


async def test_repeated_enforced_terminations_leak_neither_processes_nor_descriptors(manager, launch):
    m = manager()
    await call(m, "echo", {"text": "warm up"})
    with pytest.raises(ToolOutputLimitError):
        await call(m, "flood_stdout", {"total": -1})
    await asyncio.sleep(0.3)
    before = handles()
    for _ in range(6):
        with pytest.raises(ToolOutputLimitError):
            await call(m, "flood_stdout", {"total": -1})
    await asyncio.sleep(0.3)
    assert handles() - before <= 8, "pipes or process handles leak on every enforced termination"
    pids = started_pids(launch)
    assert len(pids) >= 7 and all(not pid_alive(pid) for pid in pids[:-1])


async def test_the_manager_works_normally_after_an_enforced_termination(manager, launch):
    m = manager()
    with pytest.raises(ToolOutputLimitError):
        await call(m, "huge_line", {"size": 300_000})
    out = await call(m, "echo", {"text": "after"})
    assert out.text == "after" and len(started_pids(launch)) == 2      # a NEW server process, the old one is gone
    assert not pid_alive(started_pids(launch)[0])


# ---- 8. logs and messages --------------------------------------------------------------------------------------------------


async def test_a_stderr_flood_does_not_amplify_into_the_application_log_and_leaks_no_secret(manager, capsys):
    m = manager()
    await call(m, "stderr_burst", {"total": 20_000})                       # the first line carries a fake credential
    monitor = monitor_of(m)
    with contextlib.suppress(ToolOutputLimitError):                       # the endless flood may cut the connection before it answers
        await call(m, "stderr_forever")
    deadline = asyncio.get_event_loop().time() + BOUND
    while monitor.violation is None and asyncio.get_event_loop().time() < deadline:
        await asyncio.sleep(0.05)
    assert monitor.violation is not None and monitor.violation.kind == "stderr-flood"
    await m.aclose()
    logged = capsys.readouterr().out + capsys.readouterr().err
    assert "sk-ant-api03-STDERR-SECRET" not in logged
    assert logged.count("mcp_stderr") <= 1 and len(logged) < 12_000      # megabytes of stderr produced a handful of short log lines


# ---- 9. an external side effect is never replayed ------------------------------------------------------------------------


async def test_an_oversized_answer_after_a_side_effect_is_an_unknown_outcome_and_the_effect_is_never_repeated(manager, tmp_path):
    counter = tmp_path / "effects.txt"
    m = manager()
    error, _ = await violated(m, "effect_then_flood", {"counter": str(counter)})
    assert error.error_code == "OUTCOME_UNKNOWN"                       # not TOOL_FAILED, not a cancellation
    assert counter.read_text().splitlines() == ["1"]                  # the effect HAPPENED once, outside the process
    await asyncio.sleep(1.0)
    assert counter.read_text().splitlines() == ["1"]                  # and the manager did not repeat it by itself
    assert (await call(m, "echo", {"text": "next"})).text == "next"    # later, independent calls work
    assert counter.read_text().splitlines() == ["1"]


EFFECT = {
    **ECHO, "toolCode": "diagnostic.effect", "mcpTool": "effect_then_flood", "name": "Effect",
    "inputSchema": {"type": "object", "properties": {"counter": {"type": "string", "maxLength": 1000}}, "required": ["counter"], "additionalProperties": False},
}


class AdversarialCore(CoreScript):
    """The Core API of the tool-execution tests, but ``begin`` hands out the adversarial tool."""

    def _begin(self, request):
        invocation = request.url.path.split("/")[-2]
        self.begins.append(invocation)
        call_id = next(c for c, i in self.invocation_for_call.items() if i == invocation)
        return httpx.Response(200, json={
            "invocationId": invocation, "toolCode": "diagnostic.effect", "mcpServer": "diagnostic", "mcpTool": "effect_then_flood",
            "arguments": self.arguments[call_id], "timeoutSeconds": 10, "maxOutputBytes": 4096,
        })


@respx.mock
async def test_the_whole_chain_reports_unknown_outcome_ends_the_execution_and_never_repeats_the_tool(manager, tmp_path):
    counter = tmp_path / "effects.txt"
    real = manager()
    e = Env(manager=real)
    runner = await e.build()
    core = AdversarialCore(respx.mock, tools=(EFFECT,))
    provider = Provider(tool_use("diagnostic_effect", "toolu_1", {"counter": str(counter)}), final("I will do it again."))
    await run(runner)
    # the Core API was told, truthfully, that the outcome is unknown (its classifier turns this into OUTCOME_UNKNOWN, never retried)
    assert core.completes == [{"success": False, "output": "", "truncated": False, "errorCode": "OUTCOME_UNKNOWN", "message": core.completes[0]["message"]}]
    assert "unknown" in core.completes[0]["message"] and "AAAA" not in core.completes[0]["message"] and "XXXX" not in core.completes[0]["message"]
    # the execution ENDED as failed: the model was not given a second turn in which it could repeat a possibly completed effect
    assert core.posted(core.fail)["errorCode"] == "TOOL_FAILED" and len(provider.requests) == 1
    assert not core.complete.called
    assert len(core.begins) == 1 and len(core.requests) == 1             # ONE tool call in total, one begin: no replay anywhere
    assert counter.read_text().splitlines() == ["1"]                    # the external effect happened exactly once
    await asyncio.sleep(0.5)
    assert counter.read_text().splitlines() == ["1"]
    await e.close()
    assert EXECUTION is not None


@respx.mock
async def test_when_the_core_api_already_closed_the_call_the_execution_still_ends_without_a_retry(manager, tmp_path):
    counter = tmp_path / "effects.txt"
    e = Env(manager=manager())
    runner = await e.build()
    core = AdversarialCore(respx.mock, tools=(EFFECT,))
    core.complete_status = 409                                           # e.g. the execution was cancelled/timed out meanwhile
    provider = Provider(tool_use("diagnostic_effect", "toolu_1", {"counter": str(counter)}), final("again"))
    await run(runner)
    assert core.posted(core.fail)["errorCode"] == "TOOL_FAILED" and len(provider.requests) == 1
    assert counter.read_text().splitlines() == ["1"]
    await e.close()
