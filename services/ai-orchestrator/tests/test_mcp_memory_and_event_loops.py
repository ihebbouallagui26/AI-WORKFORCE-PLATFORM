"""Security fix 8.4, closure: process-level memory (RSS) and the Windows SelectorEventLoop path.

* RSS: the PEAK resident set of THIS process (Windows peak working set, Linux VmHWM) before and after a 256 MiB stdout attack. It is a
  different quantity from the ``tracemalloc`` Python-heap figure of tests/test_mcp_output_limits.py: it includes the interpreter, the
  allocator, the pipes' user-space buffers and everything native. The child's own memory is NOT included.
* SelectorEventLoop (Windows): the SDK falls back to a ``Popen``-backed process there, and stderr is read through ``FileReadStream``.
"""

import asyncio
import ctypes
import sys
from pathlib import Path

import pytest
from mcp import StdioServerParameters

from app.mcp import executor
from app.mcp.bounded_stdio import McpOutputLimits
from app.mcp.configuration import McpServerConfig
from app.mcp.executor import McpServerManager, ToolExecutionError, ToolOutputLimitError

SERVER = str(Path(__file__).parent / "support" / "mcp_test_server.py")
LIMITS = McpOutputLimits(max_message_bytes=65_536, stderr_capture_bytes=1_024, stderr_flood_bytes=262_144)


def peak_rss_bytes() -> int:
    if sys.platform == "win32":
        class Counters(ctypes.Structure):
            _fields_ = [("cb", ctypes.c_ulong), ("PageFaultCount", ctypes.c_ulong), ("PeakWorkingSetSize", ctypes.c_size_t),
                        ("WorkingSetSize", ctypes.c_size_t), ("QuotaPeakPagedPoolUsage", ctypes.c_size_t),
                        ("QuotaPagedPoolUsage", ctypes.c_size_t), ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t),
                        ("QuotaNonPagedPoolUsage", ctypes.c_size_t), ("PagefileUsage", ctypes.c_size_t), ("PeakPagefileUsage", ctypes.c_size_t)]

        from ctypes import wintypes

        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        psapi = ctypes.WinDLL("psapi", use_last_error=True)
        kernel32.GetCurrentProcess.restype = wintypes.HANDLE
        psapi.GetProcessMemoryInfo.argtypes = [wintypes.HANDLE, ctypes.POINTER(Counters), wintypes.DWORD]
        psapi.GetProcessMemoryInfo.restype = wintypes.BOOL
        counters = Counters()
        counters.cb = ctypes.sizeof(Counters)
        if not psapi.GetProcessMemoryInfo(kernel32.GetCurrentProcess(), ctypes.byref(counters), counters.cb):
            raise OSError(ctypes.get_last_error(), "GetProcessMemoryInfo failed")
        return counters.PeakWorkingSetSize
    with open("/proc/self/status", encoding="utf-8") as status:
        for line in status:
            if line.startswith("VmHWM:"):
                return int(line.split()[1]) * 1024
    raise RuntimeError("no peak RSS available")


def config() -> McpServerConfig:
    return McpServerConfig(name="diagnostic", server_id="diagnostic", timeout_seconds=10.0)


def patch_launch(monkeypatch, tmp_path):
    def params(_config):
        return StdioServerParameters(command=sys.executable, args=[SERVER, "normal", str(tmp_path / "pid")])

    monkeypatch.setattr(executor, "launch_parameters", params)


async def test_peak_process_memory_does_not_grow_with_the_size_of_the_attack(monkeypatch, tmp_path):
    patch_launch(monkeypatch, tmp_path)
    m = McpServerManager([config()], limits=LIMITS)
    try:
        await m.call("diagnostic", "echo", {"text": "warm up"}, timeout_seconds=10, max_output_bytes=4096)
        before = peak_rss_bytes()
        for _ in range(3):                                                    # 3 x 256 MiB offered to the pipe, never accepted
            with pytest.raises(ToolOutputLimitError):
                await m.call("diagnostic", "flood_stdout", {"total": 268_435_456, "chunk": 268_435_456}, timeout_seconds=20, max_output_bytes=4096)
        after = peak_rss_bytes()
    finally:
        await m.aclose()
    growth = after - before
    print(f"\n[rss] peak before {before / 2**20:.1f} MiB, after {after / 2**20:.1f} MiB, growth {growth / 2**20:.1f} MiB (3 x 256 MiB attacks)")
    assert before > 10 * 2**20, "the RSS probe returned nothing useful"
    assert growth < 24 * 2**20, f"peak RSS grew by {growth / 2**20:.1f} MiB under a 768 MiB offered attack"


@pytest.mark.skipif(sys.platform != "win32", reason="the SelectorEventLoop subprocess fallback exists on Windows only")
def test_the_windows_selector_event_loop_fallback_path_is_bounded_too(monkeypatch, tmp_path):
    """No async subprocess support there: the SDK spawns with ``Popen``; stderr must still be piped, drained and bounded."""
    patch_launch(monkeypatch, tmp_path)

    async def scenario() -> dict:
        assert type(asyncio.get_running_loop()).__name__ == "_WindowsSelectorEventLoop"
        out: dict = {}
        m = McpServerManager([config()], limits=LIMITS)
        try:
            out["normal"] = (await m.call("diagnostic", "echo", {"text": "hello"}, timeout_seconds=10, max_output_bytes=4096)).text
            await m.call("diagnostic", "stderr_burst", {"total": 150_000}, timeout_seconds=10, max_output_bytes=4096)
            capture = m._connections["diagnostic"].monitor.stderr  # noqa: SLF001
            out["stderr"] = (capture.total_bytes >= 150_000, capture.retained_bytes <= 1_024, capture.truncated)
            try:
                await m.call("diagnostic", "flood_stdout", {"total": -1}, timeout_seconds=20, max_output_bytes=4096)
            except ToolOutputLimitError as exc:
                out["stdout_violation"] = (exc.reason, exc.error_code, exc.outcome_unknown)
            out["after"] = (await m.call("diagnostic", "echo", {"text": "again"}, timeout_seconds=10, max_output_bytes=4096)).text
            try:
                await m.call("diagnostic", "hang", {}, timeout_seconds=1.5, max_output_bytes=4096)
            except ToolExecutionError as exc:
                out["timeout"] = (exc.error_code, exc.outcome_unknown)
        finally:
            await m.aclose()
        return out

    result = asyncio.run(scenario(), loop_factory=asyncio.SelectorEventLoop)
    assert result["normal"] == "hello" and result["after"] == "again"
    assert result["stderr"] == (True, True, True)                               # stderr drained through FileReadStream, retained bounded
    assert result["stdout_violation"] == ("stdout-message-limit", "OUTCOME_UNKNOWN", True)
    assert result["timeout"] == ("TOOL_TIMEOUT", True)
