package com.aiworkforce.core.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Security fix 8.2: the DISPLAY redaction must never change what is executed or what an approval is bound to. The canonical form and
 * the SHA-256 of the ORIGINAL arguments are pinned by a golden value that was computed before the redaction was centralized.
 */
class ToolArgumentsTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String RAW = "{\"d\":\"Bearer abcdefghijklmnop\",\"c\":{\"z\":1.50,\"y\":\"eacute\"},\"b\":1,\"a\":[\"x\",true,null]}";
    private static final String GOLDEN_CANONICAL = "{\"a\":[\"x\",true,null],\"b\":1,\"c\":{\"y\":\"eacute\",\"z\":1.5},\"d\":\"Bearer abcdefghijklmnop\"}";
    private static final String GOLDEN_SHA256 = "22d0974ad31e2606e0d133a101d843402637659c9e0ccbc46c1b6fe3f0a41855";

    @Test
    void theApprovalHashOfTheOriginalArgumentsNeverChanges() throws Exception {
        JsonNode raw = MAPPER.readTree(RAW);
        assertThat(ToolArguments.canonical(raw)).isEqualTo(GOLDEN_CANONICAL);                 // the secret is still in the canonical (executed) form
        assertThat(ToolArguments.sha256(ToolArguments.canonical(raw))).isEqualTo(GOLDEN_SHA256);
        ToolArguments.redacted(raw);                                                          // displaying it does not alter anything
        assertThat(ToolArguments.sha256(ToolArguments.canonical(raw))).isEqualTo(GOLDEN_SHA256);
        assertThat(raw.toString()).contains("Bearer abcdefghijklmnop");                       // and the node itself is untouched
    }

    @Test
    void theDisplayFormHidesSecretsButKeepsTheContext() throws Exception {
        JsonNode raw = MAPPER.readTree("{\"url\":\"postgresql://admin:S3cret@db.internal/app\",\"headers\":{\"Authorization\":\"Bearer abcdefghijklmnop\","
                + "\"X-Trace\":\"t-1\"},\"files\":[{\"name\":\"a.txt\",\"api_key\":\"k\"}],\"note\":\"hello\"}");
        String shown = ToolArguments.redacted(raw);
        assertThat(shown).doesNotContain("S3cret").doesNotContain("abcdefghijklmnop").doesNotContain("\"k\"")
                .contains("db.internal/app").contains("X-Trace").contains("a.txt").contains("hello").contains("[REDACTED]");
    }

    @Test
    void theDisplayFormIsBounded() throws Exception {
        StringBuilder many = new StringBuilder("{");
        for (int i = 0; i < 400; i++) {
            many.append(i == 0 ? "" : ",").append("\"key").append(i).append("\":\"").append("v".repeat(50)).append('"');
        }
        assertThat(ToolArguments.redacted(MAPPER.readTree(many.append('}').toString())).length()).isLessThanOrEqualTo(2001);
    }

    @Test
    void freeTextHelperUsedByWorkflowSummariesIsTheCentralOne() {
        assertThat(ToolArguments.maskSecrets("see https://x.example/p?access_token=abc12345678 and Cookie: s=1")).doesNotContain("abc12345678")
                .doesNotContain("s=1").contains("[REDACTED]");
    }
}
