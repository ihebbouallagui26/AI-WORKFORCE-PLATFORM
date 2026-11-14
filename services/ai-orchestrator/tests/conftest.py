"""Shared fixtures. Windows needs a selector event loop for psycopg and a proactor loop for subprocesses (MCP)."""

from __future__ import annotations

import asyncio
import json
import sys
import time
from collections.abc import Callable, Iterator
from typing import Any

import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa
from jwt.algorithms import RSAAlgorithm

from app.core.config import Settings

ISSUER = "http://localhost:8082/realms/ai-workforce"
AUDIENCE = "ai-orchestrator"


def pytest_asyncio_loop_factories(config: pytest.Config, item: pytest.Item) -> dict[str, Callable[[], asyncio.AbstractEventLoop]]:
    if sys.platform == "win32":
        if item.module.__name__.endswith("test_mcp"):
            return {"proactor": asyncio.ProactorEventLoop}  # MCP stdio launches a subprocess
        return {"selector": asyncio.SelectorEventLoop}  # psycopg cannot use the proactor loop
    return {"default": asyncio.new_event_loop}


@pytest.fixture
def settings() -> Settings:
    return Settings(environment="test")


class Keys:
    """A throwaway RSA key pair standing in for Keycloak's signing key."""

    def __init__(self) -> None:
        self.private = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        self.kid = "test-key"

    def jwks(self) -> dict[str, Any]:
        jwk = json.loads(RSAAlgorithm.to_jwk(self.private.public_key()))
        return {"keys": [{**jwk, "kid": self.kid, "alg": "RS256", "use": "sig"}]}

    def token(self, **overrides: Any) -> str:
        now = int(time.time())
        claims = {
            "iss": ISSUER, "aud": AUDIENCE, "sub": "service-account-core-api", "azp": "ai-workforce-core-api",
            "iat": now, "exp": now + 300, **overrides,
        }
        return jwt.encode({k: v for k, v in claims.items() if v is not None}, self.private, algorithm="RS256", headers={"kid": self.kid})


@pytest.fixture(scope="session")
def keys() -> Keys:
    return Keys()


@pytest.fixture
def make_token(keys: Keys) -> Callable[..., str]:
    return keys.token


@pytest.fixture(autouse=True)
def _quiet_env(monkeypatch: pytest.MonkeyPatch) -> Iterator[None]:
    for name in list(__import__("os").environ):
        if name.startswith("AIO_"):
            monkeypatch.delenv(name, raising=False)
    yield
