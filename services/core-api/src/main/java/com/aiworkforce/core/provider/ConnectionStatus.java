package com.aiworkforce.core.provider;

/**
 * Status of a provider connection. Saving a credential never makes a connection VALID: only a
 * successful test does. A credential change resets it to PENDING. REVOKED is terminal.
 */
public enum ConnectionStatus {
    PENDING,
    VALID,
    INVALID,
    REVOKED
}
