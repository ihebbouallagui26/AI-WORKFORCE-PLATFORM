package com.aiworkforce.core.provider;

import com.aiworkforce.core.access.Permission;
import com.aiworkforce.core.access.TenantAccessService;
import com.aiworkforce.core.audit.AuditLog;
import com.aiworkforce.core.common.exception.ConflictException;
import com.aiworkforce.core.common.exception.ResourceNotFoundException;
import com.aiworkforce.core.common.exception.ServiceUnavailableException;
import com.aiworkforce.core.identity.AuthenticatedUser;
import com.aiworkforce.core.provider.ProviderAdapter.Outcome;
import com.aiworkforce.core.provider.ProviderAdapter.ProviderException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Provider connections of an organization. Credentials go straight into the {@link SecretStore}
 * and never come back: no method returns, logs or audits a credential, a fragment or a mask.
 * Saving a credential only makes the connection PENDING; {@link #test} is what makes it VALID.
 * Calls to the provider happen outside database transactions.
 */
@Service
public class ProviderConnectionService {

    static final int MAX_CONNECTIONS = 20;
    private static final String UNREACHABLE = "The last test could not reach the provider.";
    private static final String REJECTED = "The provider rejected the credential.";

    /** Outcome of a test, for the API. */
    public record TestResult(ConnectionView connection, Outcome outcome) {
    }

    private record Snapshot(ProviderType type, String credential, UUID reference) {
    }

    private final ProviderConnectionRepository connections;
    private final SecretStore secrets;
    private final ProviderAdapters adapters;
    private final TenantAccessService access;
    private final List<ConnectionUsage> usages;
    private final AuditLog audit;
    private final Clock clock;
    private final TransactionTemplate tx;

    public ProviderConnectionService(ProviderConnectionRepository connections, SecretStore secrets,
            ProviderAdapters adapters, TenantAccessService access, List<ConnectionUsage> usages, AuditLog audit,
            Clock clock, PlatformTransactionManager transactionManager) {
        this.connections = connections;
        this.secrets = secrets;
        this.adapters = adapters;
        this.access = access;
        this.usages = usages;
        this.audit = audit;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public Page<ConnectionView> list(UUID organizationId, AuthenticatedUser user, ProviderType type,
            ConnectionStatus status, Pageable pageable) {
        access.require(organizationId, user, Permission.PROVIDER_READ);
        return connections.searchByOrganization(organizationId, type, status, pageable).map(ConnectionView::of);
    }

    @Transactional(readOnly = true)
    public ConnectionView get(UUID organizationId, UUID connectionId, AuthenticatedUser user) {
        access.require(organizationId, user, Permission.PROVIDER_READ);
        return ConnectionView.of(load(organizationId, connectionId));
    }

    @Transactional
    public ConnectionView create(UUID organizationId, AuthenticatedUser user, ProviderType type, String displayName,
            String accountIdentifier, String credential) {
        access.require(organizationId, user, Permission.PROVIDER_CONNECT);
        adapters.forType(type);
        String name = displayName.trim();
        if (connections.existsByOrganizationIdAndDisplayNameIgnoreCase(organizationId, name)) {
            throw new ConflictException("A provider connection with this name already exists.");
        }
        if (connections.countByOrganizationId(organizationId) >= MAX_CONNECTIONS) {
            throw new ConflictException("An organization can have at most " + MAX_CONNECTIONS + " provider connections.");
        }
        UUID reference = secrets.store(organizationId, credential.trim());
        ProviderConnection connection = connections.save(new ProviderConnection(organizationId, type, name,
                blankToNull(accountIdentifier), reference, user.userId(), Instant.now(clock)));
        connections.flush();
        audit.record(organizationId, user.userId(), "provider.connection.created", "provider_connection",
                connection.getId(), Map.of("providerType", type.name(), "name", name));
        return ConnectionView.of(connection);
    }

    @Transactional
    public ConnectionView update(UUID organizationId, UUID connectionId, AuthenticatedUser user, String displayName,
            String accountIdentifier, Long expectedVersion) {
        access.require(organizationId, user, Permission.PROVIDER_MANAGE);
        ProviderConnection connection = loadMutable(organizationId, connectionId);
        if (expectedVersion != null && expectedVersion != connection.getVersion()) {
            throw new ConflictException("This connection was modified by someone else. Reload and retry.");
        }
        String name = displayName == null ? connection.getDisplayName() : displayName.trim();
        if (!name.equalsIgnoreCase(connection.getDisplayName())
                && connections.existsByOrganizationIdAndDisplayNameIgnoreCaseAndIdNot(organizationId, name, connectionId)) {
            throw new ConflictException("A provider connection with this name already exists.");
        }
        connection.update(name, accountIdentifier == null ? connection.getAccountIdentifier()
                : blankToNull(accountIdentifier), Instant.now(clock));
        connections.flush();
        audit.record(organizationId, user.userId(), "provider.connection.updated", "provider_connection", connectionId,
                Map.of("name", name));
        return ConnectionView.of(connection);
    }

    /** Replaces the credential. The connection becomes PENDING until it is tested again. */
    @Transactional
    public ConnectionView replaceCredential(UUID organizationId, UUID connectionId, AuthenticatedUser user,
            String credential) {
        access.require(organizationId, user, Permission.PROVIDER_MANAGE);
        ProviderConnection connection = loadMutable(organizationId, connectionId);
        UUID previous = connection.getCredentialRef();
        UUID reference = secrets.store(organizationId, credential.trim());
        connection.replaceCredential(reference, Instant.now(clock));
        connections.flush();
        if (previous != null) {
            secrets.delete(organizationId, previous);
        }
        audit.record(organizationId, user.userId(), "provider.credential.replaced", "provider_connection", connectionId,
                Map.of());
        return ConnectionView.of(connection);
    }

    /** Validates the credential with a read-only provider call (no completion, no cost). */
    public TestResult test(UUID organizationId, UUID connectionId, AuthenticatedUser user) {
        Snapshot snapshot = tx.execute(status -> {
            access.require(organizationId, user, Permission.PROVIDER_TEST);
            return snapshotOf(organizationId, loadMutable(organizationId, connectionId));
        });
        Outcome outcome = adapters.forType(snapshot.type()).validate(snapshot.credential());
        return tx.execute(status -> {
            ProviderConnection connection = loadMutable(organizationId, connectionId);
            Instant now = Instant.now(clock);
            if (!snapshot.reference().equals(connection.getCredentialRef())) {
                // The credential was replaced while the provider was being called: ignore this result.
                return new TestResult(ConnectionView.of(connection), Outcome.UNAVAILABLE);
            }
            switch (outcome) {
                case VALID -> connection.recordTest(ConnectionStatus.VALID, null, now);
                case INVALID -> connection.recordTest(ConnectionStatus.INVALID, REJECTED, now);
                case UNAVAILABLE -> connection.noteUnavailable(UNREACHABLE, now);
            }
            connections.flush();
            audit.record(organizationId, user.userId(), "provider.connection.tested", "provider_connection", connectionId,
                    Map.of("outcome", outcome.name()));
            return new TestResult(ConnectionView.of(connection), outcome);
        });
    }

    /** Models the provider reports for this credential (discovered, never hardcoded). */
    public List<ProviderAdapter.ModelInfo> models(UUID organizationId, UUID connectionId, AuthenticatedUser user) {
        Snapshot snapshot = tx.execute(status -> {
            access.require(organizationId, user, Permission.PROVIDER_TEST);
            return snapshotOf(organizationId, loadMutable(organizationId, connectionId));
        });
        try {
            return adapters.forType(snapshot.type()).listModels(snapshot.credential());
        } catch (ProviderException e) {
            if (e.isRejectedCredential()) {
                throw new ConflictException(REJECTED);
            }
            throw new ServiceUnavailableException(e.getMessage(), null);
        }
    }

    /** Revokes the connection: the credential is deleted for good. Refused while an active agent depends on it. */
    @Transactional
    public void revoke(UUID organizationId, UUID connectionId, AuthenticatedUser user) {
        access.require(organizationId, user, Permission.PROVIDER_MANAGE);
        ProviderConnection connection = loadMutable(organizationId, connectionId);
        long dependants = usages.stream().mapToLong(u -> u.activeAgentsUsingAsPrimary(organizationId, connectionId)).sum();
        if (dependants > 0) {
            throw new ConflictException("Active AI agents use this connection as their primary provider. "
                    + "Pause them or change their provider first.");
        }
        UUID reference = connection.getCredentialRef();
        connection.revoke(Instant.now(clock));
        connections.flush();
        if (reference != null) {
            secrets.delete(organizationId, reference);
        }
        audit.record(organizationId, user.userId(), "provider.connection.revoked", "provider_connection", connectionId,
                Map.of("name", connection.getDisplayName()));
    }

    // ------------------------------------------------------------------ helpers

    private Snapshot snapshotOf(UUID organizationId, ProviderConnection connection) {
        UUID reference = connection.getCredentialRef();
        String credential = reference == null ? null : secrets.reveal(organizationId, reference).orElse(null);
        if (credential == null) {
            throw new ConflictException("This connection has no credential.");
        }
        return new Snapshot(connection.getProviderType(), credential, reference);
    }

    private ProviderConnection load(UUID organizationId, UUID connectionId) {
        return connections.findByOrganizationIdAndId(organizationId, connectionId)
                .orElseThrow(() -> new ResourceNotFoundException("provider connection"));
    }

    private ProviderConnection loadMutable(UUID organizationId, UUID connectionId) {
        ProviderConnection connection = load(organizationId, connectionId);
        if (connection.getStatus() == ConnectionStatus.REVOKED) {
            throw new ConflictException("This provider connection was revoked.");
        }
        return connection;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
