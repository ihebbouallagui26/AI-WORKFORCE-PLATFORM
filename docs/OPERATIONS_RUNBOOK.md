# Operations Runbook — AI Workforce Platform

Scope: the Core API (Java/Spring), the AI orchestrator (Python), PostgreSQL, Keycloak and the desktop client, as delivered by Sprint C3.6.
Audience: the person on call. Every procedure has the same six parts. **No credential, key or token value appears in this document or must be pasted into a ticket, chat or log.** Commands refer to variables and files by name only.

**Conventions.** `COMPOSE` = `docker compose -f infra/docker/compose.yaml --env-file infra/docker/.env --profile orchestrator`. Core health: `GET /api/v1/health`. Orchestrator liveness: `GET /health`; readiness: `GET /health/ready` (503 when the orchestrator cannot serve). SQL examples are **read-only** (`SELECT`) and are run with the least-privileged role that works. Row counts in examples are illustrative.

**Ground rules that apply to every procedure**
1. The Core API database is the source of truth. The orchestrator is replaceable; its checkpoint database only lets a paused run resume.
2. A tool invocation in `OUTCOME_UNKNOWN` means the external effect may or may not have happened. **It is never replayed automatically and must never be replayed by editing rows.** Verify the effect in the target system first.
3. Never edit application tables, `flyway_schema_history`, or checkpoint rows by hand to "unstick" something. Use the API (cancel, retry, approve) so the audit trail and invariants hold.
4. The two encryption keys (`SECRETS_MASTER_KEY` for the Core API, `ORCHESTRATOR_STATE_ENCRYPTION_KEY` mounted as `AIO_STATE_ENCRYPTION_KEY` in the orchestrator) are **not in the database**. Never regenerate, truncate or "fix" a key to get a service to start.
5. Do not delete Docker volumes (`docker compose down -v`, `docker volume rm`, `docker system prune --volumes`) unless a restore has been verified first.
6. Keep the C1 limit of 3 open runs per organization: do not raise it to "clear a backlog".

---

## 1. Core API outage
- **Symptoms:** desktop shows connection errors; `GET /api/v1/health` fails or is not `UP`; the orchestrator logs `core_api_error` and calls fail with `CoreApiUnavailable`; running executions stop progressing.
- **Diagnostics:** `COMPOSE ps`; `COMPOSE logs --tail=200 core-api` (look for `Application run failed`, `Caused by`, Flyway or `permission denied`); port in use; database reachable (procedure 3); invalid HTTPS/OIDC configuration (procedure 19).
- **Safe recovery:** fix the cause shown in the log, then `COMPOSE up -d core-api`. Do not touch the orchestrator. On start the Core API recovers by itself: stale `RUNNING` executions are closed by the reaper after `EXECUTIONS_TIMEOUT` (default `PT5M`) and their `RUNNING` tool invocations become `OUTCOME_UNKNOWN`; the V11 backfill converts any remaining plaintext tool result (log line `tool result backfill finished: converted=N failed_verification=M`).
- **Never:** delete the database or its volume; set `spring.flyway` options to skip validation; start two Core API instances against the same database during a Flyway upgrade.
- **Data-loss risk:** none for committed data. Executions that were in flight during the outage fail (`ORCHESTRATOR_UNAVAILABLE` or timeout); tool calls caught mid-flight become `OUTCOME_UNKNOWN` (procedure 11).
- **Verify:** health `UP`; sign in from the desktop; open a recent run (history intact); `failed_verification=0` in the backfill line; no `ERROR` in the first minutes of the log.

