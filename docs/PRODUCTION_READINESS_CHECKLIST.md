# Production Readiness Checklist — Sprint C3.6

Status vocabulary: **VERIFIED** (evidence in this sprint, kind stated) · **RESTRICTION** (works, with a documented limit the operator must accept) · **NOT VERIFIED** (no evidence was produced; not a claim of failure) · **ACTION** (must be done before production by the operator).
Evidence: `docs/SPRINT_C3_6_SECURITY_INTEGRATION_REPORT.md` (report), `docs/SECURITY_AUDIT.md` (audit), `docs/OPERATIONS_RUNBOOK.md` (runbook).

## 1. Security controls
| Item | Status | Evidence / note |
|---|---|---|
| Tool results encrypted at rest (AES-256-GCM, HKDF, AAD) + verified backfill | VERIFIED (I, R) | report §11, §18; audit 8.1 |
| Final answers and workflow action results encrypted at rest | NOT DONE — RESTRICTION | only **tool results** are encrypted (audit N-9); do not claim all results are encrypted |
| Sensitive arguments redacted in display, history, audit, logs; execution and approval hash untouched | VERIFIED (U, I) | report §13; audit 8.2 |
| Checkpoints sealed and bound to organization, execution, thread, invocation | VERIFIED (U, I, R) | report §12; audit 8.3 |
| MCP output bounded (message, call, flood, stderr); unknown outcomes never replayed | VERIFIED (U, R real subprocess + container) | report §10; audit 8.4 |
| Approver identity organization-scoped, no foreign names | VERIFIED (I, W) | report §14 |
| Electron credential path validated in the main process; Electron never stores provider keys | VERIFIED (U, W) | report §15; renderer-memory exposure is an accepted residual |
| HTTPS enforced for authenticated service traffic outside dev/test; certificate chain, expiry and host name verified; no plaintext fallback | VERIFIED (U, real TLS handshakes) | report §16 |
| Mutual TLS | NOT IMPLEMENTED — not claimed | service identity = OAuth2 client credentials |
| Production TLS terminator / certificates | NOT VERIFIED | **ACTION:** provision certificates and the internal CA at deployment (runbook 19) |
| Certificate revocation checking | NOT VERIFIED | audit N-6 |
| No secret, key, certificate or `.env` in the repository or images | VERIFIED (inspection + repository scan) | report §16.2, §17 |
| Encryption key management (generation, escrow, rotation) | RESTRICTION | keys are not in the database; **ACTION:** store them in a secret manager with separate backup; no rotation tool (audit N-2) |

## 2. Data and recovery
| Item | Status | Evidence / note |
|---|---|---|
| Migrations V1→V11 on an empty database | VERIFIED (R) | report §18 |
| Upgrade V10→V11 on real data | VERIFIED (R) | report §18 (22 results converted, 0 verification failures) |
| Backup and restore, both databases, encrypted data readable afterwards | VERIFIED (R, tiny dataset) | report §18; restore ownership pitfall documented |
| RPO / RTO at production size | NOT VERIFIED | seconds on 300 KB; not predictive |
| WAL archiving / point-in-time recovery | NOT VERIFIED | **ACTION:** deployment decision (audit N-7) |
| Old backups hold pre-V11 plaintext | RESTRICTION | retention policy for old backups |
| Recovery after restart (orphan close, unknown outcomes) | VERIFIED (R) | report scenarios; runbook 22 |

## 3. Reliability and performance
| Item | Status | Evidence / note |
|---|---|---|
| C1 limit of 3 open runs per organization unchanged | VERIFIED (code, tests) | report §19 |
| Parallel scheduling vs base commit on the same machine | VERIFIED (I, simulated workers) | no regression attributable to C3.6 |
| Encryption and redaction overhead | VERIFIED (microbenchmark) | 1 MB seal ≈ 2 ms; redaction capped (≈ 230 ms at 1 MB) |
| Load, soak, multi-organization concurrency | NOT VERIFIED | none was run |
| Real provider latency and Anthropic connectivity | NOT VERIFIED | mock provider only |

## 4. Quality gates
See the final run table in the report §20 for counts and platforms. Linux is verified only for the Python orchestrator (container). Core API, Angular and Electron were run on Windows; **Linux and macOS for those, and packaged Electron installers, are NOT VERIFIED.**

## 5. Dependencies and supply chain
| Item | Status | Evidence / note |
|---|---|---|
| npm production dependencies | VERIFIED — 0 vulnerabilities | `npm audit --omit=dev`; 6 moderate in build tooling (accepted) |
| Python locked dependencies | VERIFIED — none known | `pip-audit` on `uv.lock` export (advisories, not artefact hashes) |
| Java runtime dependencies | VERIFIED against OSV only | OWASP Dependency-Check not run |
| Container image OS packages | NOT VERIFIED | **ACTION:** scan and pin by digest in CI |
| MCP SDK private-API constraint | RESTRICTION | `mcp==2.3.0` pinned; compatibility test and upgrade procedure in report §17.1 |

## 6. Operations
| Item | Status | Evidence / note |
|---|---|---|
| Runbook: 22 procedures with symptoms, diagnostics, recovery, never-do, data-loss risk, verification | VERIFIED (written; procedures grounded in code and exercises) | `docs/OPERATIONS_RUNBOOK.md` |
| Alerting on `tool result … could not be read`, `STATE_UNAVAILABLE`, `OUTCOME_UNKNOWN`, `mcp_output_limit_exceeded` | ACTION | no alerting is shipped (audit N-1) |
| Log aggregation does not receive raw MCP stderr | ACTION | redacted bounded tail only is kept by the orchestrator |

## 7. Pre-production sign-off list (for the operator)
1. Real certificates and trust stores in place; `https://` everywhere; dev profile absent.
2. Keys generated by the operator, escrowed separately, fingerprints recorded.
3. Backup schedule defined (and WAL archiving decided); a restore rehearsal passed on a new database.
4. Container images scanned and pinned.
5. Alerts wired to the log signals above.
6. Linux/macOS runs of the Core API and the desktop client, and a packaged installer, validated if those platforms are in scope.
