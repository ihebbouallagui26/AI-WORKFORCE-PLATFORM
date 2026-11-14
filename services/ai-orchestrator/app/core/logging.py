"""Structured JSON logging with correlation ids and defensive redaction of secrets."""

from __future__ import annotations

import logging
import re
import sys
from collections.abc import MutableMapping
from contextvars import ContextVar
from typing import Any

import structlog

correlation_id_var: ContextVar[str | None] = ContextVar("correlation_id", default=None)

REDACTED = "[REDACTED]"
_SENSITIVE_KEY = re.compile(
    r"(secret|token|password|passwd|authorization|api[-_]?key|credential|cookie|private[-_]?key|jwt)", re.I
)
# Shapes of secrets that may leak inside free text (bearer tokens, provider keys, JWTs).
_SENSITIVE_VALUE = re.compile(
    r"(Bearer\s+[A-Za-z0-9._~+/=-]{8,}|sk-[A-Za-z0-9_-]{8,}|AIza[0-9A-Za-z_-]{20,}"
    r"|eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]*)"
)


def redact(value: Any, *, _key: str | None = None) -> Any:
    """Returns a copy of ``value`` where secret-looking keys and token-looking strings are masked."""
    if _key is not None and _SENSITIVE_KEY.search(_key):
        return REDACTED
    if isinstance(value, dict):
        return {k: redact(v, _key=str(k)) for k, v in value.items()}
    if isinstance(value, list | tuple):
        return [redact(v) for v in value]
    if isinstance(value, str):
        return _SENSITIVE_VALUE.sub(REDACTED, value)
    return value


def _redaction_processor(_: Any, __: str, event: MutableMapping[str, Any]) -> MutableMapping[str, Any]:
    return {k: redact(v, _key=k) for k, v in event.items()}


def _correlation_processor(_: Any, __: str, event: MutableMapping[str, Any]) -> MutableMapping[str, Any]:
    request_id = correlation_id_var.get()
    if request_id:
        event.setdefault("request_id", request_id)
    return event


def configure_logging(level: str = "INFO") -> None:
    structlog.configure(
        processors=[
            structlog.stdlib.add_log_level,
            structlog.processors.TimeStamper(fmt="iso", utc=True),
            _correlation_processor,
            structlog.processors.format_exc_info,
            _redaction_processor,
            structlog.processors.JSONRenderer(),
        ],
        wrapper_class=structlog.make_filtering_bound_logger(logging.getLevelNamesMapping()[level]),
        logger_factory=structlog.PrintLoggerFactory(file=sys.stdout),
        cache_logger_on_first_use=False,
    )


def get_logger(name: str = "orchestrator") -> structlog.typing.FilteringBoundLogger:
    return structlog.get_logger(name)
