from __future__ import annotations

from typing import Annotated

from fastapi import Depends, Request

from app.core.errors import UnauthenticatedError
from app.core.security import CallerIdentity, JwtVerifier


async def require_caller(request: Request) -> CallerIdentity:
    """Every non-health endpoint requires a service token from an allowed caller client."""
    header = request.headers.get("authorization", "")
    scheme, _, token = header.partition(" ")
    if scheme.lower() != "bearer" or not token.strip():
        raise UnauthenticatedError
    verifier: JwtVerifier = request.app.state.verifier
    return await verifier.verify(token.strip())


Caller = Annotated[CallerIdentity, Depends(require_caller)]
