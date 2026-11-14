"""Authentication of the callers of this service: Keycloak-issued JWTs from allowed clients only.

The orchestrator is internal. Its only callers are other backend services holding a service-account
token (OAuth2 client credentials) whose audience is this service. Tokens issued to humans or to the
desktop application are rejected: the ``azp`` (authorized party) must be an allowed caller client.
"""

from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass
from typing import Any

import httpx
import jwt
from jwt import PyJWK

from app.core.config import Settings
from app.core.errors import UnauthenticatedError, UnavailableError

_ALGORITHMS = ["RS256", "ES256"]
_JWKS_TTL_SECONDS = 300.0


@dataclass(frozen=True)
class CallerIdentity:
    client_id: str
    subject: str


def _is_signing_key(jwk: dict[str, Any]) -> bool:
    """Keycloak also publishes encryption keys (``use: enc``, RSA-OAEP): they can never verify a signature."""
    return "kid" in jwk and jwk.get("use", "sig") == "sig" and jwk.get("alg", "RS256") in _ALGORITHMS


def _signing_key(jwk: dict[str, Any]) -> PyJWK:
    return PyJWK(jwk)


class JwtVerifier:
    """Verifies signature, issuer, audience, expiry and the authorized party of a bearer token."""

    def __init__(self, settings: Settings, http: httpx.AsyncClient) -> None:
        self._settings = settings
        self._http = http
        self._keys: dict[str, PyJWK] = {}
        self._fetched_at = 0.0
        self._lock = asyncio.Lock()

    async def _refresh(self, *, force: bool = False) -> None:
        async with self._lock:
            if not force and self._keys and time.monotonic() - self._fetched_at < _JWKS_TTL_SECONDS:
                return
            try:
                response = await self._http.get(self._settings.oidc_jwks_url, timeout=5.0)
                response.raise_for_status()
                document: dict[str, Any] = response.json()
                self._keys = {k["kid"]: _signing_key(k) for k in document["keys"] if _is_signing_key(k)}
            except (httpx.HTTPError, KeyError, ValueError, jwt.PyJWTError) as exc:
                raise UnavailableError("The identity provider keys are unavailable.") from exc
            self._fetched_at = time.monotonic()

    async def verify(self, token: str) -> CallerIdentity:
        try:
            header = jwt.get_unverified_header(token)
        except jwt.PyJWTError as exc:
            raise UnauthenticatedError("Invalid bearer token.") from exc
        kid = header.get("kid")
        await self._refresh()
        if kid not in self._keys:
            await self._refresh(force=True)  # key rotation
        key = self._keys.get(kid) if isinstance(kid, str) else None
        if key is None:
            raise UnauthenticatedError("Invalid bearer token.")
        try:
            claims = jwt.decode(
                token,
                key.key,
                algorithms=_ALGORITHMS,
                audience=self._settings.oidc_audience,
                issuer=self._settings.oidc_issuer,
                options={"require": ["exp", "iss", "aud", "sub"]},
                leeway=10,
            )
        except jwt.PyJWTError as exc:
            raise UnauthenticatedError("Invalid bearer token.") from exc
        client_id = claims.get("azp")
        if client_id not in self._settings.allowed_caller_client_ids:
            raise UnauthenticatedError("Invalid bearer token.")
        return CallerIdentity(client_id=client_id, subject=str(claims["sub"]))
