import uuid

import httpx
import pytest
import respx
from pydantic import SecretStr

from app.clients.core_api_client import (
    CoreApiAuthenticationError,
    CoreApiClient,
    CoreApiForbidden,
    CoreApiInvalidResponse,
    CoreApiNotFound,
    CoreApiUnavailable,
    ServiceTokenProvider,
)

TOKEN_URL = "http://idp.test/token"
BASE = "http://core.test"
ORG = uuid.uuid4()
AGENT = uuid.uuid4()
PATH = f"/api/v1/internal/organizations/{ORG}/agents/{AGENT}/execution-context"
SECRET = "client-secret-DO-NOT-LOG"
ACCESS = "access-token-DO-NOT-LOG"


def context_body(**overrides):
    body = {
        "organizationId": str(ORG), "agentId": str(AGENT), "handle": "ada", "name": "Ada", "status": "ACTIVE", "configVersion": 3,
        "systemInstructions": "Be brief.",
        "providers": [{"connectionId": str(uuid.uuid4()), "providerType": "ANTHROPIC", "modelId": "m-1", "role": "PRIMARY", "priority": 0}],
        "tools": [{"toolCode": "docs.search", "level": "READ", "approvalRequired": False}],
        "skills": ["Code review"], "memory": {"enabled": False, "scope": "PROJECT", "accessMode": "AGENT_ONLY"},
    }
    return {**body, **overrides}


@pytest.fixture
async def make_client():
    clients: list[httpx.AsyncClient] = []

    def factory(*, retries=2, secret=SECRET, clock=None):
        idp = httpx.AsyncClient()
        core = httpx.AsyncClient(base_url=BASE)
        clients.extend([idp, core])
        sleeps: list[float] = []

        async def sleep(delay: float) -> None:
            sleeps.append(delay)

        kwargs = {"clock": clock} if clock else {}
        tokens = ServiceTokenProvider(idp, TOKEN_URL, "ai-orchestrator", SecretStr(secret) if secret else None, **kwargs)
        return CoreApiClient(core, tokens, max_retries=retries, backoff_seconds=0.01, sleep=sleep), tokens, sleeps

    yield factory
    for c in clients:
        await c.aclose()


def token_route(mock, **extra):
    return mock.post(TOKEN_URL).respond(json={"access_token": ACCESS, "expires_in": 300, **extra})


@respx.mock
async def test_fetches_context_with_a_service_account_token(make_client):
    client, _, _ = make_client()
    token = token_route(respx.mock)
    api = respx.get(BASE + PATH).respond(json=context_body())
    context = await client.get_execution_context(ORG, AGENT)
    assert context.handle == "ada" and context.providers[0].provider_type == "ANTHROPIC" and context.tools[0].tool_code == "docs.search"
    form = token.calls.last.request.content.decode()
    assert "grant_type=client_credentials" in form and "client_id=ai-orchestrator" in form
    assert api.calls.last.request.headers["authorization"] == f"Bearer {ACCESS}"


@respx.mock
async def test_token_is_cached_between_calls(make_client):
    client, _, _ = make_client()
    token = token_route(respx.mock)
    respx.get(BASE + PATH).respond(json=context_body())
    await client.get_execution_context(ORG, AGENT)
    await client.get_execution_context(ORG, AGENT)
    assert token.call_count == 1


@respx.mock
async def test_expired_token_is_renewed(make_client):
    now = [0.0]
    client, _, _ = make_client(clock=lambda: now[0])
    token = token_route(respx.mock)
    respx.get(BASE + PATH).respond(json=context_body())
    await client.get_execution_context(ORG, AGENT)
    now[0] = 10_000.0
    await client.get_execution_context(ORG, AGENT)
    assert token.call_count == 2


