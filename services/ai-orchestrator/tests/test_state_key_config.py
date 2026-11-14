"""Configuration of the state-encryption key: strict validation, fail-closed production, no leakage (synthetic keys only)."""

import base64
import logging

import httpx
import pytest
import respx
from pydantic import SecretStr, ValidationError

from app.core.config import Settings
from app.core.crypto import StateIntegrityError, StateSealer
from app.main import create_app

GOOD = base64.b64encode(bytes(range(32))).decode()          # synthetic test key, not a real secret
PROD = dict(
    environment="prod", core_api_base_url="https://core.example.com", token_url="https://idp.example.com/token",
    oidc_issuer="https://idp.example.com/realms/x", oidc_jwks_url="https://idp.example.com/certs",
    client_secret=SecretStr("s"), database_url=SecretStr("postgresql://x"),
)


def test_production_fails_closed_without_a_key_and_with_an_empty_one():
    for missing in ({}, {"state_encryption_key": SecretStr("")}):
        with pytest.raises(ValidationError, match="AIO_STATE_ENCRYPTION_KEY is required"):
            Settings(**PROD, **missing)
    assert Settings(**PROD, state_encryption_key=SecretStr(GOOD)).tools_enabled


@pytest.mark.parametrize(
    "bad",
    [
        "not base64 at all!!",
        base64.b64encode(b"short").decode(),                  # wrong length
        base64.b64encode(bytes(31)).decode(),
        base64.b64encode(bytes(33)).decode(),
        GOOD + "\n",
        " " + GOOD,
        GOOD[:-1],                                            # truncated
    ],
)
def test_invalid_keys_are_rejected_and_never_echoed(bad):
    for env in ("dev", "prod"):
        extra = PROD if env == "prod" else {}
        with pytest.raises(ValidationError) as caught:
            Settings(**{**extra, "environment": env}, state_encryption_key=SecretStr(bad))
        message = str(caught.value)
        assert bad.strip() not in message or not bad.strip()   # the value is never part of the error
        assert "AIO_STATE_ENCRYPTION_KEY" in message


def test_the_key_is_never_printed_by_the_settings_object():
    settings = Settings(state_encryption_key=SecretStr(GOOD))
    for rendered in (repr(settings), str(settings), settings.model_dump_json(), str(settings.model_dump())):
        assert GOOD not in rendered


def test_a_development_key_enables_tools_and_no_key_disables_them_without_any_plaintext_fallback():
    assert Settings(state_encryption_key=SecretStr(GOOD)).tools_enabled
    assert not Settings().tools_enabled                       # no key: no tool loop, so nothing is ever paused or persisted


async def test_the_key_is_not_exposed_by_health_runtime_info_or_logs(keys, make_token, caplog):
    jwks = "http://localhost:8082/realms/ai-workforce/protocol/openid-connect/certs"
    app = create_app(Settings(environment="test", state_encryption_key=SecretStr(GOOD)))
    caplog.set_level(logging.DEBUG)
    with respx.mock(assert_all_called=False) as mock:
        mock.get(jwks).respond(json=keys.jwks())
        async with app.router.lifespan_context(app):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://t") as http:
                bodies = [
                    (await http.get("/health/live")).text,
                    (await http.get("/health/ready")).text,
                    (await http.get("/api/v1/runtime/info", headers={"Authorization": f"Bearer {make_token()}"})).text,
                ]
    raw = base64.b64decode(GOOD)
    for text in [*bodies, caplog.text]:
        assert GOOD not in text and raw.hex() not in text and "AIO_STATE_ENCRYPTION_KEY" not in text.replace("AIO_STATE_ENCRYPTION_KEY is", "")


def test_sealed_state_is_authenticated_and_bound_to_its_key_and_context():
    import uuid

    org, execution = uuid.uuid4(), uuid.uuid4()
    aad = StateSealer.aad(org, execution)
    sealed = StateSealer(bytes(range(32))).seal(b"sensitive conversation", aad)
    assert "sensitive" not in sealed and StateSealer(bytes(range(32))).open(sealed, aad) == b"sensitive conversation"
    assert sealed != StateSealer(bytes(range(32))).seal(b"sensitive conversation", aad)      # fresh nonce every time
    with pytest.raises(StateIntegrityError):                                                  # different key
        StateSealer(bytes(range(1, 33))).open(sealed, aad)
    with pytest.raises(StateIntegrityError):                                                  # other execution
        StateSealer(bytes(range(32))).open(sealed, StateSealer.aad(org, uuid.uuid4()))
    flipped = sealed[:-2] + ("00" if sealed[-2:] != "00" else "ff")
    for bad in (flipped, sealed[:-2], "zz", "", "02" + sealed[2:]):                           # tampered/truncated/garbage/version
        with pytest.raises(StateIntegrityError):
            StateSealer(bytes(range(32))).open(bad, aad)


def test_a_configuration_error_never_shows_any_part_of_the_secrets_it_was_given():
    """Regression: pydantic used to print the tail of the input mapping (``input_value=...``), which included the key."""
    with pytest.raises(ValidationError) as caught:
        Settings(
            environment="prod", client_secret="client-secret-sentinel-0123", database_url="postgresql://u:db-password-sentinel@h/d",
            state_encryption_key=GOOD, core_api_base_url="http://x.example.com",
        )
    message = str(caught.value)
    assert "must use HTTPS" in message
    for secret in (GOOD, GOOD[-20:], "client-secret-sentinel", "db-password-sentinel", "input_value"):
        assert secret not in message
