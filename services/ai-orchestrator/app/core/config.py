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
    model_config = SettingsConfigDict(env_prefix="AIO_", extra="ignore", frozen=True, hide_input_in_errors=True)

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

    # --- Tool execution (C2) -------------------------------------------------------------------------
    # Tools are offered to the model only when BOTH a state encryption key is configured (a run that
    # pauses for a human approval is persisted sealed) and at least one allowlisted MCP server is enabled.
    state_encryption_key: SecretStr | None = None
    # Security fix 8.3: sealed pauses written by the previous format (raw key, AAD organization:execution) are still READ so runs paused
    # at the upgrade are not lost. Set to false once no pre-upgrade pause can remain (an approval expires after 15 minutes by default).
    state_accept_legacy_format: bool = True
    tool_max_rounds: int = Field(default=6, ge=1, le=20)
    tool_max_calls_per_run: int = Field(default=8, ge=1, le=10)  # the Core API caps an execution at 10
    tool_output_max_chars: int = Field(default=8000, ge=256, le=65_536)
    mcp_servers: Annotated[tuple[str, ...], NoDecode] = ("diagnostic",)
    mcp_max_concurrent_calls: int = Field(default=4, ge=1, le=32)

    # --- MCP --------------------------------------------------------------------------------------
    mcp_timeout_seconds: float = Field(default=15.0, gt=0, le=300)
    # Memory limits of an MCP server's output (security fix 8.4; see app/mcp/bounded_stdio.py for the defaults' justification).
    # One stdout line = one JSON-RPC message: a longer one terminates the server. stderr: only the head is kept; a flood terminates it.
    mcp_max_message_bytes: int = Field(default=1_048_576, ge=65_536, le=16_777_216)
    mcp_stderr_capture_bytes: int = Field(default=16_384, ge=1_024, le=1_048_576)
    mcp_stderr_flood_bytes: int = Field(default=8_388_608, ge=65_536, le=268_435_456)  # per 10-second window
    # Aggregate bounds on stdout (a stream of individually valid messages): bytes and messages per 10-second window, bytes per tool call.
    mcp_stdout_flood_bytes: int = Field(default=16_777_216, ge=262_144, le=1_073_741_824)
    mcp_stdout_flood_messages: int = Field(default=50_000, ge=100, le=10_000_000)
    mcp_max_call_bytes: int = Field(default=4_194_304, ge=65_536, le=268_435_456)

    @field_validator("allowed_caller_client_ids", "execution_enabled_providers", "mcp_servers", mode="before")
    @classmethod
    def _split_callers(cls, value: object) -> object:
        if isinstance(value, str):
            return tuple(part.strip() for part in value.split(",") if part.strip())
        return value

    @field_validator("state_encryption_key")
    @classmethod
    def _valid_state_key(cls, value: SecretStr | None) -> SecretStr | None:
        if value is not None and value.get_secret_value():
            import base64
            import binascii

            try:
                raw = base64.b64decode(value.get_secret_value(), validate=True)
            except (binascii.Error, ValueError) as exc:
                raise ValueError("AIO_STATE_ENCRYPTION_KEY must be base64") from exc
            if len(raw) != 32:
                raise ValueError("AIO_STATE_ENCRYPTION_KEY must be the base64 of 32 bytes")
        return value

    @property
    def tools_enabled(self) -> bool:
        key = self.state_encryption_key
        return bool(key and key.get_secret_value()) and bool(self.mcp_servers)

    def state_key_bytes(self) -> bytes:
        import base64

        assert self.state_encryption_key is not None
        return base64.b64decode(self.state_encryption_key.get_secret_value())

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
            if self.state_encryption_key is None or not self.state_encryption_key.get_secret_value():
                raise ValueError("AIO_STATE_ENCRYPTION_KEY is required in production")
        return self


@lru_cache
def get_settings() -> Settings:
    return Settings()
