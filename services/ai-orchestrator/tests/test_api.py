import asyncio

import httpx
import jwt as pyjwt
import pytest
import respx
from cryptography.hazmat.primitives.asymmetric import rsa

from app.core.config import Settings
from app.main import create_app
from tests.conftest import AUDIENCE, ISSUER, Keys

JWKS_URL = "http://localhost:8082/realms/ai-workforce/protocol/openid-connect/certs"


@pytest.fixture
async def client(settings: Settings, keys):
    app = create_app(settings)
    with respx.mock(assert_all_called=False) as mock:
        mock.get(JWKS_URL).respond(json=keys.jwks())
        async with app.router.lifespan_context(app):
            transport = httpx.ASGITransport(app=app, raise_app_exceptions=False)
            async with httpx.AsyncClient(transport=transport, base_url="http://test") as http:
                yield http


def bearer(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


async def test_live_is_up_without_authentication(client):
    response = await client.get("/health/live")
    assert response.status_code == 200 and response.json() == {"status": "UP"}


async def test_ready_reports_unconfigured_store_without_details(client):
    response = await client.get("/health/ready")
    assert response.status_code == 503
    assert response.json() == {"status": "DOWN", "checks": {"checkpointStore": "NOT_CONFIGURED"}}
    assert "localhost" not in response.text and "postgres" not in response.text.lower()


async def test_correlation_id_is_generated_and_echoed(client):
    generated = (await client.get("/health/live")).headers["x-request-id"]
    assert len(generated) == 36
    echoed = await client.get("/health/live", headers={"X-Request-Id": "abcdef12-test"})
    assert echoed.headers["x-request-id"] == "abcdef12-test"


async def test_malformed_correlation_id_is_replaced(client):
    response = await client.get("/health/live", headers={"X-Request-Id": "bad id with spaces!"})
    assert response.headers["x-request-id"] != "bad id with spaces!"


async def test_errors_are_problem_documents_with_request_id(client):
    response = await client.get("/nope", headers={"X-Request-Id": "req-12345678"})
    body = response.json()
    assert response.status_code == 404
    assert response.headers["content-type"].startswith("application/problem+json")
    assert body["type"] == "urn:aiworkforce:orchestrator:problem:http" and body["requestId"] == "req-12345678"


async def test_runtime_info_requires_a_bearer_token(client):
    assert (await client.get("/api/v1/runtime/info")).status_code == 401
    assert (await client.get("/api/v1/runtime/info", headers={"Authorization": "Basic abc"})).status_code == 401


async def test_runtime_info_for_an_allowed_service(client, make_token):
    response = await client.get("/api/v1/runtime/info", headers=bearer(make_token()))
    body = response.json()
    assert response.status_code == 200
    assert body["providers"] == ["ANTHROPIC", "OPENAI", "GEMINI"] and body["mcpServers"] == ["diagnostic"]
    assert body["features"]["agentExecution"] is False and body["features"]["toolExecution"] is False


@pytest.mark.parametrize(
    "overrides",
    [
        {"azp": "ai-workforce-desktop"},  # a human/desktop token is not a service caller
        {"azp": None},
        {"aud": "ai-workforce-api"},  # token meant for the Core API
        {"iss": "http://evil.example/realms/x"},
        {"exp": 1},
    ],
)
async def test_tokens_that_are_not_for_this_service_are_rejected(client, make_token, overrides):
    response = await client.get("/api/v1/runtime/info", headers=bearer(make_token(**overrides)))
    assert response.status_code == 401 and "Invalid bearer token" in response.json()["detail"]


async def test_forged_signature_is_rejected(client):
    other = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    forged = pyjwt.encode(
        {"iss": ISSUER, "aud": AUDIENCE, "sub": "x", "azp": "ai-workforce-core-api", "exp": 4102444800},
        other,
        algorithm="RS256",
        headers={"kid": "test-key"},
    )
    assert (await client.get("/api/v1/runtime/info", headers=bearer(forged))).status_code == 401
    assert (await client.get("/api/v1/runtime/info", headers=bearer("not-a-jwt"))).status_code == 401


async def test_unreachable_identity_provider_is_a_503_not_a_leak(settings):
    app = create_app(settings)
    with respx.mock() as mock:
        mock.get(JWKS_URL).mock(side_effect=httpx.ConnectError("boom"))
        async with app.router.lifespan_context(app):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://t") as http:
                response = await http.get("/api/v1/runtime/info", headers=bearer(Keys().token()))
    assert response.status_code == 503 and "boom" not in response.text


async def test_request_timeout_returns_504():
    app = create_app(Settings(environment="test", request_timeout_seconds=0.05))

    @app.get("/slow")
    async def slow():  # pragma: no cover - cancelled by the timeout
        await asyncio.sleep(5)

    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://t") as http:
        response = await http.get("/slow")
    assert response.status_code == 504 and response.json()["type"].endswith(":timeout")


async def test_oversized_body_is_rejected():
    app = create_app(Settings(environment="test", max_request_bytes=100))

    @app.post("/echo")
    async def echo(payload: dict):  # pragma: no cover
        return payload

    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://t") as http:
        response = await http.post("/echo", content=b"x" * 500, headers={"content-type": "application/json"})
    assert response.status_code == 413


async def test_validation_errors_do_not_echo_input():
    app = create_app(Settings(environment="test"))

    @app.post("/typed")
    async def typed(count: int):  # pragma: no cover
        return count

    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://t") as http:
        response = await http.post("/typed?count=super-secret-text")
    assert response.status_code == 422 and "super-secret-text" not in response.text


async def test_unexpected_errors_are_generic(capsys):
    app = create_app(Settings(environment="test"))

    @app.get("/boom")
    async def boom():
        raise RuntimeError("password=hunter2 leaked")

    transport = httpx.ASGITransport(app=app, raise_app_exceptions=False)
    async with httpx.AsyncClient(transport=transport, base_url="http://t") as http:
        response = await http.get("/boom")
    assert response.status_code == 500 and "hunter2" not in response.text
    assert "hunter2" not in capsys.readouterr().out


async def test_docs_are_hidden_outside_development():
    app = create_app(Settings(environment="test"))
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://t") as http:
        assert (await http.get("/docs")).status_code == 404
        assert (await http.get("/openapi.json")).status_code == 404


async def test_shutdown_closes_resources(settings):
    app = create_app(settings)
    async with app.router.lifespan_context(app):
        core_http = app.state.core_api._http
        assert not core_http.is_closed
    assert core_http.is_closed


async def test_encryption_keys_in_the_jwks_are_ignored(settings, keys, make_token):
    """Keycloak publishes use=enc (RSA-OAEP) keys next to the signing key: they must not break verification."""
    document = keys.jwks()
    document["keys"].append({"kty": "RSA", "kid": "enc-key", "use": "enc", "alg": "RSA-OAEP", "n": "AQAB", "e": "AQAB"})
    app = create_app(settings)
    with respx.mock() as mock:
        mock.get(JWKS_URL).respond(json=document)
        async with app.router.lifespan_context(app):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://t") as http:
                response = await http.get("/api/v1/runtime/info", headers=bearer(make_token()))
    assert response.status_code == 200
