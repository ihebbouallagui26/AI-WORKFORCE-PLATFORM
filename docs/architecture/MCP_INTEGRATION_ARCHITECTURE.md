# MCP integration architecture (Phase C0)

C0 delivers the **protocol foundation** with the official Python MCP SDK (`mcp` 2.3.0, client `mcp.Client`, server `mcp.server.mcpserver.MCPServer`; note that the 2.x API differs from 1.x: `FastMCP` became `MCPServer`). It does **not** expose or execute shell commands, arbitrary file access, git mutations, unrestricted HTTP or any project modification. Real tool execution and the authorization workflow are C2.

## 1. Components (`app/mcp`)

| Module | Role |
| --- | --- |
| `configuration.py` | `McpServerConfig(name, server_id, timeout)` — `extra="forbid"`; `server_id` is a **closed Literal** (`diagnostic`). `launch_parameters` maps an id to a fixed launch specification in code. There is no way to configure a command, arguments or environment. |
| `registry.py` | `connect()` (lifecycle: launch → handshake under a deadline → always shut down), `McpConnection.discover_tools()` / `call_tool()`, `ToolRegistry`, `validate_tool_metadata`, `authorize_tool_call`. |
| `diagnostic_server.py` | The only launchable server: two pure functions (`echo`, `add`). No shell, file, network or state. |

## 2. Tool metadata is untrusted input

`validate_tool_metadata` rejects: names outside `^[a-z][a-z0-9_.-]{0,63}$`; names that look like shell/exec/eval/bash/powershell/cmd/spawn/sudo/write_file/delete/git_/fetch_url/http_ capabilities (refused regardless of grants in C0); descriptions over 1000 characters or containing credential-looking strings; schemas that are not an object schema or exceed 16 KiB. A rejected tool is never registered.

## 3. Authorization (enforced before any call)

A call goes through `authorize_tool_call(context, permissions, …)`:

1. the permissions must have been fetched **for the execution's organization**; otherwise `ToolNotAuthorized`;
2. an **exact** B3 grant must exist for that MCP server **and** tool (`mcp_server`/`mcp_tool` from the registry), at a level ≥ the requested level (`READ < WRITE < EXECUTE`);
3. the tool must have been discovered and registered.

Everything else is `ToolNotAuthorized` (HTTP 403 semantics) and the server is not contacted. In C2 the Core API will validate again (it owns grants and audit); the orchestrator check is defense in depth.

## 4. Lifecycle, timeouts, errors

* `connect` bounds startup/handshake with `asyncio.timeout` (reschedule removed once connected) → `RunTimeoutError`; a missing executable or protocol failure → `McpConnectionError`; discovery/call timeouts → `RunTimeoutError`; protocol errors or tool-reported errors → `McpProtocolError`. Messages are fixed and safe.
* The child process receives the SDK's minimal environment: no inherited secrets.

## 5. Tests (`tests/test_mcp.py`, real stdio handshake)

Handshake and discovery; authorized call (`add` → 5); missing grant, wrong tool, wrong level, wrong server, wrong organization, unregistered tool → rejected; 10 invalid-metadata cases; arbitrary commands impossible to configure; startup timeout; missing executable. On Windows this module runs on the proactor loop (subprocesses), the PostgreSQL tests on the selector loop.

## 6. Deferred to C2

Remote/third-party servers, authenticated MCP transports, per-organization server registration, approval workflows, per-call audit through the Core API, rate limits and output size limits for real tools, sandboxing.
