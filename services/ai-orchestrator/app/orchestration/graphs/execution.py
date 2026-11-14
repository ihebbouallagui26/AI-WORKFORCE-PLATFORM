"""The first real execution graph: one supervised, text-generation-only run of one agent.

Stages: validate → start (Core API: PENDING→RUNNING) → load context → build prompt → invoke model
(credential resolved inside this node only) → report (Core API: SUCCEEDED) → finalize, with a single
failure path that reports a safe error code to the Core API.

Secret and content hygiene
--------------------------
* The provider credential **never enters graph state**. It is obtained by an execution-scoped
  ``CredentialResolver`` *inside* ``invoke_model`` (a single node, so there is no hand-over between
  nodes), lives in local variables of the provider call and is dropped when it returns.
* The instruction and the result are run-local data (``RunContext``), not checkpointed: the
  checkpoint holds identifiers, stage names, token counts and an error *code* only.
* Tool grants are ignored: execution is text generation. Nothing here runs a tool, a shell, a file
  access, a browser or an MCP call.
"""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from typing import Any
from uuid import UUID

from langgraph.checkpoint.base import BaseCheckpointSaver
from langgraph.graph import END, START, StateGraph

from app.clients.contracts import AgentExecutionContext, ProviderBindingRef
from app.clients.core_api_client import (
    CoreApiAuthenticationError,
    CoreApiClient,
    CoreApiConflict,
    CoreApiForbidden,
    CoreApiNotFound,
    CoreApiUnavailable,
)
from app.core.errors import OrchestratorError
from app.core.logging import get_logger
from app.orchestration.state.models import ExecutionGraphState, assert_state_safe
from app.providers.base import (
    ChatMessage,
    CredentialResolver,
    GenerationRequest,
    ProviderAdapter,
    ProviderAuthenticationError,
    ProviderBadRequestError,
    ProviderRateLimitError,
    ProviderResponseError,
    ProviderScope,
    ProviderTimeoutError,
    ProviderUnavailableError,
)

log = get_logger("execution-graph")

# Safe, user-facing messages by error code. Provider or Core API bodies are never copied into them.
FAILURES: dict[str, str] = {
    "INVALID_REQUEST": "The execution request is invalid.",
    "AGENT_NOT_ELIGIBLE": "The agent is not eligible for execution.",
    "PROVIDER_NOT_ENABLED": "This provider is not enabled for agent execution yet.",
    "PROVIDER_NOT_CONFIGURED": "The agent has no usable primary provider.",
    "CREDENTIAL_UNAVAILABLE": "The provider credential is unavailable or not authorized for this execution.",
    "PROVIDER_AUTHENTICATION": "The provider rejected the credential. Check the provider connection.",
    "PROVIDER_RATE_LIMIT": "The provider is rate limiting requests. Try again later.",
    "PROVIDER_TIMEOUT": "The provider did not answer in time.",
    "PROVIDER_UNAVAILABLE": "The provider is unavailable. Try again later.",
    "PROVIDER_BAD_REQUEST": "The provider rejected the request (check the model configured for the agent).",
    "PROVIDER_INVALID_RESPONSE": "The provider returned an unexpected response.",
    "CORE_API_UNAVAILABLE": "The business backend is unavailable.",
    "CORE_API_REJECTED": "The business backend refused the operation.",
    "EXECUTION_TIMEOUT": "The execution did not finish in time.",
    "EXECUTION_FAILED": "The execution failed.",
}


class ExecutionFailure(Exception):  # noqa: N818 - control-flow signal carrying a safe code
    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code if code in FAILURES else "EXECUTION_FAILED"


def classify(exc: BaseException) -> str:
    """Maps any error to a safe code. Only the *type* is considered, never the message."""
    if isinstance(exc, ExecutionFailure):
        return exc.code
    if isinstance(exc, ProviderAuthenticationError):
        return "PROVIDER_AUTHENTICATION"
    if isinstance(exc, ProviderRateLimitError):
        return "PROVIDER_RATE_LIMIT"
    if isinstance(exc, ProviderTimeoutError):
        return "PROVIDER_TIMEOUT"
    if isinstance(exc, ProviderUnavailableError):
        return "PROVIDER_UNAVAILABLE"
    if isinstance(exc, ProviderBadRequestError):
        return "PROVIDER_BAD_REQUEST"
    if isinstance(exc, ProviderResponseError):
        return "PROVIDER_INVALID_RESPONSE"
    if isinstance(exc, CoreApiUnavailable):
        return "CORE_API_UNAVAILABLE"
    if isinstance(exc, CoreApiConflict | CoreApiForbidden | CoreApiNotFound | CoreApiAuthenticationError):
        return "CORE_API_REJECTED"
    if isinstance(exc, OrchestratorError):
        return "EXECUTION_FAILED"
    return "EXECUTION_FAILED"


