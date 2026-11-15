"""Controlled MCP tool execution engine (C2).

Trust model
-----------
* MCP servers are chosen by the PLATFORM ADMINISTRATOR through configuration, as ids of a closed
  allowlist in code (see ``configuration.py``). An agent, a model or a user can never provide a
  command, arguments, environment variables, a working directory or a URL.
* A tool is executable only if its registry entry (Core API) names an enabled server and a tool that
  the server really exposes with valid, screened metadata.
* A call returns a NORMALIZED, BOUNDED result: text only, truncated to the tool's output limit.
  Whatever a server returns is untrusted data.

Lifecycle
---------
One persistent connection per server, owned by a dedicated background task (so the child process is
always started and stopped from the same task and is cleaned up on shutdown, timeout or crash).
A health check (ping) runs before use; an unhealthy or crashed connection is replaced. A call whose
outcome is unknown is NEVER retried (at most once): it is reported as failed.
"""

from __future__ import annotations

import asyncio
import json
import warnings
from dataclasses import dataclass
from typing import Any

from mcp import Client, MCPError

from app.core.errors import OrchestratorError
from app.core.logging import get_logger
from app.mcp.bounded_stdio import BoundedStdioTransport, McpOutputLimits, TransportMonitor
from app.mcp.configuration import McpServerConfig, launch_parameters
from app.mcp.registry import ToolInfo, ToolRegistry, validate_tool_metadata

log = get_logger("mcp-executor")

_NON_TEXT = "[non-text content omitted]"
_METHOD_NOT_FOUND = -32601
# JSON-RPC errors that a server returns BEFORE any tool code runs (parse error, invalid request, method not found, invalid params).
_NOT_EXECUTED = frozenset({-32700, -32600, -32601, -32602})


class ToolExecutionError(OrchestratorError):
    """A tool call failed. ``code`` is a safe code reported to the Core API; the message is user safe."""

    status = 502
    code = "tool-failed"
    default_message = "The tool failed."

    def __init__(self, error_code: str, message: str, *, outcome_unknown: bool = False) -> None:
        super().__init__(message)
        self.error_code = error_code
        # True only when the request MAY have reached the tool server and no trustworthy result came back: the external action
        # may have happened. The caller reports it to the Core API as OUTCOME_UNKNOWN and never repeats the call.
        self.outcome_unknown = outcome_unknown


class ToolOutputLimitError(ToolExecutionError):
    """The server exceeded a hard output limit (stdout message or stderr flood) and its process was terminated.

    The call had already been STARTED, so the tool may have produced its external effect while its result is lost and cannot be
    trusted. It is therefore reported with the Core API's unknown-outcome code, never as a plain failure or a cancellation, and the
    caller must neither retry nor repeat it. ``reason`` is a safe constant; nothing of the offending output is kept.
    """

    def __init__(self, reason: str) -> None:
        super().__init__(
            "OUTCOME_UNKNOWN",
            "The tool server exceeded the output limit and was stopped: whether the action took effect is unknown. It is not repeated.",
            outcome_unknown=True,
        )
        self.reason = reason


@dataclass(frozen=True)
class ToolOutput:
    text: str
    truncated: bool
    is_error: bool


def truncate_utf8(text: str, max_bytes: int) -> tuple[str, bool]:
    raw = text.encode("utf-8")
    if len(raw) <= max_bytes:
        return text, False
    return raw[:max_bytes].decode("utf-8", errors="ignore"), True


def normalize_result(result: Any, max_bytes: int) -> ToolOutput:
    """Text content only; structured content as compact JSON when there is no text; everything else is dropped."""
    parts: list[str] = []
    for item in result.content or []:
        text = getattr(item, "text", None)
        parts.append(text if isinstance(text, str) else _NON_TEXT)
    if not any(p != _NON_TEXT for p in parts) and result.structured_content is not None:
        parts = [json.dumps(result.structured_content, separators=(",", ":"), sort_keys=True, default=str)]
    text, truncated = truncate_utf8("\n".join(parts), max_bytes)
    return ToolOutput(text=text, truncated=truncated, is_error=bool(result.is_error))


