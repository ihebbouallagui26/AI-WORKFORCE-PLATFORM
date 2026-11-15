package com.aiworkforce.core.agent.redaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Security fix 8.2, Core API side. The corpus mirrors tests/test_redaction.py of the orchestrator: the same families must be hidden by
 * both implementations.
 */
class RedactorTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String PEM = "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC7\nabcdefghijklmnopqrstuvwxyz0123456789+/=\n-----END PRIVATE KEY-----";

    static Stream<Arguments> secretsInText() {
        return Stream.of(
                Arguments.of("bearer", "call with Authorization header Bearer abcdefghijklmnop1234 please", "abcdefghijklmnop1234"),
                Arguments.of("basic", "curl -H 'Basic dXNlcjpwYXNzd29yZDEyMzQ='", "dXNlcjpwYXNzd29yZDEyMzQ"),
                Arguments.of("jwt", "token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r", "dBjftJeZ4CVPmB92K27uhbUJU1p1r"),
                Arguments.of("anthropic key", "key sk-ant-api03-ABCDEFGHIJKLMNOPQRSTUVWX0123456789", "ABCDEFGHIJKLMNOPQRSTUVWX0123456789"),
                Arguments.of("stripe", "sk_" + "live_abcdefghijklmnop12345678", "abcdefghijklmnop12345678"),
                Arguments.of("google", "AIzaSyD-abcdefghijklmnopqrstuvwxyz01234", "abcdefghijklmnopqrstuvwxyz01234"),
                Arguments.of("aws", "id AKIAIOSFODNN7EXAMPLE here", "AKIAIOSFODNN7EXAMPLE"),
                Arguments.of("github", "ghp_abcdefghijklmnopqrstuvwxyz0123456789", "abcdefghijklmnopqrstuvwxyz0123456789"),
                Arguments.of("github fine-grained", "github_pat_11ABCDEFG0abcdefghijklmnop_qrstuvwxyz", "abcdefghijklmnop_qrstuvwxyz"),
                Arguments.of("slack", "xox" + "b-123456789012-abcdefghijklmnop", "abcdefghijklmnop"),
                Arguments.of("gitlab", "glpat-abcdefghijklmnopqrstu", "abcdefghijklmnopqrstu"),
                Arguments.of("PEM", "key follows\n" + PEM + "\nend", "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC7"),
                Arguments.of("PEM without END", "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEAabcdefghijklmnopqrstuvwxyz", "MIIEowIBAAKCAQEA"),
                Arguments.of("postgres", "postgresql://admin:S3cretPassw0rd@db.internal:5432/app", "S3cretPassw0rd"),
                Arguments.of("mongodb+srv", "mongodb+srv://svc:hunter2hunter2@cluster0.example.net/db", "hunter2hunter2"),
                Arguments.of("amqp", "amqps://guest:guestpass@broker.example:5671", "guestpass"),
                Arguments.of("query token", "https://api.example.com/v1/items?id=7&access_token=abcDEF123456&limit=10", "abcDEF123456"),
                Arguments.of("query api key", "https://maps.example.com/x?api_key=AbCdEf1234567890&q=paris", "AbCdEf1234567890"),
                Arguments.of("signed url", "https://bucket.s3.amazonaws.com/f?X-Amz-Signature=0123456789abcdef0123456789abcdef", "0123456789abcdef0123456789abcdef"),
                Arguments.of("jdbc", "jdbc:postgresql://h:5432/db;user=app;password=Sup3rS3cret;ssl=true", "Sup3rS3cret"),
                Arguments.of("pair", "password=hunter2 and client_secret: 'zzzzzzzzzz'", "hunter2"),
                Arguments.of("cookie header", "Cookie: session=abcdef0123456789; theme=dark", "abcdef0123456789"),
                Arguments.of("api key header", "X-API-Key: 0123456789abcdef", "0123456789abcdef"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("secretsInText")
    void recognizableSecretShapesInTextAreMasked(String name, String text, String secret) {
        String out = Redactor.maskText(text);
        assertThat(out).doesNotContain(secret).contains(Redactor.MASK);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "The quarterly numbers were summarised in 3 bullet points.",
            "Visit https://example.com/docs/page?id=7&lang=fr for details",
            "Meeting at 10:30 with the finance team; budget=1200 EUR",
            "File path C:/reports/2026/q1.pdf and branch feat/tool-results",
            "sk is a prefix, ghp alone is not a token, AKIA is short"})
    void ordinaryTextIsNotTouched(String text) {
        assertThat(Redactor.maskText(text)).isEqualTo(text);
    }

    @Test
    void theUrlKeepsItsHostAndPathSoAReviewerKeepsTheMeaning() {
        String out = Redactor.maskText("postgresql://admin:S3cret@db.internal:5432/app?sslmode=require&password=x1y2z3");
        assertThat(out).contains("db.internal:5432/app").contains("sslmode=require").doesNotContain("S3cret").doesNotContain("x1y2z3");
    }

    @ParameterizedTest
    @ValueSource(strings = {"password", "PASSWORD", "Api_Key", "apiKey", "x-api-key", "Authorization", "client_secret", "accessToken",
            "refresh_token", "private_key", "Cookie", "session_id", "passphrase", "connection_string", "dsn", "jwt"})
    void sensitiveKeysAreMaskedWhateverTheCaseOrTheValueType(String key) throws Exception {
        for (String value : new String[] {"\"a-secret-value\"", "12345", "true", "[\"x\",\"y\"]", "{\"nested\":\"value\"}"}) {
            JsonNode out = Redactor.maskJson(MAPPER.readTree("{\"" + key + "\":" + value + "}"));
            assertThat(out.get(key).asString()).isEqualTo(Redactor.MASK);
        }
    }

    @Test
    void nestingArraysAndMixedTypesAreAllWalked() throws Exception {
        JsonNode data = MAPPER.readTree("{\"a\":{\"b\":[{\"c\":{\"password\":\"p\"}},\"plain\",7,null,[{\"token\":\"t\"},\"Bearer abcdefghijkl1234\"]]},\"n\":1.5}");
        String out = Redactor.maskJson(data).toString();
        assertThat(out).doesNotContain("\"p\"").doesNotContain("\"t\"").doesNotContain("abcdefghijkl1234").contains("plain").contains("1.5");
    }

    @Test
    void theOriginalNodeIsNeverModified() throws Exception {
        JsonNode data = MAPPER.readTree("{\"password\":\"p\",\"list\":[{\"token\":\"t\",\"text\":\"Bearer abcdefghijkl1234\"}]}");
        String before = data.toString();
        Redactor.maskJson(data);
        assertThat(data.toString()).isEqualTo(before);
    }

    @Test
    void veryDeepNestingNeverShowsWhatIsBeyondTheCap() throws Exception {
        StringBuilder json = new StringBuilder();
        int depth = Redactor.MAX_DEPTH + 40;
        for (int i = 0; i < depth; i++) {
            json.append("{\"child\":");
        }
        json.append("{\"password\":\"deep-secret\",\"leaf\":\"visible-leaf\"}");
        json.append("}".repeat(depth));
        String out = Redactor.maskJson(MAPPER.readTree(json.toString())).toString();
        assertThat(out).doesNotContain("deep-secret").doesNotContain("visible-leaf").contains(Redactor.TRUNCATED);
    }

    @Test
    void veryLargeStructuresAndStringsAreCut() throws Exception {
        StringBuilder array = new StringBuilder("[");
        for (int i = 0; i < 1000; i++) {
            array.append(i == 0 ? "" : ",").append(i);
        }
        JsonNode cut = Redactor.maskJson(MAPPER.readTree(array.append("]").toString()));
        assertThat(cut.size()).isEqualTo(Redactor.MAX_ELEMENTS + 1);
        assertThat(cut.get(Redactor.MAX_ELEMENTS).asString()).isEqualTo(Redactor.TRUNCATED);
        JsonNode longString = Redactor.maskJson(MAPPER.readTree("\"" + "x".repeat(10_000) + "\""));
        assertThat(longString.asString().length()).isLessThanOrEqualTo(Redactor.MAX_STRING + 1);
    }

    @Test
    void thePlaceholderIsExplicitEvenForAnEmptySecret() throws Exception {
        assertThat(Redactor.maskJson(MAPPER.readTree("{\"password\":\"\"}")).get("password").asString()).isEqualTo("[REDACTED]");
        assertThat(Redactor.maskText(null)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"A", "Bearer ", "-----BEGIN PRIVATE KEY-----", "eyJ", "://", "a://", "password=", "Authorization:", "a:"})
    @Timeout(value = 5)
    void pathologicalInputIsProcessedInBoundedTime(String seed) {
        String payload = seed.length() <= 1 ? seed.repeat(1_000_000) : seed + (seed.equals("://") ? "" : "x").repeat(200_000)
                + (seed.equals("://") ? "://".repeat(100_000) : "");
        long started = System.nanoTime();
        Redactor.maskText(payload);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
    }
}
