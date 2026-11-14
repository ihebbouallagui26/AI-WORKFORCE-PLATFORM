"""Centralized exception handling: every error is an RFC 9457 problem with a safe message."""

from __future__ import annotations

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.core.errors import OrchestratorError, problem
from app.core.logging import get_logger

log = get_logger("errors")
PROBLEM_JSON = "application/problem+json"


def _request_id(request: Request) -> str | None:
    state = request.scope.get("state") or {}
    return state.get("request_id")


def install_exception_handlers(app: FastAPI) -> None:
    @app.exception_handler(OrchestratorError)
    async def _domain(request: Request, exc: OrchestratorError) -> JSONResponse:
        if exc.status >= 500:
            log.error("request_failed", code=exc.code, status=exc.status)
        body = problem(exc.status, exc.code, exc.code.replace("-", " ").capitalize(), exc.message, _request_id(request))
        return JSONResponse(body, status_code=exc.status, media_type=PROBLEM_JSON)

    @app.exception_handler(RequestValidationError)
    async def _validation(request: Request, exc: RequestValidationError) -> JSONResponse:
        body = problem(422, "validation", "Validation failed", "The request is invalid.", _request_id(request))
        # Field paths and messages only: the submitted input is never echoed back.
        body["errors"] = [{"field": ".".join(str(p) for p in e["loc"]), "message": e["msg"]} for e in exc.errors()]
        return JSONResponse(body, status_code=422, media_type=PROBLEM_JSON)

    @app.exception_handler(StarletteHTTPException)
    async def _http(request: Request, exc: StarletteHTTPException) -> JSONResponse:
        title = {404: "Not found", 405: "Method not allowed"}.get(exc.status_code, "Request failed")
        body = problem(exc.status_code, "http", title, title + ".", _request_id(request))
        return JSONResponse(body, status_code=exc.status_code, media_type=PROBLEM_JSON)

    @app.exception_handler(Exception)
    async def _unexpected(request: Request, exc: Exception) -> JSONResponse:
        log.error("unhandled_exception", exception=type(exc).__name__)  # type only: messages may hold data
        body = problem(500, "internal-error", "Internal error", "An unexpected error occurred.", _request_id(request))
        return JSONResponse(body, status_code=500, media_type=PROBLEM_JSON)
