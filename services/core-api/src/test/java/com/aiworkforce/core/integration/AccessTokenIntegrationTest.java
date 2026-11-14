package com.aiworkforce.core.integration;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiworkforce.core.support.ApiIntegrationTest;
import com.aiworkforce.core.support.TestIdentityProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Bearer token validation by the production decoder: signature (remote JWKS), issuer, audience,
 * expiry, not-before and token type; and just-in-time user provisioning keyed by (iss, sub).
 */
class AccessTokenIntegrationTest extends ApiIntegrationTest {

    @Test
    void aValidAccessTokenAuthenticatesAndProvisionsTheUserOnce() throws Exception {
        String subject = "sub-" + UUID.randomUUID();
        String token = TestIdentityProvider.token(subject).email("Jane.Doe@Example.test").name("Jane Doe").sign();
        String firstId = body(withToken(token).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("jane.doe@example.test"))
                .andExpect(jsonPath("$.emailVerified").value(true))
                .andExpect(jsonPath("$.displayName").value("Jane Doe"))).get("id").asString();
        String secondToken = TestIdentityProvider.token(subject).email("jane.new@example.test").sign();
        withToken(secondToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(firstId))
                .andExpect(jsonPath("$.email").value("jane.new@example.test"));
    }

    @Test
    void usersAreIdentifiedBySubjectNeverByEmail() throws Exception {
        String email = "shared." + UUID.randomUUID() + "@example.test";
        String a = body(withToken(TestIdentityProvider.token("sub-a-" + UUID.randomUUID()).email(email).sign())
                .andExpect(status().isOk())).get("id").asString();
        String b = body(withToken(TestIdentityProvider.token("sub-b-" + UUID.randomUUID()).email(email).sign())
                .andExpect(status().isOk())).get("id").asString();
        org.assertj.core.api.Assertions.assertThat(a).isNotEqualTo(b);
    }

    @Test
    void theProfileCanBeRenamed() throws Exception {
        TestUser user = newUser("rename");
        patchAs(user, "/api/v1/me", Map.of("displayName", "  New Name ")).andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("New Name"));
        patchAs(user, "/api/v1/me", Map.of("displayName", "")).andExpect(status().isBadRequest());
    }

    @Test
    void rejectsATokenSignedWithAnotherKey() throws Exception {
        expectInvalid(TestIdentityProvider.token("sub-x").signedWithForeignKey().sign());
    }

    @Test
    void rejectsATokenFromAnotherIssuer() throws Exception {
        expectInvalid(TestIdentityProvider.token("sub-x").issuer("http://127.0.0.1/realms/other").sign());
    }

    @Test
    void rejectsATokenForAnotherAudience() throws Exception {
        expectInvalid(TestIdentityProvider.token("sub-x").audience("account", "another-api").sign());
    }

    @Test
    void rejectsAnExpiredToken() throws Exception {
        expectInvalid(TestIdentityProvider.token("sub-x").expiresAt(Instant.now().minus(Duration.ofMinutes(2))).sign());
    }

    @Test
    void rejectsATokenNotYetValid() throws Exception {
        expectInvalid(TestIdentityProvider.token("sub-x").notBefore(Instant.now().plus(Duration.ofMinutes(5))).sign());
    }

    @Test
    void rejectsAnIdTokenUsedAsAnAccessToken() throws Exception {
        expectInvalid(TestIdentityProvider.token("sub-x").type("ID").sign());
        expectInvalid(TestIdentityProvider.token("sub-x").type(null).sign());
    }

    @Test
    void rejectsMalformedAndUnsignedTokens() throws Exception {
        expectInvalid("not-a-jwt");
        // alg=none: header {"alg":"none"}, payload {"sub":"x"}
        expectInvalid("eyJhbGciOiJub25lIn0.eyJzdWIiOiJ4In0.");
    }

    @Test
    void requestsWithoutTokenAreUnauthenticated() throws Exception {
        mvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"ai-workforce\""));
    }

    private ResultActions withToken(String token) throws Exception {
        return mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private void expectInvalid(String token) throws Exception {
        withToken(token)
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("error=\"invalid_token\"")))
                .andExpect(jsonPath("$.type").value("urn:aiworkforce:problem:unauthorized"));
    }
}
