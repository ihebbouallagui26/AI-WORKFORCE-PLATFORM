"""Harmless local MCP server used as the reference integration (stdio).

It exposes three pure functions and nothing else: no shell, no file system, no network, no persistent
state. It is the only server the platform can start.

* ``echo`` and ``add`` are read-only reference tools (automatic execution).
* ``record_note`` changes nothing outside the call (it returns a receipt) but is registered as a WRITE
  tool that needs a human approval, so the complete approval workflow can be exercised end to end
  without any dangerous capability.
"""

import uuid

from mcp.server.mcpserver import MCPServer

server = MCPServer("diagnostic", instructions="Pure diagnostic tools for protocol and approval checks.")


@server.tool()
def echo(text: str) -> str:
    """Returns the given text unchanged."""
    return text[:1000]


@server.tool()
def add(a: int, b: int) -> int:
    """Adds two integers."""
    return a + b


@server.tool()
def record_note(text: str) -> dict[str, object]:
    """Records a short note and returns a receipt (reference tool that requires human approval)."""
    note = text[:500]
    return {"recorded": True, "receipt": str(uuid.uuid4()), "length": len(note)}


if __name__ == "__main__":
    server.run("stdio")
