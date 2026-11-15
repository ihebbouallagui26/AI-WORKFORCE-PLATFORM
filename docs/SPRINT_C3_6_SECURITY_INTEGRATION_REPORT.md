# Sprint C3.6 — Final security audit & full system integration (work in progress)

Status of this document: **partial**. It currently covers the real-stack end-to-end scenarios A–I (sections 1–8) and the
real Electron window validation (section 9). Security fixes 8.1–8.7, migration/backup checks, dependency audit, performance
figures and the final status are **not done yet** and are therefore not claimed here. The product changes so far are the run-page fix of section 9.6
(`run-page.ts`, `run-monitor.ts`) and security fix 8.4 of section 10 (MCP output limits); all uncommitted. Sections 1–9.5 describe the state before them.

## 1. Test environment (what is real and what is not)

| Element | Reality |
|---|---|
| Keycloak 26.8.2, PostgreSQL 18 (pgvector) | **Real**, the existing dev containers. New test databases only: `aiwf_c36` (Core API), `orch_c36` (orchestrator checkpoints). No volume was touched or recreated. |
| Core API | **Real**, host process (`java -jar` of the C3.6 branch build), port 8081, Flyway V1–V10. Test-only settings: `EXECUTIONS_TIMEOUT=PT90S` (production default PT5M) so a lost orchestrator is detected in about 2 minutes. |
| Orchestrator | **Real** image `ai-workforce/ai-orchestrator:local` in a dedicated container `aiwf-c36-orchestrator` (read-only root, all capabilities dropped, `no-new-privileges`), port 8191. Started with `docker run` (`orch.sh`), **not** with the Compose service: Compose was not used for the orchestrator. "Recreation" below means `docker rm -f` + `docker run` of the same definition (same image, network, database and encryption key). |
| MCP | **Real** allowlisted `diagnostic` stdio server, one real subprocess per execution inside the orchestrator container. |
| AI provider | **Mocked**: a deterministic fake Anthropic API. It runs inside the orchestrator container, so it restarts with the container (its in-memory counters reset). **Real Anthropic connectivity is NOT VERIFIED.** |
| Users / tokens | Temporary Keycloak client `c36-e2e` and users `c36-*` (password grant for REST). Secrets never printed. |

Git: branch `feat/workflow-security-integration-c3-6`, working tree clean before this document. Nothing committed or pushed.

## 2. Results summary

| Scenario | Result | What was really executed |
|---|---|---|
| A auth + multi-tenancy | 14/14 | Real REST calls with real Keycloak tokens (tampered/absent/user-on-internal) |
| B agent lifecycle | 6/6 | Real execution through the real orchestrator container |
| C MCP + approval | 13/13 | Real MCP subprocess, real approvals (approve, reject, duplicate, wrong hash, cross-org) |
| D sequential, E coordinated, F parallel | 11/11 | Real runs; peak 3 concurrent provider calls in F |
| G transient failure, one worker retried | 10/10 | See 3 |
| H OUTCOME_UNKNOWN | 23/23 | Real worker executions + real `docker kill`; the in-flight call was **simulated at Core level** (see 4) |
| **I-A** Core API restart during parallel run | 10/10 | **Real process restart** |
| **I-B** orchestrator recreation during worker | 8/8 | **Real container kill + recreation** |
| **I-C** orchestrator recreation during WAITING_APPROVAL | 13/13 | **Real container kill + recreation**, real C2 approvals |
| **H2** real MCP subprocess in flight | 5/5 | **Real subprocess frozen mid-call + real container kill + recreation** |

## 3. Scenario G (retry of one worker only)

