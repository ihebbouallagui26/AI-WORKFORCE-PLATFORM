"""Controlled local MCP server used ONLY by the integration tests (stdio, real SDK).

Usage: ``python mcp_test_server.py <mode> <pid-file>``. It records its PID in ``<pid-file>`` so a test can prove the
process is really gone. Modes: ``normal`` | ``crash-before-handshake`` | ``hang-before-handshake``.
Tools are deliberately hostile so the production manager's limits can be exercised; none touches files or network.
"""

import asyncio
import os
import sys

mode = sys.argv[1] if len(sys.argv) > 1 else "normal"
if len(sys.argv) > 2:
    with open(sys.argv[2], "w", encoding="utf-8") as handle:
        handle.write(str(os.getpid()))

if mode == "crash-before-handshake":
    os._exit(3)
if mode == "hang-before-handshake":
    import time

    time.sleep(3600)

from mcp.server.mcpserver import MCPServer  # noqa: E402

server = MCPServer("test-server")


@server.tool()
def echo(text: str) -> str:
    """Returns the text."""
    return text


@server.tool()
def pid() -> int:
    """Returns the server process id."""
    return os.getpid()


@server.tool()
async def hang() -> str:
    """Never returns in time."""
    await asyncio.sleep(3600)
    return "late"


@server.tool()
def crash() -> str:
    """Kills the server process in the middle of the call."""
    os._exit(7)


@server.tool()
def big(size: int) -> str:
    """Returns ``size`` characters."""
    return "A" * size


@server.tool()
def fail() -> str:
    """Reports a tool error carrying a secret-looking string."""
    raise ValueError("boom sk-ant-api03-SHOULD-NOT-LEAK-0123456789")


@server.tool()
def visible_secrets() -> str:
    """Names of the environment variables of this process that look like orchestrator secrets."""
    return ",".join(sorted(k for k in os.environ if k.startswith("AIO_") or "SECRET" in k.upper() or "KEY" in k.upper() or "TOKEN" in k.upper()))


@server.tool()
def run_shell(command: str) -> str:
    """A forbidden capability the registry must refuse to register."""
    return command


if __name__ == "__main__":
    server.run("stdio")
