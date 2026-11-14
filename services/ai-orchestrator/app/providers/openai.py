"""OpenAI adapter (Chat Completions API). Auth: ``Authorization: Bearer``."""

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


class OpenAIAdapter(ProviderAdapter):
    kind = "OPENAI"

    def _auth_headers(self, key: str) -> dict[str, str]:
        return {"Authorization": f"Bearer {key}"}

    @staticmethod
    def _body(request: GenerationRequest, *, stream: bool) -> dict[str, Any]:
        body: dict[str, Any] = {
            "model": request.model,
            "messages": [{"role": m.role, "content": m.content} for m in request.messages],
            "max_completion_tokens": request.max_output_tokens,
        }
        if request.temperature is not None:
            body["temperature"] = request.temperature
        if stream:
            body["stream"] = True
            body["stream_options"] = {"include_usage": True}
        return body

    def _generate_spec(self, request: GenerationRequest) -> RequestSpec:
        return RequestSpec("POST", "/v1/chat/completions", self._body(request, stream=False))

    def _stream_spec(self, request: GenerationRequest) -> RequestSpec:
        return RequestSpec("POST", "/v1/chat/completions", self._body(request, stream=True))

    def _parse_generation(self, request: GenerationRequest, payload: dict[str, Any]) -> GenerationResponse:
        try:
            choice = payload["choices"][0]
            usage = payload.get("usage") or {}
            return GenerationResponse(
                model=str(payload.get("model", request.model)),
                text=choice["message"].get("content") or "",
                usage=Usage(input_tokens=int(usage.get("prompt_tokens", 0)), output_tokens=int(usage.get("completion_tokens", 0))),
                finish_reason=choice.get("finish_reason"),
            )
        except (KeyError, IndexError, TypeError, ValueError) as exc:
            raise ProviderResponseError() from exc

    def _parse_stream_chunk(self, payload: dict[str, Any]) -> list[StreamEvent]:
        events: list[StreamEvent] = []
        for choice in payload.get("choices") or []:
            text = (choice.get("delta") or {}).get("content")
            if isinstance(text, str) and text:
                events.append(StreamEvent(kind="delta", text=text))
            if choice.get("finish_reason"):
                events.append(StreamEvent(kind="done", finish_reason=choice["finish_reason"]))
        usage = payload.get("usage")
        if isinstance(usage, dict):
            events.append(
                StreamEvent(
                    kind="usage",
                    usage=Usage(input_tokens=int(usage.get("prompt_tokens", 0)), output_tokens=int(usage.get("completion_tokens", 0))),
                )
            )
        return events

    def _models_spec(self) -> RequestSpec:
        return RequestSpec("GET", "/v1/models")

    def _parse_models(self, payload: dict[str, Any]) -> list[ModelInfo]:
        try:
            return [ModelInfo(id=m["id"], display_name=m["id"]) for m in payload["data"]]
        except (KeyError, TypeError) as exc:
            raise ProviderResponseError() from exc