## 2. Orchestrator outage
- **Symptoms:** new executions fail with `ORCHESTRATOR_UNAVAILABLE`; paused runs do not resume after approval; `/health/ready` returns 503 or connection refused.
- **Diagnostics:** `COMPOSE ps ai-orchestrator`; `COMPOSE logs --tail=200 ai-orchestrator`; readiness body (which dependency is failing: database, Core API, key); container restarts and OOM (`docker inspect` → `State.OOMKilled`); memory limit versus `mcp_*` limits.
- **Safe recovery:** fix the cause, then `COMPOSE up -d ai-orchestrator` (or procedure 21 for a failed recreation). Paused runs are stored sealed in PostgreSQL and resume after the orchestrator returns with **the same state key**; approvals that were granted while it was down are picked up from the Core API.
- **Never:** change `ORCHESTRATOR_STATE_ENCRYPTION_KEY`; drop `orchestrator_runtime.*`; delete the container's database; re-send a tool call that is `OUTCOME_UNKNOWN`.
- **Data-loss risk:** an execution that was `RUNNING` (not paused) when the process died is lost and closed by the Core API; its running tool call is `OUTCOME_UNKNOWN`. Paused (`WAITING_APPROVAL`) runs are not lost.
- **Verify:** `/health/ready` is 200; start a harmless agent run and see it complete; resume one paused run if any exists.

## 3. PostgreSQL outage
- **Symptoms:** Core API health degraded, `HikariPool … Connection is not available`; orchestrator readiness 503; both services log connection errors.
- **Diagnostics:** `COMPOSE ps postgres`; `COMPOSE logs --tail=200 postgres`; disk full (`df`, volume size); too many connections; role/ownership errors (`permission denied for table …`).
- **Safe recovery:** restore the service (free disk, restart `postgres`). Services reconnect on their own; if they do not, restart `core-api` then `ai-orchestrator`. After a **crash** let PostgreSQL finish its own recovery; do not kill it again.
- **Never:** delete `pg_wal`; run `pg_resetwal`; delete the volume; restart repeatedly while recovery is in progress.
- **Data-loss risk:** transactions not committed at the crash are lost; with logical dumps only (no WAL archiving, see procedure 16) a lost volume loses everything since the last dump.
- **Verify:** `SELECT 1` through each role; Core health `UP`; orchestrator ready; a run's history is complete; `flyway_schema_history` still V1–V11 all successful.

## 4. Keycloak outage
- **Symptoms:** sign-in fails; the desktop cannot refresh its session; the orchestrator cannot obtain its service token (`The identity provider is unavailable`); the Core API cannot fetch signing keys after its cache expires.
- **Diagnostics:** `COMPOSE logs --tail=200 keycloak`; its own database (separate from the application database); the issuer URL is **HTTPS outside dev/test** (procedure 19); certificate expiry.
- **Safe recovery:** restart Keycloak; the Core API validates tokens from its cached key set and re-fetches on demand; the orchestrator retries token acquisition (bounded); users sign in again after recovery.
- **Never:** disable token validation or audience checks "temporarily"; switch issuer/JWKS to plain HTTP in a non-dev profile (the Core API refuses to start); change the realm without a realm export.
- **Data-loss risk:** none for application data; in-flight executions that need a service token fail and must be re-run.
- **Verify:** sign in from the desktop; the orchestrator logs no authentication errors; a run completes end to end.

## 5. Provider outage (AI provider)
- **Symptoms:** executions fail or retry with transient provider errors; workflow steps show `TRANSIENT_PROVIDER_FAILURE`; many failures at once.
- **Diagnostics:** the provider's status page; the execution error code and message in the run page; orchestrator log (provider errors are logged without prompts or keys); whether only one provider/connection is affected; connection "test" in the providers dialog.
- **Safe recovery:** wait; automatic step retries (workflow `maxRetriesPerStep` 0–2, exponential back-off with jitter) cover short outages; use the documented fallback provider binding when configured; re-run failed runs afterwards from the UI.
- **Never:** paste a provider key into logs or tickets; raise retry limits to hammer a failing provider; replay `OUTCOME_UNKNOWN` tool calls.
- **Data-loss risk:** none; tokens spent on failed attempts are still accounted.
- **Verify:** the connection test passes; a short run completes; token accounting shows only the expected usage. Real Anthropic connectivity was **NOT VERIFIED** in this repository's tests (a deterministic mock provider was used).

