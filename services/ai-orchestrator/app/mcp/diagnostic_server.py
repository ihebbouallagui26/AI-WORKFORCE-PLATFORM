"""Harmless local MCP server used to verify protocol connectivity (stdio).

It exposes two pure functions and nothing else: no shell, no file system, no network, no state.
It is the only server the C0 registry can launch.
"""

from mcp.server.mcpserver import MCPServer

server = MCPServer("diagnostic", instructions="Pure diagnostic tools for protocol checks.")


@server.tool()
def echo(text: str) -> str:
    """Returns the given text unchanged."""
    return text[:1000]


@server.tool()
def add(a: int, b: int) -> int:
    """Adds two integers."""
    return a + b


if __name__ == "__main__":
    server.run("stdio")
