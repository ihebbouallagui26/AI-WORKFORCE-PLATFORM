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
}
