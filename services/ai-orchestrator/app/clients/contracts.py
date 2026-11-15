"""Contracts exchanged with the Core API (JSON camelCase on the wire, snake_case in Python).

Only :class:`AgentExecutionContext` is served by the Core API in C0. The reporting and approval
contracts are defined here so that C1 can implement both sides against a reviewed shape; the
Core API owns every business record they will create. No contract carries a credential: provider
secrets are resolved server-side by the Core API at call time, never by this service's state.
"""

from __future__ import annotations

from datetime import datetime
from enum import StrEnum
from typing import Any, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, SecretStr
from pydantic.alias_generators import to_camel


class WireModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, frozen=True, extra="ignore")


ProviderType = Literal["ANTHROPIC", "OPENAI", "GEMINI"]
ToolLevel = Literal["READ", "WRITE", "EXECUTE"]


class ProviderBindingRef(WireModel):
    """Which connection and model an agent may use. The connection id is a reference, not a secret."""

    connection_id: UUID
    provider_type: ProviderType
    model_id: str
    role: Literal["PRIMARY", "FALLBACK"]
    priority: int


ExecutionPolicy = Literal["READ_ONLY", "APPROVAL_REQUIRED", "DENIED"]


class ToolPermission(WireModel):
    """A tool the Core API would let this agent use (explicit grant, enabled, policy not DENIED).

    It is what the orchestrator may OFFER to the model. It is not an authorization: the Core API decides
    again, call by call, and re-validates immediately before every execution."""

    tool_code: str
    level: ToolLevel
    approval_required: bool
    mcp_server: str | None = None
    mcp_tool: str | None = None
    name: str = ""
    description: str = ""
    input_schema: dict[str, Any] = Field(default_factory=lambda: {"type": "object"})
    execution_policy: ExecutionPolicy = "DENIED"
    required_level: ToolLevel = "READ"
    risk: Literal["LOW", "MEDIUM", "HIGH"] = "HIGH"
    timeout_seconds: int = 10
    max_output_bytes: int = 4096
    policy_version: int = 1


class MemorySettings(WireModel):
    enabled: bool
    scope: Literal["PROJECT", "ORGANIZATION"]
    access_mode: str
    retention_days: int | None = None


class AgentExecutionContext(WireModel):
    """The authorized, validated view of one agent for one organization (read-only, no secrets)."""

    organization_id: UUID
    agent_id: UUID
    handle: str
    name: str
    status: Literal["DRAFT", "ACTIVE", "PAUSED", "ARCHIVED"]
    config_version: int
    system_instructions: str = ""
    providers: list[ProviderBindingRef] = Field(default_factory=list)
    tools: list[ToolPermission] = Field(default_factory=list)
    skills: list[str] = Field(default_factory=list)
    memory: MemorySettings | None = None


# ---- Execution run contracts (C1): the orchestrator drives an execution through the Core API ----------


class PlatformAction(WireModel):
    """A platform-native action (C3.2) offered ONLY to a workflow coordinator turn. It is not an MCP tool: the Core
    API defines it, validates it and records it; the orchestrator only forwards what the provider asked for."""

    name: str
    description: str = ""
    input_schema: dict[str, Any] = Field(default_factory=dict)


class WorkflowActionResult(WireModel):
    """The Core API's normalized answer to a forwarded coordinator action."""

    action: str
    status: Literal["ACCEPTED", "REJECTED"] | str
    code: str = ""
    message: str = ""


class ExecutionStarted(WireModel):
    """PENDING to RUNNING answer: the instruction and the agent it was given for."""

    execution_id: UUID
    agent_id: UUID
    task: str
    workflow_role: str | None = None
    platform_actions: list[PlatformAction] = Field(default_factory=list)


class ExecutionCredential(WireModel):
    """A single-use provider credential. It is a ``SecretStr`` and lives only in the call that needs it:
    it is never placed in graph state, logs, errors or checkpoints."""

    connection_id: UUID
    provider_type: ProviderType
    model_id: str
    credential: SecretStr


# ---- Tool calls (C2): the Core API decides, records and audits; the orchestrator executes ---------------


class ToolCallDecision(WireModel):
    """The Core API's decision about a requested call. ``invocation_id`` is null only for an unknown tool."""

    invocation_id: UUID | None = None
    decision: Literal["DENIED", "AUTO", "APPROVAL_REQUIRED", "APPROVED"] | str
    code: str = ""
    message: str = ""
    approval_id: UUID | None = None
    expires_at: datetime | None = None


class ToolCallStart(WireModel):
    """Everything needed to run an authorized call. The arguments here are the ones the Core API stored and
    hashed (what a person approved), never the ones the model may have re-sent."""

    invocation_id: UUID
    tool_code: str
    mcp_server: str
    mcp_tool: str
    arguments: dict[str, Any]
    timeout_seconds: int
    max_output_bytes: int


class ResumeInfo(WireModel):
    """The human decision of a paused execution."""

    invocation_id: UUID
    approval_id: UUID
    decision: Literal["APPROVED", "REJECTED"]
    tool_code: str
    rejection_reason: str | None = None


# ---- Contracts prepared for later phases (no HTTP call exists yet) ---------------------------------------


class ExecutionState(StrEnum):
    QUEUED = "QUEUED"
    RUNNING = "RUNNING"
    WAITING_APPROVAL = "WAITING_APPROVAL"
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"


class ExecutionStatusReport(WireModel):
    organization_id: UUID
    agent_id: UUID
    execution_id: UUID
    state: ExecutionState
    reported_at: datetime
    detail: str = ""


class ExecutionResultSubmission(WireModel):
    organization_id: UUID
    agent_id: UUID
    execution_id: UUID
    summary: str
    usage_input_tokens: int = 0
    usage_output_tokens: int = 0


class ApprovalRequest(WireModel):
    organization_id: UUID
    agent_id: UUID
    execution_id: UUID
    tool_code: str
    level: ToolLevel
    reason: str
