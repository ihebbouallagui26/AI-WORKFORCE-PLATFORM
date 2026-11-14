package com.aiworkforce.core.agent;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
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
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(secret|token|password|passwd|authorization|api[-_]?key|credential|cookie|private[-_]?key)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET_VALUE = Pattern.compile(
            "(Bearer\\s+[A-Za-z0-9._~+/=-]{8,}|sk-[A-Za-z0-9_-]{8,}|AIza[0-9A-Za-z_-]{20,}|eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]*)");
    static final String MASK = "[REDACTED]";
    private static final int MAX_STRING = 300;
    private static final int MAX_DISPLAY = 2000;

    private ToolArguments() {
    }

    /** Masks credential-looking values in free text (Bearer tokens, API keys, JWTs). */
    static String maskSecrets(String text) {
        return text == null ? "" : SECRET_VALUE.matcher(text).replaceAll(MASK);
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

    /** Masked, bounded rendering for humans (approval card, history). Never the raw arguments. */
    static String redacted(JsonNode node) {
        String text = canonical(mask(node, null));
        return text.length() > MAX_DISPLAY ? text.substring(0, MAX_DISPLAY) + "…" : text;
    }

    private static JsonNode mask(JsonNode node, String key) {
        if (key != null && SENSITIVE_KEY.matcher(key).find()) {
            return MAPPER.getNodeFactory().stringNode(MASK);
        }
        if (node.isObject()) {
            var copy = MAPPER.createObjectNode();
            for (var entry : node.properties()) {
                copy.set(entry.getKey(), mask(entry.getValue(), entry.getKey()));
            }
            return copy;
        }
        if (node.isArray()) {
            var copy = MAPPER.createArrayNode();
            for (JsonNode element : node.values()) {
                copy.add(mask(element, null));
            }
            return copy;
        }
        if (node.isString()) {
            String value = SECRET_VALUE.matcher(node.asString()).replaceAll(MASK);
            return MAPPER.getNodeFactory().stringNode(value.length() > MAX_STRING ? value.substring(0, MAX_STRING) + "…" : value);
        }
        return node;
    }

    static JsonNode parse(String json) {
        return MAPPER.readTree(json);
    }
}
