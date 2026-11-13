package com.aiworkforce.core.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiworkforce.core.support.TestcontainersConfiguration;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Interoperability with the real identity provider: Keycloak (same pinned image as compose)
 * with the versioned realm {@code infra/keycloak/realm/ai-workforce-realm.json}. Verifies the
 * client policy (PKCE S256, loopback redirects, disabled grants) and runs a complete
 * Authorization Code + PKCE login whose access token is then accepted by the API.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, KeycloakIntegrationTest.KeycloakConfiguration.class})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class KeycloakIntegrationTest {

    static final DockerImageName KEYCLOAK_IMAGE = DockerImageName.parse("quay.io/keycloak/keycloak:26.8.2");
    static final String REALM = "ai-workforce";
    static final String CLIENT_ID = "ai-workforce-desktop";
    static final String ADMIN_PASSWORD = "test-admin-" + UUID.randomUUID();
    static final String USER_PASSWORD = "Correct-Horse-" + UUID.randomUUID();

    @SuppressWarnings("resource")
    static final GenericContainer<?> KEYCLOAK = new GenericContainer<>(KEYCLOAK_IMAGE)
            .withCommand("start-dev", "--import-realm")
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", ADMIN_PASSWORD)
            .withEnv("KC_HEALTH_ENABLED", "true")
            .withCopyFileToContainer(
                    MountableFile.forHostPath(Path.of("..", "..", "infra", "keycloak", "realm", "ai-workforce-realm.json")),
                    "/opt/keycloak/data/import/ai-workforce-realm.json")
            .withExposedPorts(8080, 9000)
            .waitingFor(Wait.forHttp("/health/ready").forPort(9000).withStartupTimeout(Duration.ofMinutes(3)));

    static final JsonMapper JSON = JsonMapper.builder().build();

    @TestConfiguration(proxyBeanMethods = false)
    static class KeycloakConfiguration {

        @Bean
        DynamicPropertyRegistrar keycloakProperties() {
            return registry -> {
                KEYCLOAK.start();
                registry.add("app.security.oidc.issuer", KeycloakIntegrationTest::issuer);
                registry.add("app.security.oidc.jwk-set-uri", () -> issuer() + "/protocol/openid-connect/certs");
                registry.add("app.security.oidc.audience", () -> "ai-workforce-api");
            };
        }
    }

    @Autowired
    private MockMvc mvc;

    private static String userEmail;

    @BeforeAll
    static void createUser() throws Exception {
        KEYCLOAK.start();
        userEmail = "kc." + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        String adminToken = JSON.readTree(postForm(base() + "/realms/master/protocol/openid-connect/token", Map.of(
                "grant_type", "password", "client_id", "admin-cli", "username", "admin", "password", ADMIN_PASSWORD))
                .body()).get("access_token").asString();
        Map<String, Object> user = Map.of("username", userEmail, "email", userEmail, "emailVerified", true,
                "enabled", true, "firstName", "Kay", "lastName", "Cloak",
                "credentials", List.of(Map.of("type", "password", "value", USER_PASSWORD, "temporary", false)));
        HttpResponse<String> created = HTTP.send(HttpRequest.newBuilder(URI.create(base() + "/admin/realms/" + REALM + "/users"))
                .header("Authorization", "Bearer " + adminToken).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(user))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
    }

    // ------------------------------------------------------------------ client policy

    @Test
    void discoveryAdvertisesTheIssuerAndPkceS256() throws Exception {
        JsonNode discovery = JSON.readTree(HTTP.send(HttpRequest.newBuilder(
                URI.create(issuer() + "/.well-known/openid-configuration")).build(),
                HttpResponse.BodyHandlers.ofString()).body());
        assertThat(discovery.get("issuer").asString()).isEqualTo(issuer());
        assertThat(discovery.get("code_challenge_methods_supported").valueStream().map(JsonNode::asString))
                .contains("S256");
        assertThat(discovery.get("revocation_endpoint").asString()).isNotBlank();
        assertThat(discovery.get("end_session_endpoint").asString()).isNotBlank();
    }

    @Test
    void authorizationRequiresPkceS256() throws Exception {
        String redirect = "http://127.0.0.1:50123/callback";
        assertThat(authorize(redirect, null, null).statusCode()).isNotEqualTo(200);
        assertThat(authorize(redirect, "plain", "a".repeat(43)).statusCode()).isNotEqualTo(200);
        assertThat(authorize(redirect, "S256", challenge(verifier())).statusCode()).isEqualTo(200);
    }

    @Test
    void onlyTheLoopbackCallbackIsAcceptedOnAnyPort() throws Exception {
        String challenge = challenge(verifier());
        assertThat(authorize("http://127.0.0.1:61234/callback", "S256", challenge).statusCode()).isEqualTo(200);
        assertThat(authorize("http://127.0.0.1:61234/other", "S256", challenge).statusCode()).isEqualTo(400);
        assertThat(authorize("http://localhost:61234/callback", "S256", challenge).statusCode()).isEqualTo(400);
        assertThat(authorize("https://evil.example/callback", "S256", challenge).statusCode()).isEqualTo(400);
    }

    @Test
    void passwordAndImplicitGrantsAreDisabled() throws Exception {
        HttpResponse<String> password = postForm(issuer() + "/protocol/openid-connect/token", Map.of(
                "grant_type", "password", "client_id", CLIENT_ID, "username", userEmail, "password", USER_PASSWORD));
        assertThat(password.statusCode()).isIn(400, 401);
        assertThat(password.body()).doesNotContain("access_token");

        HttpResponse<String> implicit = HTTP.send(HttpRequest.newBuilder(URI.create(issuer()
                + "/protocol/openid-connect/auth?client_id=" + CLIENT_ID + "&response_type=token"
                + "&scope=openid&redirect_uri=" + enc("http://127.0.0.1:50123/callback") + "&state=s&nonce=n"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(implicit.statusCode() == 200 && implicit.body().contains("kc-form-login")).isFalse();
    }

    // ------------------------------------------------------------------ full login

    @Test
    void aPkceLoginYieldsAnAccessTokenForTheApiWithRotatingRefreshTokens() throws Exception {
        Tokens tokens = login();

        JsonNode access = claims(tokens.accessToken());
        assertThat(access.get("iss").asString()).isEqualTo(issuer());
        assertThat(access.get("azp").asString()).isEqualTo(CLIENT_ID);
        assertThat(audiences(access)).contains("ai-workforce-api");
        assertThat(access.get("typ").asString()).isEqualTo("Bearer");
        JsonNode id = claims(tokens.idToken());
        assertThat(id.get("nonce").asString()).isEqualTo(tokens.nonce());
        assertThat(audiences(id)).doesNotContain("ai-workforce-api");

        // The API accepts the access token and provisions the user from (iss, sub).
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(userEmail))
                .andExpect(jsonPath("$.emailVerified").value(true));
        // ... but never the ID token.
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.idToken()))
                .andExpect(status().isUnauthorized());

        // Refresh token rotation: the new refresh token works, the old one is rejected.
        HttpResponse<String> refreshed = refresh(tokens.refreshToken());
        assertThat(refreshed.statusCode()).isEqualTo(200);
        String rotated = JSON.readTree(refreshed.body()).get("refresh_token").asString();
        assertThat(rotated).isNotEqualTo(tokens.refreshToken());
        assertThat(refresh(tokens.refreshToken()).statusCode()).isEqualTo(400);

        // Logout: revoking the refresh token ends the session.
        HttpResponse<String> revoked = postForm(issuer() + "/protocol/openid-connect/revoke",
                Map.of("client_id", CLIENT_ID, "token", rotated, "token_type_hint", "refresh_token"));
        assertThat(revoked.statusCode()).isEqualTo(200);
        assertThat(refresh(rotated).statusCode()).isEqualTo(400);
    }

    @Test
    void theAuthorizationCodeCannotBeRedeemedWithoutTheVerifier() throws Exception {
        Authorization authorization = authorizeAndSignIn();
        HttpResponse<String> exchange = postForm(issuer() + "/protocol/openid-connect/token", Map.of(
                "grant_type", "authorization_code", "client_id", CLIENT_ID, "code", authorization.code(),
                "redirect_uri", authorization.redirectUri(), "code_verifier", verifier()));
        assertThat(exchange.statusCode()).isEqualTo(400);
        assertThat(exchange.body()).doesNotContain("access_token");
    }

    // ------------------------------------------------------------------ helpers

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();

    record Tokens(String accessToken, String idToken, String refreshToken, String nonce) {
    }

    record Authorization(String code, String redirectUri, String verifier, String nonce) {
    }

    private static Tokens login() throws Exception {
        Authorization authorization = authorizeAndSignIn();
        HttpResponse<String> exchange = postForm(issuer() + "/protocol/openid-connect/token", Map.of(
                "grant_type", "authorization_code", "client_id", CLIENT_ID, "code", authorization.code(),
                "redirect_uri", authorization.redirectUri(), "code_verifier", authorization.verifier()));
        assertThat(exchange.statusCode()).as(exchange.body()).isEqualTo(200);
        JsonNode body = JSON.readTree(exchange.body());
        return new Tokens(body.get("access_token").asString(), body.get("id_token").asString(),
                body.get("refresh_token").asString(), authorization.nonce());
    }

    /** Drives the Keycloak login form like a browser would, and returns the authorization code. */
    private static Authorization authorizeAndSignIn() throws Exception {
        // Keycloak marks its cookies Secure (browsers treat http://localhost as secure, the JDK
        // cookie store does not), so they are carried over explicitly like a browser would.
        HttpClient browser = HTTP;
        String verifier = verifier();
        String state = verifier().substring(0, 20);
        String nonce = verifier().substring(0, 20);
        String redirectUri = "http://127.0.0.1:" + (49152 + new SecureRandom().nextInt(10000)) + "/callback";
        HttpResponse<String> page = browser.send(HttpRequest.newBuilder(URI.create(authorizationUrl(redirectUri,
                "S256", challenge(verifier)) + "&state=" + state + "&nonce=" + nonce)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(page.statusCode()).isEqualTo(200);
        Matcher action = Pattern.compile("id=\"kc-form-login\"[^>]*action=\"([^\"]+)\"").matcher(page.body());
        assertThat(action.find()).as("login form").isTrue();
        String formUrl = action.group(1).replace("&amp;", "&");
        String cookies = page.headers().allValues("Set-Cookie").stream()
                .map(cookie -> cookie.split(";", 2)[0]).collect(Collectors.joining("; "));
        HttpResponse<String> submitted = browser.send(HttpRequest.newBuilder(URI.create(formUrl))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Cookie", cookies)
                .POST(HttpRequest.BodyPublishers.ofString(form(Map.of("username", userEmail, "password", USER_PASSWORD))))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(submitted.statusCode()).as(submitted.body()).isEqualTo(302);
        URI location = URI.create(submitted.headers().firstValue("Location").orElseThrow());
        assertThat(location.getHost()).isEqualTo("127.0.0.1");
        Map<String, String> query = parseQuery(location.getRawQuery());
        assertThat(query.get("state")).isEqualTo(state);
        assertThat(query).containsKey("code").doesNotContainKey("access_token");
        return new Authorization(query.get("code"), redirectUri, verifier, nonce);
    }

    private static HttpResponse<String> authorize(String redirectUri, String method, String challenge)
            throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(URI.create(authorizationUrl(redirectUri, method, challenge)
                + "&state=state123&nonce=nonce123")).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String authorizationUrl(String redirectUri, String method, String challenge) {
        StringBuilder url = new StringBuilder(issuer()).append("/protocol/openid-connect/auth?response_type=code")
                .append("&client_id=").append(CLIENT_ID)
                .append("&scope=").append(enc("openid profile email"))
                .append("&redirect_uri=").append(enc(redirectUri));
        if (method != null) {
            url.append("&code_challenge_method=").append(method).append("&code_challenge=").append(challenge);
        }
        return url.toString();
    }

    private static HttpResponse<String> refresh(String refreshToken) throws IOException, InterruptedException {
        return postForm(issuer() + "/protocol/openid-connect/token",
                Map.of("grant_type", "refresh_token", "client_id", CLIENT_ID, "refresh_token", refreshToken));
    }

    private static HttpResponse<String> postForm(String url, Map<String, String> fields)
            throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(fields))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String form(Map<String, String> fields) {
        return fields.entrySet().stream().map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> values = new HashMap<>();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            values.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return values;
    }

    private static JsonNode claims(String jwt) {
        return JSON.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
    }

    private static List<String> audiences(JsonNode claims) {
        JsonNode aud = claims.get("aud");
        if (aud == null) {
            return List.of();
        }
        return aud.isArray() ? aud.valueStream().map(JsonNode::asString).toList() : List.of(aud.asString());
    }

    private static String verifier() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String challenge(String verifier) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String base() {
        return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080);
    }

    static String issuer() {
        return base() + "/realms/" + REALM;
    }
}
