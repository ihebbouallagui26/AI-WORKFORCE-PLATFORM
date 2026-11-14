"""Domain errors and their RFC 9457 problem representation (messages are always safe to show)."""

from __future__ import annotations

PROBLEM_TYPE_PREFIX = "urn:aiworkforce:orchestrator:problem:"


class OrchestratorError(Exception):
    """Base class: ``message`` never contains secrets, provider bodies or infrastructure details."""

    status = 500
    code = "internal-error"
    default_message = "The orchestrator could not complete the request."

    def __init__(self, message: str | None = None) -> None:
        self.message = message or self.default_message
        super().__init__(self.message)


class UnauthenticatedError(OrchestratorError):
    status = 401
    code = "unauthenticated"
    default_message = "Authentication is required."


class ForbiddenError(OrchestratorError):
    status = 403
    code = "forbidden"
    default_message = "This caller is not allowed to perform the operation."


class NotFoundError(OrchestratorError):
    status = 404
    code = "not-found"
    default_message = "Not found."


class UnavailableError(OrchestratorError):
    status = 503
    code = "unavailable"
    default_message = "A required dependency is unavailable."


class RunTimeoutError(OrchestratorError):
    status = 504
    code = "timeout"
    default_message = "The operation did not finish in time."


def problem(status: int, code: str, title: str, detail: str, request_id: str | None) -> dict[str, object]:
    body: dict[str, object] = {
        "type": f"{PROBLEM_TYPE_PREFIX}{code}",
        "title": title,
        "status": status,
        "detail": detail,
    }
    if request_id:
        body["requestId"] = request_id
    return body