## 6. MCP subprocess crash
- **Symptoms:** tool calls fail with `MCP_UNAVAILABLE`; orchestrator log `mcp_reconnect` or `mcp_stdout_read_failed`; a tool call that was being executed ends as `OUTCOME_UNKNOWN`.
- **Diagnostics:** `COMPOSE logs ai-orchestrator | grep -E "mcp_"`; the captured stderr tail (bounded, redacted); container memory; the server command and its dependencies inside the image.
- **Safe recovery:** the orchestrator restarts the stdio server on the next call. If it keeps crashing, fix the server (or disable it by removing it from `AIO_MCP_SERVERS` and recreating the orchestrator) rather than raising limits.
- **Never:** replay the interrupted call; run the MCP server outside the container "to see"; set limits to their maximum to hide a crash loop.
- **Data-loss risk:** the interrupted call's external effect is unknown (procedure 11).
- **Verify:** a read-only tool call succeeds; no `mcp_reconnect` loop; container RSS stable.

## 7. Oversized MCP stdout
- **Symptoms:** a tool call fails with a bounded-output error; log `mcp_output_limit_exceeded kind=… limit_bytes=…` or `mcp_invalid_message`; an aggregate limit (`mcp_max_call_bytes`, default 4 MiB per call) or the flood limits (`mcp_stdout_flood_bytes` 16 MiB / `mcp_stdout_flood_messages` 50,000) were hit.
- **Diagnostics:** which server and tool (`server=`, `tool=` fields); whether the tool legitimately returns large data; whether the server writes non-protocol text to stdout.
- **Safe recovery:** make the tool paginate or return less; fix a server that logs to stdout (it must log to stderr). Limits can be adjusted only inside their validated ranges and only with a reason (`AIO_MCP_MAX_MESSAGE_BYTES` 64 KiB–16 MiB, `AIO_MCP_MAX_CALL_BYTES` 64 KiB–256 MiB).
- **Never:** disable the limits; read unbounded output into memory; replay a call that had started (the outcome is classified as unknown when input was already written).
- **Data-loss risk:** none for stored data; the call's effect, if the tool writes, is unknown.
- **Verify:** the same call with smaller output succeeds; orchestrator memory unchanged after the retry.

## 8. Excessive MCP stderr
- **Symptoms:** `mcp_*` warnings about stderr volume; the orchestrator is slow; log volume spikes.
- **Diagnostics:** `AIO_MCP_STDERR_FLOOD_BYTES` (8 MiB per 10 s window) exceeded; which server; whether a debug flag is enabled in the server.
- **Safe recovery:** turn the server's verbosity down; the orchestrator keeps only a bounded redacted tail (`AIO_MCP_STDERR_CAPTURE_BYTES`, 16 KiB) and drops the rest.
- **Never:** forward raw stderr to a shared log aggregator (it may contain tool arguments); raise the flood limit to hide it.
- **Data-loss risk:** none.
- **Verify:** stderr volume back to normal; no truncation warnings.

## 9. Stuck workflow
- **Symptoms:** a run stays `RUNNING`, `WAITING_APPROVAL` or `CANCEL_REQUESTED` far beyond its step timeout or deadline; the open-run limit (3 per organization) blocks new launches.
- **Diagnostics:** the run page (current step, attempt, failure category); `SELECT id, status, updated_at FROM workflow_runs WHERE status IN ('PENDING','RUNNING','WAITING_APPROVAL','CANCEL_REQUESTED') ORDER BY updated_at;` the matching `agent_executions` row; the pending approval (procedure 10); orchestrator and Core logs for the run id.
- **Safe recovery:** wait for the Core maintenance cycle (`STEP_TIMEOUT` / `DEADLINE_EXCEEDED` close it); otherwise **cancel the run from the UI/API** (a cancel grace period applies); an unanswered approval is decided or lets expire; if the orchestrator is down fix it (procedure 2).
- **Never:** `UPDATE workflow_runs SET status = …`; delete steps or messages (`workflow_messages` is append-only by trigger); raise `maxOpenRunsPerOrganization`.
- **Data-loss risk:** cancelling discards the remaining steps; completed steps and their results are kept.
- **Verify:** the run is terminal; the open-run count for the organization decreased; a new run can launch.

