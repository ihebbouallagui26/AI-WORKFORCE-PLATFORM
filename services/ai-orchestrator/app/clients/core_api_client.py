"""Typed asynchronous Core API client authenticated as a dedicated Keycloak service account.

* OAuth2 client credentials: the identity is the ``ai-orchestrator`` client, never a human user and
  never a token supplied by the desktop. The client secret is a ``SecretStr`` read from the
  environment; the access token lives in memory only and is never logged.
* Idempotent GET requests are retried (network errors, timeouts, 502/503/504) with exponential
  backoff and jitter; a 401 triggers exactly one token refresh.
* Every failure becomes a safe :class:`OrchestratorError`: response bodies are never copied into
  messages or logs.
"""

from __future__ import annotations

import asyncio
import random
import time
from collections.abc import Awaitable, Callable
from typing import Any
from uuid import UUID

import httpx
from pydantic import SecretStr, ValidationError

from app.clients.contracts import AgentExecutionContext
from app.core.errors import ForbiddenError, NotFoundError, OrchestratorError, UnavailableError
from app.core.logging import get_logger

log = get_logger("core-api-client")

_RETRYABLE_STATUS = frozenset({502, 503, 504})
_TOKEN_SAFETY_MARGIN = 30.0


class CoreApiUnavailable(UnavailableError):
    default_message = "The Core API is unavailable."


class CoreApiAuthenticationError(OrchestratorError):
    status = 502
    code = "core-api-authentication"
    default_message = "The orchestrator could not authenticate to the Core API."


class CoreApiForbidden(ForbiddenError):
    default_message = "The orchestrator is not authorized for this Core API operation."


class CoreApiNotFound(NotFoundError):
    default_message = "The requested resource was not found or is not accessible."


class CoreApiRequestError(OrchestratorError):
    status = 502
    code = "core-api-request"
    default_message = "The Core API rejected the request."


class CoreApiInvalidResponse(OrchestratorError):
    status = 502
    code = "core-api-invalid-response"
    default_message = "The Core API returned an unexpected response."


class ServiceTokenProvider:
    """Obtains and caches a service-account access token (OAuth2 client credentials)."""

    def __init__(
        self,
        http: httpx.AsyncClient,
        token_url: str,
        client_id: str,
        client_secret: SecretStr | None,
        *,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._http = http
        self._token_url = token_url
        self._client_id = client_id
        self._client_secret = client_secret
        self._clock = clock
        self._token: SecretStr | None = None
        self._expires_at = 0.0
        self._lock = asyncio.Lock()

    def invalidate(self) -> None:
        self._token = None

    async def get_token(self) -> SecretStr:
        async with self._lock:
            if self._token is not None and self._clock() < self._expires_at:
                return self._token
            if self._client_secret is None or not self._client_secret.get_secret_value():
                raise CoreApiAuthenticationError("The service client secret is not configured.")
            try:
                response = await self._http.post(
                    self._token_url,
                    data={
                        "grant_type": "client_credentials",
                        "client_id": self._client_id,
                        "client_secret": self._client_secret.get_secret_value(),
                    },
                    headers={"Accept": "application/json"},
                )
            except httpx.HTTPError as exc:
                raise CoreApiUnavailable("The identity provider is unavailable.") from exc
            if response.status_code in (400, 401, 403):
                raise CoreApiAuthenticationError
            if response.status_code != 200:
                raise CoreApiUnavailable("The identity provider is unavailable.")
            try:
                payload: dict[str, Any] = response.json()
                token = str(payload["access_token"])
                lifetime = float(payload.get("expires_in", 60))
            except (ValueError, KeyError, TypeError) as exc:
                raise CoreApiAuthenticationError from exc
            self._token = SecretStr(token)
            self._expires_at = self._clock() + max(lifetime - _TOKEN_SAFETY_MARGIN, 1.0)
            return self._token


class CoreApiClient:
    def __init__(
        self,
        http: httpx.AsyncClient,
        tokens: ServiceTokenProvider,
        *,
        max_retries: int = 2,
        backoff_seconds: float = 0.25,
        sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
    ) -> None:
        self._http = http
        self._tokens = tokens
        self._max_retries = max_retries
        self._backoff = backoff_seconds
        self._sleep = sleep

    async def get_execution_context(self, organization_id: UUID, agent_id: UUID) -> AgentExecutionContext:
        """The authorized configuration of one agent. Validated by the Core API, never trusted from callers."""
        path = f"/api/v1/internal/organizations/{organization_id}/agents/{agent_id}/execution-context"
        payload = await self._get(path)
        try:
            context = AgentExecutionContext.model_validate(payload)
        except ValidationError as exc:
            raise CoreApiInvalidResponse from exc
        if context.organization_id != organization_id or context.agent_id != agent_id:
            # Defense in depth: the answer must be about exactly what was asked.
            raise CoreApiInvalidResponse
        return context

    async def _get(self, path: str) -> Any:
        refreshed = False
        attempt = 0
        while True:
            token = await self._tokens.get_token()
            try:
                response = await self._http.get(path, headers={"Authorization": f"Bearer {token.get_secret_value()}"})
            except httpx.HTTPError as exc:
                if attempt >= self._max_retries:
                    raise CoreApiUnavailable from exc
                await self._pause(attempt)
                attempt += 1
                continue
            status = response.status_code
            if status == 401 and not refreshed:
                refreshed = True
                self._tokens.invalidate()
                continue
            if status in _RETRYABLE_STATUS and attempt < self._max_retries:
                await self._pause(attempt)
                attempt += 1
                continue
            return self._interpret(response)

    def _interpret(self, response: httpx.Response) -> Any:
        status = response.status_code
        if 200 <= status < 300:
            try:
                return response.json()
            except ValueError as exc:
                raise CoreApiInvalidResponse from exc
        log.warning("core_api_error", status=status)
        if status == 401:
            raise CoreApiAuthenticationError
        if status == 403:
            raise CoreApiForbidden
        if status == 404:
            raise CoreApiNotFound
        if status in _RETRYABLE_STATUS or status >= 500:
            raise CoreApiUnavailable
        raise CoreApiRequestError

    async def _pause(self, attempt: int) -> None:
        await self._sleep(self._backoff * (2**attempt) * (0.5 + random.random()))  # noqa: S311
