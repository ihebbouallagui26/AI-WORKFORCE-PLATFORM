"""MCP server configuration model.

C0 deliberately has no way to configure an arbitrary command: a server is selected by *id* from a
closed allowlist of launch specifications that live in code. The only entry is the harmless local
diagnostic server (two pure functions). Remote and third-party servers, and the authorization
workflow around them, belong to C2.
"""

from __future__ import annotations

import sys
from typing import Literal

from mcp import StdioServerParameters
from pydantic import BaseModel, ConfigDict, Field

ServerId = Literal["diagnostic"]


class McpServerConfig(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid")

    name: str = Field(pattern=r"^[a-z][a-z0-9-]{0,39}$")
    server_id: ServerId
    timeout_seconds: float = Field(default=15.0, gt=0, le=300)


def launch_parameters(config: McpServerConfig) -> StdioServerParameters:
    """The fixed launch specification of an allowlisted server. Nothing here comes from user input."""
    if config.server_id == "diagnostic":
        # No inherited secrets: the child receives only the SDK's minimal safe environment.
        return StdioServerParameters(command=sys.executable, args=["-m", "app.mcp.diagnostic_server"])
    raise ValueError("unknown MCP server")  # unreachable: ServerId is a closed Literal
