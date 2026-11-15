package com.aiworkforce.core.agent.redaction;

import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The ONE place that decides what is hidden when data is shown to people or written to logs (security fix 8.2).
 *
 * <h2>Scope</h2>
 * Display only: approval cards, execution history, tool result display, workflow summaries and anything else a person or a log sees.
 * It never touches what is executed: the real arguments sent to the MCP server, their canonical form and the SHA-256 an approval is
 * bound to are produced by other code from the ORIGINAL data and are not affected by anything here.
 *
 * <h2>What it hides</h2>
 * <ul>
 *   <li><b>By key</b> (JSON objects, case-insensitive, at any depth): secret, token, password, passphrase, pwd, authorization, api key,
 *       access key, credential, cookie, private key, session id, signature, bearer, jwt, connection string, dsn.</li>
 *   <li><b>By shape in text</b>: PEM private key blocks, {@code Bearer}/{@code Basic} credentials, JWTs, provider key formats
 *       (OpenAI/Anthropic {@code sk-}, Stripe, Google, AWS access key ids, GitHub, Slack, GitLab), credentials embedded in a URL or
 *       connection string ({@code scheme://user:password@host}), sensitive name=value pairs and URL query parameters, and
 *       {@code Authorization}/{@code Cookie}-style header lines.</li>
 * </ul>
 * A secret is replaced by {@link #MASK}, which cannot be mistaken for an empty value. Meaning is kept for reviewers: key names,
 * hosts, paths and non-sensitive values stay readable.
 *
 * <h2>Safety</h2>
 * Every expression is linear (no nested or unbounded quantifiers: lengths are capped), nesting is capped at {@link #MAX_DEPTH}
 * (anything deeper is replaced as a whole, never shown), strings, arrays and objects are cut, and the input is never modified.
 * Known limit: redaction is heuristic. A secret with no recognizable shape under an innocent key name is not detected.
 */
public final class Redactor {

    public static final String MASK = "[REDACTED]";
    public static final String TRUNCATED = "[TRUNCATED]";
    public static final int MAX_DEPTH = 32;
    static final int MAX_STRING = 300;
    static final int MAX_ELEMENTS = 100;
    /** Free text longer than this is cut BEFORE the patterns run (bounded work, whatever the input). */
    static final int MAX_TEXT = 1_000_000;

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(secret|token|password|passwd|passphrase|pwd|authorization|api[-_]?key|apikey|access[-_]?key|credential|cookie|private[-_]?key"
                    + "|session[-_]?id|signature|bearer|jwt|connection[-_]?string|dsn)", Pattern.CASE_INSENSITIVE);

    private static final Pattern PEM = Pattern.compile(
            "-----BEGIN [A-Z ]{0,40}PRIVATE KEY-----[A-Za-z0-9+/=\\s]{0,16384}(?:-----END [A-Z ]{0,40}PRIVATE KEY-----)?");
    private static final Pattern AUTH_SCHEME = Pattern.compile("\\b(?:Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]{8,512}", Pattern.CASE_INSENSITIVE);
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{8,2048}\\.[A-Za-z0-9_-]{8,2048}\\.[A-Za-z0-9_-]{0,2048}");
    private static final Pattern KEY_FORMATS = Pattern.compile(
            "sk-[A-Za-z0-9_-]{8,256}|sk_(?:live|test)_[A-Za-z0-9]{8,128}|rk_(?:live|test)_[A-Za-z0-9]{8,128}|AIza[0-9A-Za-z_-]{20,128}"
                    + "|\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b|\\bgh[pousr]_[A-Za-z0-9]{20,255}|github_pat_[A-Za-z0-9_]{20,255}"
                    + "|\\bxox[abprs]-[A-Za-z0-9-]{10,255}|\\bglpat-[A-Za-z0-9_-]{15,255}");
    /** {@code scheme://user:password@host}: only the credential part is hidden. */
    private static final Pattern URL_USERINFO = Pattern.compile("\\b([a-zA-Z][a-zA-Z0-9+.-]{1,20})://[^\\s/:@]{1,128}:[^\\s/@]{1,256}@");
    private static final Pattern SENSITIVE_PAIR = Pattern.compile(
            "(?<![A-Za-z0-9_])((?:access[_-]?token|refresh[_-]?token|id[_-]?token|api[_-]?key|apikey|client[_-]?secret|secret(?:[_-]?key)?"
                    + "|password|passwd|pwd|passphrase|token|signature|x-amz-signature|x-amz-credential|session[_-]?id|sas[_-]?token)"
                    + "\\s{0,3}[:=]\\s{0,3})(\"?)([^\\s&,;\"']{1,512})", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEADER_LINE = Pattern.compile(
            "\\b(authorization|proxy-authorization|cookie|set-cookie|x-api-key)(\\s{0,3}:\\s{0,3})[^\\r\\n]{1,1024}", Pattern.CASE_INSENSITIVE);

    private Redactor() {
    }

    public static boolean isSensitiveKey(String key) {
        return key != null && SENSITIVE_KEY.matcher(key).find();
    }

    /** Free text with every recognizable secret replaced by {@link #MASK}. Null gives the empty string. */
    public static String maskText(String text) {
        if (text == null) {
            return "";
        }
        String bounded = text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
        String out = PEM.matcher(bounded).replaceAll(MASK);
        out = HEADER_LINE.matcher(out).replaceAll("$1$2" + MASK);
        out = URL_USERINFO.matcher(out).replaceAll("$1://" + MASK + "@");
        out = AUTH_SCHEME.matcher(out).replaceAll(MASK);
        out = JWT.matcher(out).replaceAll(MASK);
        out = KEY_FORMATS.matcher(out).replaceAll(MASK);
        out = SENSITIVE_PAIR.matcher(out).replaceAll("$1$2" + MASK);
        return out;
    }

    /** A masked, bounded COPY of the JSON value for display. The argument is never modified. */
    public static JsonNode maskJson(JsonNode node) {
        return mask(node, null, 0);
    }

    private static JsonNode mask(JsonNode node, String key, int depth) {
        if (isSensitiveKey(key)) {
            return MAPPER.getNodeFactory().stringNode(MASK);
        }
        if (node == null || node.isNull() || node.isMissingNode()) {
            return MAPPER.getNodeFactory().nullNode();
        }
        if (depth >= MAX_DEPTH && (node.isObject() || node.isArray())) {
            return MAPPER.getNodeFactory().stringNode(TRUNCATED); // deeper content is never shown, whatever it holds
        }
        if (node.isObject()) {
            ObjectNode copy = MAPPER.createObjectNode();
            int count = 0;
            for (var entry : node.properties()) {
                if (++count > MAX_ELEMENTS) {
                    copy.put(TRUNCATED, "more properties omitted");
                    break;
                }
                copy.set(entry.getKey(), mask(entry.getValue(), entry.getKey(), depth + 1));
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = MAPPER.createArrayNode();
            int count = 0;
            for (JsonNode element : node.values()) {
                if (++count > MAX_ELEMENTS) {
                    copy.add(TRUNCATED);
                    break;
                }
                copy.add(mask(element, null, depth + 1));
            }
            return copy;
        }
        if (node.isString()) {
            String value = maskText(node.asString());
            return MAPPER.getNodeFactory().stringNode(value.length() > MAX_STRING ? value.substring(0, MAX_STRING) + "…" : value);
        }
        return node;
    }
}
