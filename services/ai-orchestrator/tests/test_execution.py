"""Supervised agent execution: the first real graph, with a mocked Core API and a MOCKED Anthropic.

Everything here proves orchestration logic and secret hygiene. It says nothing about whether the real
Anthropic API accepts these requests: that is only verified by the opt-in live test.
"""

import asyncio
import json
import uuid

import httpx
import pytest
import respx
from langgraph.checkpoint.memory import InMemorySaver
from pydantic import SecretStr

from app.clients.core_api_client import CoreApiClient, ServiceTokenProvider
from app.core.config import Settings
from app.orchestration.executor import DuplicateExecution, ExecutionRunner
from app.orchestration.state.models import thread_id_for
from app.providers.registry import ProviderRegistry

ORG = uuid.uuid4()
AGENT = uuid.uuid4()
EXECUTION = uuid.uuid4()
CONNECTION = uuid.uuid4()
SECRET = "sk-ant-api03-THIS-IS-A-FAKE-KEY-for-tests-0123456789"
TASK = "Draft a two-line status update about the migration."
RESULT = "Migration is on track. Cutover scheduled for Friday."

CORE = "http://core.test"
ANTHROPIC = "https://anthropic.test"
TOKEN_URL = "http://idp.test/token"


def base(org=ORG, execution=EXECUTION, action=""):
    return f"{CORE}/api/v1/internal/organizations/{org}/executions/{execution}/{action}".rstrip("/")


def context_json(**overrides):
    body = {
        "organizationId": str(ORG), "agentId": str(AGENT), "handle": "ada", "name": "Ada Analyst", "status": "ACTIVE",
        "configVersion": 2, "systemInstructions": "Be precise and brief.",
        "providers": [{"connectionId": str(CONNECTION), "providerType": "ANTHROPIC", "modelId": "model-x", "role": "PRIMARY", "priority": 0}],
        "tools": [{"toolCode": "git.access", "level": "EXECUTE", "approvalRequired": True, "mcpServer": "git", "mcpTool": "push"}],
        "skills": ["Code review", "Writing"], "memory": {"enabled": False, "scope": "PROJECT", "accessMode": "AGENT_ONLY"},
    }
    return {**body, **overrides}


class Env:
    """A runner wired to mocked Core API and Anthropic, with an in-memory checkpointer."""

    def __init__(self, settings: Settings | None = None) -> None:
        self.settings = settings or Settings(
            environment="test", anthropic_base_url=ANTHROPIC, provider_max_retries=1, execution_max_output_tokens=300, run_timeout_seconds=5,
        )
        self.saver = InMemorySaver()
        self.sleeps: list[float] = []
        self.http: list[httpx.AsyncClient] = []

    async def build(self) -> ExecutionRunner:
        idp = httpx.AsyncClient()
        core_http = httpx.AsyncClient(base_url=CORE)
        self.http += [idp, core_http]
        tokens = ServiceTokenProvider(idp, TOKEN_URL, "ai-orchestrator", SecretStr("client-secret-value"))

        async def sleep(delay: float) -> None:
            self.sleeps.append(delay)

        self.core = CoreApiClient(core_http, tokens, max_retries=1, backoff_seconds=0.01, sleep=sleep)

        class Unavailable:
            async def resolve(self, scope):
                raise AssertionError("the shared resolver must not be used by executions")

        self.providers = ProviderRegistry(self.settings, Unavailable())
        return ExecutionRunner(self.settings, self.core, self.providers, self.saver)

    async def close(self) -> None:
        await self.providers.aclose()
        for client in self.http:
            await client.aclose()


@pytest.fixture
async def env():
    e = Env()
    yield e
    await e.close()


class CoreMock:
    def __init__(self, mock, *, context=None, credential_status=200, start_status=200, complete_status=200):
        respx.post(TOKEN_URL).respond(json={"access_token": "service-access-token", "expires_in": 300})
        self.start = mock.post(base(action="start")).respond(
            start_status, json={"executionId": str(EXECUTION), "agentId": str(AGENT), "task": TASK}
        )
        self.context = mock.get(f"{CORE}/api/v1/internal/organizations/{ORG}/agents/{AGENT}/execution-context").respond(
            json=context or context_json()
        )
        self.credential = mock.post(base(action="credential")).respond(
            credential_status, json={"connectionId": str(CONNECTION), "providerType": "ANTHROPIC", "modelId": "model-x", "credential": SECRET}
        )
        self.complete = mock.post(base(action="complete")).respond(complete_status, json={})
        self.fail = mock.post(base(action="fail")).respond(json={})

    def posted(self, route) -> dict:
        return json.loads(route.calls.last.request.content)


