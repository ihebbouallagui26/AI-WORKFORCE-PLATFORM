"""Google Gemini adapter (Gemini API ``generateContent``). Auth: ``x-goog-api-key`` header.

An invalid key answers HTTP 400 with ``API_KEY_INVALID`` (not 401): the adapter recognizes that
marker without ever copying the response body anywhere.
"""

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


class GoogleAdapter(ProviderAdapter):
    kind = "GEMINI"

    def _auth_headers(self, key: str) -> dict[str, str]:
        return {"x-goog-api-key": key}

    def _is_auth_failure(self, status: int, body: bytes) -> bool:
        return status in (401, 403) or (status == 400 and b"API_KEY_INVALID" in body)

    @staticmethod
    def _model_path(request: GenerationRequest) -> str:
        # The id is validated (no slash) and cannot alter the path.
        return f"/v1beta/models/{request.model.removeprefix('models/')}"

    @staticmethod
    def _body(request: GenerationRequest) -> dict[str, Any]:
        system = "\n\n".join(m.content for m in request.messages if m.role == "system")
        body: dict[str, Any] = {
            "contents": [
                {"role": "model" if m.role == "assistant" else "user", "parts": [{"text": m.content}]}
                for m in request.messages
                if m.role != "system"
            ],
            "generationConfig": {"maxOutputTokens": request.max_output_tokens},
        }
        if system:
            body["systemInstruction"] = {"parts": [{"text": system}]}
        if request.temperature is not None:
            body["generationConfig"]["temperature"] = request.temperature
        return body

    def _generate_spec(self, request: GenerationRequest) -> RequestSpec:
        return RequestSpec("POST", f"{self._model_path(request)}:generateContent", self._body(request))

    def _stream_spec(self, request: GenerationRequest) -> RequestSpec:
        return RequestSpec("POST", f"{self._model_path(request)}:streamGenerateContent", self._body(request), {"alt": "sse"})

    @staticmethod
    def _text(payload: dict[str, Any]) -> tuple[str, str | None]:
        candidate = payload["candidates"][0]
        parts = (candidate.get("content") or {}).get("parts") or []
        return "".join(p.get("text", "") for p in parts), candidate.get("finishReason")

    @staticmethod
    def _usage(payload: dict[str, Any]) -> Usage | None:
        meta = payload.get("usageMetadata")
        if not isinstance(meta, dict):
            return None
        return Usage(input_tokens=int(meta.get("promptTokenCount", 0)), output_tokens=int(meta.get("candidatesTokenCount", 0)))

    def _parse_generation(self, request: GenerationRequest, payload: dict[str, Any]) -> GenerationResponse:
        try:
            text, reason = self._text(payload)
            return GenerationResponse(
                model=str(payload.get("modelVersion", request.model)),
                text=text,
                usage=self._usage(payload) or Usage(),
                finish_reason=reason,
            )
        except (KeyError, IndexError, TypeError, ValueError) as exc:
            raise ProviderResponseError() from exc

    def _parse_stream_chunk(self, payload: dict[str, Any]) -> list[StreamEvent]:
        events: list[StreamEvent] = []
        try:
            if payload.get("candidates"):
                text, reason = self._text(payload)
                if text:
                    events.append(StreamEvent(kind="delta", text=text))
                if reason:
                    events.append(StreamEvent(kind="done", finish_reason=reason))
        except (KeyError, IndexError, TypeError) as exc:
            raise ProviderResponseError() from exc
        usage = self._usage(payload)
        if usage is not None:
            events.append(StreamEvent(kind="usage", usage=usage))
        return events

    def _models_spec(self) -> RequestSpec:
        return RequestSpec("GET", "/v1beta/models", params={"pageSize": 100})

    def _parse_models(self, payload: dict[str, Any]) -> list[ModelInfo]:
        try:
            return [
                ModelInfo(id=m["name"].removeprefix("models/"), display_name=str(m.get("displayName") or m["name"]))
                for m in payload["models"]
            ]
        except (KeyError, TypeError) as exc:
            raise ProviderResponseError() from exc
