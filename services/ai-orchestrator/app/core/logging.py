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
TRUNCATED = "[TRUNCATED]"
# Security fix 8.2: the same families as the Core API's Redactor (docs/SPRINT_C3_6_SECURITY_INTEGRATION_REPORT.md, section 13).
# Every expression is linear (lengths are capped, no nested unbounded quantifiers) and the work is bounded.
MAX_DEPTH = 32
MAX_ELEMENTS = 100
MAX_STRING = 4096
MAX_TEXT = 1_000_000
_SENSITIVE_KEY = re.compile(
    r"(secret|token|password|passwd|passphrase|pwd|authorization|api[-_]?key|apikey|access[-_]?key|credential|cookie|private[-_]?key"
    r"|session[-_]?id|signature|bearer|jwt|connection[-_]?string|dsn)",
    re.I,
)
_PEM = re.compile(r"-----BEGIN [A-Z ]{0,40}PRIVATE KEY-----[A-Za-z0-9+/=\s]{0,16384}(?:-----END [A-Z ]{0,40}PRIVATE KEY-----)?")
_HEADER_LINE = re.compile(r"\b(authorization|proxy-authorization|cookie|set-cookie|x-api-key)(\s{0,3}:\s{0,3})[^\r\n]{1,1024}", re.I)
_URL_USERINFO = re.compile(r"\b([a-zA-Z][a-zA-Z0-9+.-]{1,20})://[^\s/:@]{1,128}:[^\s/@]{1,256}@")
_AUTH_SCHEME = re.compile(r"\b(?:Bearer|Basic)\s+[A-Za-z0-9._~+/=-]{8,512}", re.I)
_JWT = re.compile(r"eyJ[A-Za-z0-9_-]{8,2048}\.[A-Za-z0-9_-]{8,2048}\.[A-Za-z0-9_-]{0,2048}")
_KEY_FORMATS = re.compile(
    r"sk-[A-Za-z0-9_-]{8,256}|sk_(?:live|test)_[A-Za-z0-9]{8,128}|rk_(?:live|test)_[A-Za-z0-9]{8,128}|AIza[0-9A-Za-z_-]{20,128}"
    r"|\b(?:AKIA|ASIA)[0-9A-Z]{16}\b|\bgh[pousr]_[A-Za-z0-9]{20,255}|github_pat_[A-Za-z0-9_]{20,255}"
    r"|\bxox[abprs]-[A-Za-z0-9-]{10,255}|\bglpat-[A-Za-z0-9_-]{15,255}"
)
_SENSITIVE_PAIR = re.compile(
    r"(?<![A-Za-z0-9_])((?:access[_-]?token|refresh[_-]?token|id[_-]?token|api[_-]?key|apikey|client[_-]?secret|secret(?:[_-]?key)?"
    r"|password|passwd|pwd|passphrase|token|signature|x-amz-signature|x-amz-credential|session[_-]?id|sas[_-]?token)"
    r"\s{0,3}[:=]\s{0,3})(\"?)([^\s&,;\"']{1,512})",
    re.I,
)


def redact_text(text: str) -> str:
    """Free text with every recognizable secret replaced by ``[REDACTED]`` (bounded work, never raises)."""
    out = text[:MAX_TEXT]
    out = _PEM.sub(REDACTED, out)
    out = _HEADER_LINE.sub(lambda m: m.group(1) + m.group(2) + REDACTED, out)
    out = _URL_USERINFO.sub(lambda m: f"{m.group(1)}://{REDACTED}@", out)
    out = _AUTH_SCHEME.sub(REDACTED, out)
    out = _JWT.sub(REDACTED, out)
    out = _KEY_FORMATS.sub(REDACTED, out)
    return _SENSITIVE_PAIR.sub(lambda m: m.group(1) + m.group(2) + REDACTED, out)


def redact(value: Any, *, _key: str | None = None, truncate: bool = True, _depth: int = 0) -> Any:
    """Returns a masked COPY of ``value``; the argument is never modified.

    Secret-looking keys (any depth, any case) and recognizable secret shapes in strings are masked. Nesting deeper than ``MAX_DEPTH``
    is replaced as a whole. With ``truncate`` (logs) long strings and big containers are also cut; the state guard compares before and
    after and therefore passes ``truncate=False``.
    """
    if _key is not None and _SENSITIVE_KEY.search(_key):
        return REDACTED
    if isinstance(value, dict | list | tuple | set | frozenset):
        if _depth >= MAX_DEPTH:
            return TRUNCATED
        items = list(value.items()) if isinstance(value, dict) else list(value)
        cut = truncate and len(items) > MAX_ELEMENTS
        items = items[:MAX_ELEMENTS] if cut else items
        if isinstance(value, dict):
            masked: Any = {k: redact(v, _key=str(k), truncate=truncate, _depth=_depth + 1) for k, v in items}
            if cut:
                masked[TRUNCATED] = "more properties omitted"
            return masked
        result = [redact(v, truncate=truncate, _depth=_depth + 1) for v in items]
        return [*result, TRUNCATED] if cut else result
    if isinstance(value, str):
        text = redact_text(value)
        return text[:MAX_STRING] + TRUNCATED if truncate and len(text) > MAX_STRING else text
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
