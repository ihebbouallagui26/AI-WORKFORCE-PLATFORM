package com.aiworkforce.core.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * Minimal identity provider for integration tests: an RSA key published as a JWKS on a loopback
 * HTTP server, and a token builder. The API validates these tokens with its <em>production</em>
 * decoder and validators (remote JWKS, signature, issuer, audience, timestamps, token type);
 * nothing is mocked or disabled. Interoperability with the real Keycloak is covered separately
 * by {@code KeycloakIntegrationTest}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestIdentityProvider {

    public static final String ISSUER = "http://127.0.0.1/realms/ai-workforce-test";
    public static final String AUDIENCE = "ai-workforce-api";
    public static final String CLIENT_ID = "ai-workforce-desktop";

    private static final RSAKey SIGNING_KEY = generateKey("test-key-1");
    private static final RSAKey FOREIGN_KEY = generateKey("test-key-1");
    private static final String JWKS_URI = startJwksServer();

    @Bean
    DynamicPropertyRegistrar identityProviderProperties() {
        return registry -> {
            registry.add("app.security.oidc.issuer", () -> ISSUER);
            registry.add("app.security.oidc.jwk-set-uri", () -> JWKS_URI);
            registry.add("app.security.oidc.audience", () -> AUDIENCE);
        };
    }

    public static Token token(String subject) {
        return new Token(subject);
    }

    /** A user identity: one subject with a verified e-mail. */
    public static String accessToken(String subject, String email) {
        return token(subject).email(email).sign();
    }

    /** Fluent builder for valid and deliberately invalid tokens. */
    public static final class Token {

        private final JWTClaimsSet.Builder claims;
        private String type = "Bearer";
        private JOSEObjectType headerType = JOSEObjectType.JWT;
        private RSAKey key = SIGNING_KEY;
        private JWSAlgorithm algorithm = JWSAlgorithm.RS256;

        private Token(String subject) {
            Instant now = Instant.now();
            claims = new JWTClaimsSet.Builder()
                    .jwtID(UUID.randomUUID().toString())
                    .issuer(ISSUER)
                    .subject(subject)
                    .audience(List.of(AUDIENCE, "account"))
                    .issueTime(Date.from(now))
                    .notBeforeTime(Date.from(now.minusSeconds(1)))
                    .expirationTime(Date.from(now.plus(Duration.ofMinutes(5))))
                    .claim("azp", CLIENT_ID)
                    .claim("email", subject + "@example.test")
                    .claim("email_verified", true)
                    .claim("name", "User " + subject);
        }

        public Token email(String email) {
            claims.claim("email", email);
            return this;
        }

        public Token emailVerified(boolean verified) {
            claims.claim("email_verified", verified);
            return this;
        }

        public Token name(String name) {
            claims.claim("name", name);
            return this;
        }

        public Token issuer(String issuer) {
            claims.issuer(issuer);
            return this;
        }

        public Token audience(String... audience) {
            claims.audience(List.of(audience));
            return this;
        }

        public Token expiresAt(Instant instant) {
            claims.expirationTime(Date.from(instant));
            return this;
        }

        public Token notBefore(Instant instant) {
            claims.notBeforeTime(Date.from(instant));
            return this;
        }

        /** Keycloak's {@code typ} claim: "Bearer" for access tokens, "ID" for ID tokens. */
        public Token type(String type) {
            this.type = type;
            return this;
        }

        public Token signedWithForeignKey() {
            this.key = FOREIGN_KEY;
            return this;
        }

        public Token algorithm(JWSAlgorithm algorithm) {
            this.algorithm = algorithm;
            return this;
        }

        public String sign() {
            try {
                if (type != null) {
                    claims.claim("typ", type);
                }
                SignedJWT jwt = new SignedJWT(
                        new JWSHeader.Builder(algorithm).keyID(key.getKeyID()).type(headerType).build(),
                        claims.build());
                jwt.sign(new RSASSASigner(key));
                return jwt.serialize();
            } catch (JOSEException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static RSAKey generateKey(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String startJwksServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            byte[] body = new JWKSet(SIGNING_KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/certs", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.setExecutor(null);
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/certs";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
