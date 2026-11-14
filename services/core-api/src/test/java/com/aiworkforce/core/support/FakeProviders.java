package com.aiworkforce.core.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * Local fake servers for the three AI providers (no real API key is ever needed). The behavior
 * depends on the credential used by the caller:
 * <ul>
 *   <li>{@code valid-*}: models are listed ({@code valid-paged} answers over two pages);</li>
 *   <li>{@code bad-*}: rejected the way each real provider does (401; Gemini: 400 API_KEY_INVALID),
 *       with a body that <em>echoes the credential</em>, to prove the API never relays it;</li>
 *   <li>{@code rate-*}: 429; {@code down-*}: 503.</li>
 * </ul>
 * Every request is recorded (path, query and headers) so tests can assert how each adapter authenticates.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeProviders {

    /** A request received by a fake provider. */
    public record Received(String provider, String path, Map<String, String> headers) {
    }

    private static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();
    private static final HttpServer ANTHROPIC = start("anthropic", FakeProviders::anthropic);
    private static final HttpServer OPENAI = start("openai", FakeProviders::openai);
    private static final HttpServer GEMINI = start("gemini", FakeProviders::gemini);

    @Bean
    DynamicPropertyRegistrar fakeProviderProperties() {
        return registry -> {
            registry.add("app.providers.anthropic.base-url", () -> url(ANTHROPIC));
            registry.add("app.providers.openai.base-url", () -> url(OPENAI));
            registry.add("app.providers.gemini.base-url", () -> url(GEMINI));
            registry.add("app.providers.timeout", () -> "3s");
        };
    }

    public static List<Received> received() {
        return List.copyOf(RECEIVED);
    }

    public static void reset() {
        RECEIVED.clear();
    }

    // ------------------------------------------------------------------ behaviors

    private static Reply anthropic(String credential, String path) {
        Reply failure = failure(credential, 401);
        if (failure != null) {
            return failure;
        }
        if (credential.equals("valid-paged")) {
            return path.contains("after_id=")
                    ? new Reply(200, "{\"data\":[{\"id\":\"claude-test-2\",\"display_name\":\"Claude Test 2\"}],\"has_more\":false}")
                    : new Reply(200, "{\"data\":[{\"id\":\"claude-test-1\",\"display_name\":\"Claude Test 1\"}],"
                            + "\"has_more\":true,\"last_id\":\"claude-test-1\"}");
        }
        return new Reply(200, "{\"data\":[{\"id\":\"claude-test-1\",\"display_name\":\"Claude Test 1\"},"
                + "{\"id\":\"claude-test-2\",\"display_name\":\"Claude Test 2\"}],\"has_more\":false}");
    }

    private static Reply openai(String credential, String path) {
        Reply failure = failure(credential, 401);
        return failure != null ? failure : new Reply(200,
                "{\"object\":\"list\",\"data\":[{\"id\":\"gpt-test-1\",\"object\":\"model\"},{\"id\":\"gpt-test-2\",\"object\":\"model\"}]}");
    }

    private static Reply gemini(String credential, String path) {
        if (credential.startsWith("bad")) {
            // Verified against the real API: an invalid key is a 400 with reason API_KEY_INVALID.
            return new Reply(400, "{\"error\":{\"code\":400,\"message\":\"API key not valid: " + credential
                    + "\",\"status\":\"INVALID_ARGUMENT\",\"details\":[{\"reason\":\"API_KEY_INVALID\"}]}}");
        }
        Reply failure = failure(credential, 403);
        return failure != null ? failure : new Reply(200, "{\"models\":["
                + "{\"name\":\"models/gemini-test-1\",\"displayName\":\"Gemini Test 1\",\"supportedGenerationMethods\":[\"generateContent\"]},"
                + "{\"name\":\"models/embedding-test\",\"displayName\":\"Embedding\",\"supportedGenerationMethods\":[\"embedContent\"]}]}");
    }

    private static Reply failure(String credential, int rejectedStatus) {
        if (credential.startsWith("bad")) {
            return new Reply(rejectedStatus, "{\"error\":\"Incorrect API key provided: " + credential + "\"}");
        }
        if (credential.startsWith("rate")) {
            return new Reply(429, "{\"error\":\"rate limited for " + credential + "\"}");
        }
        if (credential.startsWith("down")) {
            return new Reply(503, "{\"error\":\"unavailable\"}");
        }
        return credential.startsWith("valid") ? null : new Reply(rejectedStatus, "{\"error\":\"unknown credential " + credential + "\"}");
    }

    // ------------------------------------------------------------------ plumbing

    private record Reply(int status, String body) {
    }

    private static String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static HttpServer start(String provider, java.util.function.BiFunction<String, String, Reply> behavior) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> handle(provider, behavior, exchange));
            server.setExecutor(null);
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void handle(String provider, java.util.function.BiFunction<String, String, Reply> behavior,
            HttpExchange exchange) throws IOException {
        Map<String, String> headers = new java.util.HashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(), String.join(",", values)));
        String path = exchange.getRequestURI().toString();
        RECEIVED.add(new Received(provider, path, headers));
        String credential = switch (provider) {
            case "anthropic" -> headers.getOrDefault("x-api-key", "");
            case "openai" -> headers.getOrDefault("authorization", "").replaceFirst("^Bearer ", "");
            default -> headers.getOrDefault("x-goog-api-key", "");
        };
        Reply reply = credential.isEmpty() ? new Reply(401, "{\"error\":\"no credential\"}") : behavior.apply(credential, path);
        byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(reply.status(), body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
