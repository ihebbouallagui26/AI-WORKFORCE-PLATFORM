package com.aiworkforce.core.provider;

import com.aiworkforce.core.common.net.TransportPolicy;
import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Security fix 8.7: the provider endpoints receive the decrypted provider credential. Outside the explicit {@code dev}/{@code test} profiles
 * they must be credential-free HTTPS, with no loopback exception (the binding itself only allows loopback HTTP for local development).
 */
@Component
class ProviderTransportValidator {

    private final ProviderProperties providers;
    private final Environment environment;

    ProviderTransportValidator(ProviderProperties providers, Environment environment) {
        this.providers = providers;
        this.environment = environment;
    }

    @PostConstruct
    void validateAtStartup() {
        Map<String, String> urls = new LinkedHashMap<>();
        urls.put("app.providers.anthropic.base-url", providers.anthropic().baseUrl().toString());
        urls.put("app.providers.openai.base-url", providers.openai().baseUrl().toString());
        urls.put("app.providers.gemini.base-url", providers.gemini().baseUrl().toString());
        TransportPolicy.validate(urls, !environment.acceptsProfiles(Profiles.of("dev", "test")));
    }
}
