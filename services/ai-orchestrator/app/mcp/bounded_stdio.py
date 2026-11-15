"""Memory-bounded stdio transport for allowlisted MCP servers (security finding 8.4).

Why this exists
---------------
The MCP SDK's own ``stdio_client`` reads the child's stdout in 64 KiB chunks but keeps everything since the last newline in an
unbounded ``str`` (``buffer = (buffer + chunk).split("\\n")``), then parses the whole line. A server that never sends a newline, or
sends one enormous line, makes the orchestrator's memory grow without limit BEFORE any of the platform's output limits (which only
truncate the *normalized* result, after the full receipt) can act. Its stderr is not captured at all: the child inherits the
orchestrator's own stderr, so a chatty or hostile server writes straight into the container log with no bound.

What this transport does instead (same wire protocol, same session streams as the SDK)
--------------------------------------------------------------------------------------
* stdout is consumed as BYTES in chunks of at most ``read_chunk_bytes``. The bytes of the line being assembled are retained in one
  buffer whose size is checked BEFORE every append: the retained size never exceeds ``max_message_bytes`` (plus the one chunk in
  flight). A line that is already too long is detected while it is being read, not after.
* On overflow the child process tree is terminated at once, nothing of the partial line is parsed or delivered (a truncated
  JSON-RPC message is never accepted), the session sees a closed stream, and the violation is recorded on the monitor so the caller
  can report an honest "outcome unknown" instead of a generic failure.
* stderr is piped and drained concurrently (no pipe deadlock). Only the first ``stderr_capture_bytes`` are retained, marked as
  truncated beyond that; the rest is counted and discarded. A server that writes more than ``stderr_flood_bytes`` inside a rolling
  window is treated as abusive and terminated. The only log output is ONE bounded, redacted excerpt per connection.
* Shutdown reuses the SDK's own sequence (close stdin, grace period, kill the whole tree), so no child or pipe outlives the transport.

The SDK's process helpers are private (leading underscore). They are pinned by ``mcp==2.3.0`` in ``pyproject.toml`` and
``tests/test_mcp_output_limits.py`` fails loudly if a future SDK removes or renames one of them.
"""

from __future__ import annotations

import re
import subprocess  # noqa: S404 - only the PIPE constant: the launch specification is a fixed allowlist entry, never user input
import sys
import time
from collections.abc import AsyncGenerator
from contextlib import asynccontextmanager, suppress
from dataclasses import dataclass, field
from typing import Any

import anyio
import anyio.lowlevel
import mcp_types as types
from anyio.streams.file import FileReadStream
from mcp import StdioServerParameters
from mcp.client._transport import TransportStreams
from mcp.client.stdio import (  # pinned SDK internals, guarded by a test
    _aclose_all,
    _close_pipe,
    _drain_stdout,
    _get_executable_command,
    _stop_server_process,
    _terminate_process_tree,
    get_default_environment,
)
from mcp.os.win32.utilities import FallbackProcess, ServerProcess, create_windows_process
from mcp.shared.message import SessionMessage

from app.core.logging import get_logger, redact

log = get_logger("mcp-transport")

# Hard bounds of the configurable limits (validated here AND in the application settings).
MIN_MESSAGE_BYTES, MAX_MESSAGE_BYTES = 65_536, 16_777_216
MIN_STDERR_CAPTURE, MAX_STDERR_CAPTURE = 1_024, 1_048_576
MIN_STDERR_FLOOD, MAX_STDERR_FLOOD = 65_536, 268_435_456
MIN_STDOUT_FLOOD, MAX_STDOUT_FLOOD = 262_144, 1_073_741_824
MIN_STDOUT_MESSAGES, MAX_STDOUT_MESSAGES = 100, 10_000_000
MIN_CALL_BYTES, MAX_CALL_BYTES = 65_536, 268_435_456
# One log line per connection carries at most this many characters of the (redacted) stderr capture.
LOG_EXCERPT_CHARS = 2_048
_CONTROL = re.compile(r"[\x00-\x08\x0b-\x1f\x7f]")