## 10. Expired approval
- **Symptoms:** an approval card shows `EXPIRED`; the run reports `APPROVAL_EXPIRED`; the tool did not run.
- **Diagnostics:** `tool_approvals.status`, `expires_at`; whether the approver was notified; the invocation status `EXPIRED`.
- **Safe recovery:** re-run the task (or retry the step) so a **new** approval is created. An approval is bound to the SHA-256 of the exact arguments; an old decision cannot be reused for different arguments.
- **Never:** change `status`/`expires_at` in SQL; approve from a copied hash after expiry.
- **Data-loss risk:** none; the tool call was not executed.
- **Verify:** the new approval shows the redacted arguments and, once decided, `decided … by <member name>`; the run resumes.

## 11. `OUTCOME_UNKNOWN`
- **Symptoms:** a tool invocation or step is `OUTCOME_UNKNOWN`; the run stops and is **not** retried automatically; the final answer says the result is unknown.
- **Diagnostics:** the tool call card (tool, redacted arguments, time); orchestrator log around the crash/timeout; the **target system** (did the write happen?); `SELECT status, started_at FROM tool_invocations WHERE status = 'OUTCOME_UNKNOWN';`
- **Safe recovery:** establish the real outcome in the external system. Only if the effect did **not** happen, re-run the task so a new invocation (new approval if required) is created. If it did, record that and continue manually.
- **Never:** flip the status to `FAILED`/`SUCCEEDED`; replay with the same approval; script a blind retry; claim exactly-once delivery (the platform is at-most-once for tools with side effects and reports uncertainty honestly).
- **Data-loss risk:** a duplicate external effect if replayed blindly; a missing effect if ignored.
- **Verify:** the external system is consistent; the new invocation (if any) is `SUCCEEDED`; no other `OUTCOME_UNKNOWN` rows remain without a documented decision.

## 12. Retry exhaustion
- **Symptoms:** a step failed after its automatic retries (`maxRetriesPerStep`, at most 2); the run is `FAILED`; the failure category is shown.
- **Diagnostics:** the step's attempts (`launch_reason` `INITIAL`/`AUTO_RETRY`/`MANUAL_RETRY`) and categories (`TRANSIENT_PROVIDER_FAILURE`, `PERMANENT_PROVIDER_FAILURE`, `TOOL_FAILURE`, `OUTCOME_UNKNOWN`, `AUTHORIZATION_FAILURE`, `WORKFLOW_LIMIT_EXCEEDED`, `UNCLASSIFIED`).
- **Safe recovery:** fix the cause (provider, tool, authorization), then use the **manual retry** offered by the UI for the failed step; permanent, authorization and unknown-outcome failures are not auto-retried and the UI restricts manual retry.
- **Never:** raise retry counts to mask a permanent failure; retry an `OUTCOME_UNKNOWN` step without procedure 11.
- **Data-loss risk:** none; each attempt is recorded and token usage accumulates against the budget.
- **Verify:** the retried step succeeds; the run completes or the next failure is understood.

## 13. Token budget exhaustion
- **Symptoms:** run fails with `TOKEN_BUDGET_EXCEEDED`; a step is refused before launch because the reservation does not fit.
- **Diagnostics:** the run's token usage versus the workflow's `tokenBudget` (1,000–10,000,000); the per-worker reservation; unusually long prompts or loops.
- **Safe recovery:** reduce work per run, or edit the workflow definition (a new version) with a justified larger budget; re-run.
- **Never:** edit counters in the database; remove the budget; run loops of retries to "use up" the remainder.
- **Data-loss risk:** none; the partial results of completed steps are kept.
- **Verify:** the new run finishes within budget; accounting totals match the sum of the steps.

