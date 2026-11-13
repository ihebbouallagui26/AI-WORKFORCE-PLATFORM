package com.aiworkforce.core.config;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiworkforce.core.health.ApplicationHealthService;
import com.aiworkforce.core.health.HealthController;
import com.aiworkforce.core.health.HealthResponse;
import com.aiworkforce.core.support.TestSupportController;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import com.aiworkforce.core.auth.AccessTokenDecoderConfig;
import com.aiworkforce.core.identity.UserProvisioningService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** HTTP security policy for anonymous callers: deny by default, explicit public endpoints, strict CORS. */
@WebMvcTest(controllers = {HealthController.class, TestSupportController.class})
@Import({SecurityConfig.class, CorsConfig.class, TimeConfig.class, AccessTokenDecoderConfig.class})
@ActiveProfiles({"test", "test-support"})
class SecurityConfigTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:4200";

    @Autowired
    private MockMvc mvc;

    /** Required by the identity argument resolver; not used by these endpoints. */
    @MockitoBean
    private UserProvisioningService provisioning;

    @MockitoBean
    private ApplicationHealthService healthService;

    @BeforeEach
    void healthy() {
        when(healthService.currentHealth())
                .thenReturn(new HealthResponse("UP", "core-api", "0.1.0", Instant.parse("2026-11-11T10:15:30Z")));
    }

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.timestamp").value("2026-11-11T10:15:30Z"));
    }

    @Test
    void onlyGetIsPublicOnHealth() throws Exception {
        mvc.perform(post("/api/v1/health")).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/test-support/items/1",
            "/api/v1/organizations",
            "/api/v2/anything",
            "/actuator/env",
            "/actuator/metrics",
            "/v3/api-docs",
            "/swagger-ui.html",
            "/"
    })
    void everythingElseRequiresAuthentication(String path) throws Exception {
        mvc.perform(get(path))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"ai-workforce\""))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:aiworkforce:problem:unauthorized"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(content().string(not(containsString("Exception"))))
                .andExpect(content().string(not(containsString("trace"))));
    }

    @Test
    void rejectsAMalformedBearerTokenAsInvalidToken() throws Exception {
        mvc.perform(get("/api/v1/test-support/items/1").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
                        "Bearer realm=\"ai-workforce\", error=\"invalid_token\""))
                .andExpect(jsonPath("$.type").value("urn:aiworkforce:problem:unauthorized"));
    }

    @Test
    void doesNotCreateSessionsOrCookies() throws Exception {
        mvc.perform(get("/api/v1/test-support/items/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    void sendsSecurityHeaders() throws Exception {
        mvc.perform(get("/api/v1/health"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"))
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void allowsCorsPreflightFromAConfiguredOriginWithoutCredentials() throws Exception {
        mvc.perform(options("/api/v1/health")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    void rejectsCorsFromAnUnknownOrigin() throws Exception {
        mvc.perform(options("/api/v1/health")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));

        mvc.perform(get("/api/v1/health").header(HttpHeaders.ORIGIN, "https://evil.example"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
}
