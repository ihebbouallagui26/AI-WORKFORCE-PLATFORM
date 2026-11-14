package com.aiworkforce.core.agent;

import com.aiworkforce.core.auth.OrchestratorProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * Calls the orchestrator with the Core API's own Keycloak service account (OAuth2 client credentials,
 * client {@code ai-workforce-core-api}, audience {@code ai-orchestrator}). The access token is held in
 * memory only, renewed before it expires and never logged. A user token is never forwarded.
 */
@Component
class HttpOrchestratorGateway implements OrchestratorGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpOrchestratorGateway.class);
    private static final long SAFETY_MARGIN_SECONDS = 30;

    private final OrchestratorProperties.Dispatch config;
    private final RestClient client;
    private String token;
    private Instant tokenExpiresAt = Instant.EPOCH;

    HttpOrchestratorGateway(OrchestratorProperties properties) {
        this.config = properties.dispatch();
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(config.timeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(config.timeoutSeconds()));
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public void dispatch(UUID organizationId, UUID executionId) {
        if (!config.configured()) {
            throw new IllegalStateException("The orchestrator is not configured.");
        }
        try {
            client.post().uri(URI.create(config.baseUrl().replaceAll("/+$", "") + "/api/v1/executions"))
                    .header("Authorization", "Bearer " + accessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("organizationId", organizationId.toString(), "executionId", executionId.toString()))
                    .retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            // Class only: the message of a failed HTTP call must never end up in a log or a record.
            log.warn("orchestrator dispatch failed: {}", e.getClass().getSimpleName());
            throw new IllegalStateException("The orchestrator could not be reached.");
        }
    }

    private synchronized String accessToken() {
        if (token != null && Instant.now().isBefore(tokenExpiresAt)) {
            return token;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", config.clientId());
        form.add("client_secret", config.clientSecret());
        JsonNode response = client.post().uri(URI.create(config.tokenUrl()))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(JsonNode.class);
        if (response == null || !response.hasNonNull("access_token")) {
            throw new IllegalStateException("The service token could not be obtained.");
        }
        token = response.get("access_token").asText();
        tokenExpiresAt = Instant.now()
                .plusSeconds(Math.max(response.path("expires_in").asLong(60) - SAFETY_MARGIN_SECONDS, 1));
        return token;
    }
}
