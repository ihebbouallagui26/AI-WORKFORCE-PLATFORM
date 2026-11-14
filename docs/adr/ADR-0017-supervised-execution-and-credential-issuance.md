# ADR-0017 — Supervised executions: asynchronous hand-over, Core API state machine, single-use credential issuance

- **Status:** Accepted
- **Date:** 2026-11-14 (Sprint C1)

## Context
C1 runs a real agent for the first time. Three decisions are hard to reverse: how long-running work is handed to the Python runtime, who owns the execution record, and how the provider credential (held encrypted in the B3 SecretStore) reaches the runtime without becoming a second source of truth or leaking.

## Decision
1. **Human-initiated only.** An execution exists only after an authenticated user with `agent.execute` calls `POST …/agents/{id}/executions`. Nothing starts one autonomously.
2. **The Core API owns the record and the state machine** (`agent_executions`, V5): `PENDING → RUNNING → SUCCEEDED | FAILED` and `PENDING → FAILED`; terminal states are final. Transitions are made only by `AgentExecution` (illegal or duplicate transitions are 409) under a row lock. The orchestrator never writes the database: it calls guarded internal endpoints (`start`, `credential`, `complete`, `fail`) with its service account. Cancellation is not offered (a running provider call cannot be stopped consistently).
3. **Asynchronous hand-over without a queue.** After the launch transaction commits, the Core API sends identifiers only (`organizationId`, `executionId`) to the orchestrator (`POST /api/v1/executions`, answered `202`) using its **own** Keycloak service account (`ai-workforce-core-api`, audience `ai-orchestrator`). The run is an in-process asyncio task of the orchestrator, bounded by a concurrency limit and a run timeout. A dispatch failure fails the execution immediately (`ORCHESTRATOR_UNAVAILABLE`) **only if it is still `PENDING`**: an orchestrator that already started the run (its 202 lost to a timeout) must be able to finish and report. A saturated hand-over pool fails the execution at once (`DISPATCH_CAPACITY_EXCEEDED`); the hand-over never runs on the request thread. A reaper in the Core API fails executions still open after `app.executions.timeout` (`TIMEOUT`), which covers an orchestrator that crashed mid-run. The Core API also caps open executions per organization (default 3).
4. **The orchestrator trusts nothing it is sent.** It re-fetches the instruction (`start`), the agent configuration (C0 execution-context endpoint) and the credential from the Core API, which re-validates (organization, execution, agent, binding, status) on each call.
5. **Credential issuance** (`POST …/executions/{id}/credential`): allowed only for the orchestrator's service account; only while the execution is `RUNNING`; **once** per execution (`credential_resolved_at`); only if the agent is still `ACTIVE`, its `PRIMARY` binding still targets the connection and model recorded at launch, and the provider is enabled for execution (Anthropic only); decryption goes through `ProviderCredentialBroker` in the provider package (the only package allowed to call `SecretStore.reveal`), which refuses non-`VALID` connections. The response is `Cache-Control: no-store`, redacted in `toString`, never persisted or audited. A refused issuance does not consume the single use.
6. **The credential never enters graph state.** It is obtained by an execution-scoped `CredentialResolver` **inside** the model-invocation node, held in local variables for one provider call and dropped. The instruction and result are run-local too, not checkpointed; checkpoints hold identifiers, stage names, counts and a safe error code.
7. **Execution is text generation.** Tool grants are not interpreted as authorization; no tool, shell, file, browser, MCP call or fallback between providers exists in C1.

## Consequences
* A user cannot make the orchestrator do more than the Core API would authorize for that execution; a compromised orchestrator can obtain only the credential of a RUNNING execution, once, and only through a valid service token.
* Runs do not survive an orchestrator restart (they end as `TIMEOUT`); acceptable for a first supervised execution and replaced by a durable queue only when a requirement appears.
* Polling (desktop) instead of push; results are final once stored.
* Two service identities (one per direction) must be provisioned in Keycloak and their secrets managed.
* The synchronous alternative was simpler but holds an HTTP request open for the length of a model call and ties its fate to the desktop connection.

## Alternatives considered
* Synchronous call Core API → provider: rejected (the provider lives behind the orchestrator; long requests; no isolation).
* Redis/RabbitMQ/Kafka queue: rejected for now (no demonstrated requirement; extra infrastructure).
* Sending the credential in the dispatch request: rejected (the secret would travel on a path that is not execution-scoped, single-use or re-validated).
* A second credential store in the orchestrator: rejected (two sources of truth).
