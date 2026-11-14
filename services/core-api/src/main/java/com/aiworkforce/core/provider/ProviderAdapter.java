package com.aiworkforce.core.provider;

import java.util.List;

/**
 * Provider-specific credential validation and model discovery. Implementations only call fixed,
 * official, read-only endpoints (model listing): they never produce a completion, so a check
 * costs nothing. They never log or return the credential, nor the provider's raw error body.
 */
public interface ProviderAdapter {

    /** Result of a credential check. UNAVAILABLE means "could not tell" (network, rate limit, 5xx). */
    enum Outcome { VALID, INVALID, UNAVAILABLE }

    /** A model offered by the provider. */
    record ModelInfo(String id, String displayName) {
    }

    ProviderType type();

    Outcome validate(String credential);

    /**
     * Models the credential can use, as reported by the provider (never a hardcoded list).
     *
     * @throws ProviderException when the provider cannot be queried
     */
    List<ModelInfo> listModels(String credential);

    /** The provider answered with an error or could not be reached. The message is safe to show. */
    class ProviderException extends RuntimeException {

        private final boolean rejectedCredential;

        public ProviderException(String message, boolean rejectedCredential) {
            super(message);
            this.rejectedCredential = rejectedCredential;
        }

        public boolean isRejectedCredential() {
            return rejectedCredential;
        }
    }
}