@respx.mock
async def test_a_401_refreshes_the_token_once(make_client):
    client, _, _ = make_client()
    token = token_route(respx.mock)
    respx.get(BASE + PATH).mock(side_effect=[httpx.Response(401), httpx.Response(200, json=context_body())])
    assert (await client.get_execution_context(ORG, AGENT)).name == "Ada"
    assert token.call_count == 2


@respx.mock
async def test_a_persistent_401_is_an_authentication_error(make_client):
    client, _, _ = make_client()
    token_route(respx.mock)
    respx.get(BASE + PATH).respond(401)
    with pytest.raises(CoreApiAuthenticationError):
        await client.get_execution_context(ORG, AGENT)


@respx.mock
async def test_rejected_client_credentials(make_client):
    client, _, _ = make_client()
    respx.post(TOKEN_URL).respond(401, json={"error": "unauthorized_client"})
    with pytest.raises(CoreApiAuthenticationError):
        await client.get_execution_context(ORG, AGENT)


async def test_missing_client_secret_fails_closed(make_client):
    client, _, _ = make_client(secret="")
    with pytest.raises(CoreApiAuthenticationError, match="not configured"):
        await client.get_execution_context(ORG, AGENT)


@respx.mock
@pytest.mark.parametrize("status,error", [(403, CoreApiForbidden), (404, CoreApiNotFound)])
async def test_authorization_outcomes_map_to_domain_errors(make_client, status, error):
    client, _, sleeps = make_client()
    token_route(respx.mock)
    respx.get(BASE + PATH).respond(status, json={"detail": "internal details"})
    with pytest.raises(error) as raised:
        await client.get_execution_context(ORG, AGENT)
    assert "internal details" not in raised.value.message and sleeps == []


@respx.mock
async def test_transient_failures_are_retried_with_backoff(make_client):
    client, _, sleeps = make_client()
    token_route(respx.mock)
    respx.get(BASE + PATH).mock(side_effect=[httpx.Response(503), httpx.ConnectError("down"), httpx.Response(200, json=context_body())])
    assert (await client.get_execution_context(ORG, AGENT)).config_version == 3
    assert len(sleeps) == 2 and sleeps[1] > 0


@respx.mock
async def test_retries_are_bounded(make_client):
    client, _, sleeps = make_client(retries=1)
    token_route(respx.mock)
    route = respx.get(BASE + PATH).respond(503)
    with pytest.raises(CoreApiUnavailable):
        await client.get_execution_context(ORG, AGENT)
    assert route.call_count == 2 and len(sleeps) == 1


@respx.mock
async def test_network_failure_and_timeout_are_unavailable(make_client):
    client, _, _ = make_client(retries=0)
    token_route(respx.mock)
    respx.get(BASE + PATH).mock(side_effect=httpx.ReadTimeout("slow"))
    with pytest.raises(CoreApiUnavailable):
        await client.get_execution_context(ORG, AGENT)


@respx.mock
@pytest.mark.parametrize("payload", ["not json", {"unexpected": True}, context_body(organizationId=str(uuid.uuid4())), context_body(agentId=str(uuid.uuid4()))])
async def test_invalid_or_mismatched_responses_are_rejected(make_client, payload):
    client, _, _ = make_client()
    token_route(respx.mock)
    if isinstance(payload, str):
        respx.get(BASE + PATH).respond(200, content=payload.encode())
    else:
        respx.get(BASE + PATH).respond(json=payload)
    with pytest.raises(CoreApiInvalidResponse):
        await client.get_execution_context(ORG, AGENT)


@respx.mock
async def test_secrets_never_reach_logs_or_errors(make_client, capsys):
    client, tokens, _ = make_client(retries=0)
    token_route(respx.mock)
    respx.get(BASE + PATH).respond(500, text=f"echo {ACCESS} {SECRET}")
    with pytest.raises(CoreApiUnavailable) as raised:
        await client.get_execution_context(ORG, AGENT)
    visible = capsys.readouterr().out + str(raised.value) + repr(tokens) + repr(client)
    assert SECRET not in visible and ACCESS not in visible
