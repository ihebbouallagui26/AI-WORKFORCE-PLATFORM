import pytest
from pydantic import SecretStr, ValidationError

from app.core.config import Settings, is_secure_url


def test_defaults_are_local_and_safe():
    s = Settings()
    assert s.host == "127.0.0.1" and s.port == 8091
    assert s.client_secret is None and s.database_url is None


def test_secrets_never_appear_in_repr_or_dump():
    s = Settings(client_secret=SecretStr("super-secret-value"), database_url=SecretStr("postgresql://u:pw-123@h/db"))
    for text in (repr(s), str(s), s.model_dump_json()):
        assert "super-secret-value" not in text and "pw-123" not in text


def test_environment_variables_are_read(monkeypatch):
    monkeypatch.setenv("AIO_PORT", "9100")
    monkeypatch.setenv("AIO_ALLOWED_CALLER_CLIENT_IDS", "a, b")
    s = Settings()
    assert s.port == 9100 and s.allowed_caller_client_ids == ("a", "b")


@pytest.mark.parametrize("field,value", [("port", 70000), ("request_timeout_seconds", 0), ("log_level", "TRACE"), ("environment", "staging")])
def test_invalid_values_are_rejected(field, value):
    with pytest.raises(ValidationError):
        Settings(**{field: value})


def test_production_requires_https_secret_and_database():
    base = dict(
        environment="prod", core_api_base_url="https://core.example.com", token_url="https://idp.example.com/token",
        oidc_issuer="https://idp.example.com/realms/x", oidc_jwks_url="https://idp.example.com/certs",
    )
    with pytest.raises(ValidationError, match="CLIENT_SECRET"):
        Settings(**base, database_url=SecretStr("postgresql://x"))
    with pytest.raises(ValidationError, match="DATABASE_URL"):
        Settings(**base, client_secret=SecretStr("s"))
    with pytest.raises(ValidationError, match="HTTPS"):
        Settings(**{**base, "core_api_base_url": "http://core.example.com"}, client_secret=SecretStr("s"), database_url=SecretStr("postgresql://x"))
    assert Settings(**base, client_secret=SecretStr("s"), database_url=SecretStr("postgresql://x")).environment == "prod"


def test_provider_endpoints_must_be_https_or_loopback():
    with pytest.raises(ValidationError):
        Settings(anthropic_base_url="http://evil.example.com")
    with pytest.raises(ValidationError):
        Settings(openai_base_url="https://user:pw@api.openai.com")
    assert Settings(openai_base_url="http://127.0.0.1:9999").openai_base_url == "http://127.0.0.1:9999"
    with pytest.raises(ValidationError):
        Settings(environment="prod", openai_base_url="http://127.0.0.1:9999")


def test_is_secure_url():
    assert is_secure_url("https://a.example")
    assert not is_secure_url("ftp://a.example")
    assert not is_secure_url("http://a.example")
    assert not is_secure_url("https://u:p@a.example")
