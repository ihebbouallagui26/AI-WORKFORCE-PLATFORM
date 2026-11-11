# ADR-0005 — RFC 9457 problem details for every error

- **Status:** Accepted
- **Date:** 2026-11-12 (Sprint B0)

## Context
The desktop client already displays errors from its data layer and will need to branch on error kinds (validation, conflict, permission). Errors must never leak internals (stack traces, SQL, credentials).

## Decision
- Every error, including 401/403 produced by Spring Security, is an RFC 9457 `application/problem+json` document built in `GlobalExceptionHandler` (Spring `ProblemDetail`).
- Members:
  - `type`: a stable URN such as `urn:aiworkforce:problem:validation-error`;
  - `title`, `status`, `detail` (human readable), `instance` (request path);
  - extensions `timestamp` (UTC) and `requestId`;
  - for validation errors, `errors[{field, message}]` without rejected values.
- Unexpected exceptions are logged server-side with the request id and returned as a generic 500.

## Consequences
- One error contract for the whole API. Clients branch on `type`, and support teams correlate with `requestId`.
- New error kinds are added to `ProblemType`, which keeps the contract explicit.

## Alternatives considered
- **Custom error envelope:** non-standard, and duplicates what Spring Framework 7 provides.
- **Spring Boot's default `/error` JSON:** inconsistent between MVC and security errors, and exposes framework fields.
