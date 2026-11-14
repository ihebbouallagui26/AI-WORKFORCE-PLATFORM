"""OPTIONAL live connectivity smoke tests (never run in CI, never required).

They run only when ALL of these hold: AIO_LIVE_PROVIDER_TESTS=1 and a real key for the provider in
AIO_LIVE_ANTHROPIC_KEY / AIO_LIVE_OPENAI_KEY / AIO_LIVE_GEMINI_KEY. They call the official,
NON-BILLABLE model catalog endpoint only: no generation is ever requested. Everything else in the
suite is mocked and proves nothing about real provider connectivity.
"""

import os
import uuid

import httpx
import pytest
from pydantic import SecretStr

from app.providers.anthropic import AnthropicAdapter
from app.providers.base import ProviderScope
from app.providers.google import GoogleAdapter
from app.providers.openai import OpenAIAdapter

pytestmark = pytest.mark.live_provider
ENABLED = os.environ.get("AIO_LIVE_PROVIDER_TESTS") == "1"
SCOPE = ProviderScope(uuid.uuid4(), uuid.uuid4())

LIVE = {
    "anthropic": ("AIO_LIVE_ANTHROPIC_KEY", AnthropicAdapter, "https://api.anthropic.com"),
    "openai": ("AIO_LIVE_OPENAI_KEY", OpenAIAdapter, "https://api.openai.com"),
    "gemini": ("AIO_LIVE_GEMINI_KEY", GoogleAdapter, "https://generativelanguage.googleapis.com"),
}


class EnvResolver:
    def __init__(self, key: str) -> None:
        self._key = key

    async def resolve(self, scope):
        return SecretStr(self._key)


@pytest.mark.parametrize("name", list(LIVE))
async def test_live_model_catalog(name):
    variable, adapter_class, base = LIVE[name]
    key = os.environ.get(variable, "")
    if not ENABLED or not key:
        pytest.skip(f"live smoke test disabled (set AIO_LIVE_PROVIDER_TESTS=1 and {variable})")
    async with httpx.AsyncClient(base_url=base, timeout=30) as http:
        models = await adapter_class(http, EnvResolver(key)).list_models(SCOPE)
    assert models, "the provider returned an empty model catalog"


GENERATION_ENABLED = ENABLED and os.environ.get("AIO_LIVE_ANTHROPIC_GENERATION") == "1"


async def test_live_anthropic_generation():
    """BILLABLE (a few tokens): needs a second explicit opt-in, a real key and a real model id.

    AIO_LIVE_PROVIDER_TESTS=1 AIO_LIVE_ANTHROPIC_GENERATION=1 AIO_LIVE_ANTHROPIC_KEY=... AIO_LIVE_ANTHROPIC_MODEL=<model id>
    """
    key = os.environ.get("AIO_LIVE_ANTHROPIC_KEY", "")
    model = os.environ.get("AIO_LIVE_ANTHROPIC_MODEL", "")
    if not (GENERATION_ENABLED and key and model):
        pytest.skip("live generation test disabled (billable): see the docstring for the opt-in variables")
    from app.providers.base import ChatMessage, GenerationRequest

    async with httpx.AsyncClient(base_url="https://api.anthropic.com", timeout=60) as http:
        response = await AnthropicAdapter(http, EnvResolver(key)).generate(
            SCOPE, GenerationRequest(model=model, messages=[ChatMessage(role="user", content="Reply with the single word: ok")], max_output_tokens=16)
        )
    assert response.text.strip() and response.usage.output_tokens > 0
