package com.aiworkforce.core.agent;

import java.util.UUID;

/**
 * Port towards the AI orchestrator: "please run this execution". Only identifiers travel: the
 * orchestrator re-fetches the instruction, the agent configuration and (once) the credential from the
 * Core API with its own service identity, so nothing it is told can widen what it is allowed to do.
 */
public interface OrchestratorGateway {

    /** Hands the execution over (the orchestrator answers 202 right away). Throws when it cannot. */
    void dispatch(UUID organizationId, UUID executionId);

    /**
     * Tells the orchestrator that a human decided the pending tool approval of this execution, so it can
     * continue. Identifiers only; it may be sent more than once (the orchestrator and the Core API make it
     * idempotent) and is retried until the orchestrator takes the decision into account.
     */
    void resume(UUID organizationId, UUID executionId);
}