class _ServerConnection:
    """One MCP server: a background task owns the connection from start to clean shutdown."""

    def __init__(self, config: McpServerConfig, limits: McpOutputLimits) -> None:
        self.config = config
        self.limits = limits
        self.monitor: TransportMonitor | None = None
        self.registry = ToolRegistry()
        self.client: Client | None = None
        self._ready = asyncio.Event()
        self._stop = asyncio.Event()
        self._failure: BaseException | None = None
        self._task: asyncio.Task[None] | None = None

    async def start(self) -> None:
        self._task = asyncio.create_task(self._own(), name=f"mcp-{self.config.name}")
        try:
            async with asyncio.timeout(self.config.timeout_seconds):
                await self._ready.wait()
        except TimeoutError as exc:
            await self.stop()
            raise ToolExecutionError("MCP_UNAVAILABLE", "The tool server did not start in time.") from exc
        if self._failure is not None:
            await self.stop()
            raise ToolExecutionError("MCP_UNAVAILABLE", "The tool server could not be started.") from self._failure

    async def _own(self) -> None:
        try:
            transport = BoundedStdioTransport(launch_parameters(self.config), self.limits, label=self.config.name)
            self.monitor = transport.monitor
            async with Client(transport, read_timeout_seconds=self.config.timeout_seconds) as client:
                self.client = client
                listed = await client.list_tools()
                for tool in listed.tools:
                    try:
                        self.registry.register(validate_tool_metadata(self.config.name, tool.name, tool.description, tool.input_schema))
                    except OrchestratorError:
                        log.warning("mcp_tool_rejected", server=self.config.name, tool=tool.name[:40])
                self._ready.set()
                await self._stop.wait()
        except BaseException as exc:  # noqa: BLE001 - reported to start(); the child process is closed by the context manager
            self._failure = exc
            self._ready.set()
            if isinstance(exc, asyncio.CancelledError):
                raise
        finally:
            self.client = None

    @property
    def alive(self) -> bool:
        return self._task is not None and not self._task.done() and self.client is not None

    async def healthy(self) -> bool:
        if not self.alive or self.client is None:
            return False
        try:
            async with asyncio.timeout(3):
                with warnings.catch_warnings():
                    # ping is deprecated by the SDK (legacy mode only); a "method not found" answer still proves liveness.
                    warnings.simplefilter("ignore")
                    await self.client.send_ping()
            return True
        except MCPError as exc:
            # A server that answers "method not found" to ping is alive (ping is optional in the protocol).
            return exc.code == _METHOD_NOT_FOUND
        except (TimeoutError, OSError, RuntimeError):
            return False

    async def stop(self) -> None:
        self._stop.set()
        task, self._task = self._task, None
        if task is not None:
            try:
                async with asyncio.timeout(5):
                    await task
            except (TimeoutError, asyncio.CancelledError):
                task.cancel()
                await asyncio.gather(task, return_exceptions=True)


