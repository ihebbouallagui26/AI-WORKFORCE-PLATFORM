# Security Findings Register — Sprint C3.6

Date: 2026-11-15. Branch `feat/workflow-security-integration-c3-6` (uncommitted working tree on top of `d3ed5fc`).
Method: code inspection, reproduction on the real stack where stated, regression tests, dependency scanners. Evidence is in `docs/SPRINT_C3_6_SECURITY_INTEGRATION_REPORT.md` (section numbers below) and `docs/evidence/c3-6-electron/`.

**Severity is a qualitative judgement of this audit** (CRITICAL: remote compromise or cross-tenant exposure with no precondition; HIGH: exposure of secrets/identity or availability of the whole service under a realistic precondition; MEDIUM: weakened defence in depth; LOW: hardening or hygiene). It is not a CVSS score.
**Test kinds:** U = unit; I = integration with real PostgreSQL (Testcontainers); R = real subprocess/container/stack; W = real Electron window; M = mock provider/simulated.

## Summary

| ID | Component | Severity (before) | Status |
|---|---|---|---|
| 8.1 | Core API — tool results at rest | HIGH | FIXED |
| 8.2 | Core API / orchestrator — secrets in displayed arguments and logs | HIGH | FIXED |
| 8.3 | Orchestrator — checkpoint encryption and identity binding | MEDIUM | FIXED |
| 8.4 | Orchestrator — unbounded MCP output | HIGH | FIXED |
| 8.5 | Core API / desktop — approver identity | LOW | FIXED |
| 8.6 | Electron — provider credential lifecycle | MEDIUM | FIXED (residual accepted) |
| 8.7 | Core API — plain HTTP for OIDC keys and provider endpoints | HIGH | FIXED |
| N-1 | Core API — unreadable result is silent | LOW | ACCEPTED (documented) |
| N-2 | Platform — no key rotation tool | MEDIUM | ACCEPTED (documented decision) |
| N-3 | Operations — restore ownership and provisioning pitfalls | LOW | MITIGATED (runbook) |
| N-4 | Desktop build tooling — `minimatch` advisory | LOW | ACCEPTED |
| N-5 | Container images — OS packages not scanned | LOW | OPEN — NOT VERIFIED |
| N-6 | TLS — no revocation checking, no mTLS | LOW | ACCEPTED (documented) |
| N-7 | Operations — no WAL archiving / PITR | MEDIUM | OPEN — deployment decision |
| N-8 | Tests — two suites still used the format-1 sealer API | INFO | FIXED |
| N-9 | Core API — final answers and workflow action results stored in plaintext | MEDIUM | ACCEPTED (justified residual, follow-up recommended) |

**Acceptance:** no CRITICAL or HIGH finding is unresolved. Every MEDIUM is fixed or has a justified residual-risk decision (8.6 residual, N-2, N-7, N-9). LOW findings are documented. Nothing here claims zero risk.

---

## 8.1 — Tool results stored in plaintext
- **Component:** Core API `tool_invocations.result` (report §11).
- **Original risk:** a database reader, backup or WAL consumer could read every tool result (customer data returned by MCP tools).
- **Root cause:** results were persisted as plain text, bounded in size but not encrypted.
- **Reproduction:** on the real stack, `SELECT result FROM tool_invocations` returned the readable text of tool results (22 of 40 rows in the validation database).
- **Remediation:** AES-256-GCM envelope with an HKDF-derived purpose key (`tool-result|key-id=1`) and AAD binding of organization, execution and invocation; additive migration V11; verified application backfill (convert, re-read and compare before clearing the plaintext); legacy plaintext still readable until converted.
- **Regression tests:** `ToolResultProtectorTest` (U), `ToolResultEncryptionIntegrationTest` (I), `DatabaseSecurityIntegrationTest` (I, V11 and upgrade), backfill and restored-database check (R, §18: plaintext 22 → 0, `failed_verification=0`).
- **Residual risk:** historical plaintext may remain in WAL and in backups taken before V11; the key is held by the Core API process; no key rotation (N-2).
- **Status:** FIXED.

