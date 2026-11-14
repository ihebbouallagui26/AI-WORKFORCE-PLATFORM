package com.aiworkforce.core.agent;

import com.aiworkforce.core.audit.AuditLog;
import com.aiworkforce.core.common.exception.ConflictException;
import com.aiworkforce.core.common.exception.ResourceNotFoundException;
import com.aiworkforce.core.provider.ProviderCredentialBroker;
import com.aiworkforce.core.provider.ProviderCredentialBroker.ResolvedCredential;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service-side operations on an execution, used only by the orchestrator's service account through
 * {@code InternalExecutionRunController}. Every operation locks the execution row, re-validates the
 * (organization, execution) pair and lets {@link AgentExecution} enforce the state machine, so a
 * duplicate or out-of-order call is a 409 and never corrupts the record.
 */
@Component
class ExecutionCoordinator {

    /** What the orchestrator needs to run: the instruction and the agent it was given for. */
    record StartedExecution(UUID executionId, UUID agentId, String task) {
    }

    /**
     * A credential grant. The secret is excluded from {@code toString}; the response carrying it is
     * served with {@code Cache-Control: no-store} and is never logged or persisted.
     */
    record CredentialGrant(UUID connectionId, String providerType, String modelId, String credential) {

        @Override
        public String toString() {
            return "CredentialGrant[connectionId=" + connectionId + ", providerType=" + providerType
                    + ", modelId=" + modelId + ", credential=<redacted>]";
        }
    }

    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{2,39}$");
    private static final int MAX_MESSAGE = 300;

    private final ExecutionRepository executions;
    private final AgentSupport support;
    private final ProviderBindingRepository bindings;
    private final ProviderCredentialBroker broker;
    private final AuditLog audit;
    private final ExecutionProperties properties;
    private final Clock clock;

    ExecutionCoordinator(ExecutionRepository executions, AgentSupport support, ProviderBindingRepository bindings,
            ProviderCredentialBroker broker, AuditLog audit, ExecutionProperties properties, Clock clock) {
        this.executions = executions;
        this.support = support;
        this.bindings = bindings;
        this.broker = broker;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    private AgentExecution lock(UUID organizationId, UUID executionId) {
        return executions.lockByOrganizationAndId(organizationId, executionId)
                .orElseThrow(() -> new ResourceNotFoundException("execution"));
    }

    @Transactional
    public StartedExecution start(UUID organizationId, UUID executionId) {
        AgentExecution execution = lock(organizationId, executionId);
        execution.start(Instant.now(clock));
        audit.record(organizationId, null, "agent.execution.started", "agent", execution.getAgentId(),
                AgentSupport.details("executionId", executionId));
        return new StartedExecution(execution.getId(), execution.getAgentId(), execution.getTask());
    }

    /**
     * Hands the provider credential to the orchestrator: once per execution, only while RUNNING, only if
     * the agent is still ACTIVE and its PRIMARY binding still targets the connection and model recorded
     * at launch, and only through the broker (which refuses non-VALID connections). Fails closed.
     */
    @Transactional
    public CredentialGrant resolveCredential(UUID organizationId, UUID executionId) {
        AgentExecution execution = lock(organizationId, executionId);
        Agent agent = support.load(organizationId, execution.getAgentId());
        if (agent.getStatus() != AgentStatus.ACTIVE) {
            throw new ConflictException("The agent is no longer active.");
        }
        AgentParts.ProviderBinding primary = bindings
                .findByOrganizationIdAndAgentIdAndRole(organizationId, agent.getId(), BindingRole.PRIMARY)
                .orElseThrow(() -> new ConflictException("The agent has no primary provider."));
        if (!primary.getConnectionId().equals(execution.getConnectionId())
                || !ExecutionService.ENABLED_PROVIDERS.contains(execution.getProviderType())) {
            throw new ConflictException("The provider configuration changed since the execution was requested.");
        }
        execution.markCredentialResolved(Instant.now(clock)); // 409 if repeated or not RUNNING
        ResolvedCredential credential = broker.resolve(organizationId, execution.getConnectionId());
        audit.record(organizationId, null, "agent.execution.credential-issued", "agent", agent.getId(),
                AgentSupport.details("executionId", executionId, "connectionId", execution.getConnectionId()));
        return new CredentialGrant(credential.connectionId(), credential.providerType().name(), primary.getModelId(),
                credential.secret());
    }

    @Transactional
    public ExecutionView complete(UUID organizationId, UUID executionId, String result, Integer inputTokens,
            Integer outputTokens, String model) {
        AgentExecution execution = lock(organizationId, executionId);
        String bounded = result == null ? "" : result;
        if (bounded.length() > properties.maxResultChars()) {
            bounded = bounded.substring(0, properties.maxResultChars());
        }
        execution.succeed(bounded, nonNegative(inputTokens), nonNegative(outputTokens), model, Instant.now(clock));
        audit.record(organizationId, null, "agent.execution.succeeded", "agent", execution.getAgentId(),
                AgentSupport.details("executionId", executionId, "inputTokens", execution.getInputTokens(),
                        "outputTokens", execution.getOutputTokens()));
        return ExecutionView.of(execution);
    }

    @Transactional
    public ExecutionView fail(UUID organizationId, UUID executionId, String code, String message) {
        AgentExecution execution = lock(organizationId, executionId);
        String safeCode = code != null && CODE.matcher(code).matches() ? code : "EXECUTION_FAILED";
        execution.fail(safeCode, safeMessage(message), Instant.now(clock));
        audit.record(organizationId, null, "agent.execution.failed", "agent", execution.getAgentId(),
                AgentSupport.details("executionId", executionId, "errorCode", safeCode));
        return ExecutionView.of(execution);
    }

    /** Failure decided by the Core API itself (dispatch impossible, timeout): same rules, tolerant of races. */
    @Transactional
    public void failIfOpen(UUID organizationId, UUID executionId, String code, String message) {
        AgentExecution execution = lock(organizationId, executionId);
        if (execution.getStatus().isTerminal()) {
            return;
        }
        fail(organizationId, executionId, code, message);
    }

    /**
     * Failure of the HAND-OVER to the orchestrator (dispatch impossible or rejected): fails the execution
     * <em>only if it is still PENDING</em>. A dispatch error can arrive after the orchestrator already
     * accepted the request (for example the 202 was lost to a timeout) and moved the execution to RUNNING:
     * that run is alive, may already be billed by the provider and must be allowed to complete, so it is
     * never overwritten here. RUNNING, SUCCEEDED and FAILED executions are left untouched. Stale executions
     * of any open state are closed by the reaper through {@link #failIfOpen}.
     *
     * <p>Runs in its own transaction: it is called from an after-commit callback, where joining the
     * (already completed) transaction of the launch would silently lose the write.
     *
     * @return true when the execution was failed by this call
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean failIfPending(UUID organizationId, UUID executionId, String code, String message) {
        AgentExecution execution = lock(organizationId, executionId);
        if (execution.getStatus() != ExecutionStatus.PENDING) {
            return false;
        }
        fail(organizationId, executionId, code, message);
        return true;
    }

    private static Integer nonNegative(Integer value) {
        return value == null || value < 0 ? null : value;
    }

    /** Printable text only, bounded: an error message is shown to users and must not carry raw provider output. */
    private static String safeMessage(String message) {
        String text = message == null ? "The execution failed." : message.replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
        if (text.isEmpty()) {
            text = "The execution failed.";
        }
        return text.length() > MAX_MESSAGE ? text.substring(0, MAX_MESSAGE) : text;
    }
}
