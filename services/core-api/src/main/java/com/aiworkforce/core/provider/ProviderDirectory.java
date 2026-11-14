package com.aiworkforce.core.provider;

import com.aiworkforce.core.common.exception.ConflictException;
import com.aiworkforce.core.common.exception.ResourceNotFoundException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Provider connection lookups for the agent module (no permission check; organization-scoped). */
@Service
public class ProviderDirectory {

    /** Short reference to a connection: never any credential material. */
    @Schema(name = "ProviderConnectionReference")
    public record ConnectionRef(UUID id, ProviderType providerType, String displayName, ConnectionStatus status) {

        static ConnectionRef of(ProviderConnection c) {
            return new ConnectionRef(c.getId(), c.getProviderType(), c.getDisplayName(), c.getStatus());
        }
    }

    private final ProviderConnectionRepository connections;

    ProviderDirectory(ProviderConnectionRepository connections) {
        this.connections = connections;
    }

    @Transactional(readOnly = true)
    public Map<UUID, ConnectionRef> findRefs(UUID organizationId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return connections.findByOrganizationIdAndIdIn(organizationId, ids).stream().map(ConnectionRef::of)
                .collect(Collectors.toMap(ConnectionRef::id, Function.identity()));
    }

    /** A connection of this organization that is not revoked: 404 if unknown here, 409 if revoked. */
    @Transactional(readOnly = true)
    public ConnectionRef requireUsable(UUID organizationId, UUID connectionId) {
        ProviderConnection connection = connections.findByOrganizationIdAndId(organizationId, connectionId)
                .orElseThrow(() -> new ResourceNotFoundException("provider connection"));
        if (connection.getStatus() == ConnectionStatus.REVOKED) {
            throw new ConflictException("This provider connection was revoked.");
        }
        return ConnectionRef.of(connection);
    }
}
