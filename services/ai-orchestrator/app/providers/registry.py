"""Builds the three adapters from settings: fixed official base URLs, one shared retry policy."""

from __future__ import annotations

import httpx

from app.core.config import Settings
from app.providers.anthropic import AnthropicAdapter
from app.providers.base import CredentialResolver, ProviderAdapter, RetryPolicy
from app.providers.google import GoogleAdapter
from app.providers.openai import OpenAIAdapter

PROVIDER_KINDS = ("ANTHROPIC", "OPENAI", "GEMINI")


class ProviderRegistry:
    """Owns one HTTP client per provider (separate base URLs, no redirects, bounded timeouts)."""

    def __init__(self, settings: Settings, credentials: CredentialResolver) -> None:
        retry = RetryPolicy(max_retries=settings.provider_max_retries)
        timeout = settings.provider_timeout_seconds
        self._retry = retry
        self._timeout = timeout
        self._clients = {
            "ANTHROPIC": httpx.AsyncClient(base_url=settings.anthropic_base_url, follow_redirects=False, timeout=timeout),
            "OPENAI": httpx.AsyncClient(base_url=settings.openai_base_url, follow_redirects=False, timeout=timeout),
            "GEMINI": httpx.AsyncClient(base_url=settings.google_base_url, follow_redirects=False, timeout=timeout),
        }
        self._adapters: dict[str, ProviderAdapter] = {
            "ANTHROPIC": AnthropicAdapter(self._clients["ANTHROPIC"], credentials, retry=retry, timeout=timeout),
            "OPENAI": OpenAIAdapter(self._clients["OPENAI"], credentials, retry=retry, timeout=timeout),
            "GEMINI": GoogleAdapter(self._clients["GEMINI"], credentials, retry=retry, timeout=timeout),
        }

    def get(self, kind: str) -> ProviderAdapter:
        return self._adapters[kind]

    def adapter_with(self, kind: str, credentials: CredentialResolver) -> ProviderAdapter:
        """An adapter bound to an execution-scoped credential resolver (shares the HTTP client only).

        The shared adapters above fail closed; a run uses this method so its credential comes from a
        resolver that exists only for that execution and holds no secret itself."""
        adapters = {"ANTHROPIC": AnthropicAdapter, "OPENAI": OpenAIAdapter, "GEMINI": GoogleAdapter}
        return adapters[kind](self._clients[kind], credentials, retry=self._retry, timeout=self._timeout)

    async def aclose(self) -> None:
        for client in self._clients.values():
            await client.aclose()
