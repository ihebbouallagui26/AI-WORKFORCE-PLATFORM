"""The model/tool interaction loop of one run segment (C2).

A segment is bounded in rounds and tool calls and ends in one of two ways: the model produces a final
answer, or a tool call needs a human approval and the run is PAUSED. A paused run keeps only what it
needs to continue (the conversation and the position inside the pending tool calls), sealed with
AES-256-GCM before it reaches the checkpoint; the provider credential is never part of it.

Per tool call the order is fixed and every step can only narrow what happens:
  validate arguments -> Core API decision (denied | automatic | human approval) -> [pause] ->
  Core API ``begin`` (revalidated now, the single moment a call may start) -> MCP call -> Core API ``complete``.
A tool call is executed at most once: nothing here retries a call whose outcome is unknown.
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
from uuid import UUID

from pydantic import BaseModel, Field

from app.clients.contracts import PlatformAction, ResumeInfo
from app.clients.core_api_client import (
    CoreApiClient,
    CoreApiConflict,
    CoreApiForbidden,
    CoreApiNotFound,
    CoreApiUnavailable,
)
from app.core.errors import OrchestratorError
from app.core.logging import get_logger
from app.mcp.executor import McpServerManager, ToolExecutionError
from app.orchestration.tools import OfferedTool, clean_tool_name, untrusted, validate_arguments
from app.providers.base import (
    ProviderAdapter,
    ProviderScope,
    ToolResultBlock,
    ToolSpec,
    ToolUseBlock,
    TurnMessage,
    TurnRequest,
)

log = get_logger("tool-loop")


class PendingTools(BaseModel):
    """The tool calls of one assistant turn and how far they were handled."""

    uses: list[ToolUseBlock]
    results: list[ToolResultBlock] = Field(default_factory=list)
    index: int = 0
    reason: str = ""
    awaiting_use_id: str | None = None
    awaiting_invocation: UUID | None = None


class LoopState(BaseModel):
    """Everything needed to continue the run later. Business content only: no credential, no token."""

    system: str
    messages: list[TurnMessage]
    round: int = 0
    tool_calls: int = 0
    usage_input: int = 0
    usage_output: int = 0
    model: str = ""
    pending: PendingTools | None = None


@dataclass(frozen=True)
class Completed:
    text: str


@dataclass(frozen=True)
class Paused:
    invocation_id: UUID
    approval_id: UUID | None


def platform_alias(action_name: str) -> str:
    """Provider tool names cannot contain a dot: ``workflow.delegate`` is shown as ``workflow_delegate``."""
    return action_name.replace(".", "_")


@dataclass(frozen=True)
class ActionTaken:
    """The Core API accepted the coordinator control action: the turn is over, nothing else may happen in it."""

    action: str
    message: str


class LoopFailure(OrchestratorError):
    """A safe, coded failure of the loop (round limit, ...)."""

    status = 502

    def __init__(self, error_code: str) -> None:
        super().__init__(error_code)
        self.error_code = error_code


@dataclass
class LoopDeps:
    core: CoreApiClient
    adapter: ProviderAdapter
    scope: ProviderScope
    organization_id: UUID
    execution_id: UUID
    model: str
    max_output_tokens: int
    offered: dict[str, OfferedTool]  # alias -> tool
    manager: McpServerManager | None
    max_rounds: int
    max_tool_calls: int
    output_max_chars: int
    # C3.2: a coordinator turn offers ONLY these platform actions (alias -> action) and never an MCP tool.
    platform: dict[str, PlatformAction] = field(default_factory=dict)
    # C3.4: how many delegations one coordinator turn may make (the Core API enforces it too).
    max_delegations: int = 1

    @property
    def coordinator(self) -> bool:
        return bool(self.platform)

    def specs(self) -> list[ToolSpec]:
        if self.coordinator:
            return [
                ToolSpec(name=alias, description=action.description[:1000], input_schema=action.input_schema)
                for alias, action in self.platform.items()
            ]
        return [
            ToolSpec(
                name=t.alias,
                description=(t.permission.description or t.permission.name)[:1000],
                input_schema=t.permission.input_schema,
            )
            for t in self.offered.values()
        ]


def _error_block(use: ToolUseBlock, message: str) -> ToolResultBlock:
    return ToolResultBlock(tool_use_id=use.id, content=untrusted(message), is_error=True)


async def run_loop(deps: LoopDeps, state: LoopState, resume: ResumeInfo | None = None) -> Completed | Paused:
    """Runs until a final answer or a pause. ``resume`` carries the human decision of the pending call, if any."""
    while True:
        if state.pending is None:
            if state.round >= deps.max_rounds:
                raise LoopFailure("TOOL_ROUND_LIMIT")
            turn = await deps.adapter.generate_turn(
                deps.scope,
                TurnRequest(
                    model=deps.model, system=state.system, messages=state.messages, tools=deps.specs(),
                    max_output_tokens=deps.max_output_tokens,
                ),
            )
            state.round += 1
            state.usage_input += turn.usage.input_tokens
            state.usage_output += turn.usage.output_tokens
            state.model = turn.model or state.model
            uses = turn.tool_uses
            if not uses:
                return Completed(text=turn.text)
            state.messages.append(TurnMessage(role="assistant", content=list(turn.blocks)))
            state.pending = PendingTools(uses=uses, reason=turn.text[:500])

        pending = state.pending
        if deps.coordinator:
            done = await _coordinator_turn(deps, pending)
            if done is not None:
                return done
            pending.index = len(pending.uses)  # every call was answered with an error block: the model may choose again
        while pending.index < len(pending.uses):
            use = pending.uses[pending.index]
            handled = await _handle_use(deps, state, pending, use, resume)
            resume = None  # a decision is consumed by the call it belongs to
            if isinstance(handled, Paused):
                return handled
            pending.results.append(handled)
            pending.index += 1
            pending.awaiting_use_id = None
            pending.awaiting_invocation = None
        state.messages.append(TurnMessage(role="user", content=list(pending.results)))
        state.pending = None


FINISH_ACTION = "workflow.finish"


async def _coordinator_turn(deps: LoopDeps, pending: PendingTools) -> Completed | None:
    """Handles ALL the calls of one coordinator response: up to ``max_delegations`` delegations (independent tasks that will run in
    parallel), or one finish, never both. Returns the end of the turn once the Core API accepted something, else None (every call
    got an error block and the model may choose again)."""
    delegations = 0
    for use in pending.uses:
        taken = await _handle_platform_action(deps, use, delegations)
        if isinstance(taken, ActionTaken):
            if taken.action == FINISH_ACTION:
                return Completed(text=taken.message)
            delegations += 1
            continue
        pending.results.append(taken)
    if delegations:
        return Completed(text="workflow.delegate accepted." if delegations == 1 else f"{delegations} workflow.delegate actions accepted.")
    return None


async def _handle_platform_action(deps: LoopDeps, use: ToolUseBlock, delegations: int) -> ToolResultBlock | ActionTaken:
    """Forwards ONE coordinator action to the Core API and reports its decision back to the model.

    The orchestrator interprets nothing: it checks the name and the argument shape, forwards, and relays the answer. What it refuses
    locally (without a request) is only what can never be valid: an unknown action, too many delegations, a finish after a delegation."""
    action = deps.platform.get(use.name)
    if action is None:
        return _error_block(use, "Unknown action. Only the provided workflow actions exist.")  # never reaches a tool
    if action.name == FINISH_ACTION and delegations > 0:
        return _error_block(use, "A finish cannot be combined with delegations in the same turn.")
    if action.name != FINISH_ACTION and delegations >= deps.max_delegations:
        if deps.max_delegations == 1:
            return _error_block(use, "Only one action per turn is allowed.")
        return _error_block(use, f"At most {deps.max_delegations} delegations per turn.")
    problem = validate_arguments(action.input_schema, use.input)
    if problem is not None:
        return _error_block(use, problem)
    try:
        result = await deps.core.submit_workflow_action(
            deps.organization_id, deps.execution_id, tool_call_id=use.id, action=action.name, arguments=use.input
        )
    except CoreApiUnavailable as exc:
        raise LoopFailure("COORDINATION_UNAVAILABLE") from exc
    except (CoreApiConflict, CoreApiForbidden, CoreApiNotFound) as exc:
        raise LoopFailure("COORDINATION_REJECTED") from exc
    if result.status == "ACCEPTED":
        return ActionTaken(action=action.name, message=f"{action.name} accepted.")
    reason = f"The action was rejected ({result.code or 'REJECTED'}): {result.message}"
    return _error_block(use, reason[:300])


async def _handle_use(
    deps: LoopDeps, state: LoopState, pending: PendingTools, use: ToolUseBlock, resume: ResumeInfo | None
) -> ToolResultBlock | Paused:
    tool = deps.offered.get(use.name)
    awaiting = pending.awaiting_use_id == use.id and pending.awaiting_invocation is not None
    if tool is not None:
        problem = validate_arguments(tool.permission.input_schema, use.input)
        if problem is not None:
            return _error_block(use, problem)  # never reaches the Core API or a tool
    if not awaiting and state.tool_calls >= deps.max_tool_calls:
        return _error_block(use, "Tool call limit reached for this run. Answer with what you have.")

    if awaiting:
        # This very call paused for a human: the decision is the only thing that can move it.
        if resume is None or resume.invocation_id != pending.awaiting_invocation:
            raise LoopFailure("RESUME_MISMATCH")
        if resume.decision == "REJECTED":
            note = f" Reason given: {resume.rejection_reason}" if resume.rejection_reason else ""
            return _error_block(use, "A person rejected this action. It was not executed." + note)
        invocation_id = resume.invocation_id
    else:
        state.tool_calls += 1
        code = tool.permission.tool_code if tool is not None else clean_tool_name(use.name)
        decision = await deps.core.request_tool_call(
            deps.organization_id, deps.execution_id, tool_call_id=use.id, tool_code=code, arguments=use.input,
            reason=pending.reason or None,
        )
        if decision.decision == "DENIED":
            return _error_block(use, f"Tool call denied ({decision.code or 'DENIED'}). This action is not available to you.")
        if decision.decision == "APPROVAL_REQUIRED":
            if decision.invocation_id is None:
                return _error_block(use, "Tool call denied.")
            pending.awaiting_use_id = use.id
            pending.awaiting_invocation = decision.invocation_id
            return Paused(invocation_id=decision.invocation_id, approval_id=decision.approval_id)
        if decision.invocation_id is None:
            return _error_block(use, "Tool call denied.")
        invocation_id = decision.invocation_id
    return await _execute(deps, invocation_id, use)


# Bounded attempts to tell the Core API that a call's outcome is unknown (it may be briefly unreachable). Reporting is idempotent: a
# repeated report after one that did arrive gets a 409, which means "already decided".
UNKNOWN_REPORT_ATTEMPTS = 4
UNKNOWN_REPORT_PAUSE_SECONDS = 1.0


async def _report_unknown_outcome(deps: LoopDeps, invocation_id: UUID, exc: ToolExecutionError) -> None:
    for attempt in range(UNKNOWN_REPORT_ATTEMPTS):
        try:
            await deps.core.complete_tool_call(
                deps.organization_id, deps.execution_id, invocation_id, success=False, output="", truncated=False,
                error_code="OUTCOME_UNKNOWN", message=exc.message,
            )
            return
        except (CoreApiConflict, CoreApiForbidden, CoreApiNotFound):
            return  # the Core API already decided (call closed, execution ended): it stays the source of truth for the outcome
        except CoreApiUnavailable:
            if attempt == UNKNOWN_REPORT_ATTEMPTS - 1:
                # Nothing is lost: the call stays RUNNING in the Core API's own database and its recovery closes it as OUTCOME_UNKNOWN
                # when the execution ends. The caller fails the execution (CORE_API_UNAVAILABLE) and the tool is NOT executed again.
                raise
            await asyncio.sleep(UNKNOWN_REPORT_PAUSE_SECONDS * (attempt + 1))


async def _execute(deps: LoopDeps, invocation_id: UUID, use: ToolUseBlock) -> ToolResultBlock:
    try:
        start = await deps.core.begin_tool_call(deps.organization_id, deps.execution_id, invocation_id)
    except (CoreApiConflict, CoreApiForbidden, CoreApiNotFound):
        return _error_block(use, "This action is no longer authorized and was not executed.")
    try:
        output = await deps.manager.call(
            start.mcp_server, start.mcp_tool, start.arguments,
            timeout_seconds=float(start.timeout_seconds), max_output_bytes=start.max_output_bytes,
        )
    except ToolExecutionError as exc:
        if exc.outcome_unknown:
            # The request may have reached the tool server and no trustworthy result came back (timeout, lost connection, crash, output
            # limit): the action may have taken effect. Tell the Core API truthfully (it keeps it as an unknown outcome, never retried),
            # then END the execution: the model is not given the chance to repeat a possibly completed action.
            await _report_unknown_outcome(deps, invocation_id, exc)
            raise LoopFailure("TOOL_FAILED") from exc
        await deps.core.complete_tool_call(
            deps.organization_id, deps.execution_id, invocation_id, success=False, output="", truncated=False,
            error_code=exc.error_code, message=exc.message,
        )
        return _error_block(use, f"The tool failed ({exc.error_code}).")
    text = output.text[: deps.output_max_chars]
    await deps.core.complete_tool_call(
        deps.organization_id, deps.execution_id, invocation_id, success=True, output=text,
        truncated=output.truncated or len(output.text) > len(text), error_code=None, message=None,
    )
    return ToolResultBlock(tool_use_id=use.id, content=untrusted(text), is_error=False)


def initial_state(system: str, task: str, model: str) -> LoopState:
    return LoopState(system=system, messages=[TurnMessage(role="user", content=task)], model=model)


def dump(state: LoopState) -> bytes:
    return state.model_dump_json().encode()


def load(raw: bytes) -> LoopState:
    return LoopState.model_validate_json(raw)
