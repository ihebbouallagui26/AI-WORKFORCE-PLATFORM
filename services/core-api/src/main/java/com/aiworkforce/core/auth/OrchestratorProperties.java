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
public record OrchestratorProperties(String clientId, Dispatch dispatch) {

    public static final String DEFAULT_CLIENT_ID = "ai-orchestrator";

    /**
     * Outbound side (Core API to orchestrator): the Core API authenticates with its own Keycloak
     * service account (client credentials), separate from the orchestrator's. Dispatching is disabled
     * (executions fail safely) while {@code baseUrl} or the secret is blank.
     *
     * @param baseUrl      orchestrator base URL, e.g. {@code http://localhost:8091}
     * @param tokenUrl     Keycloak token endpoint
     * @param clientId     service client of the Core API (default {@code ai-workforce-core-api})
     * @param clientSecret its secret, from the environment only
     * @param timeoutSeconds connect/read timeout of the dispatch call (the orchestrator answers 202 immediately)
     */
    public record Dispatch(String baseUrl, String tokenUrl, String clientId, String clientSecret, Integer timeoutSeconds) {

        public Dispatch {
            clientId = clientId == null || clientId.isBlank() ? "ai-workforce-core-api" : clientId.trim();
            timeoutSeconds = timeoutSeconds == null || timeoutSeconds < 1 ? 10 : timeoutSeconds;
        }

        public boolean configured() {
            return baseUrl != null && !baseUrl.isBlank() && tokenUrl != null && !tokenUrl.isBlank()
                    && clientSecret != null && !clientSecret.isBlank();
        }

        @Override
        public String toString() {
            return "Dispatch[baseUrl=" + baseUrl + ", clientId=" + clientId + ", clientSecret=<redacted>]";
        }
    }

    public OrchestratorProperties {
        clientId = clientId == null || clientId.isBlank() ? DEFAULT_CLIENT_ID : clientId.trim();
        dispatch = dispatch == null ? new Dispatch(null, null, null, null, null) : dispatch;
    }
}
