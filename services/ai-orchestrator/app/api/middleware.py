"""Pure ASGI middleware: correlation ids, request size limit and request timeout."""

from __future__ import annotations

import asyncio
import json
import re
import uuid

from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app.core.errors import problem
from app.core.logging import correlation_id_var, get_logger

log = get_logger("http")
REQUEST_ID_HEADER = b"x-request-id"
_VALID_REQUEST_ID = re.compile(r"^[A-Za-z0-9-]{8,64}$")


async def _send_problem(send: Send, status: int, code: str, title: str, detail: str, request_id: str) -> None:
    body = json.dumps(problem(status, code, title, detail, request_id)).encode()
    await send(
        {
            "type": "http.response.start",
            "status": status,
            "headers": [
                (b"content-type", b"application/problem+json"),
                (b"content-length", str(len(body)).encode()),
                (REQUEST_ID_HEADER, request_id.encode()),
            ],
        }
    )
    await send({"type": "http.response.body", "body": body})


class RequestContextMiddleware:
    """Correlation id (accepted if well formed, else generated), body limit, per-request timeout."""

    def __init__(self, app: ASGIApp, *, timeout_seconds: float, max_body_bytes: int) -> None:
        self.app = app
        self.timeout = timeout_seconds
        self.max_body = max_body_bytes

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        headers = dict(scope["headers"])
        supplied = headers.get(REQUEST_ID_HEADER, b"").decode("latin-1")
        request_id = supplied if _VALID_REQUEST_ID.fullmatch(supplied) else str(uuid.uuid4())
        scope.setdefault("state", {})["request_id"] = request_id
        token = correlation_id_var.set(request_id)
        started = False

        declared = headers.get(b"content-length")
        if declared is not None and declared.isdigit() and int(declared) > self.max_body:
            await _send_problem(send, 413, "payload-too-large", "Payload too large", "The request body is too large.", request_id)
            correlation_id_var.reset(token)
            return

        received = 0

        async def limited_receive() -> Message:
            nonlocal received
            message = await receive()
            if message["type"] == "http.request":
                received += len(message.get("body", b""))
                if received > self.max_body:
                    raise _BodyTooLarge
            return message

        async def tracking_send(message: Message) -> None:
            nonlocal started
            if message["type"] == "http.response.start":
                started = True
                message = {**message, "headers": [*message.get("headers", []), (REQUEST_ID_HEADER, request_id.encode())]}
            await send(message)

        try:
            async with asyncio.timeout(self.timeout):
                await self.app(scope, limited_receive, tracking_send)
        except TimeoutError:
            log.warning("request_timeout", path=scope["path"])
            if not started:
                await _send_problem(send, 504, "timeout", "Request timeout", "The request took too long.", request_id)
        except _BodyTooLarge:
            if not started:
                await _send_problem(send, 413, "payload-too-large", "Payload too large", "The request body is too large.", request_id)
        finally:
            correlation_id_var.reset(token)


class _BodyTooLarge(Exception):  # noqa: N818 - control-flow signal, not an application error
    pass