@dataclass(frozen=True)
class McpOutputLimits:
    """Memory limits of one MCP connection. Defaults are justified in docs/SPRINT_C3_6_SECURITY_INTEGRATION_REPORT.md (fix 8.4)."""

    # One JSON-RPC message (one stdout line). The platform truncates a tool result to at most 65,536 characters (default 8,000)
    # AFTER receipt; JSON escaping can multiply that by up to 6 and structured content repeats it, so 1 MiB is far above any
    # legitimate result and tool-list (each tool schema is capped at 16 KiB) while a hard ceiling stays small.
    max_message_bytes: int = 1_048_576
    # Diagnostic stderr kept per connection (the head: start-up errors and the first traceback are what is useful).
    stderr_capture_bytes: int = 16_384
    # Total stderr tolerated per 10-second window before the server is considered abusive and terminated.
    stderr_flood_bytes: int = 8_388_608
    # AGGREGATE bounds (the per-message limit alone does not stop a stream of individually valid messages). All are counted over the
    # same rolling window as the stderr flood limit. A server that exceeds one is terminated like one that breaks the message limit.
    # 16 MiB / 10 s = 16 maximum-size messages; 50,000 messages / 10 s is far above any legitimate request/response/notification rate.
    stdout_flood_bytes: int = 16_777_216
    stdout_flood_messages: int = 50_000
    # Everything a server may send while ONE tool call is in flight (responses, progress and log notifications): 4x the message limit.
    max_call_bytes: int = 4_194_304
    stderr_flood_window_seconds: float = 10.0
    # Size of one read. It bounds how far past the limit a buffer can briefly be (one chunk).
    read_chunk_bytes: int = 65_536

    def __post_init__(self) -> None:
        checks = (
            ("max_message_bytes", self.max_message_bytes, MIN_MESSAGE_BYTES, MAX_MESSAGE_BYTES),
            ("stderr_capture_bytes", self.stderr_capture_bytes, MIN_STDERR_CAPTURE, MAX_STDERR_CAPTURE),
            ("stderr_flood_bytes", self.stderr_flood_bytes, MIN_STDERR_FLOOD, MAX_STDERR_FLOOD),
            ("stdout_flood_bytes", self.stdout_flood_bytes, MIN_STDOUT_FLOOD, MAX_STDOUT_FLOOD),
            ("stdout_flood_messages", self.stdout_flood_messages, MIN_STDOUT_MESSAGES, MAX_STDOUT_MESSAGES),
            ("max_call_bytes", self.max_call_bytes, MIN_CALL_BYTES, MAX_CALL_BYTES),
        )
        for name, value, low, high in checks:
            if not low <= value <= high:
                raise ValueError(f"{name} must be between {low} and {high}")
        if not 1_024 <= self.read_chunk_bytes <= 262_144:
            raise ValueError("read_chunk_bytes must be between 1024 and 262144")
        if not 0.5 <= self.stderr_flood_window_seconds <= 600:
            raise ValueError("stderr_flood_window_seconds must be between 0.5 and 600")


@dataclass(frozen=True)
class OutputViolation:
    """A hard limit that was exceeded. ``kind`` is a safe constant; no content of the offending output is kept."""

    kind: str  # "stdout-message-limit" | "stdout-flood" | "stdout-message-flood" | "stdout-call-limit" | "stderr-flood"
    limit_bytes: int


class StderrCapture:
    """Bounded diagnostic capture of a child's stderr: keeps the head, counts the rest."""

    def __init__(self, capacity: int) -> None:
        self._capacity = capacity
        self._head = bytearray()
        self.total_bytes = 0

    def feed(self, chunk: bytes) -> None:
        self.total_bytes += len(chunk)
        room = self._capacity - len(self._head)
        if room > 0:
            self._head += chunk[:room]

    @property
    def retained_bytes(self) -> int:
        return len(self._head)

    @property
    def truncated(self) -> bool:
        return self.total_bytes > len(self._head)

    def text(self) -> str:
        """The retained diagnostics as safe text: control characters removed, credentials redacted, truncation marked."""
        raw = bytes(self._head).decode("utf-8", errors="replace")
        cleaned = redact(_CONTROL.sub(" ", raw))
        if self.truncated:
            cleaned += f"\n[stderr truncated: {self.total_bytes - len(self._head)} more byte(s) were discarded]"
        return cleaned


@dataclass
class TransportMonitor:
    """What the transport observed. Read by the caller after a failure; never contains protocol payloads."""

    limits: McpOutputLimits
    violation: OutputViolation | None = None
    stdout_peak_retained_bytes: int = 0
    stdout_messages: int = 0
    stdout_total_bytes: int = 0
    # Requests written to the server (counted BEFORE the write starts: a write that fails midway may still have reached it).
    stdin_started: int = 0
    # Bytes received from the server while a tool call is in flight (see begin_call / end_call).
    call_bytes: int = 0
    call_active: bool = False
    stderr: StderrCapture = field(init=False)

    def __post_init__(self) -> None:
        self.stderr = StderrCapture(self.limits.stderr_capture_bytes)

    def begin_call(self) -> int:
        """Starts the per-call byte budget. Returns the request counter, to tell later whether a request was sent during the call."""
        self.call_bytes = 0
        self.call_active = True
        return self.stdin_started

    def end_call(self) -> None:
        self.call_active = False


