package com.aiworkforce.core.agent;

import com.aiworkforce.core.agent.redaction.Redactor;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Canonical form, hash and display form of tool-call arguments.
 *
 * <ul>
 *   <li><b>Canonical JSON</b>: object keys sorted, no whitespace, numbers normalized. The same logical
 *       arguments always give the same text, so the SHA-256 binds an approval to exactly one call: any
 *       change of an argument changes the hash and invalidates a previous decision.</li>
 *   <li><b>Redacted display</b>: values under secret-looking keys are masked and long strings are cut, so
 *       the approval card and the history never expose raw sensitive values. The full arguments exist only
 *       in the encrypted vault.</li>
 * </ul>
 */
final class ToolArguments {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    static final String MASK = Redactor.MASK;
    private static final int MAX_DISPLAY = 2000;

    private ToolArguments() {
    }

    /** Masks credential-looking values in free text (see {@link Redactor}). */
    static String maskSecrets(String text) {
        return Redactor.maskText(text);
    }

    static String canonical(JsonNode node) {
        StringBuilder out = new StringBuilder();
        write(node, out);
        return out.toString();
    }

    private static void write(JsonNode node, StringBuilder out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            out.append("null");
        } else if (node.isObject()) {
            List<String> names = new ArrayList<>(node.propertyNames());
            names.sort(String::compareTo);
            out.append('{');
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(quote(names.get(i))).append(':');
                write(node.get(names.get(i)), out);
            }
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            boolean first = true;
            for (JsonNode element : node.values()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                write(element, out);
            }
            out.append(']');
        } else if (node.isString()) {
            out.append(quote(node.asString()));
        } else if (node.isNumber()) {
            BigDecimal value = node.decimalValue();
            out.append(value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString());
        } else {
            out.append(node.isBoolean() ? Boolean.toString(node.asBoolean()) : "null");
        }
    }

    private static String quote(String value) {
        return MAPPER.writeValueAsString(value);
    }

    static String sha256(String canonicalJson) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalJson.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Masked, bounded rendering for humans (approval card, history). Never the raw arguments. DISPLAY ONLY: the canonical form and the
     * hash an approval is bound to are computed from the original arguments and are not influenced by this method.
     */
    static String redacted(JsonNode node) {
        String text = canonical(Redactor.maskJson(node));
        return text.length() > MAX_DISPLAY ? text.substring(0, MAX_DISPLAY) + "…" : text;
    }

    static JsonNode parse(String json) {
        return MAPPER.readTree(json);
    }
}
