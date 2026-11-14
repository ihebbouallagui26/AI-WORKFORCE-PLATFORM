"""Typed, validated configuration read from environment variables (prefix ``AIO_``).

Secrets are ``SecretStr``: they never appear in ``repr``, logs or health responses. Nothing here has
a default for a secret, and production refuses to start with insecure endpoints.
"""

from __future__ import annotations

from functools import lru_cache
from typing import Annotated, Literal
from urllib.parse import urlsplit

from pydantic import Field, SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, NoDecode, SettingsConfigDict

LOOPBACK_HOSTS = frozenset({"localhost", "127.0.0.1", "::1"})


def is_secure_url(url: str, *, allow_loopback_http: bool = True) -> bool:
    """HTTPS, or plain HTTP to the loopback interface only (local development and tests)."""
    parts = urlsplit(url)
    if not parts.hostname or parts.username or parts.password:
        return False
    if parts.scheme == "https":
        return True
    return allow_loopback_http and parts.scheme == "http" and parts.hostname in LOOPBACK_HOSTS


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="AIO_", extra="ignore", frozen=True)

    environment: Literal["dev", "test", "prod"] = "dev"
    # Bound to loopback by default: the service is internal and never published by default.
    host: str = "127.0.0.1"
    port: int = Field(default=8091, ge=1, le=65535)
    log_level: Literal["DEBUG", "INFO", "WARNING", "ERROR"] = "INFO"
    request_timeout_seconds: float = Field(default=30.0, gt=0, le=600)
    max_request_bytes: int = Field(default=1_048_576, gt=0, le=16_777_216)
    shutdown_grace_seconds: float = Field(default=10.0, gt=0, le=120)

    # --- Core API (authoritative business backend) and service identity -------------------------
    core_api_base_url: str = "http://localhost:8081"
    core_api_timeout_seconds: float = Field(default=10.0, gt=0, le=120)
    core_api_max_retries: int = Field(default=2, ge=0, le=5)
    token_url: str = "http://localhost:8082/realms/ai-workforce/protocol/openid-connect/token"  # noqa: S105
    client_id: str = "ai-orchestrator"
    client_secret: SecretStr | None = None

    # --- Authentication of callers of this service ---------------------------------------------
    oidc_issuer: str = "http://localhost:8082/realms/ai-workforce"
    oidc_jwks_url: str = "http://localhost:8082/realms/ai-workforce/protocol/openid-connect/certs"
    oidc_audience: str = "ai-orchestrator"
    allowed_caller_client_ids: Annotated[tuple[str, ...], NoDecode] = ("ai-workforce-core-api",)

    # --- Runtime state (LangGraph checkpoints): dedicated database and restricted role ----------
    database_url: SecretStr | None = None
    checkpoint_schema: str = Field(default="orchestrator_runtime", pattern=r"^[a-z_][a-z0-9_]{0,62}$")
    run_timeout_seconds: float = Field(default=60.0, gt=0, le=3600)

    # --- Model providers: fixed official endpoints, never user supplied -------------------------
    provider_timeout_seconds: float = Field(default=60.0, gt=0, le=600)
    provider_max_retries: int = Field(default=2, ge=0, le=5)
    anthropic_base_url: str = "https://api.anthropic.com"
    openai_base_url: str = "https://api.openai.com"
    google_base_url: str = "https://generativelanguage.googleapis.com"

    # --- Supervised execution (C1): text generation only -------------------------------------------
    # Providers allowed to run an execution. OpenAI and Gemini adapters exist but are not enabled yet.
    execution_enabled_providers: Annotated[tuple[str, ...], NoDecode] = ("ANTHROPIC",)
    execution_max_output_tokens: int = Field(default=1024, ge=1, le=64_000)
    max_concurrent_executions: int = Field(default=4, ge=1, le=64)

    # --- MCP --------------------------------------------------------------------------------------
    mcp_timeout_seconds: float = Field(default=15.0, gt=0, le=300)

    @field_validator("allowed_caller_client_ids", "execution_enabled_providers", mode="before")
    @classmethod
    def _split_callers(cls, value: object) -> object:
        if isinstance(value, str):
            return tuple(part.strip() for part in value.split(",") if part.strip())
        return value

    @model_validator(mode="after")
    def _validate_endpoints(self) -> Settings:
        production = self.environment == "prod"
        urls = {
            "core_api_base_url": self.core_api_base_url,
            "token_url": self.token_url,
            "oidc_issuer": self.oidc_issuer,
            "oidc_jwks_url": self.oidc_jwks_url,
        }
        for name, url in urls.items():
            # Inside a private compose network plain HTTP between containers is allowed outside prod.
            if production and not is_secure_url(url, allow_loopback_http=False):
                raise ValueError(f"{name} must use HTTPS in production")
            if not production and urlsplit(url).scheme not in {"http", "https"}:
                raise ValueError(f"{name} must be an http(s) URL")
        for name in ("anthropic_base_url", "openai_base_url", "google_base_url"):
            if not is_secure_url(getattr(self, name), allow_loopback_http=not production):
                raise ValueError(f"{name} must be HTTPS (loopback HTTP is allowed outside production)")
        if production:
            if self.client_secret is None or not self.client_secret.get_secret_value():
                raise ValueError("AIO_CLIENT_SECRET is required in production")
            if self.database_url is None:
                raise ValueError("AIO_DATABASE_URL is required in production")
            if not self.allowed_caller_client_ids:
                raise ValueError("AIO_ALLOWED_CALLER_CLIENT_IDS must not be empty in production")
        return self


@lru_cache
def get_settings() -> Settings:
    return Settings()
