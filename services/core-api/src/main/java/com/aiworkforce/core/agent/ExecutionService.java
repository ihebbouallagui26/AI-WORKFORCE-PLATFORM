package com.aiworkforce.core.agent;

import com.aiworkforce.core.access.OrganizationLock;
import com.aiworkforce.core.access.Permission;
import com.aiworkforce.core.access.TenantAccessService;
import com.aiworkforce.core.audit.AuditLog;
import com.aiworkforce.core.common.exception.ConflictException;
import com.aiworkforce.core.common.exception.ResourceNotFoundException;
import com.aiworkforce.core.common.logging.RequestIdFilter;
import com.aiworkforce.core.identity.AuthenticatedUser;
import com.aiworkforce.core.provider.ConnectionStatus;
import com.aiworkforce.core.provider.ProviderDirectory;
import com.aiworkforce.core.provider.ProviderType;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Human-initiated, supervised executions of AI agents (user-facing side).
 *
 * <p>Starting one validates, in this order: membership and {@code agent.execute}; the agent is found
 * through the caller's organization and is ACTIVE; it has a PRIMARY binding whose connection is VALID
 * and whose provider is enabled for execution (Anthropic only in C1); the organization is under its
 * concurrency limit. It then records a PENDING execution and, after the transaction commits, asks the
 * orchestrator to run it. Nothing is ever started without this explicit, authorized call.
 */
@Service
public class ExecutionService {

    /** Providers that may run an execution in C1. OpenAI and Gemini adapters exist but are not enabled. */
    static final List<ProviderType> ENABLED_PROVIDERS = List.of(ProviderType.ANTHROPIC);

    /** Published after commit: the dispatcher reacts to it. */
    record ExecutionRequested(UUID organizationId, UUID executionId) {
    }

    private final ExecutionRepository executions;
    private final ProviderBindingRepository bindings;
    private final AgentSupport support;
    private final ProviderDirectory providers;
    private final TenantAccessService access;
    private final OrganizationLock organizationLock;
    private final AuditLog audit;
    private final ExecutionProperties properties;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ExecutionService(ExecutionRepository executions, ProviderBindingRepository bindings, AgentSupport support,
            ProviderDirectory providers, TenantAccessService access, OrganizationLock organizationLock, AuditLog audit,
            ExecutionProperties properties, ApplicationEventPublisher events, Clock clock) {
        this.executions = executions;
        this.bindings = bindings;
        this.support = support;
        this.providers = providers;
        this.access = access;
        this.organizationLock = organizationLock;
        this.audit = audit;
        this.properties = properties;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public ExecutionView start(UUID organizationId, UUID agentId, AuthenticatedUser user, String task) {
        access.require(organizationId, user, Permission.AGENT_EXECUTE);
        String cleaned = task == null ? "" : task.strip();
        if (cleaned.isEmpty()) {
            throw new ConflictException("An instruction is required.");
        }
        Agent agent = support.load(organizationId, agentId);
        if (agent.getStatus() != AgentStatus.ACTIVE) {
            throw new ConflictException("Only an active agent can be executed.");
        }
        AgentParts.ProviderBinding primary = bindings
                .findByOrganizationIdAndAgentIdAndRole(organizationId, agentId, BindingRole.PRIMARY)
                .orElseThrow(() -> new ConflictException("The agent has no primary provider."));
        ProviderDirectory.ConnectionRef connection = providers
                .findRefs(organizationId, List.of(primary.getConnectionId())).get(primary.getConnectionId());
        if (connection == null || connection.status() != ConnectionStatus.VALID) {
            throw new ConflictException("The agent's primary provider connection is not valid.");
        }
        if (!ENABLED_PROVIDERS.contains(connection.providerType())) {
            throw new ConflictException("This provider is not enabled for agent execution yet.");
        }

        // Serialize the limit check per organization so concurrent launches cannot exceed it.
        organizationLock.acquire(organizationId);
        if (executions.countOpenByOrganization(organizationId) >= properties.maxConcurrentPerOrganization()) {
            throw new ConflictException("Too many executions are in progress. Try again when one has finished.");
        }

        Instant now = Instant.now(clock);
        AgentExecution execution = new AgentExecution(UUID.randomUUID(), organizationId, agentId, user.userId(), cleaned,
                connection.providerType(), connection.id(), primary.getModelId(), MDC.get(RequestIdFilter.MDC_KEY), now);
        executions.save(execution);
        // The audit record never contains the instruction or any result.
        audit.record(organizationId, user.userId(), "agent.execution.requested", "agent", agentId,
                AgentSupport.details("executionId", execution.getId(), "provider", connection.providerType().name(),
                        "model", primary.getModelId()));
        events.publishEvent(new ExecutionRequested(organizationId, execution.getId()));
        return ExecutionView.of(execution);
    }

    /**
     * The execution as it is now. Used right after {@link #start}: the after-commit hand-over may have
     * already failed it (e.g. {@code DISPATCH_CAPACITY_EXCEEDED}), and the launch response must say so
     * instead of reporting a stale PENDING. The caller was authorized by {@link #start}.
     */
    @Transactional(readOnly = true)
    ExecutionView current(UUID organizationId, ExecutionView launched) {
        return executions.findByOrganizationIdAndId(organizationId, launched.id()).map(ExecutionView::of)
                .orElse(launched);
    }

    @Transactional(readOnly = true)
    public ExecutionView get(UUID organizationId, UUID executionId, AuthenticatedUser user) {
        access.require(organizationId, user, Permission.AGENT_EXECUTION_READ);
        return executions.findByOrganizationIdAndId(organizationId, executionId).map(ExecutionView::of)
                .orElseThrow(() -> new ResourceNotFoundException("execution"));
    }

    /** History of the organization, optionally narrowed to one agent (which must belong to the organization). */
    @Transactional(readOnly = true)
    public Page<ExecutionView> list(UUID organizationId, UUID agentId, ExecutionStatus status, AuthenticatedUser user,
            Pageable pageable) {
        access.require(organizationId, user, Permission.AGENT_EXECUTION_READ);
        if (agentId != null) {
            support.load(organizationId, agentId);
        }
        return executions.searchByOrganization(organizationId, agentId, status, pageable).map(ExecutionView::of);
    }
}
