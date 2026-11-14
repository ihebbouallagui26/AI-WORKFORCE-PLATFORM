package com.aiworkforce.core.provider;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model of a provider connection: safe metadata only. There is deliberately no field for the
 * credential, its reference, a hash, a fragment or a mask.
 */
public record ConnectionView(
        UUID id,
        UUID organizationId,
        ProviderType providerType,
        String displayName,
        String accountIdentifier,
        ConnectionStatus status,
        String statusDetail,
        Instant lastTestedAt,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    static ConnectionView of(ProviderConnection c) {
        return new ConnectionView(c.getId(), c.getOrganizationId(), c.getProviderType(), c.getDisplayName(),
                c.getAccountIdentifier(), c.getStatus(), c.getStatusDetail(), c.getLastTestedAt(), c.getCreatedAt(),
                c.getUpdatedAt(), c.getVersion());
    }
}
