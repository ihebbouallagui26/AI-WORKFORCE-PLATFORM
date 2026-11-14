package com.aiworkforce.core.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Identity of the AI orchestrator service account.
 *
 * @param clientId Keycloak client id (the {@code azp} claim) of the orchestrator's service account.
 *                 Tokens issued to this client are accepted only on {@code /api/v1/internal/**} and are
 *                 refused on every user-facing endpoint, so a service token can never be provisioned as a user.
 */
@ConfigurationProperties(prefix = "app.orchestrator")
public record OrchestratorProperties(String clientId) {

    public static final String DEFAULT_CLIENT_ID = "ai-orchestrator";

    public OrchestratorProperties {
        clientId = clientId == null || clientId.isBlank() ? DEFAULT_CLIENT_ID : clientId.trim();
    }
}
