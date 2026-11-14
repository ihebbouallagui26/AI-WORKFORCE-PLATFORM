package com.aiworkforce.core.provider;

import java.util.UUID;

/**
 * Port implemented by the agent module: lets the provider module refuse to revoke a connection that
 * an ACTIVE agent still depends on, without depending on the agent module.
 */
public interface ConnectionUsage {

    /** Number of ACTIVE agents using the connection as their PRIMARY provider. */
    long activeAgentsUsingAsPrimary(UUID organizationId, UUID connectionId);
}