## 14. Encryption key loss
- **Symptoms:** after a restore or an environment change, tool results appear **missing** with the Core log line `tool result of invocation <id> could not be read` (HTTP 200, no result shown), provider connections fail to resolve their credentials, or paused runs fail with `STATE_UNAVAILABLE`.
- **Diagnostics:** was `SECRETS_MASTER_KEY` or `ORCHESTRATOR_STATE_ENCRYPTION_KEY` changed or absent? compare the key's source with the secret manager and with the key that was current at the backup time (compare **fingerprints you computed earlier**, never the values).
- **Safe recovery:** restore the **original** key from its separate backup (escrow with its own access control) and restart the service. Nothing else recovers sealed data.
- **Never:** generate a new key to "make it start" (the services deliberately refuse to start without one, and a different key makes old sealed data unreadable); print or paste a key; store a key in the repository, an image or the database.
- **Data-loss risk:** **permanent** loss of the sealed tool results, the stored provider credentials (users must re-enter them) and the paused runs' conversation state if the keys are gone. Results are not reconstructable. Key **rotation** (re-encrypting under a new key) is **not implemented**; the format carries a key-id byte so it can be added.
- **Verify:** with the original key, the result of an old invocation is readable through the API, a provider connection test passes, a paused run resumes.

## 15. Failed checkpoint decryption
- **Symptoms:** an approved run fails on resume with `STATE_UNAVAILABLE`; no tool is executed; the Core API shows the run as failed with that code.
- **Diagnostics:** wrong/changed `AIO_STATE_ENCRYPTION_KEY` (procedure 14); a checkpoint copied between executions (binding mismatch is rejected by design); a truncated/modified row; `AIO_STATE_ACCEPT_LEGACY_FORMAT` turned off while format-1 blobs remain.
- **Safe recovery:** restore the original key; if only legacy blobs remain and the switch was turned off, turn it back on until those runs finish. A tampered row cannot be repaired: close the run (cancel) and re-run it.
- **Never:** edit `orchestrator_runtime.checkpoints`; disable authentication of the state; decrypt "manually" with a guessed key.
- **Data-loss risk:** the conversation of that paused run is lost if the key is lost or the row is damaged; the Core API history and the tool invocation records remain.
- **Verify:** the run resumes (or is cleanly cancelled); no execution proceeds with a tool call when decryption failed.