## 8.2 — Secrets in displayed arguments, histories and logs
- **Component:** Core API `Redactor` / `ToolArguments`; orchestrator `redact`, `redact_text` (§13).
- **Original risk:** secrets typed or generated into tool arguments (tokens, passwords, keys, PEM, URLs with credentials) appeared in approval cards, execution history, audit trail and logs.
- **Root cause:** redaction was shallow and key-name based, not recursive and not shape based.
- **Reproduction:** an argument with a nested secret was shown verbatim in the approval card (§13 scenarios).
- **Remediation:** one central bounded linear redactor per language (key names at any depth, token shapes, credentials in URLs/queries, PEM, JWT), applied to **display and logs only**; executed arguments and the SHA-256 approval binding are untouched (golden hash test).
- **Regression tests:** `RedactorTest` (U, 59), `ToolArgumentsTest` (U), `SensitiveArgumentRedactionIntegrationTest` (I), `tests/test_redaction.py` (U, 60 incl. 9 pathological inputs under 3 s).
- **Residual risk:** heuristic: a secret without a recognizable shape under an innocent key is not detected; `token_count`-like keys are over-masked; the Java and Python pattern sets are mirrored by tests, not generated from one source.
- **Status:** FIXED.

## 8.3 — Checkpoint encryption and identity binding
- **Component:** orchestrator `StateSealer`, checkpoints (§12).
- **Original risk:** a sealed conversation could be replayed under another invocation or thread of the same execution; the raw configured key was used directly.
- **Root cause:** AAD covered only organization and execution; no key derivation or format versioning.
- **Remediation:** format 2: HKDF-derived key (`checkpoint-loop|key-id=1`), AAD = purpose, version, key id, organization, execution, thread and invocation; format 1 still readable behind `AIO_STATE_ACCEPT_LEGACY_FORMAT`; every failure is `STATE_UNAVAILABLE` with no tool executed.
- **Regression tests:** `tests/test_checkpoint_protection.py` (U/M, 23), `tests/test_state_persistence_postgres.py` (I, real PostgreSQL: sealed storage, resume, wrong key, tamper, swap), `tests/test_state_key_config.py`; restored copy: 69/69 sealed blobs open with the existing key and are refused with another (R, §18).
- **Residual risk:** legacy format accepted by default for one release (switchable); no key rotation (N-2).
- **Status:** FIXED.

