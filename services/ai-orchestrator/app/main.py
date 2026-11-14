"""FastAPI application factory and lifespan (startup and graceful shutdown of every resource)."""

from __future__ import annotations

from collections.abc import AsyncIterator
from contextlib import AsyncExitStack, asynccontextmanager

import httpx
from fastapi import FastAPI

from app import __version__
from app.api.errors import install_exception_handlers
from app.api.middleware import RequestContextMiddleware
from app.api.routes import health, runtime_info
from app.clients.core_api_client import CoreApiClient, ServiceTokenProvider
from app.core.config import Settings, get_settings
from app.core.errors import UnavailableError
from app.core.logging import configure_logging, get_logger
from app.core.security import JwtVerifier
from app.orchestration.checkpointer.postgres import open_postgres_checkpointer, ping
from app.orchestration.runtime import OrchestrationRuntime
from app.providers.base import ProviderScope
from app.providers.registry import ProviderRegistry

log = get_logger("lifespan")


class UnavailableCredentialResolver:
    """C0 has no credential resolution: a provider call fails closed until C1 adds the trusted
    Core API mechanism. Credentials are never read from the environment or from graph state."""

    async def resolve(self, scope: ProviderScope):  # noqa: ANN201
        raise UnavailableError("Provider credential resolution is not available in this version.")


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or get_settings()
    configure_logging(settings.log_level)

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        async with AsyncExitStack() as stack:
            core_http = httpx.AsyncClient(
                base_url=settings.core_api_base_url, timeout=settings.core_api_timeout_seconds, follow_redirects=False
            )
            idp_http = httpx.AsyncClient(timeout=settings.core_api_timeout_seconds, follow_redirects=False)
            stack.push_async_callback(core_http.aclose)
            stack.push_async_callback(idp_http.aclose)
            tokens = ServiceTokenProvider(idp_http, settings.token_url, settings.client_id, settings.client_secret)
            app.state.core_api = CoreApiClient(core_http, tokens, max_retries=settings.core_api_max_retries)
            app.state.verifier = JwtVerifier(settings, idp_http)

            providers = ProviderRegistry(settings, UnavailableCredentialResolver())
            stack.push_async_callback(providers.aclose)
            app.state.providers = providers

            app.state.checkpoint_probe = None
            app.state.runtime = None
            if settings.database_url is not None:
                saver = await stack.enter_async_context(open_postgres_checkpointer(settings))
                app.state.runtime = OrchestrationRuntime(saver, run_timeout_seconds=settings.run_timeout_seconds)

                async def probe() -> bool:
                    return await ping(saver)

                app.state.checkpoint_probe = probe
            log.info("started", environment=settings.environment)
            yield
            log.info("stopping")

    app = FastAPI(
        title="AI Workforce Orchestrator",
        version=__version__,
        lifespan=lifespan,
        # Internal service: no interactive documentation unless explicitly in development.
        docs_url="/docs" if settings.environment == "dev" else None,
        redoc_url=None,
        openapi_url="/openapi.json" if settings.environment == "dev" else None,
    )
    app.state.settings = settings
    app.state.checkpoint_probe = None
    install_exception_handlers(app)
    app.include_router(health.router)
    app.include_router(runtime_info.router)
    app.add_middleware(
        RequestContextMiddleware,
        timeout_seconds=settings.request_timeout_seconds,
        max_body_bytes=settings.max_request_bytes,
    )
    return app
