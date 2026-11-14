"""Provider-neutral contracts and the shared, secret-safe HTTP engine of every adapter.

Design rules
------------
* Credentials are injected per call through a :class:`CredentialResolver` (a trusted server-side
  interface backed by the Core API's SecretStore). Adapters hold a key only for the duration of one
  request: it is never stored on the adapter, logged, put in an exception or in graph state.
* Endpoints are fixed official base URLs from configuration. Model ids are caller data: they are
  validated and never allowed to alter the request path. No model name is hardcoded here.
* Provider responses are never copied into errors or logs (they can echo key fragments); every
  failure is normalized to a small taxonomy with a fixed safe message.
* Rate limits (HTTP 429) and transient failures are retried with exponential backoff, honouring
  ``Retry-After`` up to a cap, before surfacing as :class:`ProviderRateLimitError`.
"""

from __future__ import annotations

import asyncio
import json
import random
import re
from abc import ABC, abstractmethod
from collections.abc import AsyncIterator, Awaitable, Callable
from dataclasses import dataclass, field
from typing import Any, Literal, Protocol
from uuid import UUID

import httpx
from pydantic import BaseModel, ConfigDict, Field, SecretStr, field_validator

from app.core.errors import OrchestratorError

MODEL_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
MAX_RESPONSE_BYTES = 8 * 1024 * 1024
_RETRYABLE_STATUS = frozenset({500, 502, 503, 504, 529})


# ---- errors ----------------------------------------------------------------------------------------


class ProviderError(OrchestratorError):
    status = 502
    code = "provider-error"
    default_message = "The model provider could not complete the request."


class ProviderAuthenticationError(ProviderError):
    code = "provider-authentication"
    default_message = "The provider rejected the credential."


class ProviderRateLimitError(ProviderError):
    status = 429
    code = "provider-rate-limit"
    default_message = "The provider is rate limiting requests."

    def __init__(self, message: str | None = None, retry_after: float | None = None) -> None:
        super().__init__(message)
        self.retry_after = retry_after


class ProviderTimeoutError(ProviderError):
    status = 504
    code = "provider-timeout"
    default_message = "The provider did not answer in time."


class ProviderUnavailableError(ProviderError):
    status = 503
    code = "provider-unavailable"
    default_message = "The provider is unavailable."


class ProviderBadRequestError(ProviderError):
    status = 422
    code = "provider-bad-request"
    default_message = "The provider rejected the request."


class ProviderResponseError(ProviderError):
    code = "provider-invalid-response"
    default_message = "The provider returned an unexpected response."


# ---- contracts -------------------------------------------------------------------------------------


class ChatMessage(BaseModel):
    model_config = ConfigDict(frozen=True)
    role: Literal["system", "user", "assistant"]
    content: str = Field(min_length=1, max_length=200_000)


class GenerationRequest(BaseModel):
    model_config = ConfigDict(frozen=True)
    model: str
    messages: list[ChatMessage] = Field(min_length=1, max_length=200)
    max_output_tokens: int = Field(default=1024, ge=1, le=128_000)
    temperature: float | None = Field(default=None, ge=0.0, le=2.0)

    @field_validator("model")
    @classmethod
    def _valid_model_id(cls, value: str) -> str:
        if not MODEL_ID_PATTERN.fullmatch(value):
            raise ValueError("invalid model identifier")
        return value


class Usage(BaseModel):
    model_config = ConfigDict(frozen=True)
    input_tokens: int = 0
    output_tokens: int = 0


class GenerationResponse(BaseModel):
    model_config = ConfigDict(frozen=True)
    model: str
    text: str
    usage: Usage = Usage()
    finish_reason: str | None = None


class StreamEvent(BaseModel):
    model_config = ConfigDict(frozen=True)
    kind: Literal["delta", "usage", "done"]
    text: str = ""
    usage: Usage | None = None
    finish_reason: str | None = None


class ModelInfo(BaseModel):
    model_config = ConfigDict(frozen=True)
    id: str
    display_name: str


@dataclass(frozen=True)
class ProviderScope:
    """Whose credential to use. Both ids are validated by the Core API, never trusted from clients."""

    organization_id: UUID
    connection_id: UUID


@dataclass(frozen=True)
class RetryPolicy:
    max_retries: int = 2
    base_delay: float = 0.5
    max_delay: float = 20.0


class CredentialResolver(Protocol):
    """Trusted backend-to-backend resolution of a provider credential (implemented in C1 by the Core API)."""

    async def resolve(self, scope: ProviderScope) -> SecretStr: ...


@dataclass(frozen=True)
class RequestSpec:
    method: str
    path: str
    json_body: dict[str, Any] | None = None
    params: dict[str, str | int] = field(default_factory=dict)


# ---- adapter engine ----------------------------------------------------------------------------------