## 8.4 — Unbounded MCP output (memory and availability)
- **Component:** orchestrator MCP stdio transport (§10).
- **Original risk:** a compromised or buggy MCP server could exhaust the orchestrator's memory with oversized messages, floods or aggregate output, or hide a call's outcome.
- **Root cause:** the SDK's stdio reader buffers full lines without bound and aggregate limits did not exist.
- **Remediation:** a bounded stdio transport (per-message, per-call aggregate, stdout/stderr flood limits), strict classification of failures (not executed / outcome unknown), bounded unknown-outcome reporting to the Core API, no replay of unknown outcomes.
- **Regression tests:** `test_mcp_output_limits.py`, `test_mcp_aggregate_and_classification.py`, `test_mcp_sdk_compat.py`, `test_mcp_memory_and_event_loops.py`, `test_mcp_lifecycle.py`, `test_tool_execution.py` (U/R real subprocess); real container validation with RSS/cgroup measurements and an adversarial server (R, §10).
- **Residual risk:** the transport reuses private helpers of `mcp==2.3.0` (pinned; compatibility test fails loudly, upgrade procedure in §17.1); no durable local outbox for unknown-outcome reports (the Core API's `RUNNING` row is the safety net).
- **Status:** FIXED.

## 8.5 — Approver identity
- **Component:** Core API `ApprovalService`/`MembershipService`, Angular approvals tab (§14).
- **Original risk:** the history displayed a shortened user id, weak for accountability; a naive fix could leak names across organizations.
- **Remediation:** display name resolved only among members of the same organization, neutral fallback `Organization member`, additive `decidedByName`.
- **Regression tests:** `ApproverIdentityIntegrationTest` (I, 5), Angular `tool-approval-ui.spec` (U), contract spec; real Electron window in the final run.
- **Residual risk:** a person's display name is shown to members of that organization only (by design).
- **Status:** FIXED.

## 8.6 — Electron provider credential lifecycle
- **Component:** Electron main process (`auth-policy.ts`) and the providers dialog (§15).
- **Original risk:** the credential exists in renderer memory; a request without a body skipped shape validation in the main process.
- **Remediation:** shape validation moved before the body-present branch; mandatory body; exact fields and the platform pattern `^[!-~]{8,512}$`; a refusal never echoes the value; failure-path tests prove nothing reaches screen, toast, console or storage.
- **Regression tests:** Electron `auth-policy.test.ts` (U, 49 total), Angular `ai-agents.spec` (U), real-window credential entry (W, evidence directory).
- **Residual risk (accepted):** the typed key necessarily lives in renderer memory for a short time and JavaScript strings cannot be zeroed; clipboard history is outside the application.
- **Status:** FIXED (residual accepted).

## 8.7 — Plain HTTP for OIDC keys and provider endpoints
- **Component:** Core API configuration (`OidcProperties`, `ProviderProperties`) (§16).
- **Original risk:** the issuer and JWKS URLs could be `http://` in any profile; an on-path attacker could substitute the signing keys and have forged tokens accepted. Provider endpoints could be loopback HTTP in production.
- **Root cause:** only the orchestrator dispatch URLs and the orchestrator's own settings were enforced.
- **Remediation:** `OidcTransportValidator` and `ProviderTransportValidator` (rule in `common.net.TransportPolicy`) fail startup unless the profile is explicitly `dev`/`test` or the URLs are credential-free HTTPS; message names the property, never the value.
- **Regression tests:** `OidcTransportValidatorTest` (U, 7), `ProviderTransportValidatorTest` (U, 5), `TransportPolicyTest` (U, 8), `OrchestratorDispatchValidatorTest` (U, 18), orchestrator `test_internal_tls.py` (16, **real TLS handshakes**: untrusted CA, expired, host-name mismatch, no plaintext fallback, no redirect following, zero requests reach an impostor).
- **Residual risk:** no mTLS (not claimed), no revocation checking (N-6), real TLS termination of a production deployment NOT VERIFIED.
- **Status:** FIXED.

---

## N-1 — Unreadable tool result is silent
Observed during the wrong-key exercise (§18): the API answers 200 without a result and logs `tool result of invocation <id> could not be read`. This avoids leaking or crashing, but an operator may not notice. **Remediation:** documented in the runbook (procedure 14) with the log line to alert on. **Residual:** no metric/alert is shipped. **Status:** ACCEPTED.

## N-2 — No key rotation tool
Both encryption keys are single versioned (`key-id=1`). Replacing a key makes sealed data unreadable. **Decision:** rotation is out of scope for C3.6; the envelope formats carry a key id so a re-encryption job can be added. **Mitigation:** key escrow and the "never replace a key" rule in the runbook. **Status:** ACCEPTED (documented decision).

## N-3 — Restore ownership and provisioning pitfalls
Found by the restore exercise: `pg_restore --no-owner` makes the application fail with `permission denied for table flyway_schema_history`; a fresh server needs the provisioning script (extension creation needs a superuser). **Remediation:** runbook procedures 17–18. **Status:** MITIGATED.

## N-4 — `minimatch` advisory in desktop build tooling
`npm audit`: production dependencies 0 vulnerabilities; 6 moderate findings through `electron-builder` (GHSA-g3hv-75wg-m249, ReDoS in glob matching). Not shipped. The proposed fix is a major downgrade and was not applied. **Status:** ACCEPTED (§17).

## N-5 — Container image OS packages not scanned
No image scanner was available; none was installed or downloaded. Application images run as non-root and contain no keys or `.env`. The Python base image uses a floating tag. **Action required before production:** scan and pin by digest in CI. **Status:** OPEN — NOT VERIFIED (not exploitable evidence, absence of evidence).

## N-6 — No revocation checking, no mTLS
Certificate revocation (CRL/OCSP) is not configured; service identity relies on OAuth2 client credentials, not on client certificates. **Status:** ACCEPTED (documented).

## N-7 — No WAL archiving / point-in-time recovery
Only logical dumps were exercised. RPO equals the dump interval. **Status:** OPEN — deployment decision, documented in the runbook and the readiness checklist.

## N-8 — Stale tests of the format-1 sealer API
`test_state_key_config.py` and `test_state_persistence_postgres.py` still called the old `StateSealer.aad` API after fix 8.3 and failed (caught by the full regression, not by a product defect). Updated to the format-2 binding. **Status:** FIXED.

## N-9 — Final answers and workflow action results are stored in plaintext
Found while documenting the scope of V11. `agent_executions.result` (the final answer shown to users), `workflow_actions.result` and similar workflow result columns remain readable text in PostgreSQL and in backups; V11 and fix 8.1 cover **tool results** (`tool_invocations`) as specified for this sprint. These values are model outputs that may restate tool data. **Justification for the residual decision:** they are the product's user-visible content, reached through organization-scoped RBAC; encrypting them touches listing, truncation and workflow synthesis paths and cannot be done safely as a late addition to this sprint; the database roles already separate the runtime from the migration role. **Recommendation:** a follow-up sprint applying the same envelope (purpose-specific HKDF key, AAD with organization and row id) to these columns, with the same verified backfill. **Status:** ACCEPTED — residual risk stated; do not describe the platform as "all results encrypted at rest".