class McpServerManager:
    """Connection management, discovery, bounded concurrency and invocation of allowlisted MCP servers."""

    def __init__(self, configs: list[McpServerConfig], *, max_concurrent_calls: int = 4, limits: McpOutputLimits | None = None) -> None:
        self._configs = {c.name: c for c in configs}
        self._limits = limits or McpOutputLimits()
        self._connections: dict[str, _ServerConnection] = {}
        self._lock = asyncio.Lock()
        self._semaphore = asyncio.Semaphore(max_concurrent_calls)

    @property
    def server_names(self) -> frozenset[str]:
        return frozenset(self._configs)

    async def _connection(self, server: str) -> _ServerConnection:
        config = self._configs.get(server)
        if config is None:
            raise ToolExecutionError("SERVER_NOT_CONFIGURED", "This tool server is not configured.")
        async with self._lock:
            current = self._connections.get(server)
            if current is not None and await current.healthy():
                return current
            if current is not None:
                log.warning("mcp_reconnect", server=server)
                await current.stop()
            fresh = _ServerConnection(config, self._limits)
            await fresh.start()
            self._connections[server] = fresh
            return fresh

    async def discover(self, server: str) -> list[ToolInfo]:
        """Validated tool metadata of a configured server (connects if needed)."""
        return (await self._connection(server)).registry.list()

    async def has_tool(self, server: str, tool: str) -> bool:
        try:
            return (await self._connection(server)).registry.get(server, tool) is not None
        except OrchestratorError:
            return False

    async def call(self, server: str, tool: str, arguments: dict[str, Any], *, timeout_seconds: float, max_output_bytes: int) -> ToolOutput:
        """Runs ONE call. Never retried: after a timeout or a crash the outcome is unknown and is reported as a failure."""
        connection = await self._connection(server)
        if connection.registry.get(server, tool) is None:
            raise ToolExecutionError("TOOL_NOT_FOUND", "The tool server does not expose this tool.")
        async with self._semaphore:
            monitor = connection.monitor
            requests_before = monitor.begin_call() if monitor is not None else 0
            try:
                async with asyncio.timeout(timeout_seconds):
                    assert connection.client is not None
                    result = await connection.client.call_tool(tool, arguments)
            except Exception as exc:  # noqa: BLE001 - every failure is classified below by what could have happened
                # The server may still be working or broken: drop the connection (and its child process) instead of reusing it.
                await self._discard(server, connection)
                raise self._classify_failure(connection, requests_before, exc) from exc
            finally:
                if monitor is not None:
                    monitor.end_call()
        output = normalize_result(result, max_output_bytes)
        if output.is_error:
            # The tool's own error text is untrusted tool OUTPUT: it is not forwarded (it would be stored by the Core API as the call's
            # error message, i.e. outside the encrypted result), and the model is only told the code anyway.
            raise ToolExecutionError("TOOL_ERROR", "The tool reported an error.")
        return output

    @staticmethod
    def _classify_failure(connection: _ServerConnection, requests_before: int, exc: BaseException) -> ToolExecutionError:
        """What could have happened to the external action, from the evidence the transport recorded.

        * the transport enforced an output limit (the request was sent)  -> unknown outcome;
        * NO request reached the server's stdin during this call          -> provably nothing was executed: a plain failure;
        * the request may have been sent and the server answered with a protocol-level REJECTION (parse error, invalid request,
          method not found, invalid params: refused before any tool code runs)  -> known failure, not executed;
        * the request may have been sent and no trustworthy answer came back (timeout, connection lost, crash, internal error,
          unreadable answer) -> UNKNOWN outcome: never retried, never replayed.
        """
        monitor = connection.monitor
        if monitor is not None and monitor.violation is not None:
            return ToolOutputLimitError(monitor.violation.kind)
        dispatched = monitor is not None and monitor.stdin_started > requests_before
        if isinstance(exc, TimeoutError):
            if not dispatched:
                return ToolExecutionError("TOOL_TIMEOUT", "The tool did not finish in time.")
            return ToolExecutionError(
                "TOOL_TIMEOUT", "The tool did not finish in time: whether the action took effect is unknown. It is not repeated.",
                outcome_unknown=True,
            )
        if not dispatched:
            return ToolExecutionError("MCP_UNAVAILABLE", "The tool server failed before the call could be sent.")
        if isinstance(exc, MCPError) and exc.code in _NOT_EXECUTED:
            return ToolExecutionError("MCP_UNAVAILABLE", "The tool server rejected the request.")
        return ToolExecutionError(
            "MCP_UNAVAILABLE",
            "The tool server failed during the call: whether the action took effect is unknown. It is not repeated.",
            outcome_unknown=True,
        )

    async def _discard(self, server: str, connection: _ServerConnection) -> None:
        async with self._lock:
            if self._connections.get(server) is connection:
                del self._connections[server]
        await connection.stop()

    async def aclose(self) -> None:
        async with self._lock:
            connections, self._connections = list(self._connections.values()), {}
        for connection in connections:
            await connection.stop()