@dataclass
class RunContext:
    """Everything that must NOT be checkpointed: the instruction, the prompt and the result."""

    organization_id: UUID
    execution_id: UUID
    agent_id: UUID | None = None
    task: str = ""
    context: AgentExecutionContext | None = None
    binding: ProviderBindingRef | None = None
    messages: list[ChatMessage] = field(default_factory=list)
    result: str = ""


class ExecutionCredentialResolver:
    """Execution-scoped ``CredentialResolver``: asks the Core API for the single-use credential of THIS
    execution. It stores nothing, so the secret exists only while the provider call that asked for it runs."""

    def __init__(self, core: CoreApiClient, organization_id: UUID, execution_id: UUID) -> None:
        self._core = core
        self._organization_id = organization_id
        self._execution_id = execution_id

    async def resolve(self, scope: ProviderScope):  # noqa: ANN201 - returns SecretStr
        if scope.organization_id != self._organization_id:
            raise ExecutionFailure("CREDENTIAL_UNAVAILABLE")
        try:
            grant = await self._core.issue_credential(self._organization_id, self._execution_id)
        except OrchestratorError as exc:
            refused = isinstance(exc, CoreApiConflict | CoreApiForbidden | CoreApiNotFound)
            raise ExecutionFailure("CREDENTIAL_UNAVAILABLE" if refused else classify(exc)) from exc
        if grant.connection_id != scope.connection_id:
            raise ExecutionFailure("CREDENTIAL_UNAVAILABLE")
        return grant.credential


AdapterFactory = Callable[[str, CredentialResolver], ProviderAdapter]


def build_prompt(context: AgentExecutionContext, task: str) -> list[ChatMessage]:
    """System prompt from the agent's identity and configuration, then the user's instruction."""
    lines = [f"You are {context.name} (@{context.handle}), an AI employee of this organization."]
    if context.system_instructions.strip():
        lines += ["", "Your instructions:", context.system_instructions.strip()]
    if context.skills:
        lines += ["", "Your active skills: " + ", ".join(context.skills) + "."]
    lines += [
        "",
        "You answer with written text only. You cannot use tools, run code, browse the web, read files or take any "
        "action outside this conversation. If the task needs that, say so and do what you can in text.",
    ]
    return [ChatMessage(role="system", content="\n".join(lines)), ChatMessage(role="user", content=task)]


def pick_binding(context: AgentExecutionContext, enabled: tuple[str, ...]) -> ProviderBindingRef:
    """The PRIMARY binding, provided its provider is enabled for execution. No fallback between providers."""
    primary = next((b for b in context.providers if b.role == "PRIMARY"), None)
    if primary is None:
        raise ExecutionFailure("PROVIDER_NOT_CONFIGURED")
    if primary.provider_type not in enabled:
        raise ExecutionFailure("PROVIDER_NOT_ENABLED")
    return primary