## 16. Database backup
- **Symptoms (when needed):** scheduled backup, before an upgrade, before a migration, before a key or infrastructure change.
- **Diagnostics:** free disk on the dump target; the dump's size and `pg_restore -l` listing; the key backup is current.
- **Safe recovery / procedure:** per database (`ai_workforce`, `ai_orchestrator`; Keycloak's database separately): `docker exec <postgres> pg_dump -U <superuser> -Fc -d <db> -f /tmp/<db>.dump`, copy it out, store it **encrypted**, with the matching keys stored **separately**. Measured on the development dataset: seconds; this says nothing about production size.
- **Never:** keep a dump next to its keys; email or paste dumps; treat a dump as a backup before a restore test succeeded.
- **Data-loss risk:** logical dumps give an RPO equal to the dump interval; WAL archiving / point-in-time recovery is **not configured** (NOT VERIFIED). Dumps taken before V11 contain plaintext tool results; old backups keep them.
- **Verify:** `pg_restore -l` works; a periodic restore into a **new, uniquely named** database passes procedure 17's checks.

## 17. Database restore
- **Symptoms (when needed):** data corruption, deleted data, disaster recovery, environment cloning.
- **Diagnostics:** which dump, which key set, which application version (a dump older than V11 restores plaintext results; the next start converts them).
- **Safe recovery / procedure:** (1) provision the server (roles, `CREATE EXTENSION vector` as superuser, grants, default privileges — `infra/docker/postgres/init-roles.sh`); (2) `CREATE DATABASE <new_name> OWNER <migration role>`; (3) `pg_restore --exit-on-error -d <new_name>` as the superuser **keeping ownership and ACLs**; (4) start a Core API instance against the new database and check the checks below; (5) only then switch traffic. Never restore over the live database.
- **Never:** `pg_restore --no-owner` (the restored tables belong to the superuser and the application fails with `permission denied for table flyway_schema_history`); restore with a different key; restore into the live database; drop the original before verification; silently generate a replacement key.
- **Data-loss risk:** everything after the dump; sealed data is unreadable without the original keys (procedure 14).
- **Verify:** `flyway_schema_history` complete and successful; row counts match the source; an old tool result is readable through the API; a sealed checkpoint opens; a login and a short run work.

## 18. Failed Flyway migration
- **Symptoms:** the Core API does not start; log `Migration of schema "public" to version "N - …" failed! Changes successfully rolled back.`; or `Validate failed` (a checksum differs).
- **Diagnostics:** the exact message and version; `permission denied to create extension "vector"` (the server was not provisioned: procedure 17 step 1); `permission denied for table flyway_schema_history` (wrong ownership after a restore); a checksum error means an applied migration file was modified.
- **Safe recovery:** fix the environment (provision, ownership) and restart; PostgreSQL DDL is transactional so a failed migration leaves the schema at the previous version. For a checksum error, restore the original migration file from version control. If the data is in doubt, restore from the pre-upgrade backup into a new database (procedure 17).
- **Never:** edit an applied migration; `flyway repair` or edit `flyway_schema_history` without understanding why; run the new version against the production database before rehearsing the upgrade on a restored copy; disable validation. V11 is additive (new column) and its backfill is verified per row (`failed_verification` must be 0).
- **Data-loss risk:** none from a rolled-back migration; high from manual history edits.
- **Verify:** version 11 successful; the backfill line reports `failed_verification=0`; application health `UP`.

## 19. Invalid HTTPS configuration
- **Symptoms:** the Core API refuses to start with a message such as `app.security.oidc.jwk-set-uri must use HTTPS outside the dev and test profiles` or `… must not contain credentials`; the orchestrator refuses to start with `… must use HTTPS in production`; calls fail with certificate errors (`CERTIFICATE_VERIFY_FAILED`, `PKIX path building failed`, host-name mismatch, expired).
- **Diagnostics:** which property (the message names it, never its value); the active profile; the certificate chain, expiry and subject alternative names (`openssl s_client -connect host:port -servername host`); whether the internal CA is configured (`SSL_CERT_FILE` for the orchestrator, the JVM trust store for the Core API); clock skew.
- **Safe recovery:** correct the URL to a credential-free `https://` one; install the correct certificate (full chain, SAN matching the name actually used); add the internal CA to the trust store **only**; renew an expired certificate; restart.
- **Never:** set `verify=False`, a trust-all manager, or a host-name bypass; fall back to `http://`; put a certificate or private key in an image or in the repository; use a dev profile in production; claim mutual TLS (it is not implemented).
- **Data-loss risk:** none; services stay down until fixed (fail closed by design).
- **Verify:** both services start; `openssl s_client` shows a verified chain; an end-to-end run works; logs contain no token.

## 20. Credential rotation
- **Symptoms (when needed):** scheduled rotation, suspected leak, staff change.
- **Diagnostics:** which credential: (a) **provider key** (stored encrypted, write-only); (b) **orchestrator client secret** in Keycloak and its copy in `infra/docker/.env` (`ORCHESTRATOR_CLIENT_SECRET`); (c) **database role passwords**; (d) **user passwords** (Keycloak); (e) the two **encryption keys** (no online rotation: see below).
- **Safe recovery:** (a) enter the new key in the providers dialog (never returned by the API), then test the connection and revoke the old one at the provider; (b) set the new secret in Keycloak, update the environment file, `COMPOSE up -d ai-orchestrator`, verify a run, then retire the old one; (c) re-run the provisioning script after changing the role password in the environment, restart services; (d) through Keycloak.
- **Never:** write a secret in a ticket, chat or log; rotate (b) in one place only; **replace an encryption key** — there is no re-encryption tool, a new key makes existing sealed data unreadable (procedure 14).
- **Data-loss risk:** a half-applied rotation causes an outage, not data loss; replacing an encryption key causes data loss.
- **Verify:** the orchestrator obtains its service token; a run completes; the old credential is rejected; no secret in logs (`grep` for the first characters is **not** acceptable, use the redaction tests' patterns).

## 21. Failed orchestrator recreation
- **Symptoms:** after an image update the container restarts in a loop, is `unhealthy`, or `/health/ready` stays 503; a configuration error is printed and the process exits.
- **Diagnostics:** `COMPOSE logs --tail=100 ai-orchestrator` (missing `AIO_STATE_ENCRYPTION_KEY`, a non-HTTPS URL in production, invalid limits); `docker image ls` (which tag is running); healthcheck result.
- **Safe recovery:** build the new image under a **new tag** (do not overwrite the previous one), recreate only that service (`COMPOSE up -d --no-deps ai-orchestrator`); if it fails, point the compose image back to the previous tag and recreate. The checkpoint database is untouched, so paused runs resume on whichever version starts with the same key.
- **Never:** remove the previous image before the new one is verified; change the key; recreate the database container to "reset" things.
- **Data-loss risk:** runs that were `RUNNING` during the swap are lost (the Core API closes them; unknown tool outcomes: procedure 11); paused runs survive.
- **Verify:** `/health/ready` is 200; the image id/tag is the intended one; a read-only tool call works; a paused run resumes.

## 22. Recovery after application restart
- **Symptoms:** planned or unplanned restart of the Core API, the orchestrator, or the whole host.
- **Diagnostics:** start order (PostgreSQL, Keycloak, Core API, orchestrator); time since restart versus `EXECUTIONS_TIMEOUT`; runs still `RUNNING`; `OUTCOME_UNKNOWN` rows; the backfill line.
- **Safe recovery:** start dependencies first; wait for health; let the reaper and the workflow maintenance cycle close orphans (executions beyond the timeout; their running invocations become `OUTCOME_UNKNOWN`); resume approvals that were decided during the outage (the orchestrator picks them up); review `OUTCOME_UNKNOWN` rows with procedure 11.
- **Never:** force-kill services during a Flyway migration; manually mark executions complete; restart the orchestrator repeatedly while the Core API is down.
- **Data-loss risk:** in-flight (non-paused) executions are lost; their external effects may be unknown.
- **Verify:** Core `UP`, orchestrator ready; no run is `RUNNING` without recent progress; `SELECT count(*) FROM tool_invocations WHERE status = 'RUNNING';` is 0 once the timeout has passed; a new run completes.

---

## Appendix A — What was exercised for real in Sprint C3.6, and what was not
Exercised against the real Core API, PostgreSQL, a real orchestrator container and (for the UI) a real Electron window: Core and orchestrator outage and restart recovery, orchestrator recreation from a fresh image, unknown-outcome reporting, MCP flood/oversize/crash behaviour (real subprocess), checkpoint tamper/wrong-key rejection, V10→V11 upgrade with data, fresh V1→V11, dump/restore of both databases with encrypted data read afterwards, HTTPS rejection of untrusted/expired/mismatched certificates (real TLS handshakes in tests).
**Not exercised:** a production TLS terminator, WAL archiving and point-in-time recovery, production-scale restore times, key rotation (not implemented), real Anthropic connectivity, Linux/macOS Core/Electron runs beyond those listed in the report, packaged installers.