def anthropic_ok():
    return respx.post(f"{ANTHROPIC}/v1/messages").respond(
        json={"model": "model-x-resolved", "content": [{"type": "text", "text": RESULT}], "usage": {"input_tokens": 41, "output_tokens": 13}, "stop_reason": "end_turn"}
    )


async def run_to_end(runner: ExecutionRunner) -> None:
    runner.submit(ORG, EXECUTION)
    await asyncio.gather(*list(runner._tasks.values()))  # noqa: SLF001


def stored_text(saver: InMemorySaver) -> str:
    """Everything the checkpointer holds, as text (to prove what is NOT in there)."""
    parts = []
    for blobs in saver.blobs.values():
        parts.append(repr(blobs))
    parts.append(repr(saver.storage))
    parts.append(repr(saver.writes))
    return "\n".join(parts)


# ---- the happy path ----------------------------------------------------------------------------------


@respx.mock
async def test_a_supervised_execution_runs_end_to_end_with_a_mocked_provider(env):
    runner = await env.build()
    core = CoreMock(respx.mock)
    provider = anthropic_ok()
    await run_to_end(runner)

    # The provider was called once with the agent identity, instructions, skills and the task.
    request = provider.calls.last.request
    body = json.loads(request.content)
    system, user = body["system"], body["messages"]
    assert "Ada Analyst" in system and "Be precise and brief." in system and "Code review" in system and "Writing" in system
    assert "text only" in system and user == [{"role": "user", "content": TASK}]
    assert body["model"] == "model-x" and body["max_tokens"] == 300          # configured limit, model from the binding
    assert request.headers["x-api-key"] == SECRET
    # Tool grants are NOT interpreted as authorization: nothing about tools reaches the provider.
    assert "tools" not in body and "git" not in json.dumps(body).replace("digit", "")
    # The result, model and usage are reported to the Core API.
    assert core.posted(core.complete) == {"result": RESULT, "inputTokens": 41, "outputTokens": 13, "model": "model-x-resolved"}
    assert core.credential.call_count == 1 and core.fail.call_count == 0
    assert runner.active == 0


@respx.mock
async def test_the_credential_is_requested_once_even_when_the_provider_is_retried(env):
    runner = await env.build()
    core = CoreMock(respx.mock)
    respx.post(f"{ANTHROPIC}/v1/messages").mock(side_effect=[
        httpx.Response(429, headers={"retry-after": "1"}),
        httpx.Response(200, json={"content": [{"type": "text", "text": RESULT}], "usage": {}}),
    ])
    await run_to_end(runner)
    assert core.credential.call_count == 1 and core.complete.call_count == 1


# ---- secret and content isolation ---------------------------------------------------------------------


@respx.mock
async def test_nothing_sensitive_reaches_the_checkpoint(env):
    runner = await env.build()
    CoreMock(respx.mock)
    anthropic_ok()
    await run_to_end(runner)

    saved = stored_text(env.saver)
    assert saved  # the run did checkpoint
    for forbidden in (SECRET, "THIS-IS-A-FAKE-KEY", "service-access-token", "client-secret-value", TASK, RESULT, "Be precise"):
        assert forbidden not in saved
    snapshot = await env.saver.aget_tuple({"configurable": {"thread_id": thread_id_for(ORG, EXECUTION)}})
    values = snapshot.checkpoint["channel_values"]
    assert values["status"] == "succeeded" and values["usage_input"] == 41 and values["steps"][0] == "validate"
    allowed = {"organization_id", "execution_id", "agent_id", "status", "stage", "provider", "model", "usage_input", "usage_output",
               "error_code", "steps"}
    assert all(key in allowed or key.startswith(("branch:", "__")) for key in values), sorted(values)


@respx.mock
async def test_failures_carry_safe_messages_and_no_secret_in_logs(env, capsys):
    runner = await env.build()
    core = CoreMock(respx.mock)
    respx.post(f"{ANTHROPIC}/v1/messages").respond(401, json={"error": {"message": f"invalid x-api-key {SECRET}"}})
    await run_to_end(runner)

    report = core.posted(core.fail)
    assert report["errorCode"] == "PROVIDER_AUTHENTICATION"
    assert SECRET not in json.dumps(report) and "invalid x-api-key" not in json.dumps(report)
    output = capsys.readouterr().out
    assert SECRET not in output and "service-access-token" not in output and "client-secret-value" not in output
    assert core.complete.call_count == 0


# ---- provider failures --------------------------------------------------------------------------------


