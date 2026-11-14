# ADR-0016 — Python orchestrator behind a service boundary, with its own identity and runtime database

- **Status:** Accepted
- **Date:** 2026-11-14 (Phase C0)

## Context
Agents will be executed by a Python engine (FastAPI, LangGraph, MCP). Spring Boot remains the authority for business data, RBAC, audit and provider secrets. The engine must not widen the attack surface: no direct access to business tables, no reuse of human tokens, no credential in its state.

## Decision
* **Spring Boot owns all business data**; the orchestrator calls it only through typed HTTP contracts. It has **no credential for the Core API database** and uses a **separate PostgreSQL database and restricted role** (`ai_orchestrator` / `aiw_orchestrator`) for LangGraph checkpoints, in an isolated schema.
* The orchestrator authenticates to the Core API with a **dedicated Keycloak service account** (client credentials, confidential client `ai-orchestrator`, no interactive flow). The Core API serves it only on `/api/v1/internal/**` through a first-ordered security filter chain granting one authority to that client's tokens; the user chain **refuses** those tokens, so a service token is never provisioned as a user. Internal endpoints re-validate the organization they are asked about and return narrow, secret-free projections.
* The orchestrator's own API accepts only **service tokens of allow-listed client ids** (`azp`), verified against Keycloak's JWKS (signing keys only). Desktop and user tokens are rejected.
* Execution identity (organization, agent, server-generated execution id, actor, capabilities) is validated by the Core API; thread ids are derived on the server and embed the organization.
* **Credentials stay in the B3 SecretStore.** A future dedicated Core API operation will hand a secret to the orchestrator for immediate use; until then the resolver fails closed. State, logs and tool metadata are screened against secret-looking content.
* Dependencies are pinned and locked (`uv.lock`); the provider adapters use official REST APIs through HTTPX.
* The service is internal: not started by default, published on loopback only, non-root, read-only filesystem, all capabilities dropped.

## Consequences
* A compromised orchestrator cannot read business tables or other databases, and holds a token that only works on `/api/v1/internal/**`.
* Business execution records, approvals and audit must be written by the Core API (C1): the orchestrator's checkpoints are disposable.
* Keycloak needs the `ai-orchestrator` client; the realm import only runs once, so provisioning is a separate idempotent script and the realm file stays secret-free.
* Two moving parts to deploy and monitor; Windows hosts should run the Python service in Docker/WSL.

## Alternatives considered
* Orchestrator reading the Core API database: rejected (breaks ownership and tenancy guarantees).
* Forwarding the user's token to the orchestrator: rejected (privilege confusion, token exposure, no service identity).
* A shared API key between services: rejected (no expiry, rotation or audience; a service account gives all three).
