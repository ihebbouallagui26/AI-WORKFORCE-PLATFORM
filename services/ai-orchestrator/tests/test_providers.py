"""Mocked adapter tests. No network and no billable call: every provider endpoint is simulated."""

import json
import uuid

import httpx
import pytest
import respx
from pydantic import SecretStr, ValidationError

from app.providers.anthropic import AnthropicAdapter
from app.providers.base import (
    ChatMessage,
    GenerationRequest,
    ProviderAuthenticationError,
    ProviderBadRequestError,
    ProviderRateLimitError,
    ProviderResponseError,
    ProviderScope,
    ProviderTimeoutError,
    ProviderUnavailableError,
    RetryPolicy,
)
from app.providers.google import GoogleAdapter
from app.providers.openai import OpenAIAdapter

KEY = "sk-test-SECRET-KEY-1234567890"
SCOPE = ProviderScope(uuid.uuid4(), uuid.uuid4())
REQUEST = GenerationRequest(
    model="model-x", messages=[ChatMessage(role="system", content="Be brief."), ChatMessage(role="user", content="Hi")], max_output_tokens=50
)


class FixedResolver:
    def __init__(self) -> None:
        self.calls = 0

    async def resolve(self, scope):
        self.calls += 1
        return SecretStr(KEY)


CASES = {
    "anthropic": dict(
        cls=AnthropicAdapter, base="https://anthropic.test", gen=("POST", "/v1/messages"), models=("GET", "/v1/models"),
        ok={"model": "model-x", "content": [{"type": "text", "text": "Hello"}], "usage": {"input_tokens": 5, "output_tokens": 2}, "stop_reason": "end_turn"},
        models_ok={"data": [{"id": "model-a", "display_name": "Model A"}]},
        auth_header=("x-api-key", KEY),
        auth_failure=httpx.Response(401, json={"error": {"message": f"bad key {KEY}"}}),
    ),
    "openai": dict(
        cls=OpenAIAdapter, base="https://openai.test", gen=("POST", "/v1/chat/completions"), models=("GET", "/v1/models"),
        ok={"model": "model-x", "choices": [{"message": {"content": "Hello"}, "finish_reason": "stop"}], "usage": {"prompt_tokens": 5, "completion_tokens": 2}},
        models_ok={"data": [{"id": "model-a"}]},
        auth_header=("authorization", f"Bearer {KEY}"),
        auth_failure=httpx.Response(401, json={"error": {"message": f"Incorrect API key provided: {KEY}"}}),
    ),
    "gemini": dict(
        cls=GoogleAdapter, base="https://google.test", gen=("POST", "/v1beta/models/model-x:generateContent"), models=("GET", "/v1beta/models"),
        ok={"candidates": [{"content": {"parts": [{"text": "Hello"}]}, "finishReason": "STOP"}], "usageMetadata": {"promptTokenCount": 5, "candidatesTokenCount": 2}},
        models_ok={"models": [{"name": "models/model-a", "displayName": "Model A"}]},
        auth_header=("x-goog-api-key", KEY),
        auth_failure=httpx.Response(400, json={"error": {"status": "INVALID_ARGUMENT", "details": [{"reason": "API_KEY_INVALID", "key": KEY}]}}),
    ),
}


@pytest.fixture(params=list(CASES))
def case(request):
    return CASES[request.param]


@pytest.fixture
async def build(case):
    made: list[httpx.AsyncClient] = []
    sleeps: list[float] = []

    async def sleep(delay: float) -> None:
        sleeps.append(delay)

    def factory(retries=2):
        http = httpx.AsyncClient(base_url=case["base"])
        made.append(http)
        resolver = FixedResolver()
        return case["cls"](http, resolver, retry=RetryPolicy(max_retries=retries, base_delay=0.01), sleep=sleep), resolver, sleeps

    yield factory
    for http in made:
        await http.aclose()


def route(case, mock, spec="gen"):
    method, path = case[spec]
    return mock.route(method=method, url=case["base"] + path)


@respx.mock
async def test_generate_normalizes_text_usage_and_finish_reason(case, build):
    adapter, resolver, _ = build()
    r = route(case, respx.mock).respond(json=case["ok"])
    result = await adapter.generate(SCOPE, REQUEST)
    assert result.text == "Hello" and result.usage.input_tokens == 5 and result.usage.output_tokens == 2 and result.finish_reason
    name, value = case["auth_header"]
    assert r.calls.last.request.headers[name] == value and resolver.calls == 1
    body = json.loads(r.calls.last.request.content)
    assert "Be brief." in json.dumps(body) and KEY not in json.dumps(body)


@respx.mock
async def test_models_are_listed_with_the_official_catalog_call(case, build):
    adapter, _, _ = build()
    route(case, respx.mock, "models").respond(json=case["models_ok"])
    models = await adapter.list_models(SCOPE)
    assert models[0].id == "model-a"


@respx.mock
async def test_authentication_errors_are_normalized_and_do_not_leak_the_key(case, build):
    adapter, _, sleeps = build()
    route(case, respx.mock).mock(return_value=case["auth_failure"])
    with pytest.raises(ProviderAuthenticationError) as raised:
        await adapter.generate(SCOPE, REQUEST)
    assert KEY not in str(raised.value) and KEY not in repr(raised.value) and sleeps == []


