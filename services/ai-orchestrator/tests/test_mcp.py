"""MCP foundation against the harmless local diagnostic server (real stdio protocol handshake)."""

import uuid

import pytest
from pydantic import ValidationError

from app.clients.contracts import ToolPermission
from app.core.errors import RunTimeoutError
from app.mcp import configuration, registry
from app.mcp.configuration import McpServerConfig
from app.mcp.registry import (
    InvalidToolMetadata,
    McpConnectionError,
    ToolNotAuthorized,
    ToolRegistry,
    authorize_tool_call,
    connect,
    validate_tool_metadata,
)
from app.orchestration.state.models import ActorContext, ExecutionContext

CONFIG = McpServerConfig(name="diagnostic", server_id="diagnostic", timeout_seconds=30)
ORG = uuid.uuid4()
CTX = ExecutionContext.create(ORG, uuid.uuid4(), ActorContext("service", "core-api"))


def grant(tool="echo", level="READ", server="diagnostic") -> ToolPermission:
    return ToolPermission(toolCode=f"diag.{tool}", level=level, approvalRequired=False, mcpServer=server, mcpTool=tool)


async def test_protocol_handshake_and_tool_discovery():
    tools = ToolRegistry()
    async with connect(CONFIG, tools) as connection:
        discovered = await connection.discover_tools()
    assert {t.name for t in discovered} == {"echo", "add"}
    assert tools.get("diagnostic", "add") is not None and all(t.input_schema["type"] == "object" for t in discovered)


async def test_an_authorized_call_succeeds():
    async with connect(CONFIG) as connection:
        await connection.discover_tools()
        result = await connection.call_tool(CTX, [grant("add")], ORG, "add", {"a": 2, "b": 3})
    assert result == {"result": 5}


async def test_a_call_without_a_grant_is_rejected_before_reaching_the_server():
    async with connect(CONFIG) as connection:
        await connection.discover_tools()
        with pytest.raises(ToolNotAuthorized):
            await connection.call_tool(CTX, [], ORG, "echo", {"text": "x"})
        with pytest.raises(ToolNotAuthorized):
            await connection.call_tool(CTX, [grant("add")], ORG, "echo", {"text": "x"})  # grant is for another tool


async def test_unregistered_tools_are_rejected_even_with_a_grant():
    async with connect(CONFIG) as connection:  # discovery not performed: nothing registered
        with pytest.raises(ToolNotAuthorized):
            await connection.call_tool(CTX, [grant("echo")], ORG, "echo", {"text": "x"})


def test_authorization_checks_level_server_and_organization():
    ok = authorize_tool_call(CTX, [grant("echo", "WRITE")], organization_id_of_permissions=ORG, server="diagnostic", tool="echo", level="READ")
    assert ok.level == "WRITE"
    with pytest.raises(ToolNotAuthorized):  # level too low
        authorize_tool_call(CTX, [grant("echo", "READ")], organization_id_of_permissions=ORG, server="diagnostic", tool="echo", level="EXECUTE")
    with pytest.raises(ToolNotAuthorized):  # another MCP server
        authorize_tool_call(CTX, [grant("echo", server="other")], organization_id_of_permissions=ORG, server="diagnostic", tool="echo")
    with pytest.raises(ToolNotAuthorized):  # permissions fetched for another organization
        authorize_tool_call(CTX, [grant("echo")], organization_id_of_permissions=uuid.uuid4(), server="diagnostic", tool="echo")


@pytest.mark.parametrize(
    "name,description,schema",
    [
        ("Bad Name", "x", {"type": "object"}),
        ("run_shell", "x", {"type": "object"}),
        ("exec_code", "x", {"type": "object"}),
        ("git_push", "x", {"type": "object"}),
        ("fetch_url", "x", {"type": "object"}),
        ("ok", "x" * 2000, {"type": "object"}),
        ("ok", "uses Bearer abcdefghijklmnop", {"type": "object"}),
        ("ok", "x", {"type": "string"}),
        ("ok", "x", None),
        ("ok", "x", {"type": "object", "properties": {"p": "x" * 20000}}),
    ],
)
def test_invalid_tool_metadata_is_rejected(name, description, schema):
    with pytest.raises(InvalidToolMetadata):
        validate_tool_metadata("diagnostic", name, description, schema)


def test_valid_metadata_is_accepted():
    info = validate_tool_metadata("diagnostic", "docs.search", "Searches docs", {"type": "object"})
    assert info.name == "docs.search"


def test_arbitrary_commands_cannot_be_configured():
    for bad in (
        {"name": "x", "server_id": "bash"},
        {"name": "x", "server_id": "diagnostic", "command": "sh", "args": ["-c", "id"]},
        {"name": "Bad Name", "server_id": "diagnostic"},
    ):
        with pytest.raises(ValidationError):
            McpServerConfig(**bad)


def test_launch_parameters_are_fixed():
    params = configuration.launch_parameters(CONFIG)
    assert params.args == ["-m", "app.mcp.diagnostic_server"]


async def test_a_startup_timeout_is_reported():
    with pytest.raises(RunTimeoutError):
        async with connect(McpServerConfig(name="diagnostic", server_id="diagnostic", timeout_seconds=0.001)):
            pass  # pragma: no cover


async def test_a_missing_server_executable_is_a_connection_error(monkeypatch):
    from mcp import StdioServerParameters

    monkeypatch.setattr(registry, "launch_parameters", lambda _c: StdioServerParameters(command="definitely-not-a-real-binary-xyz"))
    with pytest.raises(McpConnectionError):
        async with connect(CONFIG):
            pass  # pragma: no cover