@respx.mock
@pytest.mark.parametrize(
    "outcome,code",
    [
        (httpx.Response(401), "PROVIDER_AUTHENTICATION"),
        (httpx.Response(429), "PROVIDER_RATE_LIMIT"),
        (httpx.Response(503), "PROVIDER_UNAVAILABLE"),
        (httpx.Response(400), "PROVIDER_BAD_REQUEST"),
        (httpx.Response(200, json={"unexpected": True}), "PROVIDER_INVALID_RESPONSE"),
        (httpx.ReadTimeout("slow"), "PROVIDER_TIMEOUT"),
    ],
)
async def test_provider_errors_become_safe_codes(env, outcome, code):
    runner = await env.build()
    core = CoreMock(respx.mock)
    route = respx.post(f"{ANTHROPIC}/v1/messages")
    route.mock(return_value=outcome) if isinstance(outcome, httpx.Response) else route.mock(side_effect=outcome)
    await run_to_end(runner)
    assert core.posted(core.fail)["errorCode"] == code and core.complete.call_count == 0


# ---- credential and authorization failures --------------------------------------------------------------


@respx.mock
@pytest.mark.parametrize("status", [409, 403, 404])
async def test_a_refused_credential_fails_closed_without_calling_the_provider(env, status):
    runner = await env.build()
    core = CoreMock(respx.mock, credential_status=status)
    provider = anthropic_ok()
    await run_to_end(runner)
    assert core.posted(core.fail)["errorCode"] == "CREDENTIAL_UNAVAILABLE" and provider.call_count == 0


@respx.mock
async def test_an_execution_of_another_organization_never_gets_a_credential_or_a_provider_call(env):
    runner = await env.build()
    core = CoreMock(respx.mock, start_status=404)       # the Core API does not know this (organization, execution) pair
    provider = anthropic_ok()
    await run_to_end(runner)
    assert core.posted(core.fail)["errorCode"] == "CORE_API_REJECTED"
    assert core.context.call_count == 0 and core.credential.call_count == 0 and provider.call_count == 0


@respx.mock
async def test_a_credential_for_another_connection_is_rejected(env):
    runner = await env.build()
    core = CoreMock(respx.mock)
    respx.post(base(action="credential")).respond(
        json={"connectionId": str(uuid.uuid4()), "providerType": "ANTHROPIC", "modelId": "model-x", "credential": SECRET}
    )
    provider = anthropic_ok()
    await run_to_end(runner)
    assert core.posted(core.fail)["errorCode"] == "CREDENTIAL_UNAVAILABLE" and provider.call_count == 0


@respx.mock
@pytest.mark.parametrize(
    "context,code",
    [
        (context_json(status="PAUSED"), "AGENT_NOT_ELIGIBLE"),
        (context_json(providers=[]), "PROVIDER_NOT_CONFIGURED"),
        (context_json(providers=[{"connectionId": str(CONNECTION), "providerType": "OPENAI", "modelId": "m", "role": "PRIMARY", "priority": 0}]), "PROVIDER_NOT_ENABLED"),
        # Only a FALLBACK exists: there is no automatic fallback between providers.
        (context_json(providers=[{"connectionId": str(CONNECTION), "providerType": "ANTHROPIC", "modelId": "m", "role": "FALLBACK", "priority": 1}]), "PROVIDER_NOT_CONFIGURED"),
    ],
)
async def test_ineligible_agents_and_providers_never_reach_credentials(env, context, code):
    runner = await env.build()
    core = CoreMock(respx.mock, context=context)
    provider = anthropic_ok()
    await run_to_end(runner)
    assert core.posted(core.fail)["errorCode"] == code and core.credential.call_count == 0 and provider.call_count == 0


@respx.mock
async def test_the_execution_resolver_refuses_foreign_scopes(env):
    from app.orchestration.graphs.execution import ExecutionCredentialResolver, ExecutionFailure
    from app.providers.base import ProviderScope

    await env.build()
    core = CoreMock(respx.mock)
    resolver = ExecutionCredentialResolver(env.core, ORG, EXECUTION)
    with pytest.raises(ExecutionFailure):
        await resolver.resolve(ProviderScope(uuid.uuid4(), CONNECTION))      # another organization
    assert core.credential.call_count == 0
    assert (await resolver.resolve(ProviderScope(ORG, CONNECTION))).get_secret_value() == SECRET


# ---- Core API reporting failures, timeouts, concurrency ----------------------------------------------------


@respx.mock
async def test_a_core_api_reporting_failure_is_handled_and_a_failure_report_is_attempted(env):
    runner = await env.build()
    core = CoreMock(respx.mock, complete_status=503)
    anthropic_ok()
    await run_to_end(runner)       # must not raise
    assert core.complete.call_count == 1 and core.posted(core.fail)["errorCode"] == "CORE_API_UNAVAILABLE"
    assert env.sleeps == []        # state-changing calls are never retried


