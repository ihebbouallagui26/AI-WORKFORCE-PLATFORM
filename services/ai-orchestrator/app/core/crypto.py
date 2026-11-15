"""Authenticated encryption of the few runtime data that must survive a pause (Phase C2, hardened by security fix 8.3).

A run that waits for a human approval has to be resumable after an orchestrator restart, so its conversation (instruction, tool
results) is kept in the LangGraph checkpoint (``loop_blob``). That payload is business content and may be sensitive, so it is never
stored in clear. WHAT IS AND IS NOT ENCRYPTED: only ``loop_blob`` is sealed; the rest of the checkpoint holds identifiers, stage
names, counters, the model name and a safe error code (the state guard refuses secret- or token-looking data), and the checkpointer's
own columns (``thread_id`` = ``<organization>:<execution>``) are readable. The checkpoint database as a whole is NOT encrypted.

Format 2 (written today): ``[2][key id][nonce:12][ciphertext+tag]`` in lowercase hex (so the checkpoint guard never mistakes it for a
token). AES-256-GCM, fresh random nonce, with a key DERIVED by HKDF-SHA256 from ``AIO_STATE_ENCRYPTION_KEY`` for this purpose only
(the environment key is never used directly). The AAD authenticates the purpose, the format, the key id and the identity the payload
belongs to: organization, execution (= the worker: every worker is its own child execution), the LangGraph thread id and the id of
the tool invocation the run is paused on. A blob copied to another organization, execution, thread or pause, a blob of an earlier
pause of the same execution, or one whose header was rewritten, fails authentication.

Format 1 (written before 8.3: the raw key, AAD ``<organization>:<execution>``) is still READ, so runs already paused when the new
version starts are not lost; it is never written. ``accept_legacy=False`` (``AIO_STATE_ACCEPT_LEGACY_FORMAT=false``) refuses it once no
pre-upgrade pause can remain (an approval expires after 15 minutes by default). An unknown format or key id is refused: there is no
plaintext form and no attacker-chosen downgrade (a v2 blob relabelled v1 cannot authenticate: different key and AAD).

Provider credentials, access tokens and API keys are NOT part of this payload: they are never persisted.
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from uuid import UUID

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

from app.core.errors import OrchestratorError

_NONCE_BYTES = 12
_TAG_BYTES = 16
FORMAT_V1 = 1
FORMAT_V2 = 2
KEY_ID = 1
_PURPOSE = b"aiw.checkpoint-loop"
_SALT = b"aiworkforce.orchestrator.key-derivation.v1"
_MIN_V1 = 1 + _NONCE_BYTES + _TAG_BYTES
_MIN_V2 = 2 + _NONCE_BYTES + _TAG_BYTES


class StateIntegrityError(OrchestratorError):
    status = 500
    code = "state-integrity"
    default_message = "The saved execution state could not be authenticated."


@dataclass(frozen=True)
class StateBinding:
    """The immutable identity a sealed payload belongs to. Only stable identifiers: never a display name."""

    organization_id: UUID
    execution_id: UUID
    thread_id: str
    invocation_id: UUID  # the tool invocation the run is paused on (from the Core API's decision at resume time)


class StateSealer:
    def __init__(self, key: bytes, *, accept_legacy: bool = True) -> None:
        if len(key) != 32:
            raise ValueError("the state encryption key must be 32 bytes")
        self._legacy = AESGCM(key)  # format 1 only: the raw key, read-only
        derived = HKDF(algorithm=hashes.SHA256(), length=32, salt=_SALT, info=b"checkpoint-loop|key-id=" + str(KEY_ID).encode()).derive(key)
        self._aead = AESGCM(derived)
        self._accept_legacy = accept_legacy

    @staticmethod
    def aad(organization_id: UUID, execution_id: UUID) -> bytes:
        """The format-1 AAD (read path of legacy blobs, and used by tests to forge one)."""
        return f"{organization_id}:{execution_id}".encode()

    @staticmethod
    def _aad_v2(binding: StateBinding) -> bytes:
        thread = binding.thread_id.encode()
        return (
            _PURPOSE + b"\x00" + bytes([FORMAT_V2, KEY_ID]) + binding.organization_id.bytes + binding.execution_id.bytes
            + len(thread).to_bytes(2, "big") + thread + binding.invocation_id.bytes
        )

    def seal(self, plaintext: bytes, binding: StateBinding) -> str:
        """Returns lowercase hex of a format-2 envelope."""
        nonce = os.urandom(_NONCE_BYTES)
        return (bytes([FORMAT_V2, KEY_ID]) + nonce + self._aead.encrypt(nonce, plaintext, self._aad_v2(binding))).hex()

    def open(self, token: str, binding: StateBinding) -> bytes:
        try:
            raw = bytes.fromhex(token)
            if raw[:1] == bytes([FORMAT_V2]) and len(raw) >= _MIN_V2 and raw[1] == KEY_ID:
                return self._aead.decrypt(raw[2 : 2 + _NONCE_BYTES], raw[2 + _NONCE_BYTES :], self._aad_v2(binding))
            if self._accept_legacy and raw[:1] == bytes([FORMAT_V1]) and len(raw) >= _MIN_V1:
                aad = self.aad(binding.organization_id, binding.execution_id)
                return self._legacy.decrypt(raw[1 : 1 + _NONCE_BYTES], raw[1 + _NONCE_BYTES :], aad)
        except (InvalidTag, ValueError) as exc:
            raise StateIntegrityError from exc
        raise StateIntegrityError  # unknown format, unknown key id, legacy refused, or too short
