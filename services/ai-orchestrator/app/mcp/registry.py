"""MCP client abstraction, tool metadata validation and permission-checked tool registry.

Security model (enforced again by the Core API in C2): a tool may be called only if the agent holds
a B3 tool grant for exactly that MCP server and tool, at a sufficient level, and the execution
context carries the same organization as the grant. Tool metadata supplied by a server is untrusted
input: it is validated, bounded and screened before it is registered.
"""

from __future__ import annotations

import asyncio
import re
from collections.abc import AsyncIterator, Sequence
from contextlib import asynccontextmanager
from dataclasses import dataclass
from typing import Any

from mcp import Client, MCPError

from app.clients.contracts import ToolLevel, ToolPermission
from app.core.errors import ForbiddenError, OrchestratorError, RunTimeoutError, UnavailableError
from app.core.logging import redact
from app.mcp.configuration import McpServerConfig, launch_parameters
from app.orchestration.state.models import ExecutionContext

_TOOL_NAME = re.compile(r"^[a-z][a-z0-9_.-]{0,63}$")
# Capabilities C0 refuses to register regardless of grants (shell, arbitrary code, file mutation, git).
_FORBIDDEN_NAME = re.compile(r"(shell|exec|eval|bash|powershell|cmd|spawn|sudo|write_file|delete|rm_|git_|fetch_url|http_)", re.I)
_LEVELS: tuple[ToolLevel, ...] = ("READ", "WRITE", "EXECUTE")
MAX_DESCRIPTION = 1000
MAX_SCHEMA_BYTES = 16_384


class McpConnectionError(UnavailableError):
    code = "mcp-connection"
    default_message = "The MCP server could not be reached."


class McpProtocolError(OrchestratorError):
    status = 502
    code = "mcp-protocol"
    default_message = "The MCP server returned an invalid response."


class ToolNotAuthorized(ForbiddenError):
    code = "tool-not-authorized"
    default_message = "The agent is not authorized to use this tool."


class InvalidToolMetadata(OrchestratorError):
    status = 422
    code = "invalid-tool-metadata"
    default_message = "The tool metadata is not acceptable."


@dataclass(frozen=True)
class ToolInfo:
    server: str
    name: str
    description: str
    input_schema: dict[str, Any]


def validate_tool_metadata(server: str, name: str, description: str | None, schema: object) -> ToolInfo:
    if not _TOOL_NAME.fullmatch(name):
        raise InvalidToolMetadata("Invalid tool name.")
    if _FORBIDDEN_NAME.search(name):
        raise InvalidToolMetadata("This kind of tool is not available.")
    text = description or ""
    if len(text) > MAX_DESCRIPTION:
        raise InvalidToolMetadata("Tool description too long.")
    if redact(text) != text:
        raise InvalidToolMetadata("Tool description must not contain credentials.")
    if not isinstance(schema, dict) or schema.get("type") != "object" or len(repr(schema)) > MAX_SCHEMA_BYTES:
        raise InvalidToolMetadata("Invalid tool input schema.")
    return ToolInfo(server=server, name=name, description=text, input_schema=schema)


def authorize_tool_call(
    context: ExecutionContext,
    permissions: Sequence[ToolPermission],
    *,
    organization_id_of_permissions: object,
    server: str,
    tool: str,
    level: ToolLevel = "READ",
) -> ToolPermission:
    """Raises :class:`ToolNotAuthorized` unless an exact grant allows ``tool`` at ``level``.

    ``organization_id_of_permissions`` is the organization the permissions were fetched for; it must
    equal the execution's organization (a grant can never be reused across tenants).
    """
    if organization_id_of_permissions != context.organization_id:
        raise ToolNotAuthorized
    for permission in permissions:
        if permission.mcp_server == server and permission.mcp_tool == tool:
            if _LEVELS.index(level) <= _LEVELS.index(permission.level):
                return permission
    raise ToolNotAuthorized


class ToolRegistry:
    """Validated tool metadata discovered from connected servers."""

    def __init__(self) -> None:
        self._tools: dict[tuple[str, str], ToolInfo] = {}

    def register(self, tool: ToolInfo) -> None:
        self._tools[(tool.server, tool.name)] = tool

    def get(self, server: str, name: str) -> ToolInfo | None:
        return self._tools.get((server, name))

    def list(self) -> list[ToolInfo]:
        return sorted(self._tools.values(), key=lambda t: (t.server, t.name))


class McpConnection:
    def __init__(self, config: McpServerConfig, client: Client, registry: ToolRegistry) -> None:
        self.config = config
        self._client = client
        self._registry = registry

    async def discover_tools(self) -> list[ToolInfo]:
        """Lists the server's tools, validating each one. An invalid tool is rejected, not registered."""
        try:
            async with asyncio.timeout(self.config.timeout_seconds):
                result = await self._client.list_tools()
        except TimeoutError as exc:
            raise RunTimeoutError("The MCP server did not answer in time.") from exc
        except MCPError as exc:
            raise McpProtocolError from exc
        accepted: list[ToolInfo] = []
        for tool in result.tools:
            info = validate_tool_metadata(self.config.name, tool.name, tool.description, tool.input_schema)
            self._registry.register(info)
            accepted.append(info)
        return accepted

    async def call_tool(
        self,
        context: ExecutionContext,
        permissions: Sequence[ToolPermission],
        permissions_organization_id: object,
        tool: str,
        arguments: dict[str, Any],
        *,
        level: ToolLevel = "READ",
    ) -> object:
        """Authorization first, then the call. Unknown or unregistered tools are rejected too."""
        authorize_tool_call(
            context, permissions, organization_id_of_permissions=permissions_organization_id,
            server=self.config.name, tool=tool, level=level,
        )
        if self._registry.get(self.config.name, tool) is None:
            raise ToolNotAuthorized
        try:
            async with asyncio.timeout(self.config.timeout_seconds):
                result = await self._client.call_tool(tool, arguments)
        except TimeoutError as exc:
            raise RunTimeoutError("The MCP tool did not answer in time.") from exc
        except MCPError as exc:
            raise McpProtocolError from exc
        if result.is_error:
            raise McpProtocolError("The MCP tool reported an error.")
        return result.structured_content if result.structured_content is not None else result.content


@asynccontextmanager
async def connect(config: McpServerConfig, registry: ToolRegistry | None = None) -> AsyncIterator[McpConnection]:
    """Connection lifecycle: launch the allowlisted server, handshake within a deadline, always shut it down."""
    registry = registry or ToolRegistry()
    started = False
    try:
        async with asyncio.timeout(config.timeout_seconds) as deadline:
            async with Client(launch_parameters(config), read_timeout_seconds=config.timeout_seconds) as client:
                deadline.reschedule(None)  # the deadline only covers startup and handshake
                started = True
                yield McpConnection(config, client, registry)
    except TimeoutError as exc:
        if started:
            raise
        raise RunTimeoutError("The MCP server did not start in time.") from exc
    except (OSError, MCPError) as exc:
        if started:
            raise
        raise McpConnectionError from exc
