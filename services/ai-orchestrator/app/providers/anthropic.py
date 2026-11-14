"""Anthropic Claude adapter (Messages API). Auth: ``x-api-key`` + ``anthropic-version``."""

from __future__ import annotations

from typing import Any

from app.providers.base import (
    GenerationRequest,
    GenerationResponse,
    ModelInfo,
    ProviderAdapter,
    ProviderResponseError,
    RequestSpec,
    StreamEvent,
    Usage,
)

ANTHROPIC_VERSION = "2023-06-01"


class AnthropicAdapter(ProviderAdapter):
    kind = "ANTHROPIC"

    def _auth_headers(self, key: str) -> dict[str, str]:
        return {"x-api-key": key, "anthropic-version": ANTHROPIC_VERSION}

    @staticmethod
    def _body(request: GenerationRequest, *, stream: bool) -> dict[str, Any]:
        system = "\n\n".join(m.content for m in request.messages if m.role == "system")
        body: dict[str, Any] = {
            "model": request.model,
            "max_tokens": request.max_output_tokens,
            "messages": [{"role": m.role, "content": m.content} for m in request.messages if m.role != "system"],
        }
        if system:
            body["system"] = system
        if request.temperature is not None:
            body["temperature"] = request.temperature
        if stream:
            body["stream"] = True
        return body

    def _generate_spec(self, request: GenerationRequest) -> RequestSpec:
        return RequestSpec("POST", "/v1/messages", self._body(request, stream=False))

    def _stream_spec(self, request: GenerationRequest) -> RequestSpec:
        return RequestSpec("POST", "/v1/messages", self._body(request, stream=True))

    def _parse_generation(self, request: GenerationRequest, payload: dict[str, Any]) -> GenerationResponse:
        try:
            text = "".join(b["text"] for b in payload["content"] if b.get("type") == "text")
            usage = payload.get("usage") or {}
            return GenerationResponse(
                model=str(payload.get("model", request.model)),
                text=text,
                usage=Usage(input_tokens=int(usage.get("input_tokens", 0)), output_tokens=int(usage.get("output_tokens", 0))),
                finish_reason=payload.get("stop_reason"),
            )
        except (KeyError, TypeError, ValueError) as exc:
            raise ProviderResponseError() from exc

    def _parse_stream_chunk(self, payload: dict[str, Any]) -> list[StreamEvent]:
        kind = payload.get("type")
        if kind == "content_block_delta":
            text = (payload.get("delta") or {}).get("text")
            return [StreamEvent(kind="delta", text=text)] if isinstance(text, str) else []
        if kind == "message_start":
            usage = ((payload.get("message") or {}).get("usage")) or {}
            return [StreamEvent(kind="usage", usage=Usage(input_tokens=int(usage.get("input_tokens", 0))))]
        if kind == "message_delta":
            usage = payload.get("usage") or {}
            reason = (payload.get("delta") or {}).get("stop_reason")
            return [StreamEvent(kind="usage", usage=Usage(output_tokens=int(usage.get("output_tokens", 0))), finish_reason=reason)]
        if kind == "message_stop":
            return [StreamEvent(kind="done")]
        if kind == "error":
            raise ProviderResponseError()
        return []

    def _models_spec(self) -> RequestSpec:
        return RequestSpec("GET", "/v1/models", params={"limit": 100})

    def _parse_models(self, payload: dict[str, Any]) -> list[ModelInfo]:
        try:
            return [ModelInfo(id=m["id"], display_name=str(m.get("display_name") or m["id"])) for m in payload["data"]]
        except (KeyError, TypeError) as exc:
            raise ProviderResponseError() from exc