async def _spawn(server: StdioServerParameters) -> ServerProcess:
    """Like the SDK's spawn, but with stderr PIPED (so it can be bounded) instead of inherited."""
    command = await _get_executable_command(server.command)
    env = get_default_environment() | (server.env or {})
    if sys.platform == "win32":  # pragma: no cover - exercised by the Windows development runs
        return await create_windows_process(command, server.args, env, subprocess.PIPE, server.cwd)  # type: ignore[arg-type]
    return await anyio.open_process([command, *server.args], env=env, stderr=subprocess.PIPE, cwd=server.cwd, start_new_session=True)


def _stderr_stream(process: ServerProcess) -> Any | None:
    if isinstance(process, FallbackProcess):  # Windows event loops without async subprocesses (not the default loop)
        pipe = process.popen.stderr
        return FileReadStream(pipe) if pipe is not None else None  # type: ignore[arg-type]
    return process.stderr


def _parse(line: bytes) -> SessionMessage | Exception:
    try:
        return SessionMessage(types.jsonrpc_message_adapter.validate_json(line, by_name=False))
    except ValueError:
        # Never log or raise the payload: it is untrusted and may be huge or hold secrets.
        log.warning("mcp_invalid_message", length=len(line))
        return ValueError("The MCP server sent an invalid message.")


