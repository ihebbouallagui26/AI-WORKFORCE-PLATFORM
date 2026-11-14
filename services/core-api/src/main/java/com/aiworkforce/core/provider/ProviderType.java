package com.aiworkforce.core.provider;

/** Supported AI providers. A new provider is one enum value plus one {@link ProviderAdapter}. */
public enum ProviderType {
    ANTHROPIC("Anthropic Claude"),
    OPENAI("OpenAI"),
    GEMINI("Google Gemini");

    private final String displayName;

    ProviderType(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