@respx.mock
async def test_the_run_is_bounded_in_time(env):
    env.settings = Settings(environment="test", anthropic_base_url=ANTHROPIC, run_timeout_seconds=0.2)
    runner = await env.build()
    core = CoreMock(respx.mock)

    async def slow(request):
        await asyncio.sleep(5)

    respx.post(f"{ANTHROPIC}/v1/messages").mock(side_effect=slow)
    await run_to_end(runner)
    assert core.posted(core.fail)["errorCode"] == "EXECUTION_TIMEOUT"


@respx.mock
async def test_duplicate_and_excess_submissions_are_refused(env):
    from app.core.errors import UnavailableError

    env.settings = Settings(environment="test", anthropic_base_url=ANTHROPIC, max_concurrent_executions=1, run_timeout_seconds=5)
    runner = await env.build()
    CoreMock(respx.mock)

    async def slow(request):
        await asyncio.sleep(5)

    respx.post(f"{ANTHROPIC}/v1/messages").mock(side_effect=slow)
    runner.submit(ORG, EXECUTION)
    with pytest.raises(DuplicateExecution):
        runner.submit(ORG, EXECUTION)
    with pytest.raises(UnavailableError):
        runner.submit(ORG, uuid.uuid4())
    await runner.shutdown()
    assert runner.active == 0


# ---- the HTTP entry point -----------------------------------------------------------------------------------

JWKS_URL = "http://localhost:8082/realms/ai-workforce/protocol/openid-connect/certs"


class StubRunner:
    def __init__(self):
        self.submitted = []

    def submit(self, organization_id, execution_id):
        self.submitted.append((organization_id, execution_id))


@pytest.fixture
async def api(keys):
    from app.main import create_app

    app = create_app(Settings(environment="test"))
    with respx.mock(assert_all_called=False) as mock:
        mock.get(JWKS_URL).respond(json=keys.jwks())
        async with app.router.lifespan_context(app):
            app.state.executor = StubRunner()
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app, raise_app_exceptions=False), base_url="http://t") as http:
                http.app = app
                yield http


def payload(**extra):
    return {"organizationId": str(ORG), "executionId": str(EXECUTION), **extra}


async def test_the_entry_point_requires_an_allowed_service_token(api, make_token):
    assert (await api.post("/api/v1/executions", json=payload())).status_code == 401
    for overrides in ({"azp": "ai-workforce-desktop"}, {"azp": "ai-orchestrator"}, {"aud": "ai-workforce-api"}):
        response = await api.post("/api/v1/executions", json=payload(), headers={"Authorization": f"Bearer {make_token(**overrides)}"})
        assert response.status_code == 401
    assert api.app.state.executor.submitted == []


async def test_an_allowed_caller_gets_202_and_the_run_is_started(api, make_token):
    response = await api.post("/api/v1/executions", json=payload(), headers={"Authorization": f"Bearer {make_token()}"})
    assert response.status_code == 202 and response.json() == {"status": "ACCEPTED"}
    assert api.app.state.executor.submitted == [(ORG, EXECUTION)]


async def test_the_entry_point_accepts_identifiers_only(api, make_token):
    headers = {"Authorization": f"Bearer {make_token()}"}
    for body in (payload(task="smuggled instruction"), payload(credential="sk-x"), {"organizationId": "nope", "executionId": str(EXECUTION)}, {}):
        assert (await api.post("/api/v1/executions", json=body, headers=headers)).status_code == 422
    assert api.app.state.executor.submitted == []


async def test_capacity_and_duplicates_are_reported(api, make_token):
    from app.core.errors import UnavailableError

    headers = {"Authorization": f"Bearer {make_token()}"}

    def full(*_):
        raise UnavailableError("The orchestrator is at capacity.")

    api.app.state.executor.submit = full
    assert (await api.post("/api/v1/executions", json=payload(), headers=headers)).status_code == 503

    def duplicate(*_):
        raise DuplicateExecution

    api.app.state.executor.submit = duplicate
    assert (await api.post("/api/v1/executions", json=payload(), headers=headers)).status_code == 409


async def test_without_a_runtime_database_the_entry_point_is_unavailable(make_token, keys):
    from app.main import create_app

    app = create_app(Settings(environment="test"))
    with respx.mock() as mock:
        mock.get(JWKS_URL).respond(json=keys.jwks())
        async with app.router.lifespan_context(app):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://t") as http:
                response = await http.post("/api/v1/executions", json=payload(), headers={"Authorization": f"Bearer {make_token()}"})
    assert response.status_code == 503
