package com.aiworkforce.core.provider;

import com.aiworkforce.core.common.exception.ConflictException;
import com.aiworkforce.core.common.exception.ResourceNotFoundException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only way for another module to obtain a decrypted provider credential, and only for the
 * authenticated orchestrator service (see {@code InternalExecutionRunController}).
 *
 * <p>It lives in the provider package because only that package may call {@link SecretStore#reveal}.
 * Callers must already have verified that the execution, agent and binding are authorized: this
 * class re-checks only what it owns (organization scope, connection status and credential presence).
 * It fails closed: a revoked, invalid, pending or credential-less connection yields no secret.
 */
@Component
public class ProviderCredentialBroker {

    /**
     * A resolved credential. The secret is excluded from {@code toString} so it cannot be logged by
     * accident; it must never be persisted, cached or placed in an API response other than the
     * dedicated internal one.
     */
    public record ResolvedCredential(UUID connectionId, ProviderType providerType, String secret) {

        @Override
        public String toString() {
            return "ResolvedCredential[connectionId=" + connectionId + ", providerType=" + providerType
                    + ", secret=<redacted>]";
        }
    }

    private final ProviderConnectionRepository connections;
    private final SecretStore secrets;

    ProviderCredentialBroker(ProviderConnectionRepository connections, SecretStore secrets) {
        this.connections = connections;
        this.secrets = secrets;
    }

    @Transactional(readOnly = true)
    public ResolvedCredential resolve(UUID organizationId, UUID connectionId) {
        ProviderConnection connection = connections.findByOrganizationIdAndId(organizationId, connectionId)
                .orElseThrow(() -> new ResourceNotFoundException("provider connection"));
        if (connection.getStatus() != ConnectionStatus.VALID || connection.getCredentialRef() == null) {
            throw new ConflictException("The provider connection is not usable.");
        }
        String secret = secrets.reveal(organizationId, connection.getCredentialRef())
                .orElseThrow(() -> new ConflictException("The provider connection is not usable."));
        return new ResolvedCredential(connection.getId(), connection.getProviderType(), secret);
    }
}
