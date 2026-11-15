package com.aiworkforce.core.agent;

import com.aiworkforce.core.agent.AgentParts.ToolDefinition;
import com.aiworkforce.core.agent.AgentParts.ToolGrant;
import com.aiworkforce.core.agent.ToolPolicyEvaluator.Decision;
import com.aiworkforce.core.agent.ToolPolicyEvaluator.Evaluation;
import com.aiworkforce.core.audit.AuditLog;
import com.aiworkforce.core.common.exception.ConflictException;
import com.aiworkforce.core.common.exception.ResourceNotFoundException;
import com.aiworkforce.core.provider.SensitivePayloadVault;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Service-side tool-call operations, used only by the orchestrator's service account through
 * {@code InternalToolCallController}. The Core API is the authority:
 * <ol>
 *   <li>{@link #request}: records every tool call an agent asks for and DECIDES it (denied, automatic or
 *       human approval) from the registry and the agent's explicit grant, never from anything the model said;</li>
 *   <li>{@link #begin}: the single moment a call may start. It re-validates the agent, the grant, the
 *       policy version and, for approved calls, the approval bound to this exact call, then moves the
 *       invocation APPROVED to RUNNING under a row lock, so a call runs at most once;</li>
 *   <li>{@link #complete}: stores the bounded result;</li>
 *   <li>{@link #resume}: continues an execution after the human decision, exactly once per pause.</li>
 * </ol>
 * Locks are always taken execution first, then invocation, then approval.
 */
@Component
class ToolCallCoordinator {

    /** The decision returned to the orchestrator for a requested call. */
    /** {@code invocationId} is null only for an unknown tool (there is nothing to attach a row to). */
    record ToolCallDecision(UUID invocationId, String decision, String code, String message, UUID approvalId,
            Instant expiresAt) {
    }

    /** Everything needed to run an authorized call. The arguments are decrypted here and only here. */
    record BeginResult(UUID invocationId, String toolCode, String mcpServer, String mcpTool, String arguments,
            int timeoutSeconds, int maxOutputBytes) {
    }

    /** What the orchestrator needs to continue a paused execution. */
    record ResumeResult(UUID invocationId, UUID approvalId, String decision, String toolCode, String rejectionReason) {
    }

    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{2,39}$");
    private static final Pattern CALL_ID = Pattern.compile("^[A-Za-z0-9_.:-]{1,100}$");

    private final ExecutionRepository executions;
    private final ToolInvocationRepository invocations;
    private final ToolApprovalRepository approvals;
    private final ToolDefinitionRepository toolDefinitions;
    private final ToolGrantRepository grants;
    private final AgentSupport support;
    private final SensitivePayloadVault vault;
    private final AuditLog audit;
    private final ExecutionProperties properties;
    private final Clock clock;
    private final WorkflowStepRepository workflowSteps;
    private final ToolResultProtector resultProtector;

    ToolCallCoordinator(ExecutionRepository executions, ToolInvocationRepository invocations,
            ToolApprovalRepository approvals, ToolDefinitionRepository toolDefinitions, ToolGrantRepository grants,
            AgentSupport support, SensitivePayloadVault vault, AuditLog audit, ExecutionProperties properties,
            Clock clock, WorkflowStepRepository workflowSteps, ToolResultProtector resultProtector) {
        this.workflowSteps = workflowSteps;
        this.resultProtector = resultProtector;
        this.executions = executions;
        this.invocations = invocations;
        this.approvals = approvals;
        this.toolDefinitions = toolDefinitions;
        this.grants = grants;
        this.support = support;
        this.vault = vault;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    private AgentExecution lockExecution(UUID organizationId, UUID executionId) {
        return executions.lockByOrganizationAndId(organizationId, executionId)
                .orElseThrow(() -> new ResourceNotFoundException("execution"));
    }

    private ToolInvocation lockInvocation(UUID organizationId, UUID executionId, UUID invocationId) {
        ToolInvocation invocation = invocations.lockByOrganizationAndId(organizationId, invocationId)
                .orElseThrow(() -> new ResourceNotFoundException("tool call"));
        if (!invocation.getExecutionId().equals(executionId)) {
            throw new ResourceNotFoundException("tool call"); // an invocation is only reachable through its own execution
        }
        return invocation;
    }

    /** Evaluates the CURRENT registry entry and the agent's explicit grant (read fresh every time, never cached). */
    private Evaluation evaluate(UUID organizationId, UUID agentId, ToolDefinition tool) {
        ToolGrant grant = tool == null ? null : grants.findByOrganizationIdAndIdAgentId(organizationId, agentId).stream()
                .filter(g -> g.getToolCode().equals(tool.getCode())).findFirst().orElse(null);
        return ToolPolicyEvaluator.evaluate(tool, grant);
    }

    @Transactional
    public ToolCallDecision request(UUID organizationId, UUID executionId, String toolCallId, String toolCode,
            JsonNode arguments, String reason) {
        if (toolCallId == null || !CALL_ID.matcher(toolCallId).matches()) {
            throw new ConflictException("The tool call identifier is invalid.");
        }
        AgentExecution execution = lockExecution(organizationId, executionId);
        if (execution.getStatus() != ExecutionStatus.RUNNING) {
            throw new ConflictException("The execution is not running.");
        }
        Agent agent = support.load(organizationId, execution.getAgentId());
        if (agent.getStatus() != AgentStatus.ACTIVE) {
            throw new ConflictException("The agent is no longer active.");
        }
        // A workflow coordinator turn has ONLY the two platform actions: it never reaches an MCP tool.
        if (workflowSteps.findByOrganizationIdAndExecutionId(organizationId, executionId)
                .filter(step -> WorkflowScheduler.COORDINATOR.equals(step.getRole())).isPresent()) {
            audit.record(organizationId, null, "agent.tool.denied", "agent", agent.getId(), AgentSupport.details(
                    "executionId", executionId, "code", "COORDINATOR_TOOLS_NOT_ALLOWED"));
            return new ToolCallDecision(null, "DENIED", "COORDINATOR_TOOLS_NOT_ALLOWED",
                    "A workflow coordinator can only delegate or finish.", null, null);
        }
        JsonNode args = arguments == null || arguments.isNull() ? JsonNodeFactory.instance.objectNode()
                : arguments;
        String canonical = ToolArguments.canonical(args);
        String hash = ToolArguments.sha256(canonical);

        // A replayed request (same provider call id) never creates a second invocation or approval.
        var existing = invocations.findByOrganizationIdAndExecutionIdAndToolCallId(organizationId, executionId, toolCallId);
        if (existing.isPresent()) {
            ToolInvocation previous = existing.get();
            if (!previous.getArgumentsHash().equals(hash) || !previous.getToolCode().equals(toolCode)) {
                throw new ConflictException("This tool call identifier was already used for a different call.");
            }
            return decisionOf(previous);
        }

        Instant now = Instant.now(clock);
        ToolDefinition tool = toolDefinitions.findById(toolCode == null ? "" : toolCode).orElse(null);
        if (tool == null) {
            // Unknown tools are always denied. There is no registry row to attach an invocation to, so the
            // attempt is audited (name cut and cleaned) and answered without creating one.
            String shown = cleanReason(toolCode);
            audit.record(organizationId, null, "agent.tool.denied", "agent", agent.getId(), AgentSupport.details(
                    "executionId", executionId, "tool", shown == null ? "" : shown.substring(0, Math.min(60, shown.length())),
                    "code", "UNKNOWN_TOOL", "argumentsHash", hash));
            execution.touch(now);
            return new ToolCallDecision(null, "DENIED", "UNKNOWN_TOOL", "This tool does not exist.", null, null);
        }
        int sequence = (int) invocations.countByOrganizationIdAndExecutionId(organizationId, executionId) + 1;
        String redacted = ToolArguments.redacted(args);
        String safeReason = cleanReason(reason);
        Evaluation evaluation;
        if (!args.isObject()) {
            evaluation = Evaluation.denied("INVALID_ARGUMENTS", "The arguments must be a JSON object.");
        } else if (canonical.length() > properties.maxToolArgumentBytes()) {
            evaluation = Evaluation.denied("ARGUMENTS_TOO_LARGE", "The arguments are too large.");
        } else if (sequence > properties.maxToolCallsPerExecution()) {
            evaluation = Evaluation.denied("TOOL_CALL_LIMIT", "This execution reached its tool call limit.");
        } else {
            evaluation = evaluate(organizationId, agent.getId(), tool);
        }
        ToolPolicy policy = tool.getExecutionPolicy();
        int policyVersion = tool.getPolicyVersion();

        UUID invocationId = UUID.randomUUID();
        if (!evaluation.allowed()) {
            ToolInvocation denied = new ToolInvocation(invocationId, organizationId, executionId, agent.getId(), toolCode,
                    toolCallId, sequence, InvocationStatus.DENIED, policy, policyVersion, null, redacted, hash, safeReason,
                    evaluation.code(), evaluation.message(), now);
            invocations.save(denied);
            execution.touch(now);
            audit.record(organizationId, null, "agent.tool.denied", "agent", agent.getId(), AgentSupport.details(
                    "executionId", executionId, "tool", toolCode, "code", evaluation.code(), "argumentsHash", hash));
            return decisionOf(denied);
        }

        UUID argumentsRef = vault.seal(organizationId, canonical);
        boolean automatic = evaluation.decision() == Decision.AUTO;
        ToolInvocation invocation = new ToolInvocation(invocationId, organizationId, executionId, agent.getId(), toolCode,
                toolCallId, sequence, automatic ? InvocationStatus.APPROVED : InvocationStatus.AWAITING_APPROVAL, policy,
                policyVersion, argumentsRef, redacted, hash, safeReason, null, null, now);
        invocations.save(invocation);
        if (automatic) {
            execution.touch(now);
            audit.record(organizationId, null, "agent.tool.requested", "agent", agent.getId(), AgentSupport.details(
                    "executionId", executionId, "tool", toolCode, "decision", "AUTO", "argumentsHash", hash));
            return decisionOf(invocation);
        }

        ToolApproval approval = new ToolApproval(UUID.randomUUID(), invocation, tool.getMcpServer(), tool.getRisk().name(),
                describe(tool), now, now.plus(properties.approvalTimeout()));
        approvals.save(approval);
        execution.waitForApproval(now);
        audit.record(organizationId, null, "agent.approval.requested", "agent", agent.getId(), AgentSupport.details(
                "executionId", executionId, "approvalId", approval.getId(), "tool", toolCode, "risk", tool.getRisk().name(),
                "argumentsHash", hash, "expiresAt", approval.getExpiresAt().toString()));
        return decisionOf(invocation, approval);
    }

    private ToolCallDecision decisionOf(ToolInvocation invocation) {
        ToolApproval approval = invocation.getStatus() == InvocationStatus.AWAITING_APPROVAL
                || invocation.getPolicy() == ToolPolicy.APPROVAL_REQUIRED
                ? approvals.findByOrganizationIdAndInvocationId(invocation.getOrganizationId(), invocation.getId()).orElse(null)
                : null;
        return decisionOf(invocation, approval);
    }

    private ToolCallDecision decisionOf(ToolInvocation invocation, ToolApproval approval) {
        return switch (invocation.getStatus()) {
            case DENIED -> new ToolCallDecision(invocation.getId(), "DENIED", invocation.getErrorCode(),
                    invocation.getErrorMessage(), null, null);
            case AWAITING_APPROVAL -> new ToolCallDecision(invocation.getId(), "APPROVAL_REQUIRED", "APPROVAL_REQUIRED",
                    "A human must approve this action.", approval == null ? null : approval.getId(),
                    approval == null ? null : approval.getExpiresAt());
            case APPROVED -> new ToolCallDecision(invocation.getId(), approval == null ? "AUTO" : "APPROVED", "OK",
                    "Allowed.", approval == null ? null : approval.getId(), null);
            default -> new ToolCallDecision(invocation.getId(), invocation.getStatus().name(), "ALREADY_PROCESSED",
                    "This tool call was already processed.", approval == null ? null : approval.getId(), null);
        };
    }

    private static String describe(ToolDefinition tool) {
        String text = tool.getName() + ": " + tool.getDescription();
        return text.length() > 600 ? text.substring(0, 600) : text;
    }

    private static String cleanReason(String reason) {
        if (reason == null) {
            return null;
        }
        String text = reason.replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
        return text.isEmpty() ? null : text.length() > 500 ? text.substring(0, 500) : text;
    }

    /**
     * The only way a call starts. Everything is re-validated NOW: the agent is still active, the grant and
     * the policy still allow the call, the policy version is the one the human saw, and an approved call
     * has an APPROVED approval bound to this very invocation and arguments hash. Revoked permissions beat
     * an older approval. The transition APPROVED to RUNNING happens once.
     */
    @Transactional
    public BeginResult begin(UUID organizationId, UUID executionId, UUID invocationId) {
        AgentExecution execution = lockExecution(organizationId, executionId);
        ToolInvocation invocation = lockInvocation(organizationId, executionId, invocationId);
        Instant now = Instant.now(clock);
        if (execution.getStatus() != ExecutionStatus.RUNNING) {
            throw new ConflictException("The execution is not running.");
        }
        if (invocation.getStatus() != InvocationStatus.APPROVED) {
            throw new ConflictException("The tool call is " + invocation.getStatus() + " and cannot start.");
        }
        Agent agent = support.load(organizationId, execution.getAgentId());
        ToolDefinition tool = toolDefinitions.findById(invocation.getToolCode()).orElse(null);
        Evaluation current = agent.getStatus() == AgentStatus.ACTIVE
                ? evaluate(organizationId, agent.getId(), tool)
                : Evaluation.denied("AGENT_NOT_ACTIVE", "The agent is no longer active.");
        String refusal = null;
        if (!current.allowed()) {
            refusal = "PERMISSION_REVOKED";
        } else if (tool.getPolicyVersion() != invocation.getPolicyVersion()) {
            refusal = "POLICY_CHANGED";
        } else if (invocation.getPolicy() == ToolPolicy.APPROVAL_REQUIRED || current.decision() == Decision.APPROVAL_REQUIRED) {
            ToolApproval approval = approvals.findByOrganizationIdAndInvocationId(organizationId, invocationId).orElse(null);
            if (approval == null || approval.getStatus() != ApprovalStatus.APPROVED
                    || !approval.getArgumentsHash().equals(invocation.getArgumentsHash())
                    || !approval.getToolCallId().equals(invocation.getToolCallId())
                    || !approval.getToolCode().equals(invocation.getToolCode())
                    || approval.getPolicyVersion() != invocation.getPolicyVersion()) {
                refusal = "APPROVAL_INVALID";
            }
        }
        if (refusal != null) {
            UUID sealed = invocation.getArgumentsRef();
            invocation.fail(refusal, "The tool call is no longer authorized.", now);
            invocation.clearArguments();
            execution.touch(now);
            audit.record(organizationId, null, "agent.tool.refused", "agent", agent.getId(), AgentSupport.details(
                    "executionId", executionId, "tool", invocation.getToolCode(), "code", refusal));
            discard(organizationId, sealed); // last: it clears the persistence context
            return null; // committed refusal: the caller turns it into a 409 after the transaction
        }
        String arguments = vault.open(organizationId, invocation.getArgumentsRef());
        invocation.begin(now);
        execution.touch(now);
        audit.record(organizationId, null, "agent.tool.started", "agent", agent.getId(), AgentSupport.details(
                "executionId", executionId, "tool", invocation.getToolCode(), "argumentsHash", invocation.getArgumentsHash()));
        return new BeginResult(invocation.getId(), invocation.getToolCode(), tool.getMcpServer(), tool.getMcpTool(),
                arguments, tool.getTimeoutSeconds(), tool.getMaxOutputBytes());
    }

    @Transactional
    public InvocationStatus complete(UUID organizationId, UUID executionId, UUID invocationId, boolean success,
            String output, boolean truncated, String errorCode, String message) {
        AgentExecution execution = lockExecution(organizationId, executionId);
        ToolInvocation invocation = lockInvocation(organizationId, executionId, invocationId);
        Instant now = Instant.now(clock);
        if (success) {
            String bounded = output == null ? "" : output;
            if (bounded.length() > properties.maxToolOutputChars()) {
                bounded = bounded.substring(0, properties.maxToolOutputChars());
                truncated = true;
            }
            // The result is encrypted BEFORE it reaches the entity: the plaintext is never persisted or held by the row.
            invocation.succeed(resultProtector.seal(organizationId, executionId, invocationId, bounded), truncated, now);
        } else {
            String safeCode = errorCode != null && CODE.matcher(errorCode).matches() ? errorCode : "TOOL_FAILED";
            invocation.fail(safeCode, safeMessage(message), now);
        }
        UUID sealed = invocation.getArgumentsRef();
        invocation.clearArguments();
        execution.touch(now);
        audit.record(organizationId, null, success ? "agent.tool.succeeded" : "agent.tool.failed", "agent",
                invocation.getAgentId(), AgentSupport.details("executionId", executionId, "tool", invocation.getToolCode(),
                        "code", invocation.getErrorCode()));
        InvocationStatus finalStatus = invocation.getStatus();
        discard(organizationId, sealed); // last: it clears the persistence context
        return finalStatus;
    }

    /**
     * The encrypted arguments are removed as soon as a call is final (data minimization).
     * SecretStore.delete is a bulk statement that flushes and then CLEARS the persistence context, so
     * every entity change must be made (and the audit record added) BEFORE the encrypted payload is discarded.
     */
    private void discard(UUID organizationId, UUID sealed) {
        if (sealed != null) {
            vault.discard(organizationId, sealed);
        }
    }

    private static String safeMessage(String message) {
        String text = message == null ? "The tool failed." : message.replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
        if (text.isEmpty()) {
            text = "The tool failed.";
        }
        return text.length() > 300 ? text.substring(0, 300) : text;
    }

    /**
     * A decision exists: continue the paused execution. WAITING_APPROVAL to RUNNING happens once (a duplicate
     * notification gets a 409 and does nothing), and it acknowledges the notification outbox.
     */
    @Transactional
    public ResumeResult resume(UUID organizationId, UUID executionId) {
        AgentExecution execution = lockExecution(organizationId, executionId);
        if (execution.getStatus() != ExecutionStatus.WAITING_APPROVAL) {
            throw new ConflictException("The execution is not waiting for an approval.");
        }
        ToolApproval approval = approvals.searchByOrganization(organizationId, null, executionId,
                        PageRequest.of(0, 1, Sort.by(Sort.Order.desc("requestedAt"))))
                .stream().findFirst().orElseThrow(() -> new ConflictException("There is no approval to resume from."));
        if (approval.getStatus() != ApprovalStatus.APPROVED && approval.getStatus() != ApprovalStatus.REJECTED) {
            throw new ConflictException("The approval has no decision yet.");
        }
        ToolApproval locked = approvals.lockByOrganizationAndId(organizationId, approval.getId()).orElseThrow();
        Instant now = Instant.now(clock);
        execution.resumeAfterDecision(now);
        locked.acknowledgeResume(now);
        audit.record(organizationId, null, "agent.execution.resumed", "agent", execution.getAgentId(),
                AgentSupport.details("executionId", executionId, "approvalId", locked.getId(), "decision", locked.getStatus().name()));
        return new ResumeResult(locked.getInvocationId(), locked.getId(), locked.getStatus().name(), locked.getToolCode(),
                locked.getRejectionReason());
    }
}
