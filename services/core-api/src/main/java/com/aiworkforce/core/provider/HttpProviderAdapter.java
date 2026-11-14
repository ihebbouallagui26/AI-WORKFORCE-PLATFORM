package com.aiworkforce.core.provider;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared HTTP plumbing of the adapters: GET only, fixed base URL from configuration, no redirects,
 * timeouts, bounded response size. Subclasses decide headers and how to read the JSON.
 */
abstract class HttpProviderAdapter implements ProviderAdapter {

    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final int MAX_PAGES = 3;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient client;
    private final URI baseUrl;
    private final Duration timeout;

    HttpProviderAdapter(URI baseUrl, Duration timeout) {
        this.baseUrl = baseUrl;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** Headers carrying the credential. */
    protected abstract Map<String, String> headers(String credential);

    /** Path and query of the cheapest authenticated request (one model). */
    protected abstract String validationPath();

    /** Whether this response means "the credential is wrong". */
    protected boolean rejectsCredential(int status, String body) {
        return status == 401 || status == 403;
    }

    /** Reads one page of models from the JSON body; returns the path of the next page or null. */
    protected abstract String readModelsPage(JsonNode page, List<ModelInfo> into);

    protected abstract String firstModelsPath();

    @Override
    public Outcome validate(String credential) {
        try {
            Response response = get(validationPath(), credential);
            if (response.status() == 200) {
                return Outcome.VALID;
            }
            return rejectsCredential(response.status(), response.body()) ? Outcome.INVALID : Outcome.UNAVAILABLE;
        } catch (ProviderException e) {
            return Outcome.UNAVAILABLE;
        }
    }

    @Override
    public List<ModelInfo> listModels(String credential) {
        List<ModelInfo> models = new java.util.ArrayList<>();
        String path = firstModelsPath();
        for (int page = 0; path != null && page < MAX_PAGES; page++) {
            Response response = get(path, credential);
            if (response.status() != 200) {
                boolean rejected = rejectsCredential(response.status(), response.body());
                throw new ProviderException(rejected ? "The provider rejected the credential."
                        : "The provider could not list its models.", rejected);
            }
            try {
                path = readModelsPage(JSON.readTree(response.body()), models);
            } catch (RuntimeException e) {
                throw new ProviderException("The provider answered with an unexpected format.", false);
            }
        }
        return models;
    }

    private Response get(String path, String credential) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl.toString().replaceAll("/+$", "") + path))
                .timeout(timeout).GET().header("Accept", "application/json");
        headers(credential).forEach(request::header);
        try {
            HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) {
                    throw new ProviderException("The provider response is too large.", false);
                }
                return new Response(response.statusCode(), new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new ProviderException("The provider could not be reached.", false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("The request was interrupted.", false);
        }
    }

    private record Response(int status, String body) {
    }
}