class ProviderAdapter(ABC):
    """Common asynchronous interface of Anthropic, OpenAI and Gemini."""

    kind: str

    def __init__(
        self,
        http: httpx.AsyncClient,
        credentials: CredentialResolver,
        *,
        retry: RetryPolicy = RetryPolicy(),
        timeout: float = 60.0,
        sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
    ) -> None:
        self._http = http  # configured with the fixed official base URL
        self._credentials = credentials
        self._retry = retry
        self._timeout = timeout
        self._sleep = sleep

    # -- provider specific hooks --
    @abstractmethod
    def _auth_headers(self, key: str) -> dict[str, str]: ...
    @abstractmethod
    def _generate_spec(self, request: GenerationRequest) -> RequestSpec: ...
    @abstractmethod
    def _stream_spec(self, request: GenerationRequest) -> RequestSpec: ...
    @abstractmethod
    def _parse_generation(self, request: GenerationRequest, payload: dict[str, Any]) -> GenerationResponse: ...
    @abstractmethod
    def _parse_stream_chunk(self, payload: dict[str, Any]) -> list[StreamEvent]: ...
    @abstractmethod
    def _models_spec(self) -> RequestSpec: ...
    @abstractmethod
    def _parse_models(self, payload: dict[str, Any]) -> list[ModelInfo]: ...

    def _is_auth_failure(self, status: int, body: bytes) -> bool:
        return status in (401, 403)

    # -- public API --
    async def generate(self, scope: ProviderScope, request: GenerationRequest) -> GenerationResponse:
        payload = await self._call_json(scope, self._generate_spec(request))
        return self._parse_generation(request, payload)

    async def list_models(self, scope: ProviderScope) -> list[ModelInfo]:
        """Official, non-billable catalog call: also the connectivity check used by smoke tests."""
        payload = await self._call_json(scope, self._models_spec())
        return self._parse_models(payload)

    async def stream(self, scope: ProviderScope, request: GenerationRequest) -> AsyncIterator[StreamEvent]:
        spec = self._stream_spec(request)
        key = await self._credentials.resolve(scope)
        attempt = 0
        while True:
            try:
                async with self._http.stream(
                    spec.method, spec.path, json=spec.json_body, params=spec.params or None,
                    headers=self._headers(key), timeout=self._timeout,
                ) as response:
                    if response.status_code == 200:
                        async for line in response.aiter_lines():
                            for event in self._events_from_line(line):
                                yield event
                        return
                    body = await response.aread()
                    failure = self._failure(response.status_code, body, response.headers)
            except httpx.TimeoutException as exc:
                failure = ProviderTimeoutError()
                failure.__cause__ = exc
            except httpx.HTTPError as exc:
                failure = ProviderUnavailableError()
                failure.__cause__ = exc
            if not self._should_retry(failure, attempt):
                raise failure
            await self._backoff(failure, attempt)
            attempt += 1

    # -- internals --
    def _headers(self, key: SecretStr) -> dict[str, str]:
        return {"Accept": "application/json", **self._auth_headers(key.get_secret_value())}

    def _events_from_line(self, line: str) -> list[StreamEvent]:
        if not line.startswith("data:"):
            return []
        data = line[5:].strip()
        if not data or data == "[DONE]":
            return []
        try:
            payload = json.loads(data)
        except ValueError as exc:
            raise ProviderResponseError() from exc
        if not isinstance(payload, dict):
            raise ProviderResponseError()
        return self._parse_stream_chunk(payload)

    async def _call_json(self, scope: ProviderScope, spec: RequestSpec) -> dict[str, Any]:
        key = await self._credentials.resolve(scope)
        attempt = 0
        while True:
            try:
                response = await self._http.request(
                    spec.method, spec.path, json=spec.json_body, params=spec.params or None,
                    headers=self._headers(key), timeout=self._timeout,
                )
                if response.status_code == 200:
                    return self._decode(response)
                failure = self._failure(response.status_code, response.content, response.headers)
            except httpx.TimeoutException as exc:
                failure = ProviderTimeoutError()
                failure.__cause__ = exc
            except httpx.HTTPError as exc:
                failure = ProviderUnavailableError()
                failure.__cause__ = exc
            if not self._should_retry(failure, attempt):
                raise failure
            await self._backoff(failure, attempt)
            attempt += 1

    @staticmethod
    def _decode(response: httpx.Response) -> dict[str, Any]:
        if len(response.content) > MAX_RESPONSE_BYTES:
            raise ProviderResponseError()
        try:
            payload = response.json()
        except ValueError as exc:
            raise ProviderResponseError() from exc
        if not isinstance(payload, dict):
            raise ProviderResponseError()
        return payload

    def _failure(self, status: int, body: bytes, headers: httpx.Headers) -> ProviderError:
        """Normalizes by status only: the body is inspected for a marker but never copied."""
        if self._is_auth_failure(status, body):
            return ProviderAuthenticationError()
        if status == 429:
            return ProviderRateLimitError(retry_after=_retry_after(headers))
        if status in _RETRYABLE_STATUS or status >= 500:
            return ProviderUnavailableError()
        if status in (400, 404, 413, 422):
            return ProviderBadRequestError()
        return ProviderError()

    def _should_retry(self, failure: ProviderError, attempt: int) -> bool:
        retryable = isinstance(failure, ProviderRateLimitError | ProviderUnavailableError | ProviderTimeoutError)
        return retryable and attempt < self._retry.max_retries

    async def _backoff(self, failure: ProviderError, attempt: int) -> None:
        delay = min(self._retry.base_delay * (2**attempt) * (0.5 + random.random()), self._retry.max_delay)  # noqa: S311
        if isinstance(failure, ProviderRateLimitError) and failure.retry_after is not None:
            delay = min(max(delay, failure.retry_after), self._retry.max_delay)
        await self._sleep(delay)


def _retry_after(headers: httpx.Headers) -> float | None:
    raw = headers.get("retry-after")
    try:
        return max(float(raw), 0.0) if raw is not None else None
    except ValueError:
        return None
