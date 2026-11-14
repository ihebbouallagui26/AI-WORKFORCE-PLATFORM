"""Authenticated encryption of the few runtime data that must survive a pause (Phase C2).

A run that waits for a human approval has to be resumable after an orchestrator restart, so its
conversation (instruction, tool results) is kept in the LangGraph checkpoint. That payload is business
content and may be sensitive, so it is never stored in clear: it is sealed with AES-256-GCM, a fresh
random nonce and additional authenticated data that binds it to ONE organization and ONE execution
(a blob copied to another execution or organization fails authentication). The key comes only from the
environment (``AIO_STATE_ENCRYPTION_KEY``), is never logged and never part of any state.

Provider credentials, access tokens and API keys are NOT part of this payload: they are never persisted.
"""

from __future__ import annotations

import os
from uuid import UUID

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from app.core.errors import OrchestratorError

_NONCE_BYTES = 12
_VERSION = b"\x01"


class StateIntegrityError(OrchestratorError):
    status = 500
    code = "state-integrity"
    default_message = "The saved execution state could not be authenticated."


class StateSealer:
    def __init__(self, key: bytes) -> None:
        if len(key) != 32:
            raise ValueError("the state encryption key must be 32 bytes")
        self._aead = AESGCM(key)

    @staticmethod
    def aad(organization_id: UUID, execution_id: UUID) -> bytes:
        return f"{organization_id}:{execution_id}".encode()

    def seal(self, plaintext: bytes, aad: bytes) -> str:
        """Returns lowercase hex (so the checkpoint guard never mistakes it for a token)."""
        nonce = os.urandom(_NONCE_BYTES)
        return (_VERSION + nonce + self._aead.encrypt(nonce, plaintext, aad)).hex()

    def open(self, token: str, aad: bytes) -> bytes:
        try:
            raw = bytes.fromhex(token)
            if raw[:1] != _VERSION:
                raise StateIntegrityError
            nonce, ciphertext = raw[1 : 1 + _NONCE_BYTES], raw[1 + _NONCE_BYTES :]
            return self._aead.decrypt(nonce, ciphertext, aad)
        except (InvalidTag, ValueError) as exc:
            raise StateIntegrityError from exc
