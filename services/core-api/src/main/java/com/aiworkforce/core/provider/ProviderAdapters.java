package com.aiworkforce.core.provider;

import com.aiworkforce.core.common.exception.ConflictException;
import java.net.URI;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Registry of provider adapters, built from the fixed endpoints in {@link ProviderProperties}. */
@Component
public class ProviderAdapters {

    private final Map<ProviderType, ProviderAdapter> adapters = new EnumMap<>(ProviderType.class);

    ProviderAdapters(ProviderProperties properties) {
        Duration timeout = properties.timeout();
        adapters.put(ProviderType.ANTHROPIC, new Anthropic(properties.anthropic().baseUrl(), timeout));
        adapters.put(ProviderType.OPENAI, new OpenAi(properties.openai().baseUrl(), timeout));
        adapters.put(ProviderType.GEMINI, new Gemini(properties.gemini().baseUrl(), timeout));
    }

    public ProviderAdapter forType(ProviderType type) {
        ProviderAdapter adapter = adapters.get(type);
        if (adapter == null) {
            throw new ConflictException("This provider is not supported.");
        }
        return adapter;
    }

    /** Anthropic Messages API family: {@code x-api-key} + {@code anthropic-version}; GET /v1/models. */
    private static final class Anthropic extends HttpProviderAdapter {

        Anthropic(URI baseUrl, Duration timeout) {
            super(baseUrl, timeout);
        }

        @Override public ProviderType type() { return ProviderType.ANTHROPIC; }

        @Override
        protected Map<String, String> headers(String credential) {
            return Map.of("x-api-key", credential, "anthropic-version", "2023-06-01");
        }

        @Override protected String validationPath() { return "/v1/models?limit=1"; }

        @Override protected String firstModelsPath() { return "/v1/models?limit=100"; }

        @Override
        protected String readModelsPage(JsonNode page, List<ModelInfo> into) {
            for (JsonNode model : page.path("data")) {
                String id = model.path("id").asString("");
                if (!id.isBlank()) {
                    into.add(new ModelInfo(id, model.path("display_name").asString(id)));
                }
            }
            String last = page.path("last_id").asString("");
            return page.path("has_more").asBoolean(false) && !last.isBlank()
                    ? "/v1/models?limit=100&after_id=" + java.net.URLEncoder.encode(last, java.nio.charset.StandardCharsets.UTF_8)
                    : null;
        }
    }

    /** OpenAI API: {@code Authorization: Bearer}; GET /v1/models. */
    private static final class OpenAi extends HttpProviderAdapter {

        OpenAi(URI baseUrl, Duration timeout) {
            super(baseUrl, timeout);
        }

        @Override public ProviderType type() { return ProviderType.OPENAI; }

        @Override
        protected Map<String, String> headers(String credential) {
            return Map.of("Authorization", "Bearer " + credential);
        }

        @Override protected String validationPath() { return "/v1/models"; }

        @Override protected String firstModelsPath() { return "/v1/models"; }

        @Override
        protected String readModelsPage(JsonNode page, List<ModelInfo> into) {
            for (JsonNode model : page.path("data")) {
                String id = model.path("id").asString("");
                if (!id.isBlank()) {
                    into.add(new ModelInfo(id, id));
                }
            }
            return null;
        }
    }

    /**
     * Google Gemini API: {@code x-goog-api-key}; GET /v1beta/models. Verified behavior: an invalid
     * key is answered with HTTP 400 and reason API_KEY_INVALID (not 401), handled below.
     */
    private static final class Gemini extends HttpProviderAdapter {

        Gemini(URI baseUrl, Duration timeout) {
            super(baseUrl, timeout);
        }

        @Override public ProviderType type() { return ProviderType.GEMINI; }

        @Override
        protected Map<String, String> headers(String credential) {
            return Map.of("x-goog-api-key", credential);
        }

        @Override protected String validationPath() { return "/v1beta/models?pageSize=1"; }

        @Override protected String firstModelsPath() { return "/v1beta/models?pageSize=100"; }

        @Override
        protected boolean rejectsCredential(int status, String body) {
            return status == 401 || status == 403 || (status == 400 && body.contains("API_KEY_INVALID"));
        }

        @Override
        protected String readModelsPage(JsonNode page, List<ModelInfo> into) {
            for (JsonNode model : page.path("models")) {
                JsonNode methods = model.path("supportedGenerationMethods");
                boolean generates = methods.isMissingNode() || methods.valueStream().anyMatch(m -> "generateContent".equals(m.asString()));
                String name = model.path("name").asString("");
                String id = name.startsWith("models/") ? name.substring("models/".length()) : name;
                if (generates && !id.isBlank()) {
                    into.add(new ModelInfo(id, model.path("displayName").asString(id)));
                }
            }
            String next = page.path("nextPageToken").asString("");
            return next.isBlank() ? null
                    : "/v1beta/models?pageSize=100&pageToken=" + java.net.URLEncoder.encode(next, java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