Workflow `0f708760-2c43-4ca9-9155-c74e2f73f7c8`, run `e3925988-bc3b-4cc9-a02d-2619e29abe75`, policy RETRY (1 retry per step), 3 parallel workers.
A `492980df…` and B `f8a310cf…` succeeded once. C attempt 1 `48f2df10…` FAILED with `TRANSIENT_PROVIDER_FAILURE`, `retry_safe=true`
(the provider answered 529 three times, which exhausts the orchestrator's internal 2 retries); C attempt 2 `96063f92…` (`AUTO_RETRY`) SUCCEEDED.
Provider calls: 1 + 1 + 3 (failed attempt) + 1 (retry). Tool calls: 0. Tokens: run total 126 = 3 × 42 = sum of executions; the failed attempt reported
no usage, so it counted 0 (a failed attempt with non-zero usage was **not** exercised live; it is covered by C3.3 tests).

## 4. Scenario H (OUTCOME_UNKNOWN) and its limit

Parallel run `2b05de6b-8065-4838-9496-a1ad375c3883` and sequential run `110c1b0a-0b08-4c54-ac59-96d9f68fdb8f`. The tool call was authorized and
begun through the Core internal API **with the orchestrator's service identity** (the orchestrator itself never ran it), then the orchestrator
container was killed. After 120 s the Core closed both calls as `FAILED/OUTCOME_UNKNOWN`, no retry (automatic or manual: HTTP 409), siblings
kept their results, Q2 never started, replayed `begin` → 409. The manual-retry refusal was the generic "run is FAILED, not WAITING_RECOVERY"
rule, not the specific unknown-outcome message. This scenario alone does **not** prove behaviour with a real subprocess: that is H2.

## 5. Scenario I

### I-A — Core API restart during a parallel run (real process restart)
Workflow `44e96d2d-f9c7-4b9c-8908-88e10fe496c9`, run `2574e68e-4395-45cf-b4bb-508c5d7389da`; stage 1: A (2 s), B and C (40 s), stage 2: D (merge, pending).
Before: A SUCCEEDED, B and C RUNNING, D not started, 3 executions. The Core API process was killed and restarted (PID 4372 → 16268, healthy after 23 s;
PostgreSQL, orchestrator and fake provider untouched). After: run SUCCEEDED, 4 steps / 4 executions (no duplicate, A's execution unchanged),
tokens 168 = sum of executions, no step started after the deadline. A delayed `complete` sent with the orchestrator identity for a terminated
execution (`FAILED/TIMEOUT`) was refused (409) and changed nothing; the old OUTCOME_UNKNOWN run stayed FAILED.
**Limit:** the outage (23 s) was shorter than the workers (40 s), so no completion report arrived while the Core was down; the Core-down + completion-retry path was not exercised.

### I-B — Orchestrator container recreation during a worker
Workflow `8f52cbc2-7776-46d2-8a35-799bc713e570`, run `e377a866-4b06-4477-9923-80613068d12e`. B (45 s provider call) RUNNING, A done. Container `369bd8af300b`
… see section 6 for IDs. After recreation and the 90 s test timeout: B attempt 1 FAILED `TRANSIENT_PROVIDER_FAILURE`, attempt 2 `AUTO_RETRY` SUCCEEDED,
then C ran; run SUCCEEDED in 151 s. A never relaunched, no duplicate execution, tokens 126 = 126, no tool call, key fingerprint unchanged, 485 checkpoint rows kept.
**Observation:** an orchestrator lost during a provider call (no tool call) is labelled `TRANSIENT_PROVIDER_FAILURE`; the behaviour is safe (no external effect), the label is imprecise.

### I-C — Orchestrator recreation during WAITING_APPROVAL
Org `01a1df32-1b65-7eb4-ad29-4cf51569c528`, agent `01a1df32-1cce-7a81-91e7-199b3be28af4`, executions `b71b7a69…` / `a18be745…`, approvals `11571b7e…` / `c4711caa…`.
Both WAITING_APPROVAL with 7 checkpoint rows for the first; container recreated; both still WAITING_APPROVAL; a premature `resume` of execution 2
returned 409 "The approval has no decision yet"; approving approval 1 resumed **only** execution 1 on the new container (SUCCEEDED, tool invocation SUCCEEDED ×1);
duplicate decision 409; rejecting approval 2 ended execution 2 (invocation REJECTED ×1, tool never ran); a late approve of the rejected one 409; no credential in any response.
**Not verified:** approval expiry (TTL 15 min by default; not waited). **Checkpoint confidentiality, honest scope:** across all 515 checkpoint blobs/writes/JSON rows the task text and tool arguments
appear in plaintext **0** times (a positive control confirmed the query sees plaintext). However the `__start__` channel (60 rows) is plain msgpack containing organization/execution identifiers.
Whether the paused state is AES-GCM sealed and under which AAD is part of audit item 8.3 and is **not** concluded here.

### H2 — Real MCP subprocess interrupted
A probe of the real subprocess I/O showed one server process per execution (initialize, tools/list, then two exchanges). A watcher inside the container sent `SIGSTOP`
to that real process as soon as the server had read the second post-list request (inferred to be `tools/call` from the byte sequence, not from a protocol capture) and before it answered.
Execution `84dab741-502a-4803-85d2-4965e35ee3cf`, invocation `22afac57-b86e-4ff5-9b3f-4023f3f19fdb`: process state `T (stopped)`, Core invocation `RUNNING`, execution `RUNNING`.
The container was then killed and recreated. The Core closed the call `FAILED/OUTCOME_UNKNOWN` and the execution `FAILED/TIMEOUT` (not cancelled); still exactly 1 invocation 25 s after the new orchestrator was healthy; `begin` → 409.
**NOT VERIFIED:** a persistent *external* invocation counter. The allowlisted diagnostic server has no persistent side effect and the closed allowlist forbids adding a custom MCP server without a product change.
The proof of "not replayed" is therefore at the Core-record level (1 invocation, never re-begun) and by process inspection, not by an external counter.

## 6. Container identity trail
Orchestrator container IDs, in order (each change = real `docker kill` + `docker rm` + `docker run`): `c8b83b909576` → `369bd8af300b` (I-B) → `31efacb09cc6` (I-C)
→ `91dc29fb5794`, `909ebe9ae7e2`, `ed35275aa71c` (three H2 attempts whose watcher was defective, test-tooling errors only) → `b42073861344` (H2 valid run) → `479a19e5c5fd` (current).
Image `ai-workforce/ai-orchestrator:local` (`a5678a49…`) unchanged. PostgreSQL `aa1f75d7999d` and Keycloak `f8e890c913a8` never restarted. Encryption key fingerprint (first 10 hex of its SHA-256) `f47a976be5` before and after every recreation.

## 7. Defects and corrections
No product defect was found in scenarios G, H and I; no product code was modified. Failed attempts during this work were test-tooling errors
(wrong parallelism settings, fake provider counter keyed on the task hash, watcher bugs) and were fixed in the scratch scripts only.

## 8. Unresolved limitations / risks carried forward
1. External MCP effect counter: NOT VERIFIED (section 5, H2).
2. Approval expiry after a restart: NOT VERIFIED.
3. Core outage overlapping a completion report: not exercised (I-A).
4. Orchestrator-lost label `TRANSIENT_PROVIDER_FAILURE` is imprecise (observation, low).
5. `__start__` checkpoint channel unsealed (identifiers only): to be assessed in 8.3.
6. Compose-managed orchestrator recreation, Linux, real Anthropic: NOT VERIFIED.
7. Remaining C3.6 work: 8.1–8.7, migrations backup/restore, dependency audit, performance, runbooks, final regression and final status.

## 9. Real Electron Window Validation

### 9.1 What was run
- Build: `npm run build` in `apps/desktop` (Angular production build + Electron `tsc`), then the repository launcher `node scripts/launch-electron.cjs` (the `start:built` procedure),
  Windows 11, Electron 45.2.3 / Chromium 156, unpackaged, with `--remote-debugging-port=9333` and an isolated `--user-data-dir` (scratch folder, user profile untouched).
- Real window: the Electron main process (PID 25592) owned a native window handle and the title `AI Workforce Platform` (e.g. `Sign in · AI Workforce Platform`, `Settings · …`). The renderer served `app://workforce` from `ui/dist/ui/browser`.
- Real stack behind it: Keycloak, PostgreSQL, Core API (host), orchestrator container, deterministic fake provider (section 1). Test data only, in the dedicated organization `C3.6 Electron UI org (test data)` (`01a1df52-a4b9-78f5-96e6-4c5a3ea6dc6e`).
- **Test harness (the only deviations from a human session):** (a) the login step "open the system browser" (`shell.openExternal`) was replaced, inside the main process via `NODE_OPTIONS=--require hook.cjs`, by a script that submits the Keycloak login form;
  loopback server, PKCE, state, nonce, code exchange, token store and IPC were the application's own. The Keycloak page itself was therefore **not viewed**. (b) The UI was driven through Chrome DevTools Protocol
  (DOM clicks and native-setter input events; one real `Escape` key press through `Input.dispatchKeyEvent`), and screenshots are CDP captures of the real renderer, not OS-level captures of the window.
  Scripts: `docs/evidence/c3-6-electron/{hook.cjs,cdpd.cjs,start_el.sh}`; 40 screenshots in the same folder.

### 9.2 Results

| Area | Result | Evidence |
|---|---|---|
| Startup, login screen | PASS: no blank screen, dark theme | `01-login-screen` |
| Keycloak PKCE login | PASS: auth URL carried `client_id, code_challenge(+method), nonce, redirect_uri, response_type, scope, state` and no secret; loopback callback accepted; `/dashboard` shown | hook log, `02-after-login` |
| Profile, organization selection | PASS: switcher lists the user's organizations, breadcrumb and API scope follow the choice; Profile shows the identity; renderer storage holds only `awp.preferences.v1`, no cookies, no token-like string in the DOM | `03`, `04`, `44-profile` |
| `/workflows` overview, filters, empty state, detail | PASS: real counts (1 definition, 0 runs at first), filter chips (All/Pipeline/Coordinated), "No match" empty state, detail of the pre-existing sequential workflow with its stages (compatibility OK) | `05`–`08` |
| `/workflows/new` PIPELINE editor | PASS: required-field validation (5 messages, nothing created), participants, 3 parallel steps in stage 1 + fan-in stage 2, parallel limit, RETRY limits, token/time limits, "every participant must be used" rule, review block, save, persisted (backend confirms) | `09`–`12`, `15` |
| COORDINATED editor | PASS: coordinator chosen among participants, roles summary, limits, persisted after reload; no UUID or secret in the visible text | `13`, `14` |
| Run launched from the UI (parallel, with an approval) | PASS: run `d1df4649-5f8e-4716-a290-41359c1f2378`. UI matched the backend at the same moment (A, B completed, C waiting approval; 84 tokens used, 4,000 reserved), then SUCCEEDED with 210 tokens, 4 steps, fan-in stage after the group, results per worker, 1 tool invocation | `16`, `18` |
| Approval review | PASS: card shows agent, tool, MCP server, risk, expiry, exact arguments + SHA-256 integrity note; "Confirm approval" disabled until the acknowledgement box is ticked; approve through the UI resumed the run; reject through the UI (reason stored) ended that call with the tool never run (`REJECTED`×1); history filters | `16`, `17`, `24`–`26` |
| Queued / running / waiting states | PASS: "Running", "Needs approval" and "Waiting for capacity" (real queue behind the 3-open-executions limit) are distinct | `19` |
| Cancellation | PASS: confirmation dialog describes the real consequences (queued cancelled, active stopped, approval closed, unknown outcome never shown as cancelled); a real `Escape` closes it, focus is trapped, "Keep running" changes nothing; confirming gave run CANCELLED, 3 steps Cancelled, approval closed, 0 tokens; "Cancel run" is disabled on terminal runs | `20`, `21` |
| OUTCOME_UNKNOWN | PASS (display of the real scenario-H runs): "Outcome unknown" worker, banner "not retried, replayed or reported as cancelled", stage "Partially failed", sibling results kept, no retry action; the sequential run shows the same, Q2 never shown as started | `22`, `23` |
| Unsupported parallel manual recovery | PASS: never offered; the editor disables the selector and both editor and run page say it is unavailable | `12`, `16` |
| COORDINATED run from the UI | PASS: run `43901440-cbc1-4b72-a30a-fafd9e85a5cf`: coordinator turn → 2 delegated workers in parallel → turn 2, 3 accepted coordinator actions, final result, 168 tokens (= backend), no UUID or secret visible | `50` |
| Session expiry | PASS: after revoking all Keycloak sessions of the user, the app detected it within a minute (`signed-out`, reason `expired`); the next guarded navigation redirected to `/login`, which says "Your session has expired. Please sign in again."; a new sign-in returned to the requested page | `40`, `41` |
| Logout | PASS: back to `/login`, session `signed-out`, API calls answer `unauthenticated`, guarded routes redirect with `returnUrl`, **0 Keycloak sessions left** for the user | `43` |
| Resolutions | PASS: no horizontal overflow at 1024×700, 1280×720, 1366×768, 1920×1080 (sidebar collapses to icons at 1024) | `30-*` |
| Console / IPC / security | PASS: see 9.3 | events log |

### 9.3 Console and security observations (real renderer and main process)
- 25 console/log events in the whole session; **0 unexplained** error, exception or unhandled rejection. The only errors are the ones my deliberate hostile probes triggered (CSP, blocked local resource, 403/404 traversals).
- Main-process log: only Electron deprecation warnings for `safeStorage.isEncryptionAvailable` / `encryptString` (information, to schedule); the single "Failed to load URL" line is the blocked external iframe of the probe.
- IPC (`window.desktop` exposes only `api.request`, `auth.*`, `getAppInfo`, `openExternal`): internal `/api/v1/internal/**`, `/actuator`, `..` and encoded traversal, absolute and protocol-relative URLs, DELETE on approvals, `TRACE`, non-object and null requests were all refused in the main process (`not-allowed` / `invalid-request`); an injected `Authorization` header was ignored (the real session was used).
  `openExternal` refused `file:`, `javascript:`, `data:`, `ms-msdt:`, `http(s)://evil.example` and the empty string; only the login URL was ever opened.
- Navigation: `window.open` returned null, an external `<a target=_blank>` opened nothing, the page URL never left `app://workforce`; one window only. `require/process/Buffer/module/global` are undefined in the renderer.
- CSP served: `default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; base-uri 'self'; form-action 'none'; frame-ancestors 'none'; object-src 'none'; connect-src 'self'` with `X-Content-Type-Options: nosniff`. External `fetch`, localhost `fetch`, external framing and inline script were blocked and logged. Path traversal through `app://` returned 403/404.
  (The `eval` probe returned a value only because CDP `Runtime.evaluate` bypasses the CSP; it is **not** evidence either way. The policy contains no `unsafe-eval`.)

### 9.4 Known UI defects and observations (as found; only item 1 was fixed afterwards, see 9.6)
1. **Medium — run page stuck on "Loading…" after a reload or deep link. RESOLVED, see 9.6.** Reproduced three times (reload, deep link, other organization). Root cause (read in code): `run-page.ts` starts the monitor in an `effect` that reads `this.store.can('workflow.read')` inside `untracked(...)`;
   `can()` derives from `AccessStore.permissions`, a `computed` of an asynchronous `resource` (organization details), which is empty at cold start, so `monitor.start` is never called and the effect does not re-run. The workflow detail page does not show it because it gates through the reactive `orgResource(...)`.
   Proposed fix: read the organization id and the permission in the tracked part of the effect (or reuse `orgResource`). Navigating inside the app works.
2. Low — the organization chip in the top bar is clipped/overlapped by the search box when the organization name is long (`05`, `16`).
3. Low — raw ISO timestamps are shown (`launched 2026-11-15T13:12:20.617915Z`, "Created", approval "Expires").
4. Low — the approval card shows the internal execution UUID.
5. Low — the approval history identifies the decider as `member 01a1df0c` (truncated id, no name): input for audit item 8.5.
6. Low — coordinated run: the column heading says "Coordinator turn 4" while its card says "turn 2" (internal rank vs turn number).
7. Low — the Overview page is Phase A demo data (badge "Demo data", 21 employees, "Review approvals (5)") and is not connected to the real approvals; Settings > Data source still says "not connected in Phase A" and "changes… are lost when the application restarts".
8. Info — after the session expires the authenticated shell stays visible until the next guarded navigation; the workflow list is cached and does not show definitions created outside the UI until reloaded.
9. Info (backend) — manually cancelled steps carry `failureCategory=WORKFLOW_LIMIT_EXCEEDED`; the UI shows "Cancelled", the label is only semantically odd in the API.
10. Info — the application has a single dark theme; no light theme exists to test.

### 9.5 Not verified
Human typing in the real Keycloak browser page and the system-browser hand-off; OS-level screenshots and real mouse/keyboard input (only DOM events and one CDP key press); the packaged installer build; Linux and macOS; high-DPI beyond the 1.25 scale of this machine;
approval *expiry* rendering (the "Expires" time is shown but no approval was left to expire); creating OUTCOME_UNKNOWN from the UI itself (the unknown-outcome runs were produced by scenario H and only displayed here); the WAITING_RECOVERY manual-retry screen for sequential runs; keyboard accessibility beyond Escape and the dialog focus trap.

### 9.6 Fix of the MEDIUM finding (run page stuck on "Loading…")
**Root cause (confirmed).** `WorkflowRunPage` started the monitor in an `effect` that read `this.store.can('workflow.read')` inside `untracked(...)`. `can()` reads `AccessStore.permissions`, a `computed` of the asynchronous organization-details `resource`,
which is empty at a cold start (reload, direct URL, organization switch). The effect therefore ran once with "no permission", never tracked the permission and never ran again when it arrived, so `monitor.start` was never called.

**Correction** (2 product files, no new dependency, no new authorization mechanism, no duplicated organization state):
- `features/workflows/run-page.ts`: the effect now reads the run id, `store.organizationId()` and `store.can('workflow.read')` in its tracked part. With an organization and the permission it calls `monitor.start(id)`; otherwise it calls the new `monitor.release()`. Only the side effects stay in `untracked`.
- `core/workflows/run-monitor.ts` (needed for required behaviours 6–8, found while testing):
  - the followed target is now the pair organization + run (before, only the run id, so a switch of organization or run while a poll was armed was not a new target);
  - `start()` for another run or organization clears what was shown (nothing of the previous run stays on screen) and is still idempotent for the same target (no second loop);
  - `release()` stops polling and forgets the run (permission lost / no organization);
  - latent defect fixed on the way: when the target changed while a request was in flight, the stale answer was dropped but the new target was never started (its own `start()` could not run a cycle meanwhile), so the new run stayed on "Loading…" until something else restarted it. The monitor now starts the new target as soon as the stale request ends;
  - a timer firing after the organization changed does not read the run in the other organization on its own.

**Regression tests** (13 new; the existing 280 unchanged):
- `workflows.spec.ts`, "Workflow run page: cold start with asynchronous permissions", using the **real** `AccessStore` and its real asynchronous resource (only the network is faked, with a delay on the organization details), never a permission already present at construction: cold start with empty permissions then permission arriving; organization id known later; normal internal navigation (permissions already loaded, no new organization request);
  reload (new injector); user without `workflow.read` (permissions DID load, nothing requested, "Your role does not allow viewing workflows."); organization switch (previous run disappears, only the new organization is read afterwards); route-parameter change (only the new run is read); six permission updates keep one polling loop (minimum gap between reads ≥ half the period); destruction stops reading.
- `run-monitor.spec.ts`: another run replaces and clears the previous one; the new run is followed even when the previous request was in flight; no read of the previous organization after a switch; `release()`.
- Proof that they detect the defect: with the original `run-page.ts` and the new tests, **7 of the 9 page tests fail** (cold start, organization later, reload, organization switch, route change, duplicate loops, destruction); the 2 that pass are the controls that must pass either way (unauthorized user, warm navigation).
  The in-flight monitor test failed on my first version of the fix (the restart was unreachable after a `return`), which is how that bug was caught. The monitor tests could not be run against the original monitor because they use `release()`, which did not exist.

**Results.** Focused specs 60/60. Full Angular suite **293/293** in 17 files (baseline 280: +13). Electron suite **46/46** (baseline 46). Angular production build and Electron `tsc` build (`npm run build`) succeeded without error. Core API and orchestrator: not touched, not re-run.

**Real window re-check** (same harness as 9.1, rebuilt bundle, session restored from secure storage): direct URL on a run page, then two reloads of that page, all loaded (`Refresh` button present) within 2 s; the original reproduction (switch to the scenario-H organization, then direct URL on its OUTCOME_UNKNOWN run) loaded and displayed "Outcome unknown";
0 console errors or warnings (5 events, all navigations). Screenshots: `docs/evidence/c3-6-electron/60-fixed-deep-link.png`, `61-fixed-other-org-deep-link.png`.

**Status: the MEDIUM finding is RESOLVED** (fix + regression tests + full Angular and Electron suites + real-window re-check). The LOW and INFO items of 9.4 (2–10) are intentionally untouched.
**Not covered:** real Keycloak session loss while a run page is open (the monitor then reports its error as before); packaged build.

## 10. Security Fix 8.4 — MCP Output Memory Limits

### 10.1 Original vulnerability (confirmed by measurement, not assumed)
Output of an MCP server was bounded only AFTER receipt, and the receipt itself was not bounded.

### 10.2 Data flow before the fix (audited in the code and in `mcp==2.3.0`)
`McpServerManager` → `Client(StdioServerParameters)` → SDK `stdio_client` → child process.
- **stdout**: `stdout_reader` reads 64 KiB chunks through `TextReceiveStream` and keeps `buffer = (buffer + chunk).split("\n")` (an unbounded `str`, quadratic re-concatenation) until a newline arrives; the whole line is then parsed by pydantic. The orchestrator's own limits (`max_output_bytes`, `AIO_TOOL_OUTPUT_MAX_CHARS`, Core `maxToolOutputChars`) only truncate the *normalized result* afterwards. A server that never sends `\n`, or sends one huge line, grows the buffer without limit. An asyncio `StreamReader` limit would not help: the SDK does not use `readline`; the unbounded structure is its own `str`.
- **stderr**: not captured. `stdio_client(errlog=sys.stderr)` hands the child the orchestrator's own stderr, so nothing is bounded or redacted and a chatty/hostile server writes straight into the container log (log amplification). No `communicate()` is used anywhere.
- A parse failure was logged with `logger.exception` by the SDK (pydantic error with an excerpt of the payload).
- Lifecycle and C2 invocation states were already sound (one task owns the connection; timeout/crash discard the child; Core `begin` → RUNNING → `complete`).

### 10.3 Root cause
The SDK stdio transport, which the platform used as is, trusts the server to frame its output; the platform's bounds were applied to the parsed result, not to the byte stream.

**Measured (same attack, Python heap peak via `tracemalloc`, real child process, Windows, Python 3.13):**

| Attack (stdout, ends with a newline) | SDK transport (before) | Bounded transport (after) |
|---|---|---|
| 16 MiB | peak **48.9 MiB**, 2.9 s | peak **1.6 MiB**, 1.3 s, refused at the limit |
| 64 MiB | peak **192.2 MiB**, 19.5 s | peak **1.6 MiB**, 1.1 s, refused at the limit |

The retained line buffer of the new transport peaked at 1,114,112 bytes = the 1 MiB limit + one 64 KiB chunk, whatever the attack size. In the regression test a 256 MiB single-write attack with a 64 KiB limit leaves the Python peak within 4 MiB of an ordinary call.

### 10.4 Implementation (`app/mcp/bounded_stdio.py`, used by `McpServerManager` and `registry.connect`)
`Client` accepts any `Transport`; a bounded transport replaces the SDK's `stdio_client` and keeps the same session streams and shutdown sequence (close stdin, grace period, kill the tree), reusing the SDK's own process helpers.
- **stdout**: bytes, chunks of at most 64 KiB, one line buffer whose size is checked BEFORE every append. Overflow is detected while reading, whether the data comes as one huge chunk, a stream with no newline, or a long line with a newline. On overflow: the process tree is terminated at once, nothing of the partial line is parsed or delivered (a truncated JSON-RPC message is never accepted), the session sees a closed stream, only a safe constant (`stdout-message-limit`) and the limit are recorded. Blank lines are skipped, an invalid message is reported as a short fixed error (never the payload) and logged with its length only.
- **stderr**: piped and drained concurrently with stdout (no pipe deadlock). Only the first `stderr_capture_bytes` are kept, control characters are replaced and credentials redacted, truncation is marked in the text; the rest is counted and discarded. More than `stderr_flood_bytes` in a 10-second window terminates the server (`stderr-flood`); the drain yields to the event loop on every chunk. The only log is ONE `mcp_stderr` line per connection with at most 2,048 redacted characters.
- **Process cleanup**: the SDK sequence is unchanged; additionally the stderr pipe is closed and the violation path terminates the process tree before anything else. Repeated enforced terminations leak no process and no handle (checked).
- Timeouts unchanged: a stall in the middle of an output ends at the call timeout (`TOOL_TIMEOUT`, process discarded, partial bytes never parsed).

### 10.5 Limits, defaults and justification (settings `AIO_MCP_MAX_MESSAGE_BYTES`, `AIO_MCP_STDERR_CAPTURE_BYTES`, `AIO_MCP_STDERR_FLOOD_BYTES`; validated, `.env` files untouched)

| Setting | Default | Allowed range | Why |
|---|---|---|---|
| `mcp_max_message_bytes` (one stdout line = one JSON-RPC message) | 1,048,576 (1 MiB) | 65,536 – 16,777,216 | The platform keeps at most 65,536 characters of a result (default 8,000, per-tool default 4,096); the SDK server repeats the text as structured content (≈2× on the wire, measured: 20,000 characters → ~40 kB) and JSON escaping can multiply by up to 6. 1 MiB = 16× the largest kept result and fits a tool list of 64 tools with the maximum 16 KiB schema each, while the worst retained buffer stays 1.1 MiB per connection. Same order as the service's request limit (1 MiB). |
| `mcp_stderr_capture_bytes` | 16,384 | 1,024 – 1,048,576 | Start-up errors and the first traceback are what diagnostics need. The head is kept. |
| `mcp_stderr_flood_bytes` (per 10 s) | 8,388,608 | 65,536 – 268,435,456 | ≈0.8 MB/s sustained: far above any logging server, low enough to cut a flood within seconds. |

The 4 existing C2 tests that depend on sizes were kept; ONE existing test (`test_oversized_output_is_truncated_to_the_limit_and_flagged`) encoded the vulnerable behaviour (accept a 2 MB message, then truncate); its premise changed: it now uses a result below the message limit (400,000 characters ≈ 0.8 MB on the wire), and the above-limit case is covered by the new tests.

### 10.6 External side-effect semantics
A call that exceeds a limit has already been started (`begin` done): the tool may have produced its effect while its result is lost and untrustworthy.
- `ToolOutputLimitError` carries the Core API's unknown-outcome code `OUTCOME_UNKNOWN` (never `TOOL_FAILED`, never a cancellation). The tool loop reports it via `complete` and then ENDS the execution (`TOOL_FAILED`), so the model cannot repeat a possibly completed action in a later turn; a Core API refusal of `complete` (call already closed, execution ended) does not change that, because the Core API remains the source of truth.
- Nothing replays the call: `McpServerManager.call` never retries (unchanged); the new tests prove it with an external counter file outside the server process: effect applied once, still once after the failure, after a reconnect and after the full chain.
- **Real Core API check** (PostgreSQL, Core on the C3.6 stack; the exact calls the orchestrator now makes; the production diagnostic server cannot emit more than 64 KiB, so the oversized output itself is covered by the real-subprocess tests): the invocation was recorded `FAILED / OUTCOME_UNKNOWN`, the step `OUTCOME_UNKNOWN` with `retry_safe=false`, **no automatic retry although the policy was RETRY with 2 retries** (12 s of scheduler passes), manual retry refused (409), a second `begin` refused (409), exactly 1 invocation. C2 approval behaviour untouched (all existing approval tests pass).

### 10.7 Regression tests
`tests/test_mcp_output_limits.py` (32 tests, real child processes via the extended adversarial server `tests/support/mcp_test_server.py`; only `launch_parameters` is redirected, the production allowlist is unchanged): SDK internals still present (pinned `mcp==2.3.0`); configuration bounds and settings validation; stderr capture unit test; normal handshake/discovery/call; large valid response below the limit; response over the limit; single huge chunk; huge response without newline; no truncated message accepted; small stderr kept and redacted; stderr over the capture limit drained and discarded; continuous stderr cut as a flood and the manager recovering; simultaneous stdout and stderr pressure; stall during an output; peak memory independent of attack size (`tracemalloc`); no leaked processes or handles over repeated terminations; normal call after an enforced termination; no log amplification and no secret in the logs; side effect never repeated (manager level); whole chain with the tool loop and a scripted Core API (unknown outcome reported, execution ended, one `begin`, one request, one model turn, counter = 1); Core API refusal of `complete`. Every test also asserts through the fixture that no MCP child process survives it.
While writing them, two defects of MY tests (not of the transport) were found and fixed: the SDK server diverts fd 1 to stderr, so the adversarial server must write to the private wire descriptor; and `structuredContent` doubles the wire size.

### 10.8 Results
- Python: **338 passed, 4 skipped** (baseline 306 passed, 4 skipped: +32 new tests, 0 failures, same 4 skips), ruff clean. Windows 11, Python 3.13.
- **Linux** (Python 3.13.18, kernel 6.6 WSL2, inside the orchestrator image with the working-tree code mounted): the new file and the 27 lifecycle tests, **59 passed** (the POSIX process-group termination path).
- Core API: no backend code or contract changed, tests not re-run; the real-stack check above used the running C3.6 Core API.

### 10.9 Residual risks and limits
1. The limit is **per message**. Many valid small messages are not memory growth (the session stream has no buffer, so the child blocks) but can use CPU/time; the call timeout bounds it. There is no per-call total-bytes cap.
2. Memory evidence is the **Python heap** (`tracemalloc`), not process RSS, and not the child's own memory; the kernel pipe buffer (64 KiB) is not counted.
3. stderr keeps only the **head**: the tail of a crash trace is discarded.
4. The transport uses private SDK helpers (`_stop_server_process`, `_terminate_process_tree`, `_drain_stdout`, …). They are pinned by `mcp==2.3.0` and a test fails loudly on an SDK change; an SDK upgrade needs a re-audit.
5. `TOOL_TIMEOUT` and `MCP_UNAVAILABLE` after `begin` are still reported as plain failures (the Core API already treats any started tool as not retry-safe, so no replay), not as `OUTCOME_UNKNOWN`: a pre-existing labelling gap, not changed here.
6. If the Core API is unreachable when the orchestrator reports the unknown outcome, the invocation stays `RUNNING` until the Core API's own recovery closes it as `OUTCOME_UNKNOWN` (the C3.6 scenario H mechanism).
7. The orchestrator container of the running C3.6 stack was **not rebuilt**: it still runs the pre-fix image; the fix is verified by tests (Windows and Linux) and the real Core API check, not by a rebuilt-image end-to-end run (the production allowlist cannot provoke a 64 KiB message). Windows `SelectorEventLoop` fallback process (stderr via `FileReadStream`) is implemented but untested.
8. A server that needs messages above `AIO_MCP_MAX_MESSAGE_BYTES` (for example an enormous tool list) is refused at discovery (`MCP_UNAVAILABLE`); raise the setting up to 16 MiB if that is ever legitimate.
9. Findings 8.1, 8.2, 8.3, 8.5, 8.6 and 8.7 are not addressed here; the claims about encryption in section 5 are unchanged.

### 10.10 Closure of 8.4 (stage 1 of the final hardening)
Items 1–9 above were re-examined; the ones that were gaps are now closed or explicitly bounded as follows.

**Rebuilt image (item 7 of 10.9).** `ai-workforce/ai-orchestrator:c36-8.4-validated` = `sha256:79a00e6bf95e…` built from the working tree (checked: bounded transport present, `mcp 2.3.0`, `diagnostic_server.py` identical to the working tree). The existing `:local` tag (`sha256:a5678a49…`) was NOT overwritten. For the real-stack test a clearly labelled **test-fixture layer** `…:c36-8.4-adversarial-fixture` (`sha256:ebd70e06…`, label "NEVER a production image") replaces only `app/mcp/diagnostic_server.py` with a copy that has the same tools and signatures plus trigger words; the production allowlist, tool registry, grants, approval policy and launch specification are the production ones. Only the dedicated container `aiwf-c36-orchestrator` was recreated (`f6fd59cec04e`, then `3a020c089a76` after a script error, then `7052edbc151f` during S5); PostgreSQL, Keycloak, `orch_c36`, volumes and the state key (fingerprint `f47a976be5`) are unchanged.

**Real Core API / PostgreSQL / rebuilt orchestrator image / real MCP subprocess / real container recreation / real Core API outage — 32/32** (`scratchpad` script `scen84.py`, mocked provider only):
normal initialization and call; valid ~0.8 MB response accepted; oversized stdout (3 MB line), 80,000 valid notifications and endless stdout without newline each give invocation `FAILED/OUTCOME_UNKNOWN`, execution `TOOL_FAILED`, the child process gone, the next call fine on a new connection; stderr flood terminates the server, logs stay at ~4 kB (limit event without payload, one bounded excerpt emitted when the dead connection is torn down); **approval policy intact** (the WRITE tool pauses, nothing runs before the decision, a wrong-hash approval is refused);
**effect then oversize**: effect counter (a file on a bind mount outside the container) = 1, invocation `FAILED/OUTCOME_UNKNOWN`, step `OUTCOME_UNKNOWN`, **no automatic retry under a RETRY-2 policy**, manual retry 409, container recreated, counter still 1, still 1 invocation and 1 attempt, `begin` 409;
**timeout after dispatch** (effect applied, tool never answers): `OUTCOME_UNKNOWN`, no retry, counter 1;
**Core API down while reporting**: the orchestrator's 4 report attempts failed, the invocation stayed `RUNNING`, and after the Core API restarted its own recovery closed it as `FAILED/OUTCOME_UNKNOWN` (step `OUTCOME_UNKNOWN`, no retry, effect once).

**Aggregate bounds (item 3 of 10.9).** Assessment: the per-message limit alone did not stop a stream of valid messages, which cost CPU/time (memory stays bounded because the session stream has no buffer) up to the call timeout. New limits (all validated settings): `AIO_MCP_STDOUT_FLOOD_BYTES` 16 MiB / 10 s (262,144 – 1 GiB), `AIO_MCP_STDOUT_FLOOD_MESSAGES` 50,000 / 10 s (100 – 10,000,000), `AIO_MCP_MAX_CALL_BYTES` 4 MiB per tool call = everything received while a call is in flight (65,536 – 268,435,456). Handshake and tool listing (a few kB) are far below them. Stderr stays a **rate** (8 MiB / 10 s) rather than a lifetime total: a persistent connection would otherwise be killed for healthy long-term logging; memory is bounded by the capture size whatever the volume, and a sustained flood is cut within a window. Decision recorded; no lifetime cap.

**Failure classification after `begin` (item 6).** The transport counts requests written to the server (`stdin_started`, counted before the write) and the call is classified from that evidence:

| Situation | Classification |
|---|---|
| Output limit enforced (the request was sent) | `OUTCOME_UNKNOWN`, execution ends |
| Request never reached the server's stdin (server failed before dispatch) | known failure `MCP_UNAVAILABLE` / `TOOL_TIMEOUT`; the agent continues |
| Server answered with a refusal before any tool code (parse error, invalid request, method not found, invalid params) or the tool reported its own error | known failure (`MCP_UNAVAILABLE` / `TOOL_ERROR`) |
| Timeout, connection lost, crash, internal error or unreadable answer AFTER dispatch | `OUTCOME_UNKNOWN`, execution ends, never retried |

The Core API already treated any begun tool as not retry-safe; now the unknown cases are also labelled correctly (and the pre-dispatch ones are not). Behaviour change: a post-dispatch timeout or crash now ENDS the execution instead of letting the model continue. **Core API unavailable while reporting**: the orchestrator retries the report 4 times (1 s, 2 s, 3 s pauses; idempotent: a repeated report gets 409 = already decided); if it still cannot, the execution fails `CORE_API_UNAVAILABLE` and the call stays `RUNNING` in the Core API's own database; the Core API's recovery closes it as `OUTCOME_UNKNOWN` (verified in S7). **There is NO durable local outbox in the orchestrator**: the durable record is the Core API's own `RUNNING` invocation, which is sufficient because the tool is never replayed; the diagnostic message of the unreported failure is lost (only the code `OUTCOME_UNKNOWN` is recovered).

**Memory (item 4).** Different quantities, kept apart:
- Python heap (`tracemalloc`): 256 MiB attack, peak < 4 MiB over baseline (unchanged).
- Process RSS (peak working set / `VmHWM`) of the test process: **Windows 98.6 → 99.0 MiB, Linux 131.6 → 131.8 MiB** after three 256 MiB attacks.
- Container level (Linux, Python 3.13, `--memory 1g`), same finite 128 MiB single-line attack through each image's own transport: **SDK transport (`:local`, before): peak RSS 67.2 → 963.9 MiB** (7.5× the attack, almost the 1 GiB container limit); **bounded transport (after): 68.0 → 69.5 MiB**.
- The running rebuilt orchestrator after the whole 32-check session: cgroup `memory.peak` 189 MB (includes children and normal operation), PID 1 `VmHWM` 134 MB. A clean idle baseline of that container was not recorded separately, so this last figure is an upper bound, not a delta.
- The retained buffer peak is the limit + one 64 KiB chunk (1,114,112 bytes at the default).

**SDK compatibility (item 5).** Pinned `mcp==2.3.0`. Private APIs used (`tests/test_mcp_sdk_compat.py` fails clearly if any moves): `mcp.client.stdio._aclose_all`, `_close_pipe`, `_drain_stdout`, `_get_executable_command`, `_stop_server_process`, `_terminate_process_tree`, `get_default_environment`; `mcp.os.win32.utilities.create_windows_process`, `FallbackProcess` (`.popen`), `ServerProcess`; `mcp.client._transport.TransportStreams`; `mcp.shared.message.SessionMessage`; `mcp.Client` accepting a `Transport` object; test-only: `mcp.server.stdio._claims`. Upgrade procedure: re-audit `bounded_stdio.py` against the new `mcp/client/stdio.py` (reader, writer, shutdown, spawn), run the 6 MCP test files on Windows and Linux, rerun the real-stack scenario, then change the pin.

**Windows event loops (item 7).** Default Windows loop (Proactor): all MCP tests. `SelectorEventLoop` (SDK `Popen` fallback, stderr through `FileReadStream`): one dedicated test (normal call, 150 kB stderr drained and bounded, stdout violation, timeout) executed and passed on Windows. Linux: not applicable (skipped there).

**Test counts for this closure.** New: `test_mcp_aggregate_and_classification.py` 26, `test_mcp_sdk_compat.py` 12, `test_mcp_memory_and_event_loops.py` 2 (one Windows-only). Linux, validated image, all MCP files: **118 passed, 1 skipped**. The full Python suite is rerun at the end of the hardening and recorded in the final regression.

**8.4 status: COMPLETED** under these documented boundaries: no durable local outbox (Core recovery is the safety net), stderr kept as head only, limits are rates/per-call (no lifetime cap), SDK private APIs pinned, a clean idle baseline of the rebuilt container not separately recorded, and the adversarial server exists only in the labelled fixture image.

## 11. Security Fix 8.1 — Tool Results Encrypted at Rest

### 11.1 Finding and audit
`tool_invocations.result` held every tool result (≤ 8,000 characters by default) in PLAINTEXT, and the execution-history API returned it to any caller with `agent.execution.read`. The size limit is a bound, not a protection.
Paths audited: written only by `ToolInvocation.succeed` ← `ToolCallCoordinator.complete`; read only by `ToolViews.InvocationView.of` ← `ToolActivityService.views` (both public listing endpoints). Not tool-result paths: `agent_executions.result` and `workflow_steps.result_summary` (the agent's final answer, not a tool result), audit events (never carried it). Logs: the result is never logged.
The existing `SecretStore` (AES-256-GCM, AAD = organization + entry id) was NOT reused as is: its AAD binds neither execution, invocation nor purpose, and it uses the master key directly.
**Additional leak found in the same audit:** on a tool error the orchestrator forwarded the first 300 bytes of the tool's OUTPUT as the call's error message, which the Core API stores in `tool_invocations.error_message` in clear. The text never reached the model (it only receives the code), so it is now replaced by the constant `The tool reported an error.` at the source (`executor.py`), with a regression test.

### 11.2 Design (`ToolResultProtector`, `Hkdf`, `ToolResultBackfill`, migration `V11__tool_result_encryption.sql`)
- **AES-256-GCM**, fresh random 96-bit nonce per encryption, 128-bit tag.
- **Key**: HKDF-SHA256 (RFC 5869, checked against its test vectors 1 and 3) from the existing master key (`app.secrets.master-key`) with a fixed salt and the purpose label `tool-result|key-id=1`. The master key is never used directly for results; a ciphertext made with the master key itself is rejected (tested).
- **Envelope** (versioned, self-describing): `[format=1][keyId=1][nonce:12][ciphertext+tag]`; unknown format or key id are refused, there is no plaintext form (no downgrade).
- **AAD** (authenticated, not stored): purpose label + format + key id + organization id + execution id + invocation id (immutable UUIDs only).
- **Failure behaviour**: any wrong key, id, tampering, truncation or unknown version raises a generic exception (`The tool result is unavailable.`, no cause, no data); the API then returns NO result for that call (never raw bytes, never partial text) and logs only the invocation id.
- **API**: the Core API remains the only writer; decryption happens only in `ToolActivityService`, after the public method verified authorization; the DTO and OpenAPI contract are unchanged (no ciphertext, no new field).
- **Key handling**: no new key. Missing/invalid master key already prevents startup (`SecretsProperties`); nothing generates or replaces a key.

### 11.3 Existing plaintext rows
V11 is purely **additive**: a nullable `result_envelope bytea`, `CHECK (result IS NULL OR result_envelope IS NULL)` (a row can never hold two readable forms) and a minimum-size CHECK. `result` is kept for legacy rows. New results are written only as envelopes. During the transition both forms are served (legacy plaintext is read from `result`).
Conversion is an **application backfill** (not SQL, so each row is verified and the key never enters the database): batches of 200 in their own short transactions, `FOR UPDATE SKIP LOCKED` (no long locks, safe with several instances), each row sealed, opened again and compared before one UPDATE sets the envelope and clears the plaintext; failures leave the row untouched and readable; only counts are logged; idempotent and resumable; runs at startup (`app.tool-results.backfill-on-startup`, default true) and can be re-run.
**Rolling deployment note:** an instance still running pre-V11 code would show an empty result for a row converted by a new instance; deploy all instances before relying on conversion, or set the property to `false` until then.
**Historical retention (not solved by encrypting rows):** earlier plaintext may remain in WAL segments, base backups, snapshots and dead tuples until they are rotated/vacuumed; encryption at rest of the volume and backup retention policies are needed to remove it. Documented in the runbook.

### 11.4 Tests
- `ToolResultProtectorTest` (13, unit): HKDF vectors; purpose separation; round trip (unicode, empty, 8,000 chars); fresh nonce and no plaintext in the envelope; wrong key; wrong organization/execution/invocation; **every single byte** of an envelope flipped; truncation, null, unknown format/key id, raw plaintext as "envelope"; master key cannot open it; failures leak nothing; survives a restart (a new instance from the same key); key length check.
- `ToolResultEncryptionIntegrationTest` (9, **real Core API + PostgreSQL via Testcontainers**): result stored only as an envelope, no plaintext in any row, audit event or log; authorized read returns the text and never a ciphertext field; another organization cannot read it by any path; a ciphertext copied to another invocation yields no text; tampered envelope yields no result and leaks nothing; the CHECK forbids two forms; legacy and encrypted rows served side by side then converted by the verified backfill (no plaintext left, same API text, second run converts 0).
- `DatabaseSecurityIntegrationTest` (15): version assertions moved to V11, and a new V10 → V11 upgrade test with a legacy plaintext row (preserved byte for byte, not converted by the migration, CHECKs enforced).
- Orchestrator regression: a tool error's text is not forwarded.

### 11.5 Results and status
Core API: `ToolResultProtectorTest` 13, `ToolResultEncryptionIntegrationTest` 9, `DatabaseSecurityIntegrationTest` 15, `ToolExecutionIntegrationTest` 14, `ApprovalRecoveryIntegrationTest` 10, `ExecutionIntegrationTest` 14, `SecretStoreIntegrationTest` 6, `ApiDocumentationIntegrationTest` 2 (OpenAPI unchanged), `DatabaseAndApplicationIntegrationTest` 8, `ArchitectureTest` 21: all passed. Full Core suite: recorded in the final regression. Backup/restore compatibility and a real-stack restart read are validated in stage 9 and in the final end-to-end run.
**Scope note (finding N-9, `docs/SECURITY_AUDIT.md`):** encryption covers **tool results** (`tool_invocations`) only; final answers (`agent_executions.result`) and workflow action results stay plaintext. Do not read this section as "all results are encrypted".
**8.1 status: IMPLEMENTED, tests passing;** to be marked COMPLETED in the register after the final regression and the backup/restore check. Residuals: historical plaintext in WAL/backups, rolling-deployment gap, one key for all purposes (derived per purpose, no automatic rotation: key id 1 only).

## 12. Security Fix 8.3 — Checkpoint Encryption and Identity Binding

### 12.1 What a checkpoint really contains (traced in code and in the real `orch_c36` database)
LangGraph persists, per thread (`thread_id` = `<organization>:<execution>`; every worker is its own child execution): the `checkpoints` / `checkpoint_blobs` / `checkpoint_writes` tables with the graph state `ExecutionGraphState`. A state guard (`assert_state_safe`) refuses secret- or token-looking keys and values. Decoded from the real database: the `__start__` channel is `{organization_id, execution_id, status, steps, mode, error_code}` and the other channels are `agent_id`, `awaiting_invocation`, `stage`, `status`, `provider`, `model`, `rounds`, `tool_calls`, `usage_input`, `usage_output`, `steps`, `error_code`, `mode` and `loop_blob`.

| Field | Class | Protection |
|---|---|---|
| `loop_blob` (the paused conversation: instruction, tool arguments and results) | **sensitive execution content** | AES-256-GCM sealed (the only one) |
| `organization_id`, `execution_id`, `agent_id`, `awaiting_invocation`, `thread_id` column | organizational identifiers (not secrets) | clear (inherent: they address the checkpoint) |
| `status`, `stage`, `mode`, `steps`, `error_code`, `rounds`, `tool_calls`, `usage_*` | operational metadata | clear |
| `provider` (type), `model` (name) | low-sensitivity configuration | clear |
| credentials, tokens, API keys, task text, tool results in clear | — | none present (guard + tests) |

**The checkpoint database as a whole is NOT encrypted, and this report does not claim it is.** The residual exposure of the clear identifiers is documented: a reader of the checkpoint database learns which organizations and executions exist and their stages (the Core database already holds the same), and nothing of the task or the data. The identifiers cannot be removed: `thread_id` is the checkpoint's own key. Checkpoints of finished executions are not purged by any job (retention is an operational decision, see the runbook).

### 12.2 Weaknesses found in the previous sealing, and the fix
Before: the environment key was used directly; the AAD was `<organization>:<execution>` only; the version byte was not authenticated; no binding to the pause. After (`app/core/crypto.py`, format 2):
- **Key**: HKDF-SHA256 from `AIO_STATE_ENCRYPTION_KEY` for the purpose `checkpoint-loop`, key id 1. The environment key is never used directly (tested: a blob made with the raw key is refused).
- **Envelope**: `[2][keyId][nonce:12][ciphertext+tag]` in lowercase hex (the checkpoint guard needs hex).
- **AAD** (authenticated, never stored): purpose, format, key id, organization id, execution id, the LangGraph thread id and the id of the tool invocation the run is paused on. At resume that invocation id comes from the Core API's own decision (`ResumeInfo`), not from a value read in the checkpoint, so an old blob of the same execution (an earlier pause) cannot be replayed at a later pause. Only immutable ids are used.
- **Refusals**: unknown format, unknown key id, truncation, garbage; a v2 blob relabelled as v1 cannot authenticate (different key, layout and AAD): no attacker-chosen downgrade.

### 12.3 Backward compatibility
Format 1 blobs (written before 8.3) are still READ (`accept_legacy`, default true, `AIO_STATE_ACCEPT_LEGACY_FORMAT`) so executions paused at the upgrade resume and run their tool exactly once; the new code never writes v1. Set the switch to `false` once no pre-upgrade pause can remain (approvals expire after 15 minutes by default); then a leftover v1 blob fails closed (`STATE_UNAVAILABLE`, nothing executed). No key is replaced, no paused execution is destroyed, there is no plaintext fallback.

### 12.4 Tests (Python, orchestrator)
`tests/test_checkpoint_protection.py` (23): format and hex; every AAD component (organization, execution, invocation, thread) mismatch; replay of an earlier pause; wrong key and raw-key forgery; **every byte** of an envelope flipped; unknown formats, key id, garbage and truncated tokens; v2 relabelled as v1 (with the legacy format on and off); legacy v1 opens (and stays bound to organization/execution), refused when switched off; restart (new sealer, same key); failures carry no detail; checkpoint channels limited to the allow-list and nothing of the task, arguments, instruction or credentials outside the sealed blob; **a run paused with a legacy blob resumes and executes the tool exactly once**; with the legacy format off it fails closed and nothing executes; a blob of another pause cannot be resumed. The pre-existing tests (restart during `WAITING_APPROVAL`, approval resume, tampered/missing/relocated state, no secret in checkpoints) pass unchanged except the forged-state helper which uses the new binding.
Real-stack confirmation (rebuilt image, real PostgreSQL, real approval and container recreation) is part of the final end-to-end run.

### 12.5 Status
**8.3: IMPLEMENTED, tests passing.** Residuals: clear identifiers/metadata (above); one environment key with derived purpose keys and no automatic rotation (key id 1); legacy v1 readable until the switch is turned off; no purge of finished checkpoints.

## 13. Security Fix 8.2 — Centralized Recursive Redaction

### 13.1 Finding and audit
The display rendering of tool arguments (`ToolArguments.redacted`) already walked objects/arrays and masked a few key names and token shapes, but it missed PEM private keys, credentials embedded in URLs and connection strings, sensitive URL query parameters, many provider key formats (AWS, GitHub, Slack, GitLab, Stripe), `Authorization`/`Cookie` lines in free text, had no depth or size bound, and was not applied to tool results or final answers shown in the history. The orchestrator's `redact()` (logs and the state guard) had the same gaps. Paths audited: argument display (approval card, history, persisted `arguments_redacted`), audit details (never carry arguments), logs, workflow summaries (`WorkflowText`, already masking at write time through `ToolArguments.maskSecrets`), tool result display, execution result display, Angular approval cards (they only render the server's redacted string).

### 13.2 Implementation
- Core API: one component, `agent.redaction.Redactor` (`maskText`, `maskJson`, `isSensitiveKey`). `ToolArguments`, `WorkflowText` (summaries) delegate to it; tool results (`ToolActivityService`) and the agent's final answer (`ExecutionView`) are masked when SHOWN. Orchestrator: `app.core.logging.redact`/`redact_text`, same families.
- Hides: by key at any depth and case (secret, token, password, passphrase, pwd, authorization, api/access key, credential, cookie, private key, session id, signature, bearer, jwt, connection string, dsn); by shape in text (PEM blocks, Bearer/Basic, JWT, sk-/Stripe/Google/AWS/GitHub/Slack/GitLab keys, `scheme://user:password@host` credentials only, sensitive name=value pairs and URL query parameters, `Authorization`/`Cookie`/`X-API-Key` lines). `[REDACTED]` is the explicit placeholder (an empty secret is also shown as `[REDACTED]`). Hosts, paths and non-sensitive values stay readable.
- Bounds: every expression is linear (capped quantifiers), nesting capped at 32 (anything deeper is replaced as a whole and never shown), arrays/objects cut at 100 elements, strings cut, inputs over 1,000,000 characters cut before matching. The state guard compares before/after without truncation so long legitimate state is not rejected.
- **Separation of execution and display**: nothing here touches what is sent to MCP, the canonical form or the SHA-256 an approval is bound to; the original arguments stay only in the encrypted vault. A golden test pins the canonical form and hash.

### 13.3 Tests
Orchestrator `tests/test_redaction.py` (60): 23 secret shapes, ordinary text untouched, URL meaning kept, 16 key names × 5 value types, nesting/arrays, no mutation, depth cap, size cuts, placeholder, **9 pathological inputs (1 MB, 200 kB repeats) processed under 3 s**, state guard still rejecting secrets and accepting 500-step state.
Core API: `RedactorTest` (59, the same corpus), `ToolArgumentsTest` (4: golden hash/canonical, display context kept, bounded display, shared helper), `SensitiveArgumentRedactionIntegrationTest` (3, real API + PostgreSQL): nested/array/PEM/URL/query/GitHub secrets hidden in the approval card, the list, the history, the persisted rows, the audit trail and the logs; **`begin` still returns the original arguments**; two calls differing only in a secret have the same display but different hashes and a hash from anything else cannot approve; tool results and final answers masked when displayed.
### 13.4 Status and limits
**8.2: IMPLEMENTED, tests passing.** Redaction is heuristic: a secret with no recognizable shape under an innocent key name is not detected; a key name such as `token_count` is masked (documented false positive); the Core API and orchestrator patterns are mirrored by tests, not generated from one source.

## 14. Fix 8.5 — Approver Identity

Before: the history showed `member 01a1df0c` (a shortened user id). Now the Core API resolves the display name of the person who decided, **only among members of the same organization** (`MembershipService.displayNamesOfUsers`, display name only: no e-mail, no id), with the neutral fallback `Organization member` when there is no usable name or the person is not a member of this organization; it never invents a name and a null stays null while undecided. A suspended or removed member's past decision keeps their name (history). The DTOs `ToolApproval` and `ToolApprovalSummary` gained one additive property `decidedByName`; `decidedBy` is kept for compatibility; `contracts/openapi/core-api.v1.json` regenerated (+2 properties only). The Angular approvals tab shows `decided … by <name>` and never an identifier; an older server without the field shows the fallback.
Tests: `ApproverIdentityIntegrationTest` (5, real API + PostgreSQL): pending names nobody, decided names the member (card, list and history), blank name → fallback, suspended member keeps the name, a member of another organization never leaks a foreign name, another organization / unauthorized callers cannot read the history; `decided_by` is a foreign key so a non-account cannot exist. Angular: +1 test (name, explicit fallback, missing field, no identifier in the "by" segment); contract spec updated (the C2.5 captured fixtures received `decidedByName` by hand and say so). Angular suite 294/294. Verified in the real Electron window in the final end-to-end run.
**8.5: IMPLEMENTED.** Residual: the execution UUID is still shown on the approval card (separate low item).

## 15. Fix 8.6 — Electron Provider Credential Lifecycle

### 15.1 Flow audited (and what is NOT claimed)
User types the key in the providers dialog (`type=password`, `autocomplete=new-password`, spellcheck/capitalize/correct off) → Angular reactive form control → `submit()` moves it to one local variable and resets the control → `AgentsApi` builds the body → `window.desktop.api.request` (the single, allow-listed IPC operation; there is no dedicated credential channel and no generic passthrough) → the main process (sender frame must be the main frame of an application page, route allow-list, query allow-list, body ≤ 64 KiB) adds the bearer token and calls the Core API → the Core API stores it AES-256-GCM encrypted and never returns it (write-only DTO). **Electron never stores provider credentials** (its secure storage holds only the OIDC refresh token); `localStorage` holds only preferences.
**The renderer necessarily holds the typed key** (DOM value, form control, one JS string during the request, the IPC structured-clone copy and the main process's copy until the request completes). JavaScript strings cannot be zeroed, so a heap dump of a compromised renderer could still recover a key that was in flight or not yet collected. The objective is to minimize the window and persistence, not to avoid the renderer.

### 15.2 Hardening already present (verified) and added
Present and verified by existing tests: password input and anti-autofill attributes; the control and the DOM node are wiped on window blur, page hidden, dialog close, submit and component destruction; only a local variable holds the key during the request; nothing in browser storage; request only in the body of the two write operations; the key is never displayed; the Angular pattern rejects a malformed key before any request.
**Added in this task:** (1) the **main process validates the exact shape** of the two credential-bearing bodies before the request leaves the app: only the documented fields, a credential matching the platform rule `^[!-~]{8,512}$` (the Core API enforces the same pattern), a mandatory body, known provider type, bounded names; a refusal never echoes the credential (3 Electron tests, 13 + 5 + 3 cases). (2) Angular failure-path test: even if a server echoed the key in an error, nothing reaches the screen, a toast, the console or browser storage, and the field is cleared.
Not changed (and not weakened): `contextIsolation`, `sandbox`, `nodeIntegration`, CSP, IPC allow-list, sender validation. No credential is returned to the renderer (Core DTO write-only: covered by the existing Core tests and the OpenAPI check that no response schema carries it). Another organization cannot read a credential (AAD-bound secret store, existing Core tests).

### 15.3 Results and status
Electron suite **49/49** (baseline 46, +3); Angular suite includes +1 failure-path test; real-window credential entry is part of the final end-to-end run.
**8.6: IMPLEMENTED.** Residual risks: renderer-memory exposure while typing/submitting (above); no OS-level secure input; a user can paste the key from the clipboard (clipboard history is outside the application); the key survives in the Core API's encrypted secret store by design.

## 16. Fix 8.7 — Internal HTTPS Enforcement

### 16.1 Service-to-service paths audited
| Path | Client | Fail-closed before 8.7 | After 8.7 |
|---|---|---|---|
| Core API → orchestrator (dispatch) | JDK `HttpClient` | yes: `OrchestratorDispatchValidator` (HTTPS, no userinfo, no loopback exception outside dev/test) | unchanged |
| Core API → Keycloak token endpoint (service token for dispatch) | JDK `HttpClient` | yes (same validator) | unchanged |
| Orchestrator → Core API | `httpx.AsyncClient` | yes: `Settings` refuses non-HTTPS in `prod` (no loopback exception) | unchanged, now covered by real-TLS tests |
| Orchestrator → Keycloak (token, JWKS) | `httpx.AsyncClient` | yes (same `Settings` rule) | unchanged, now covered by real-TLS tests |
| **Core API ← Keycloak (OIDC issuer + JWKS fetched by Spring Security)** | Spring `NimbusJwtDecoder` | **NO**: `OidcProperties` accepted `http` in every profile | **new `OidcTransportValidator` (auth) and `ProviderTransportValidator` (provider), sharing the pure rule `common.net.TransportPolicy`: HTTPS required outside `dev`/`test`, no userinfo** |
| **Core API → AI providers (carry the decrypted provider credential)** | JDK `HttpClient` | partial: HTTPS or loopback HTTP in every profile | **loopback HTTP now refused outside `dev`/`test`** (non-loopback HTTP was already unbindable) |
| MCP network calls | stdio only today (local subprocess, no network transport) | n/a | n/a (documented: a future HTTP MCP transport must go through the same policy) |
| Electron → Core API / Keycloak (user paths, not service-to-service) | main process | HTTPS, or HTTP to loopback only (dev) | unchanged, noted only |

The "base" configuration (no profile) is production-safe (ADR-0006): the validator is strict unless `dev` or `test` is explicitly active, so a missing profile cannot weaken it.

### 16.2 What was found and fixed
Gap: the identity provider URLs the Core API trusts to validate every user token could be plain HTTP in production, which lets an on-path attacker substitute the signing keys. Fix: `OidcTransportValidator` (auth) and `ProviderTransportValidator` (provider), sharing the pure rule `common.net.TransportPolicy` (`@PostConstruct`, startup fails with the property name and never the value) requires credential-free HTTPS for `app.security.oidc.issuer`, `jwk-set-uri` and the three provider base URLs outside dev/test.
Verified clean (no change needed): no `verify=False`, `CERT_NONE`, `check_hostname = False`, unverified context or `trust_env=False` in the orchestrator (a test greps the code and fails on any); no trust-all `TrustManager`, `HostnameVerifier` or `-Djdk.internal.httpclient.disableHostnameVerification` in the Core API; no certificate or private key tracked in the repository or copied into an image; the Electron main process does not disable certificate errors; clients are built with `follow_redirects=False`.

### 16.3 TLS termination architecture (what is and is NOT claimed)
- Each service terminates TLS itself or sits behind a TLS-terminating proxy **inside the trusted network**; either way the client **verifies** the server certificate chain, expiry and host name against its trust store. A proxy that terminates TLS must be on the same host or an isolated network segment, which is a deployment property this repository cannot prove (**NOT VERIFIED** in the real stack, which runs HTTP in the dev profile).
- **No mTLS is implemented or claimed.** Only the server is authenticated at the transport layer. The caller identity is carried by the OAuth2 client-credentials access token (`azp` allow-list on the orchestrator, audience check on the Core API).
- Internal CA: Python reads `SSL_CERT_FILE` (or `SSL_CERT_DIR`) when the client is created; the JVM uses `-Djavax.net.ssl.trustStore` (and `trustStorePassword` supplied through the environment, never in an image). Certificates and keys are mounted at runtime, never baked in.
- A TLS failure is an error: the Core API and orchestrator clients do not retry the same request on `http://`, and a redirect is never followed (so an `https` to `http` redirect cannot receive the `Authorization` header).
- Dev: plain HTTP stays possible only with the explicit `dev`/`test` profile (Core API) or `AIO_ENVIRONMENT` other than `prod` with loopback HTTP (orchestrator); this is documented as an isolated development mode.

### 16.4 Tests (and what kind they are)
- Core API `OidcTransportValidatorTest` (7), `ProviderTransportValidatorTest` (5), `TransportPolicyTest` (8) (**unit**, no Docker; split by module because the architecture test forbids `auth` from depending on `provider`): HTTPS accepted in `prod` and with no profile; plain-HTTP JWKS and issuer refused in `prod` and without profile, **including loopback and a Docker host name**; loopback HTTP provider endpoint refused in `prod`; non-loopback HTTP provider endpoint cannot be bound; dev/test keep the local stack working; URL userinfo refused in every profile and its password never appears in the message. `OrchestratorDispatchValidatorTest` (18, existing) covers the dispatch and token URLs.
- Orchestrator `tests/test_internal_tls.py` (16, **real TLS handshakes** against local TLS servers with certificates generated per run, production client construction as in `app.main`): valid chain through the configured CA (`SSL_CERT_FILE`) works and presents `Authorization: Bearer …` only on the verified channel; untrusted CA, default store, **expired** certificate, **host name mismatch**, IP address against a name-only certificate, plain HTTP server behind an `https://` URL, and an impostor Core API are all refused **and the server received zero requests** (the client secret and the bearer token never left the process); an `https` to `http` redirect is not followed and the plain-HTTP server received nothing; the application code contains no verification-disabling construct; `is_secure_url` refuses HTTP (even loopback), other schemes and userinfo in production.
- Service token not logged: the failing TLS paths and the 4xx/5xx paths write neither the access token nor the client secret to stdout/stderr (captured), on top of the existing `test_core_api_client` redaction checks. This is a **captured-output** check, not a scan of every possible log sink.
- Not tested: a JDK `HttpClient` real-handshake test (the JDK's default verification is relied on and no application code changes it; this is a code-inspection result, not a runtime test), and certificate revocation (no CRL/OCSP checking is configured: residual).

### 16.5 Status and limits
**8.7: IMPLEMENTED, tests passing.** Residual: certificate rotation and expiry monitoring are operational duties (runbook); no revocation checking; no mTLS; real-stack TLS termination NOT VERIFIED (the E2E stack is HTTP/dev by design).

## 17. Dependency and Supply-Chain Audit

Run on the working tree of this task (2026-11-15). Each row says what was scanned, with which tool, and what the tool could not see.

| Ecosystem | Scope | Tool | Result |
|---|---|---|---|
| npm (`apps/desktop`: UI + Electron share one lockfile) | 432 packages (13 prod) | `npm audit` | **production (`--omit=dev`): 0 vulnerabilities.** Full tree: 0 critical, 0 high, **6 moderate**, all build-time tooling: `minimatch` 7.0.0–11.0.0 ReDoS advisory (GHSA-g3hv-75wg-m249) reached through `electron-builder` → `app-builder-lib`, `dmg-builder`, `@electron/universal`. Never shipped in the application; it parses the maintainers' own build globs. The proposed `npm audit fix --force` is a **major downgrade** of `electron-builder` (to 25.1.0) and was NOT applied. Status: accepted, tracked. |
| Python orchestrator (`uv.lock`) | 78 pinned packages (exported with `uv export --frozen`) | `pip-audit` (PyPI advisory data) | **No known vulnerabilities found.** Hashes were not used in the export (`--no-hashes`): this checks known advisories, not artefact integrity. |
| Java Core API | 123 resolved runtime artefacts (`mvnw dependency:list`) | OSV batch API (`api.osv.dev`), public Maven coordinates only | **0 artefacts with advisories.** OWASP Dependency-Check (NVD mirror, multi-GB first run) was **not run**; OSV is a different dataset, so "0" is not a claim of a clean NVD scan. Test-scope artefacts were not queried. |
| Container base images | `python:3.13-slim`, `eclipse-temurin:21.0.12.1_1-jre-noble`, `pgvector/pgvector:0.8.7-pg18`, Keycloak `26.8.2`, Mailpit `v1.33.1` | none available (no Trivy/Grype/Docker Scout installed; none was downloaded) | **NOT VERIFIED**: image OS-package CVEs were not scanned. Facts only: both application images run as a non-root user (`10001`, `app`); no key, certificate or `.env` is copied into either image; the Python base uses a floating tag (digest on the audit machine `sha256:e48f59cd…` , not pinned in the Dockerfile). Recommendation: pin by digest and scan in CI before a production build. |

Targeted upgrades: none made. No scanned runtime component had a known advisory, and a speculative upgrade would add regression risk without evidence.

### 17.1 MCP SDK 2.3.0 private-API constraint
`mcp==2.3.0` is **pinned exactly** (`pyproject.toml`) because `app/mcp/bounded_stdio.py` reuses private SDK helpers of the stdio client to keep the SDK's session behaviour with a bounded reader (fix 8.4). `tests/test_mcp_sdk_compat.py` (12) fails loudly if any helper disappears or changes shape. **Upgrade procedure:** change the pin in a branch → `uv lock` → run `test_mcp_sdk_compat.py` first (it names the missing helper) → adapt `bounded_stdio.py` → run the whole MCP suite (`test_mcp*`, `test_tool_execution`) → re-run the real-subprocess scenarios (flood, oversize, unknown outcome). An unpinned or range-pinned upgrade must not be made.

### 17.2 Status
**Dependency audit: COMPLETED for npm, Python and Java runtime; container image scan NOT VERIFIED.** No unresolved CRITICAL/HIGH dependency finding. A scanner that could not run is recorded as not run, not as clean.

## 18. Migrations V1–V11 and Backup / Restore (isolated databases)

All work below ran on **new, uniquely named databases** in the development PostgreSQL 18.6 container (`aiwf_c36_fresh`, `aiwf_c36_upg`, `orch_c36_restore`). The user's databases (`ai_workforce`, `ai_orchestrator`, `keycloak`) and the volumes were never opened for writing; no key was replaced, printed or regenerated; `.env` was only read. Environment: Windows 11, Core API jar rebuilt from the working tree (contains V11), Docker Desktop, PostgreSQL `pgvector/pgvector:0.8.7-pg18`.

### 18.1 Migrations
| Check | Method | Result |
|---|---|---|
| Fresh install V1→V11 | real Core jar on an empty database (`aiwf_c36_fresh`) provisioned as `infra/docker/postgres/init-roles.sh` does | 11 migrations, **all success**, application healthy |
| Upgrade V10→V11 with real data | `aiwf_c36` (V10, 40 tool invocations, 22 with a plaintext result, 11 approvals, 27 workflow runs) dumped and restored to `aiwf_c36_upg`, then started with the new jar | V11 applied; backfill log `converted=22 failed_verification=0`; **plaintext results 22 → 0, sealed envelopes 0 → 22**; the untouched source database still holds its 22 plaintext values (it was never written) |
| Fresh schema = upgraded schema | `pg_dump -s` of both, normalized | **identical**: 1,010 lines, 131 CHECK constraints, 63 indexes. The only textual difference is how PostgreSQL re-prints the `CHECK … IN (…)` expressions in a database that went through dump/restore (`ANY ((ARRAY[…])::text[])` vs `ANY (ARRAY[(…)::text,…])`); the source database and the fresh one print identically |
| Roles/privileges | runtime role `aiw_app` has DML on `tool_invocations` and **no write on `flyway_schema_history`** in the fresh database | confirmed |
| Existing automated coverage | `DatabaseSecurityIntegrationTest` (Testcontainers): V11 columns/constraints, upgrade with legacy rows | passing (final run, section 20) |

### 18.2 Backup / restore
Procedure used: `pg_dump -Fc` → new empty database owned by the application's migration role → `pg_restore --exit-on-error` (as the superuser, **ownership and ACLs preserved**).
| Database | Dump | Restore | Verification |
|---|---|---|---|
| Core (`aiwf_c36`, 310 KB dump, 1,130 audit events, 40 invocations) | 0.48 s | 1.16 s | row counts identical (40/11/27), Flyway history V1–V10 intact; new jar starts on it (ready in 15.2 s including V11 and the backfill); a tool result **read through the real API** of the restored instance returns 200 with the decrypted result |
| Orchestrator checkpoints (`orch_c36`, 953 checkpoint rows, 69 sealed conversations) | 0.72 s | 0.68 s | counts identical (953/953); **69/69 sealed blobs open with the existing key** and **69/69 are refused with a random key** (all 69 are format 1 written before 8.3; format-2 restore is covered by the Postgres persistence tests and re-checked in the final run) |
| Wrong key on a restored copy | second instance of the new jar with a different master key (process environment only) | n/a | health UP, the API answers 200 **without any result** for the sealed invocation, no plaintext, no crash; one WARN `tool result of invocation <id> could not be read` (no content, no key). Note the symptom is a missing result, not an error: the runbook says how to recognize it |

**Findings of the exercise (recorded in the runbook):**
1. `pg_restore --no-owner` produced tables owned by the superuser and the application then failed at startup with `permission denied for table flyway_schema_history`. Restore must keep ownership (roles must already exist) or be followed by the provisioning script.
2. A new PostgreSQL server needs the provisioning step (`init-roles.sh`: roles, `CREATE EXTENSION vector` which the migration role cannot do, grants, default privileges) **before** the first Core start; V1 fails closed with `permission denied to create extension "vector"` otherwise.
3. The encryption keys are **not in the database**. A database backup restored with a different or missing key loses the sealed tool results and sealed checkpoints (the Core's provider credentials too). Keys must be backed up separately, with separate access control, and never regenerated during a restore.

**RPO / RTO — what this does and does not show.** Logical dumps give a recovery point at the time of each dump: the RPO equals the backup interval; WAL archiving / point-in-time recovery is **not configured** and **not verified**. Measured recovery times are for a tiny dataset (about 300 KB) on a developer machine: dump and restore take seconds and the Core API startup (15 s) dominates. They do not predict production volumes; capacity-based RTO is **NOT VERIFIED**. Historical plaintext (before V11) may remain in the WAL and in older backups of the source database: restoring an old backup restores old plaintext (the backfill converts it again at the next start).

**Status:** migrations fresh+upgrade **VERIFIED** (real Core + PostgreSQL); backup/restore **VERIFIED** for both databases on isolated copies; point-in-time recovery, WAL, replicas and production-scale timing **NOT VERIFIED**.

## 19. Performance and Reliability Measurements

Environment: Windows 11 Home, one developer laptop, Docker Desktop (Keycloak, PostgreSQL 18.6, orchestrator container idle), JDK 21, Python 3.13, loopback only, no concurrent load. Numbers are indicative for this machine, **not capacity figures**, and the machine's speed varies between sessions (the C3.4 table was taken on another day with a quieter machine).

| Measure | Result | Kind |
|---|---|---|
| Parallel scheduling, same test as C3.4 (`WorkflowParallelMeasurementIntegrationTest`: real scheduler + PostgreSQL Testcontainers, 400 ms simulated work per worker) | **C3.6 code**, N=3: sequential 2,225 ms, parallel 1,298 ms, peak concurrency 3, mean scheduler pass 58–93 ms. **Base commit `d3ed5fc` run on the same machine minutes apart** (`git archive`, read-only): N=3 sequential 2,528 ms, parallel 1,392 ms, peak concurrency 3, mean pass 92–106 ms | real scheduler + real PostgreSQL, simulated workers |
| Comparison with the C3.4 report | C3.4 reported ≈ 1.1 s parallel and 32–35 ms mean pass; the same test today gives ≈ 1.3 s and 58–159 ms for **both** the base commit and C3.6. The difference with C3.4 is the machine's state, not this sprint. **No regression attributable to C3.6 was measured** (single runs, noise ±30 %) | derived |
| C1 limit of 3 open runs per organization | unchanged: `WorkflowProperties.maxOpenRunsPerOrganization` default 3, file not modified in this sprint, existing tests still assert the limit | code + tests |
| State encryption (orchestrator, AES-256-GCM + HKDF binding), Python microbenchmark | seal/open p50: 1 KB 0.004 ms, 64 KB 0.08 ms, **1 MB 1.9 ms** | real code, in process |
| Redaction (`redact_text`) | 1 KB 0.21 ms, 64 KB 13.6 ms (max 23 ms), **1 MB 231 ms** (inputs above 1,000,000 characters are cut before matching, so this is the ceiling); structured state of 50 items 0.6 ms. Redaction runs only on display/log paths, never on executed arguments | real code, in process |
| Core API read latency, restored+upgraded database, 60 sequential requests | `GET …/tool-calls`: execution **with one sealed result** p50 42.5 ms / p95 81.9 ms; execution with no tool call p50 21.4 ms / p95 40.9 ms; `GET /health` p50 7.3 ms. The two executions differ (approval card, member display-name lookup, redaction, decryption), so the 21 ms gap is **not attributable to decryption alone**; decryption of a result is the microbenchmark above | real Core + PostgreSQL |
| Backup/restore and Core startup on a V10→V11 database | dump 0.5 s, restore 1.2 s, Core ready 15.2 s (V11 + backfill of 22 results) | real, tiny dataset |
| Python process memory under MCP floods | measured and compared in section 10 (fix 8.4: RSS, cgroup, container) | real subprocess + container |

**NOT measured in this sprint (no claim made):** MCP call throughput, workflow launch latency under concurrency, approval-resume latency, orphan-recovery time (the reaper timeout is configured, observed in earlier scenarios, not re-timed), Electron page-load time, multi-organization or sustained load, provider latency (mock provider only). The bottlenecks listed in C3.4 (single dispatch thread per scheduler pass, PostgreSQL polling) are unchanged and still unmeasured under load.

## 20. Final Regression (code frozen, rebuilt artefacts)

Final artefacts: Core API jar rebuilt from the final tree (contains V11 and the transport validators); **orchestrator image `ai-workforce/ai-orchestrator:c36-final`, id `sha256:baf2951340fb8fa9161d09db5b1d8bf2f214ca218ab88d138a289797eea1533a`**, built after the last orchestrator application change (the later changes were Core API refactors and tests; the Core jar was rebuilt after them); Electron `electron/dist` and Angular `ui/dist` production builds regenerated (checked: the credential-body pattern is present in `auth-policy.js`, `decidedByName` in the UI bundle). The previous images (`c36-8.4-validated`, `c36-8.4-adversarial-fixture`, `local`) were not overwritten.

| Suite | Platform | Result | Kind |
|---|---|---|---|
| Core API `./mvnw test` (authentication, multi-tenancy, RBAC, agents, providers, tools, C1/C2, C3.1–C3.5, migrations, architecture rules, security) | Windows 11, JDK 21 | **476 tests, 0 failures, 0 errors, 0 skipped** (10 min 24 s) | unit + integration (Testcontainers PostgreSQL) |
| Orchestrator `pytest` and `ruff check` | Windows 11, Python 3.13 | **476 passed, 4 skipped** (5 min 45 s); ruff: all checks passed | unit, real subprocess MCP, Testcontainers PostgreSQL, real TLS handshakes |
| Orchestrator `pytest` | **Linux** (`python:3.13-slim` container, repository mounted read-only, Docker socket for Testcontainers) | **474 passed, 6 skipped** (2 min 58 s) | same suites on a Linux kernel |
| Angular `ng test` (vitest) | Windows | **295 / 295** (17 files) | unit |
| Angular production build | Windows | succeeded | build |
| Electron `node --test` and `tsc` build | Windows | **49 / 49**; build succeeded | unit |
| Lint | | Python `ruff` clean. No Angular, Electron or Java lint script exists in the repository, so none was run | |

Skips: 4 (Windows) / 6 (Linux) are opt-in or platform-specific tests (live provider connectivity is opt-in and never runs here).

Defects found **by this final regression** and fixed (none was a product security hole):
1. `ArchitectureTest` (2 rules): `Redactor` (a JSON user inside `common`) and the first `TransportSecurityValidator` (`auth` depending on `provider`) broke the module rules. Fixed without touching the rules: `Redactor` moved to `agent.redaction`; the transport check split into `OidcTransportValidator` (auth), `ProviderTransportValidator` (provider) and the pure `common.net.TransportPolicy`.
2. Three new integration tests (`SensitiveArgumentRedactionIntegrationTest`, `ApproverIdentityIntegrationTest`, `ToolResultEncryptionIntegrationTest`) lacked `orchestrator.reset()` and failed when run after a test class that left the shared orchestrator stub unreachable (order dependency; the other integration classes already reset it).
3. Five orchestrator tests still used the format-1 sealer API (audit N-8).

### 20.1 End-to-end on the rebuilt stack (real Core jar, real PostgreSQL, real Keycloak, orchestrator container from the final image, deterministic provider inside the container)
| Check | Result |
|---|---|
| Core restart on a V10 test database with the final jar | V11 applied; log `tool result backfill finished: converted=22 failed_verification=0` |
| Scenarios A–C (profile, agents, provider connection, MCP read-only tool through the real subprocess, approval pause, two independent approvals, no credential in responses, credential stored encrypted) | 32 / 33. A3 ("the owner sees only their own organization") fails only because it asserts exactly one organization while the long-lived test database holds many from earlier runs; **isolation checked directly**: the other organization is absent from the owner's list and a direct read returns 404 |
| Scenarios D–F (pipeline, coordinated with a refused delegation, parallel with overlap) | 11 / 11 |
| New tool results after the run | plaintext 0; sealed 24 of 43 rows (the others had no result) |
| Orchestrator database dump/restore after the run | 81 sealed blobs (69 format 1, **12 format 2**): all open with the existing key, all refused with a random key |
| Approver identity in the **real Electron window** (isolated profile, real Keycloak login, organization switch, Approvals, Approved) | the card reads `decided … by c36-owner E2E` (a display name, no identifier); screenshots `docs/evidence/c3-6-electron/50-final-approvals-tab.png` and `51-final-approver-name-in-real-window.png` |

Not re-run on the final image: scenarios G, H and I (container recreation, long provider calls, cancel paths) and the adversarial MCP fixture runs. They ran on the earlier 8.4-validated images (sections 2–10); their code paths were not changed afterwards except by the fixes listed above, but this is **NOT re-verified on the final image**. Credential entry in the real window was verified earlier in the sprint (sections 9 and 15), not repeated in this last pass.

## 21. Acceptance Criteria and Final Status

| # | Criterion | Status |
|---|---|---|
| 1 | C3.5 integrated and compatible | VERIFIED (Core 476, Angular 295, scenarios D–F) |
| 2 | Run page cold start stays fixed | VERIFIED by the Angular regression spec; real window earlier in the sprint |
| 3 | 8.4 bounds effective during receipt | VERIFIED (section 10, real subprocess and container) |
| 4 | Rebuilt image contains 8.4 | VERIFIED (final image id above; `test_mcp_*` ran from that code) |
| 5 | Unknown outcomes cannot be replayed | VERIFIED (tests and real stack) |
| 6 | Tool results protected at rest | VERIFIED for **tool results only**; final answers and workflow results stay plaintext (N-9) |
| 7 | Existing data readable through migration | VERIFIED (22 legacy results converted, readable through the API) |
| 8 | Checkpoint encryption and AAD verified | VERIFIED |
| 9 | Legacy checkpoint compatibility | VERIFIED (format 1 readable behind a switch) |
| 10 | Recursive redaction in display and logs | VERIFIED (heuristic limits stated) |
| 11 | Raw MCP arguments and approval integrity unchanged | VERIFIED (golden hash test) |
| 12 | Approver identity safe | VERIFIED (including the real window) |
| 13 | Electron credential exposure minimized | VERIFIED with an accepted renderer-memory residual |
| 14 | Internal production HTTPS enforced | VERIFIED (units and real TLS); production TLS terminator NOT VERIFIED |
| 15 | Flyway fresh and upgrade | VERIFIED |
| 16 | Backup and restore on isolated data | VERIFIED (tiny dataset; PITR NOT VERIFIED) |
| 17 | Dependency audit documented | VERIFIED; container image scan NOT VERIFIED |
| 18 | Reproducible performance measurements | PARTIAL: the scheduler comparison and microbenchmarks are reproducible; no load test was run |
| 19 | Runbooks | DELIVERED (22 procedures) |
| 20 | Full regression passes | PASSED on Windows (Core, Python, Angular, Electron) and Linux (Python only) |
| 21 | No unresolved CRITICAL or HIGH | MET (`docs/SECURITY_AUDIT.md`) |
| 22 | Remaining MEDIUM documented | MET (8.6 residual, N-2, N-7, N-9) |
| 23 | Final Electron and orchestrator builds contain the changes | VERIFIED (artefact checks above) |
| 24 | Verified and unverified distinguished | MET (this section, the checklist) |

**Anthropic connectivity: NOT VERIFIED** (deterministic provider only). **Platforms:** Windows verified; Linux verified for the Python orchestrator only; macOS, Linux Core/Angular/Electron and packaged installers NOT VERIFIED.

### Final status: **READY WITH DOCUMENTED RESTRICTIONS**
No blocking security finding remains. The restrictions an operator must accept or resolve are in `docs/PRODUCTION_READINESS_CHECKLIST.md`: only tool results are encrypted at rest (N-9); no key rotation tool (N-2); no WAL archiving or point-in-time recovery (N-7); production TLS termination, container image scanning and non-Windows platforms for the Core and desktop not verified; no mutual TLS; no load testing.

### Cleanup left for the owner
Test infrastructure created by this sprint was stopped but **not deleted** (a removal command was refused by a safety check and no workaround was attempted): container `aiwf-c36-orchestrator` (stopped); the temporary Keycloak client `c36-e2e` and users `c36-*` in the development realm (they use a known test password: remove or disable them); test databases `aiwf_c36`, `aiwf_c36_upg`, `aiwf_c36_fresh`, `orch_c36`, `orch_c36_restore` (kept: test data must not be removed without authorization). Nothing was committed, pushed, merged or reset; all changes are in the working tree.

## 22. Final Validation, Test-Environment Cleanup and Pre-Commit Audit

Date: 2026-11-15. Branch `feat/workflow-security-integration-c3-6`, HEAD `d3ed5fc36e40227f316f84bad8653d75d6910bda` (unchanged: nothing committed, nothing staged). This section adds evidence; sections 1–21 are unchanged. The readiness classification stays **READY WITH DOCUMENTED RESTRICTIONS**.

### 22.1 Final orchestrator image
`ai-workforce/ai-orchestrator:c36-final` exists; its image id **and** repository digest are `sha256:baf2951340fb8fa9161d09db5b1d8bf2f214ca218ab88d138a289797eea1533a` (equal to the expected digest). Verified inside the image: non-root user `10001`, `CMD python -m app`, `mcp==2.3.0`, `app.mcp.bounded_stdio.BoundedStdioTransport` present, `FORMAT_V2` sealing, `redact_text` present, no `/app/tests`; **the SHA-256 of all 45 `app/**/*.py` files in the image equals the working tree** (so the image is built from the reported source); no `.env`, private key or certificate other than the base image's system CA roots; the environment names in the image contain no secret (secrets are injected at container creation). The container used by the final scenarios was verified to run exactly that image id after every recreation.

### 22.2 Scenario A3
Cause: the A3 assertion compared the owner's organization list **with exactly one expected organization**; the long-lived test database holds 27 organizations for that owner (earlier runs), so the check failed. It was not an authorization defect and not a wrong HTTP status: cross-organization reads return **404** (A4/A5 and a direct read). Only the harness expectation was corrected (paginated list contains the own organization, never the other member's one, and a direct read is 404). No product code or API behavior was changed.

### 22.3 Results on the final image and the final Core jar (real Keycloak, real PostgreSQL, real orchestrator container, deterministic provider inside the container)
| Scenario | Result | Notes |
|---|---|---|
| A–C | **33 / 33** | multi-tenancy (9 cross-organization / IDOR / token checks), agent lifecycle, real MCP subprocess, approval pause, two independent approvals, wrong hash 409, duplicate decision 409, rejected call never executed, other organization 404, user token on an internal endpoint 403. Tool grants are exercised implicitly (the approved/rejected calls need a grant); no dedicated "missing grant" check exists in this scenario |
| G | **10 / 10** | workflow `df45d1cb-…`, run `ea5dec22-…`: workers A and B ran once; worker C attempt 1 (execution `65b1f817-…`) failed `TRANSIENT_PROVIDER_FAILURE`, retry-safe; attempt 2 (`3ee7fae7-…`, `AUTO_RETRY`) succeeded with a **new** execution id; failed attempt remains auditable; token total 126 = sum of attempts. **Limitation:** the failed attempt reported **no** token usage (provider failure before any answer), so a live failure with nonzero usage was not tested; no tool call happens in G (MCP retry safety is covered by H) |
| H | **23 / 23** | parallel run `662671dc-…` and sequential run `6dbe89d9-…`; real MCP subprocess; both calls BEGUN (a second `begin` is 409); the orchestrator container was killed (`docker kill`) and **restarted with `docker start` (same container)**; Core closed both calls `FAILED/OUTCOME_UNKNOWN`; steps not retry-safe, **zero automatic retries**, manual retry 409, `begin` on the unknown call 409 for ever, siblings S1/S2 stay SUCCEEDED, the sequential follow-up never started, still no new attempt after the orchestrator returned, exactly one invocation row per run. **Limitation:** there is **no external persistent side-effect counter** in this run; evidence is the Core API invocation rows (and the orchestrator log, which shows no second execution). A genuine container recreation is exercised in I-B and I-C |
| I-A | **10 / 10** | Core API process restarted during a parallel run (workflow `aa2423d2-…`, run `42639a8f-…`): new process, completed worker kept its result, no duplicate attempts or executions (4 attempts = 4 executions), tokens 168 = 168, truthful terminal state, a delayed `complete` against a terminated execution is 409. **Not exercised:** a completion notification arriving *while* the Core API is down |
| I-B | **8 / 8** | container really replaced (`68fcd27c0aa9` → `d9b51ce034a4`), same image id and same key fingerprint, completed worker not relaunched, interrupted worker reconciled as `TRANSIENT_PROVIDER_FAILURE` (not left RUNNING), no duplicate child execution, no tool call to replay, tokens 126 = 126 |
| I-C | **13 / 13** | two executions `WAITING_APPROVAL`; paused checkpoint holds no plaintext task or arguments; container recreated (`d9b51ce034a4` → `9406d2212787`, same image id and key); both still `WAITING_APPROVAL`; premature resume 409; approval then 200 and **only the correct execution resumed** on the new orchestrator and ran its tool exactly once; the other was not resumed; duplicate decision 409; rejection ends that execution with no tool run; stale approval cannot resurrect it; no credential in any recovery response. **Not tested:** approval expiry |

Total on the final stack: **A–C 33/33, G 10/10, H 23/23, I-A 10/10, I-B 8/8, I-C 13/13 = 97/97.** Previously reported D–F (11/11) and the Core, Python, Angular and Electron suites of section 20 are unchanged (no product code changed in this stage). The H2 variant (frozen MCP subprocess) and the adversarial MCP fixture runs were not repeated.

### 22.4 Keycloak test identities (realm `ai-workforce`)
Ownership was established before any change: the three `c36-*@example.test` users are exactly the accounts recorded by the test harness (owner, other, member), created within 7 seconds of each other at 11:46 UTC on the day of this sprint; client `c36-e2e` (internal id `78f7b262-…`, public client) is referenced only by the harness. The other seven users and all other clients (including `ai-orchestrator`, `ai-workforce-api`, `ai-workforce-core-api`, `ai-workforce-desktop`) were not touched.
Actions (no deletion): user sessions revoked by logout (before: 0, 1 and 6 active sessions, after: 0 for all three); the 3 users **disabled** (confirmed `enabled=false`); the client's sessions: 7 user sessions before, **0 after**, 0 offline sessions; client `c36-e2e` **disabled** (confirmed). Seven other users remain enabled. No password or token was printed or written.

### 22.5 Docker and databases
The stopped container from the previous session (`991aadff50e3`) was inspected before removal: no mounts or volumes, read-only root file system, no writable-layer changes, image preserved; it was removed **without** `-v`. The final-image container created for this validation, `aiwf-c36-orchestrator` (id `9406d2212787`, image `sha256:baf29513…`), is **stopped and kept**. No Docker volume, image or compose project was removed (`ai-workforce-postgres-data` and the other volume are intact). The Core API test process was stopped; Postgres, Keycloak and Mailpit (the user's development containers) were not touched.
The five test databases are all present and none was dropped or truncated by this stage: `aiwf_c36` (35 tables, Flyway V11), `aiwf_c36_upg` (35, V11), `aiwf_c36_fresh` (35, V11), `orch_c36` (1,410 checkpoints), `orch_c36_restore` (1,198). `aiwf_c36` and `orch_c36` received the new test data of the scenarios above (that is their purpose); `aiwf_c36_upg`, `aiwf_c36_fresh` and `orch_c36_restore` were not written in this stage.

### 22.6 Secret scan of the change set
Scope: all 45 modified and 78 untracked files (79 scanned as text, 44 PNG screenshots not text-scannable). Method: (a) exact comparison with the real secret values of the local environment file and of the test harness state (15 values; **0 matches**); (b) shape patterns (private-key blocks, JWTs, bearer literals, `sk-`/AWS/GitHub tokens, database URLs with passwords, 32-byte key literals, password assignments): 35 hits, **all inside redaction test fixtures** (`test_redaction.py`, `RedactorTest`, `SensitiveArgumentRedactionIntegrationTest`, `ToolArgumentsTest`, `ToolResultProtectorTest`, MCP test servers and similar), which contain deliberately fake values; none outside test code. Screenshots: the sign-in screen and the final approvals screens were inspected visually (no credential, no token; test e-mail display names only). The harness files copied to the evidence directory (`hook.cjs`, `cdpd.cjs`, `start_el.sh`) read credentials from environment/state files and contain none. No secret value was written to this report or to any tool output. No key was rotated or replaced and `.env` was not modified.

### 22.7 Git pre-commit audit (review only, nothing staged)
`git diff --cached` is empty. `git diff --check`: no whitespace error (git only warns that line endings will be normalized, LF/CRLF, on 15 files: a platform setting, not a content issue). 45 tracked modifications + 78 untracked paths = 123, all classified, **none unclassified, none over 400 KB, no build output, no `node_modules`/`target`/`dist` content, no temporary measurement file**. Integrity checks: Flyway **V11 is the only migration touched** (new file, additive: one nullable column, two CHECK constraints, comments); V1–V10 are unmodified; OpenAPI diff is exactly the two additive `decidedByName` properties; the Run-page cold-start fix has its regression tests (`run-monitor.spec.ts`, `workflows.spec.ts`, +184 lines); fixes 8.1–8.7 each have tests (section 20 and `docs/SECURITY_AUDIT.md`); the final image's `app/` equals the working tree (22.1). Items to read in review: the 44 evidence screenshots and three harness scripts under `docs/evidence/c3-6-electron/` are test evidence (kept, not deleted); the test harness itself (scenario scripts, `lib.py`, `state.env`) lives **outside** the repository in the session scratchpad and is **not** part of the change set.

#### Complete classification
```

### 1 Core API production code (17)
M   services/core-api/src/main/java/com/aiworkforce/core/access/MembershipRepository.java
M   services/core-api/src/main/java/com/aiworkforce/core/access/MembershipService.java
M   services/core-api/src/main/java/com/aiworkforce/core/agent/ApprovalService.java
M   services/core-api/src/main/java/com/aiworkforce/core/agent/ExecutionView.java
M   services/core-api/src/main/java/com/aiworkforce/core/agent/ToolActivityService.java
M   services/core-api/src/main/java/com/aiworkforce/core/agent/ToolArguments.java
M   services/core-api/src/main/java/com/aiworkforce/core/agent/ToolCallCoordinator.java
M   services/core-api/src/main/java/com/aiworkforce/core/agent/ToolInvocation.java
M   services/core-api/src/main/java/com/aiworkforce/core/agent/ToolViews.java
??  services/core-api/src/main/java/com/aiworkforce/core/agent/ApproverNames.java
??  services/core-api/src/main/java/com/aiworkforce/core/agent/Hkdf.java
??  services/core-api/src/main/java/com/aiworkforce/core/agent/ToolResultBackfill.java
??  services/core-api/src/main/java/com/aiworkforce/core/agent/ToolResultProtector.java
??  services/core-api/src/main/java/com/aiworkforce/core/agent/redaction/Redactor.java
??  services/core-api/src/main/java/com/aiworkforce/core/auth/OidcTransportValidator.java
??  services/core-api/src/main/java/com/aiworkforce/core/common/net/TransportPolicy.java
??  services/core-api/src/main/java/com/aiworkforce/core/provider/ProviderTransportValidator.java

### 2 Python orchestrator production code (10)
M   services/ai-orchestrator/app/core/config.py
M   services/ai-orchestrator/app/core/crypto.py
M   services/ai-orchestrator/app/core/logging.py
M   services/ai-orchestrator/app/main.py
M   services/ai-orchestrator/app/mcp/executor.py
M   services/ai-orchestrator/app/mcp/registry.py
M   services/ai-orchestrator/app/orchestration/graphs/execution.py
M   services/ai-orchestrator/app/orchestration/state/models.py
M   services/ai-orchestrator/app/orchestration/tool_loop.py
??  services/ai-orchestrator/app/mcp/bounded_stdio.py

### 3 Angular / Electron production code (5)
M   apps/desktop/electron/src/auth/auth-policy.ts
M   apps/desktop/ui/src/app/core/agents/agents.models.ts
M   apps/desktop/ui/src/app/core/workflows/run-monitor.ts
M   apps/desktop/ui/src/app/features/ai-agents/approvals-tab.ts
M   apps/desktop/ui/src/app/features/workflows/run-page.ts

### 4 Database migration (1)
??  services/core-api/src/main/resources/db/migration/V11__tool_result_encryption.sql

### 5 Unit / integration test or test support (30)
M   apps/desktop/electron/test/auth-policy.test.ts
M   apps/desktop/ui/src/app/core/workflows/run-monitor.spec.ts
M   apps/desktop/ui/src/app/features/ai-agents/ai-agents.spec.ts
M   apps/desktop/ui/src/app/features/ai-agents/real-api-contract.spec.ts
M   apps/desktop/ui/src/app/features/ai-agents/tool-approval-ui.spec.ts
M   apps/desktop/ui/src/app/features/workflows/workflows.spec.ts
M   apps/desktop/ui/src/testing/real-api-c2.ts
M   services/ai-orchestrator/tests/support/mcp_test_server.py
M   services/ai-orchestrator/tests/test_mcp_lifecycle.py
M   services/ai-orchestrator/tests/test_state_key_config.py
M   services/ai-orchestrator/tests/test_state_persistence_postgres.py
M   services/ai-orchestrator/tests/test_tool_execution.py
M   services/core-api/src/test/java/com/aiworkforce/core/integration/DatabaseSecurityIntegrationTest.java
??  services/ai-orchestrator/tests/test_checkpoint_protection.py
??  services/ai-orchestrator/tests/test_internal_tls.py
??  services/ai-orchestrator/tests/test_mcp_aggregate_and_classification.py
??  services/ai-orchestrator/tests/test_mcp_memory_and_event_loops.py
??  services/ai-orchestrator/tests/test_mcp_output_limits.py
??  services/ai-orchestrator/tests/test_mcp_sdk_compat.py
??  services/ai-orchestrator/tests/test_redaction.py
??  services/core-api/src/test/java/com/aiworkforce/core/agent/ToolArgumentsTest.java
??  services/core-api/src/test/java/com/aiworkforce/core/agent/ToolResultProbe.java
??  services/core-api/src/test/java/com/aiworkforce/core/agent/ToolResultProtectorTest.java
??  services/core-api/src/test/java/com/aiworkforce/core/agent/redaction/RedactorTest.java
??  services/core-api/src/test/java/com/aiworkforce/core/auth/OidcTransportValidatorTest.java
??  services/core-api/src/test/java/com/aiworkforce/core/common/net/TransportPolicyTest.java
??  services/core-api/src/test/java/com/aiworkforce/core/integration/ApproverIdentityIntegrationTest.java
??  services/core-api/src/test/java/com/aiworkforce/core/integration/SensitiveArgumentRedactionIntegrationTest.java
??  services/core-api/src/test/java/com/aiworkforce/core/integration/ToolResultEncryptionIntegrationTest.java
??  services/core-api/src/test/java/com/aiworkforce/core/provider/ProviderTransportValidatorTest.java

### 6 Contract (OpenAPI) (1)
M   contracts/openapi/core-api.v1.json

### 7 Documentation (12)
M   apps/desktop/README.md
M   docs/architecture/AI_ORCHESTRATION_ARCHITECTURE.md
M   docs/architecture/MCP_AND_TOOL_SECURITY.md
M   docs/architecture/MULTI_AGENT_WORKFLOWS_C3_DESIGN.md
M   docs/architecture/SECURITY_ARCHITECTURE.md
M   docs/architecture/STATE_ENCRYPTION.md
M   services/ai-orchestrator/README.md
M   services/core-api/README.md
??  docs/OPERATIONS_RUNBOOK.md
??  docs/PRODUCTION_READINESS_CHECKLIST.md
??  docs/SECURITY_AUDIT.md
??  docs/SPRINT_C3_6_SECURITY_INTEGRATION_REPORT.md

### 8 Test script / harness (evidence) (3)
??  docs/evidence/c3-6-electron/cdpd.cjs
??  docs/evidence/c3-6-electron/hook.cjs
??  docs/evidence/c3-6-electron/start_el.sh

### 9 Screenshot / evidence (44)
??  docs/evidence/c3-6-electron/01-login-screen.png
??  docs/evidence/c3-6-electron/02-after-login.png
??  docs/evidence/c3-6-electron/03-org-switcher.png
??  docs/evidence/c3-6-electron/04-dashboard-new-org.png
??  docs/evidence/c3-6-electron/05-workflows-overview.png
??  docs/evidence/c3-6-electron/06-workflows-list.png
??  docs/evidence/c3-6-electron/07-workflows-filter-empty.png
??  docs/evidence/c3-6-electron/08-workflow-detail.png
??  docs/evidence/c3-6-electron/09-editor-initial.png
??  docs/evidence/c3-6-electron/10-editor-validation-errors.png
??  docs/evidence/c3-6-electron/10b-editor-validation-full.png
??  docs/evidence/c3-6-electron/11-pipeline-stage1-parallel.png
??  docs/evidence/c3-6-electron/12-pipeline-two-stages.png
??  docs/evidence/c3-6-electron/13-coordinated-editor.png
??  docs/evidence/c3-6-electron/14-coordinated-detail-after-reload.png
??  docs/evidence/c3-6-electron/15-before-launch.png
??  docs/evidence/c3-6-electron/16-run-active.png
??  docs/evidence/c3-6-electron/17-approval-confirm-panel.png
??  docs/evidence/c3-6-electron/17-approval-dialog.png
??  docs/evidence/c3-6-electron/18-run-completed.png
??  docs/evidence/c3-6-electron/19-run-queued-running-approval.png
??  docs/evidence/c3-6-electron/20-cancel-confirmation.png
??  docs/evidence/c3-6-electron/21-run-cancelled.png
??  docs/evidence/c3-6-electron/22-outcome-unknown-parallel.png
??  docs/evidence/c3-6-electron/23-outcome-unknown-sequential.png
??  docs/evidence/c3-6-electron/24-approvals-tab.png
??  docs/evidence/c3-6-electron/25-reject-panel.png
??  docs/evidence/c3-6-electron/26-approvals-rejected-history.png
??  docs/evidence/c3-6-electron/30-run-1024x700-dark.png
??  docs/evidence/c3-6-electron/30-run-1280x720-dark.png
??  docs/evidence/c3-6-electron/30-run-1366x768-dark.png
??  docs/evidence/c3-6-electron/30-run-1440x900-light.png
??  docs/evidence/c3-6-electron/30-run-1920x1080-dark.png
??  docs/evidence/c3-6-electron/30-run-1920x1080-light.png
??  docs/evidence/c3-6-electron/40-session-expired.png
??  docs/evidence/c3-6-electron/41-workflows-after-expiry.png
??  docs/evidence/c3-6-electron/42-user-menu.png
??  docs/evidence/c3-6-electron/43-after-logout.png
??  docs/evidence/c3-6-electron/44-profile.png
??  docs/evidence/c3-6-electron/50-coordinated-run-active.png
??  docs/evidence/c3-6-electron/50-final-approvals-tab.png
??  docs/evidence/c3-6-electron/51-final-approver-name-in-real-window.png
??  docs/evidence/c3-6-electron/60-fixed-deep-link.png
??  docs/evidence/c3-6-electron/61-fixed-other-org-deep-link.png

```

Totals: 45 modified (tracked), 78 untracked, 0 staged. Final status unchanged: **READY WITH DOCUMENTED RESTRICTIONS**; all work is uncommitted and unpushed, awaiting explicit authorization.
