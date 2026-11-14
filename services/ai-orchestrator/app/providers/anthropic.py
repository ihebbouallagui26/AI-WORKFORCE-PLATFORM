"""Anthropic Claude adapter (Messages API). Auth: ``x-api-key`` + ``anthropic-version``."""

from __future__ import annotations

from typing import Any

from app.providers.base import (
    GenerationRequest,
    GenerationResponse,
    ModelInfo,
    ProviderAdapter,
    ProviderResponseError,
    ProviderScope,
    RequestSpec,
    StreamEvent,
    TextBlock,
    ToolResultBlock,
    ToolUseBlock,
    TurnRequest,
    TurnResponse,
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

    @staticmethod
    def _turn_body(request: TurnRequest) -> dict[str, Any]:
        messages: list[dict[str, Any]] = []
        for message in request.messages:
            if isinstance(message.content, str):
                messages.append({"role": message.role, "content": message.content})
                continue
            blocks: list[dict[str, Any]] = []
            for block in message.content:
                if isinstance(block, TextBlock):
                    blocks.append({"type": "text", "text": block.text})
                elif isinstance(block, ToolUseBlock):
                    blocks.append({"type": "tool_use", "id": block.id, "name": block.name, "input": block.input})
                elif isinstance(block, ToolResultBlock):
                    entry: dict[str, Any] = {"type": "tool_result", "tool_use_id": block.tool_use_id, "content": block.content}
                    if block.is_error:
                        entry["is_error"] = True
                    blocks.append(entry)
            messages.append({"role": message.role, "content": blocks})
        body: dict[str, Any] = {"model": request.model, "max_tokens": request.max_output_tokens, "messages": messages}
        if request.system:
            body["system"] = request.system
        if request.tools:
            body["tools"] = [{"name": t.name, "description": t.description, "input_schema": t.input_schema} for t in request.tools]
        return body

    async def generate_turn(self, scope: ProviderScope, request: TurnRequest) -> TurnResponse:
        """One Messages API round with tools. The caller validates and authorizes every ``tool_use`` it returns."""
        payload = await self._call_json(scope, RequestSpec("POST", "/v1/messages", self._turn_body(request)))
        try:
            blocks: list[TextBlock | ToolUseBlock] = []
            for raw in payload["content"]:
                kind = raw.get("type")
                if kind == "text":
                    blocks.append(TextBlock(text=str(raw.get("text", ""))))
                elif kind == "tool_use":
                    arguments = raw.get("input")
                    blocks.append(
                        ToolUseBlock(id=str(raw["id"]), name=str(raw["name"]), input=arguments if isinstance(arguments, dict) else {})
                    )
            usage = payload.get("usage") or {}
            return TurnResponse(
                model=str(payload.get("model", request.model)),
                blocks=blocks,
                usage=Usage(input_tokens=int(usage.get("input_tokens", 0)), output_tokens=int(usage.get("output_tokens", 0))),
                stop_reason=payload.get("stop_reason"),
            )
        except (KeyError, TypeError, ValueError) as exc:
            raise ProviderResponseError() from exc

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
