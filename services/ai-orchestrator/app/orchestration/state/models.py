"""Execution context and graph state contracts.

Every execution is identified by an organization id, an agent id and a server-generated execution id.
The thread id used by the checkpointer is derived from them on the server: clients never choose it.

Checkpoint hygiene: state holds identifiers, counters and short status text only. It must never
contain API keys, JWTs, refresh tokens, provider credentials or personal data. ``assert_state_safe``
enforces that on every node update.
"""

from __future__ import annotations

import operator
from dataclasses import dataclass
from typing import Annotated, Any, Literal, TypedDict
from uuid import UUID, uuid4

from app.core.logging import REDACTED, redact


class StateSafetyError(ValueError):
    """A graph update contained something that must never be checkpointed."""


@dataclass(frozen=True)
class ActorContext:
    """Who asked for the execution. ``subject_id`` is an opaque identifier, never an e-mail or a token."""

    kind: Literal["user", "service", "system"]
    subject_id: str


@dataclass(frozen=True)
class ExecutionContext:
    organization_id: UUID
    agent_id: UUID
    execution_id: UUID
    actor: ActorContext
    allowed_capabilities: frozenset[str] = frozenset()

    @property
    def thread_id(self) -> str:
        """Tenant-aware and unguessable (the execution id is a random UUIDv4)."""
        return thread_id_for(self.organization_id, self.execution_id)

    @staticmethod
    def create(
        organization_id: UUID, agent_id: UUID, actor: ActorContext, capabilities: frozenset[str] = frozenset()
    ) -> ExecutionContext:
        return ExecutionContext(organization_id, agent_id, uuid4(), actor, capabilities)


def thread_id_for(organization_id: UUID, execution_id: UUID) -> str:
    return f"{organization_id}:{execution_id}"


class DiagnosticState(TypedDict, total=False):
    organization_id: str
    agent_id: str
    execution_id: str
    status: str
    target: int
    counter: int
    steps: Annotated[list[str], operator.add]
    fail_after: int | None
    result: str


def assert_state_safe(update: dict[str, Any]) -> None:
    """Rejects secret-looking keys and token-looking values before they reach a checkpoint."""
    if redact(update) != update or REDACTED in repr(update):
        raise StateSafetyError("sensitive data cannot be stored in graph state")