def build_execution_graph(
    checkpointer: BaseCheckpointSaver[Any],
    run: RunContext,
    *,
    core: CoreApiClient,
    adapter_factory: AdapterFactory,
    enabled_providers: tuple[str, ...],
    max_output_tokens: int,
) -> Any:
    """Compiled graph for ONE run. ``run`` is captured by the nodes; the state stays secret-free."""

    def guarded(stage: str, node: Callable[[ExecutionGraphState], Awaitable[dict[str, Any]]]):
        async def wrapper(state: ExecutionGraphState) -> dict[str, Any]:
            if state.get("error_code"):
                return {}
            try:
                update = await node(state)
            except Exception as exc:  # noqa: BLE001 - every failure becomes a safe code
                code = classify(exc)
                log.warning("execution_stage_failed", stage=stage, code=code, error=type(exc).__name__)
                return {"error_code": code, "stage": stage, "steps": [f"{stage}:failed"]}
            assert_state_safe(update)
            return update

        return wrapper

    async def validate(state: ExecutionGraphState) -> dict[str, Any]:
        if state.get("organization_id") != str(run.organization_id) or state.get("execution_id") != str(run.execution_id):
            raise ExecutionFailure("INVALID_REQUEST")
        return {"stage": "validate", "status": "running", "steps": ["validate"]}

    async def start(state: ExecutionGraphState) -> dict[str, Any]:
        started = await core.start_execution(run.organization_id, run.execution_id)
        run.agent_id, run.task = started.agent_id, started.task
        return {"stage": "start", "agent_id": str(started.agent_id), "steps": ["start"]}

    async def load_context(state: ExecutionGraphState) -> dict[str, Any]:
        assert run.agent_id is not None
        context = await core.get_execution_context(run.organization_id, run.agent_id)
        if context.status != "ACTIVE":
            raise ExecutionFailure("AGENT_NOT_ELIGIBLE")
        run.context = context
        run.binding = pick_binding(context, enabled_providers)
        return {
            "stage": "load_context", "provider": run.binding.provider_type, "model": run.binding.model_id,
            "steps": ["load_context"],
        }

    async def prompt(state: ExecutionGraphState) -> dict[str, Any]:
        assert run.context is not None
        run.messages = build_prompt(run.context, run.task)
        return {"stage": "build_prompt", "steps": ["build_prompt"]}

    async def invoke(state: ExecutionGraphState) -> dict[str, Any]:
        assert run.binding is not None
        resolver = ExecutionCredentialResolver(core, run.organization_id, run.execution_id)
        adapter = adapter_factory(run.binding.provider_type, resolver)
        request = GenerationRequest(model=run.binding.model_id, messages=run.messages, max_output_tokens=max_output_tokens)
        # The adapter asks `resolver` for the credential inside this call only; nothing is kept here.
        response = await adapter.generate(ProviderScope(run.organization_id, run.binding.connection_id), request)
        run.result = response.text
        return {
            "stage": "invoke_model", "model": response.model,
            "usage_input": response.usage.input_tokens, "usage_output": response.usage.output_tokens,
            "steps": ["invoke_model"],
        }

    async def report(state: ExecutionGraphState) -> dict[str, Any]:
        await core.complete_execution(
            run.organization_id, run.execution_id, result=run.result,
            input_tokens=state.get("usage_input"), output_tokens=state.get("usage_output"), model=state.get("model"),
        )
        return {"stage": "report", "steps": ["report"]}

    async def finalize(state: ExecutionGraphState) -> dict[str, Any]:
        return {"status": "failed" if state.get("error_code") else "succeeded", "steps": ["finalize"]}

    async def report_failure(state: ExecutionGraphState) -> dict[str, Any]:
        code = state.get("error_code") or "EXECUTION_FAILED"
        try:
            await core.fail_execution(run.organization_id, run.execution_id, error_code=code, message=FAILURES[code])
        except OrchestratorError as exc:
            # Nothing more can be done here: the Core API's reaper fails an execution nobody finished.
            log.warning("failure_report_failed", code=code, error=type(exc).__name__)
        return {"status": "failed", "steps": ["report_failure"]}

    graph = StateGraph(ExecutionGraphState)
    stages: list[tuple[str, Callable[[ExecutionGraphState], Awaitable[dict[str, Any]]]]] = [
        ("validate", validate), ("start", start), ("load_context", load_context),
        ("build_prompt", prompt), ("invoke_model", invoke), ("report", report),
    ]
    for name, fn in stages:
        graph.add_node(name, guarded(name, fn))
    graph.add_node("report_failure", report_failure)
    graph.add_node("finalize", finalize)

    def route(next_stage: str) -> Callable[[ExecutionGraphState], str]:
        return lambda state: "report_failure" if state.get("error_code") else next_stage

    graph.add_edge(START, "validate")
    names = [n for n, _ in stages]
    for current, following in zip(names, [*names[1:], "finalize"], strict=True):
        graph.add_conditional_edges(current, route(following), {following: following, "report_failure": "report_failure"})
    graph.add_edge("report_failure", END)
    graph.add_edge("finalize", END)
    return graph.compile(checkpointer=checkpointer)