@respx.mock
async def test_rate_limits_are_retried_honouring_retry_after(case, build):
    adapter, _, sleeps = build()
    route(case, respx.mock).mock(side_effect=[httpx.Response(429, headers={"retry-after": "3"}), httpx.Response(200, json=case["ok"])])
    assert (await adapter.generate(SCOPE, REQUEST)).text == "Hello"
    assert sleeps and sleeps[0] >= 3


@respx.mock
async def test_exhausted_rate_limit_surfaces_with_retry_after(case, build):
    adapter, _, sleeps = build(retries=1)
    route(case, respx.mock).respond(429, headers={"retry-after": "7"}, text=f"slow down {KEY}")
    with pytest.raises(ProviderRateLimitError) as raised:
        await adapter.generate(SCOPE, REQUEST)
    assert raised.value.retry_after == 7 and len(sleeps) == 1 and KEY not in str(raised.value)


@respx.mock
async def test_timeouts_and_server_errors(case, build):
    adapter, _, _ = build(retries=1)
    route(case, respx.mock).mock(side_effect=httpx.ReadTimeout("slow"))
    with pytest.raises(ProviderTimeoutError):
        await adapter.generate(SCOPE, REQUEST)
    respx.reset()
    route(case, respx.mock).respond(503)
    with pytest.raises(ProviderUnavailableError):
        await adapter.generate(SCOPE, REQUEST)


@respx.mock
async def test_client_errors_are_not_retried(case, build):
    adapter, _, sleeps = build()
    route(case, respx.mock).respond(400, json={"error": "invalid"})
    with pytest.raises(ProviderBadRequestError):
        await adapter.generate(SCOPE, REQUEST)
    assert sleeps == []


@respx.mock
@pytest.mark.parametrize("payload", [{"unexpected": True}, []])
async def test_unexpected_payloads_are_response_errors(case, build, payload):
    adapter, _, _ = build()
    route(case, respx.mock).respond(json=payload)
    with pytest.raises(ProviderResponseError):
        await adapter.generate(SCOPE, REQUEST)


async def test_adapters_do_not_retain_the_credential(case, build):
    adapter, _, _ = build()
    with respx.mock:
        route(case, respx.mock).respond(json=case["ok"])
        await adapter.generate(SCOPE, REQUEST)
    assert KEY not in repr(vars(adapter)) and KEY not in repr(vars(adapter._http))


@pytest.mark.parametrize("model", ["", "../../etc/passwd", "a b", "x" * 200, "models/../x", "a?b=c"])
def test_model_ids_cannot_alter_the_request_path(model):
    with pytest.raises(ValidationError):
        GenerationRequest(model=model, messages=[ChatMessage(role="user", content="x")])


def test_no_model_name_is_hardcoded():
    import pathlib

    source = "".join(p.read_text(encoding="utf-8") for p in pathlib.Path("app/providers").glob("*.py"))
    for marker in ("claude-", "gpt-", "gemini-1", "gemini-2", "o1-", "o3-"):
        assert marker not in source


# ---- streaming --------------------------------------------------------------------------------------

SSE = {
    "anthropic": (
        'event: message_start\ndata: {"type":"message_start","message":{"usage":{"input_tokens":5}}}\n\n'
        'data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"Hel"}}\n\n'
        'data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"lo"}}\n\n'
        'data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}\n\n'
        'data: {"type":"message_stop"}\n\n'
    ),
    "openai": (
        'data: {"choices":[{"delta":{"content":"Hel"}}]}\n\n'
        'data: {"choices":[{"delta":{"content":"lo"},"finish_reason":"stop"}]}\n\n'
        'data: {"choices":[],"usage":{"prompt_tokens":5,"completion_tokens":2}}\n\n'
        "data: [DONE]\n\n"
    ),
    "gemini": (
        'data: {"candidates":[{"content":{"parts":[{"text":"Hel"}]}}]}\n\n'
        'data: {"candidates":[{"content":{"parts":[{"text":"lo"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":2}}\n\n'
    ),
}


@respx.mock
@pytest.mark.parametrize("name", ["anthropic", "openai", "gemini"])
async def test_streaming_yields_deltas_and_usage(name):
    case = CASES[name]
    path = case["gen"][1] if name != "gemini" else "/v1beta/models/model-x:streamGenerateContent"
    respx.post(case["base"] + path).respond(200, text=SSE[name], headers={"content-type": "text/event-stream"})
    async with httpx.AsyncClient(base_url=case["base"]) as http:
        adapter = case["cls"](http, FixedResolver())
        events = [event async for event in adapter.stream(SCOPE, REQUEST)]
    assert "".join(e.text for e in events if e.kind == "delta") == "Hello"
    usage = [e.usage for e in events if e.kind == "usage" and e.usage]
    assert sum(u.input_tokens for u in usage) == 5 and sum(u.output_tokens for u in usage) == 2


@respx.mock
async def test_stream_errors_are_normalized_before_any_event():
    case = CASES["openai"]
    respx.post(case["base"] + "/v1/chat/completions").respond(401, text=f"bad {KEY}")
    async with httpx.AsyncClient(base_url=case["base"]) as http:
        adapter = OpenAIAdapter(http, FixedResolver())
        with pytest.raises(ProviderAuthenticationError) as raised:
            [event async for event in adapter.stream(SCOPE, REQUEST)]
    assert KEY not in str(raised.value)