@asynccontextmanager
async def bounded_stdio_client(
    server: StdioServerParameters, monitor: TransportMonitor, *, label: str = "mcp"
) -> AsyncGenerator[TransportStreams, None]:
    """Spawns the allowlisted server and yields the session streams, with every byte read from it bounded."""
    if server.encoding.lower().replace("_", "-") not in ("utf-8", "utf8"):
        raise ValueError("Only UTF-8 MCP servers are supported.")
    limits = monitor.limits
    process = await _spawn(server)
    # The spawn succeeded; no awaits until the task group is entered, or a cancellation in the gap would leak the live process.
    read_stream_writer, read_stream = anyio.create_memory_object_stream[SessionMessage | Exception](0)
    write_stream, write_stream_reader = anyio.create_memory_object_stream[SessionMessage](0)
    shutting_down = False
    writer_done = anyio.Event()

    async def terminate(kind: str, limit: int) -> None:
        """Records the violation (first one wins) and kills the whole process tree. Nothing else is read from the child."""
        if monitor.violation is None:
            monitor.violation = OutputViolation(kind, limit)
            log.warning("mcp_output_limit_exceeded", server=label, kind=kind, limit_bytes=limit)
        with anyio.CancelScope(shield=True):
            await _terminate_process_tree(process)

    async def stdout_reader() -> None:
        assert process.stdout, "Opened process is missing stdout"
        limit = limits.max_message_bytes
        line = bytearray()  # the line being assembled: its size is checked BEFORE every append
        violated = False
        window_start = time.monotonic()
        window_bytes = window_messages = 0
        try:
            async with read_stream_writer:
                try:
                    while True:
                        try:
                            chunk = await process.stdout.receive(limits.read_chunk_bytes)
                        except anyio.EndOfStream:
                            break
                        monitor.stdout_peak_retained_bytes = max(monitor.stdout_peak_retained_bytes, len(line) + len(chunk))
                        # Aggregate bounds: many individually valid messages are still bounded in bytes, in count and per tool call.
                        monitor.stdout_total_bytes += len(chunk)
                        now = time.monotonic()
                        if now - window_start >= limits.stderr_flood_window_seconds:
                            window_start, window_bytes, window_messages = now, 0, 0
                        window_bytes += len(chunk)
                        if monitor.call_active:
                            monitor.call_bytes += len(chunk)
                        breach = None
                        if window_bytes > limits.stdout_flood_bytes:
                            breach = ("stdout-flood", limits.stdout_flood_bytes)
                        elif monitor.call_active and monitor.call_bytes > limits.max_call_bytes:
                            breach = ("stdout-call-limit", limits.max_call_bytes)
                        if breach is not None:
                            violated = True
                            await terminate(*breach)
                            return
                        position = 0
                        while position < len(chunk):
                            newline = chunk.find(b"\n", position)
                            end = len(chunk) if newline < 0 else newline
                            if len(line) + (end - position) > limit:  # checked BEFORE the append: the buffer never exceeds the limit
                                violated = True
                                await terminate("stdout-message-limit", limit)
                                return
                            line += chunk[position:end]
                            if newline < 0:
                                break  # the line continues in the next chunk
                            position = newline + 1
                            complete = bytes(line)
                            line.clear()
                            if complete.strip():
                                monitor.stdout_messages += 1
                                window_messages += 1
                                if window_messages > limits.stdout_flood_messages:
                                    violated = True
                                    await terminate("stdout-message-flood", limits.stdout_flood_messages)
                                    return
                                try:
                                    await read_stream_writer.send(_parse(complete))
                                except (anyio.ClosedResourceError, anyio.BrokenResourceError):
                                    return  # the session is gone; only the drain below remains
                finally:
                    if not violated:
                        await _drain_stdout(process)  # keep a flushing server from blocking on a full pipe (bytes discarded)
        except anyio.ClosedResourceError:
            pass  # our own shutdown closed the stdout stream under the read
        except (anyio.BrokenResourceError, ConnectionError):
            if not shutting_down:
                log.warning("mcp_stdout_read_failed", server=label)

    async def stderr_reader() -> None:
        stream = _stderr_stream(process)
        if stream is None:
            return
        window_start = time.monotonic()
        window_bytes = 0
        with suppress(anyio.EndOfStream, anyio.ClosedResourceError, anyio.BrokenResourceError, ConnectionError, OSError):
            while True:
                chunk = await stream.receive(limits.read_chunk_bytes)
                monitor.stderr.feed(chunk)
                now = time.monotonic()
                if now - window_start >= limits.stderr_flood_window_seconds:
                    window_start, window_bytes = now, 0
                window_bytes += len(chunk)
                if window_bytes > limits.stderr_flood_bytes:
                    await terminate("stderr-flood", limits.stderr_flood_bytes)
                    return
                await anyio.lowlevel.checkpoint()  # a flood must not monopolize the event loop

    async def stdin_writer() -> None:
        assert process.stdin, "Opened process is missing stdin"
        try:
            async with write_stream_reader:
                async for session_message in write_stream_reader:
                    payload = session_message.message.model_dump_json(by_alias=True, exclude_unset=True)
                    monitor.stdin_started += 1  # counted BEFORE the write: from here on the server may have received the request
                    await process.stdin.send((payload + "\n").encode("utf-8"))
        except (anyio.ClosedResourceError, anyio.BrokenResourceError, OSError):
            await read_stream_writer.aclose()  # the session sees the connection end instead of a request hanging forever
        finally:
            writer_done.set()

    async def shutdown() -> None:
        read_stream.close()
        write_stream.close()
        with anyio.move_on_after(0.5) as flush_scope:
            await writer_done.wait()
        if flush_scope.cancelled_caught:
            await anyio.lowlevel.cancel_shielded_checkpoint()
        await _stop_server_process(process)
        stderr_pipe = _stderr_stream(process)
        if stderr_pipe is not None:
            await _close_pipe(stderr_pipe)
        await _aclose_all(read_stream, write_stream, read_stream_writer, write_stream_reader)
        await anyio.lowlevel.checkpoint()

    try:
        async with anyio.create_task_group() as tg:
            tg.start_soon(stdout_reader)
            tg.start_soon(stderr_reader)
            tg.start_soon(stdin_writer)
            try:
                yield read_stream, write_stream
            finally:
                shutting_down = True
                with anyio.CancelScope(shield=True):
                    await shutdown()
                tg.cancel_scope.cancel()
        await anyio.lowlevel.cancel_shielded_checkpoint()
    finally:
        if monitor.stderr.total_bytes:
            # ONE bounded, redacted excerpt per connection: stderr never becomes an uncontrolled application log.
            log.info(
                "mcp_stderr", server=label, bytes=monitor.stderr.total_bytes, kept=monitor.stderr.retained_bytes,
                truncated=monitor.stderr.truncated, excerpt=monitor.stderr.text()[:LOG_EXCERPT_CHARS],
            )


class BoundedStdioTransport:
    """An MCP SDK ``Transport`` (async context manager yielding the session streams) around :func:`bounded_stdio_client`."""

    def __init__(self, server: StdioServerParameters, limits: McpOutputLimits | None = None, *, label: str = "mcp") -> None:
        self.monitor = TransportMonitor(limits or McpOutputLimits())
        self._server = server
        self._label = label
        self._context: Any = None

    async def __aenter__(self) -> TransportStreams:
        self._context = bounded_stdio_client(self._server, self.monitor, label=self._label)
        return await self._context.__aenter__()

    async def __aexit__(self, exc_type: Any, exc: Any, traceback: Any) -> bool | None:
        context, self._context = self._context, None
        return await context.__aexit__(exc_type, exc, traceback)
