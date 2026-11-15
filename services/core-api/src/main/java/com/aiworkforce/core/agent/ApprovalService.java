package com.aiworkforce.core.agent;

import com.aiworkforce.core.access.Permission;
import com.aiworkforce.core.access.TenantAccessService;
import com.aiworkforce.core.agent.AgentParts.ToolDefinition;
import com.aiworkforce.core.agent.AgentParts.ToolGrant;
import com.aiworkforce.core.agent.ToolViews.ApprovalView;
import com.aiworkforce.core.audit.AuditLog;
import com.aiworkforce.core.common.exception.ConflictException;
import com.aiworkforce.core.common.exception.ResourceNotFoundException;
import com.aiworkforce.core.identity.AuthenticatedUser;
import com.aiworkforce.core.provider.SensitivePayloadVault;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Human review of tool actions (user-facing). Deciding requires membership AND {@code approval.decide};
 * listing requires {@code approval.read}. A decision authorizes ONE invocation: the caller must present
 * the arguments hash it reviewed, which has to equal the stored one (so a stale screen can never approve
 * different arguments), the approval must still be PENDING and not expired, the execution must still be
 * waiting for it, and the agent's grant and the policy are evaluated again: a human approval can never
 * replace a missing grant. The decision is final and cannot be replayed.
 */
@Service
public class ApprovalService {

    /** Published after commit: the notifier tells the orchestrator that it can continue. */
    record ApprovalDecided(UUID organizationId, UUID executionId) {
    }

    private final ToolApprovalRepository approvals;
    private final ToolInvocationRepository invocations;
    private final ExecutionRepository executions;
    private final ToolDefinitionRepository toolDefinitions;
    private final ToolGrantRepository grants;
    private final AgentRepository agents;
    private final TenantAccessService access;
    private final ApprovalLifecycle lifecycle;
    private final SensitivePayloadVault vault;
    private final AuditLog audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final ApproverNames approverNames;

    public ApprovalService(ToolApprovalRepository approvals, ToolInvocationRepository invocations,
            ExecutionRepository executions, ToolDefinitionRepository toolDefinitions, ToolGrantRepository grants,
            AgentRepository agents, TenantAccessService access, ApprovalLifecycle lifecycle, SensitivePayloadVault vault,
            AuditLog audit,
            ApplicationEventPublisher events, Clock clock, ApproverNames approverNames) {
        this.approverNames = approverNames;
        this.approvals = approvals;
        this.invocations = invocations;
        this.executions = executions;
        this.toolDefinitions = toolDefinitions;
        this.grants = grants;
        this.agents = agents;
        this.access = access;
        this.lifecycle = lifecycle;
        this.vault = vault;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<ApprovalView> list(UUID organizationId, AuthenticatedUser user, ApprovalStatus status, UUID executionId,
            Pageable pageable) {
        access.require(organizationId, user, Permission.APPROVAL_READ);
        Page<ToolApproval> page = approvals.searchByOrganization(organizationId, status, executionId, pageable);
        return toViews(organizationId, page);
    }

    @Transactional(readOnly = true)
    public ApprovalView get(UUID organizationId, UUID approvalId, AuthenticatedUser user) {
        access.require(organizationId, user, Permission.APPROVAL_READ);
        ToolApproval approval = approvals.findByOrganizationIdAndId(organizationId, approvalId)
                .orElseThrow(() -> new ResourceNotFoundException("approval"));
        return toViews(organizationId, List.of(approval)).getFirst();
    }

    private Page<ApprovalView> toViews(UUID organizationId, Page<ToolApproval> page) {
        List<ApprovalView> views = toViews(organizationId, page.getContent());
        return new org.springframework.data.domain.PageImpl<>(views, page.getPageable(), page.getTotalElements());
    }

    private List<ApprovalView> toViews(UUID organizationId, List<ToolApproval> list) {
        Map<UUID, String> agentNames = new HashMap<>();
        agents.findByOrganizationIdAndIdIn(organizationId, list.stream().map(ToolApproval::getAgentId).distinct().toList())
                .forEach(a -> agentNames.put(a.getId(), a.getName()));
        Map<UUID, ExecutionStatus> executionStatus = new HashMap<>();
        Map<UUID, InvocationStatus> invocationStatus = new HashMap<>();
        for (ToolApproval a : list) {
            executions.findByOrganizationIdAndId(organizationId, a.getExecutionId())
                    .ifPresent(e -> executionStatus.put(e.getId(), e.getStatus()));
            invocations.findByOrganizationIdAndId(organizationId, a.getInvocationId())
                    .ifPresent(i -> invocationStatus.put(i.getId(), i.getStatus()));
        }
        Map<String, String> toolNames = toolDefinitions.findAllById(list.stream().map(ToolApproval::getToolCode).distinct().toList())
                .stream().collect(Collectors.toMap(ToolDefinition::getCode, ToolDefinition::getName));
        Map<UUID, String> deciders = approverNames.resolve(organizationId, list.stream().map(ToolApproval::getDecidedBy).toList());
        return list.stream().map(a -> ApprovalView.of(a, agentNames.get(a.getAgentId()), toolNames.get(a.getToolCode()),
                executionStatus.get(a.getExecutionId()), invocationStatus.get(a.getInvocationId()), deciders.get(a.getDecidedBy()))).toList();
    }

    @Transactional
    public ApprovalView approve(UUID organizationId, UUID approvalId, AuthenticatedUser user, String argumentsHash) {
        return decide(organizationId, approvalId, user, argumentsHash, true, null);
    }

    @Transactional
    public ApprovalView reject(UUID organizationId, UUID approvalId, AuthenticatedUser user, String reason) {
        return decide(organizationId, approvalId, user, null, false, reason);
    }

    private ApprovalView decide(UUID organizationId, UUID approvalId, AuthenticatedUser user, String argumentsHash,
            boolean approve, String reason) {
        access.require(organizationId, user, Permission.APPROVAL_DECIDE);
        Instant now = Instant.now(clock);
        ToolApproval peek = approvals.findByOrganizationIdAndId(organizationId, approvalId)
                .orElseThrow(() -> new ResourceNotFoundException("approval"));
        if (peek.getStatus() == ApprovalStatus.PENDING && peek.isExpiredAt(now)) {
            lifecycle.expireIfDue(organizationId, approvalId); // durable even though this request is refused
            throw new ConflictException("This approval request has expired.");
        }
        if (approve && (argumentsHash == null || !argumentsHash.equals(peek.getArgumentsHash()))) {
            throw new ConflictException("The arguments changed or were not the ones reviewed: review the request again.");
        }

        AgentExecution execution = executions.lockByOrganizationAndId(organizationId, peek.getExecutionId())
                .orElseThrow(() -> new ResourceNotFoundException("execution"));
        ToolInvocation invocation = invocations.lockByOrganizationAndId(organizationId, peek.getInvocationId())
                .orElseThrow(() -> new ResourceNotFoundException("tool call"));
        ToolApproval approval = approvals.lockByOrganizationAndId(organizationId, approvalId)
                .orElseThrow(() -> new ResourceNotFoundException("approval"));

        if (approval.getStatus() != ApprovalStatus.PENDING) {
            throw new ConflictException("This approval request is already " + approval.getStatus() + ".");
        }
        if (execution.getStatus() != ExecutionStatus.WAITING_APPROVAL
                || invocation.getStatus() != InvocationStatus.AWAITING_APPROVAL) {
            throw new ConflictException("The execution is no longer waiting for this decision.");
        }
        UUID sealed = null;
        if (approve) {
            // Approval never replaces a grant: the tool, the policy version and the grant are checked now.
            ToolDefinition tool = toolDefinitions.findById(approval.getToolCode()).orElse(null);
            ToolGrant grant = grants.findByOrganizationIdAndIdAgentId(organizationId, approval.getAgentId()).stream()
                    .filter(g -> g.getToolCode().equals(approval.getToolCode())).findFirst().orElse(null);
            if (!ToolPolicyEvaluator.evaluate(tool, grant).allowed()) {
                throw new ConflictException("The agent no longer has permission to use this tool: it cannot be approved.");
            }
            if (tool.getPolicyVersion() != approval.getPolicyVersion()) {
                throw new ConflictException("The tool policy changed since the request: it cannot be approved.");
            }
            approval.approve(user.userId(), now);
            invocation.approve(now);
        } else {
            String why = reason == null || reason.isBlank() ? null
                    : reason.replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
            approval.reject(user.userId(), now, why == null ? null : why.length() > 500 ? why.substring(0, 500) : why);
            invocation.reject(now);
            sealed = invocation.getArgumentsRef();
            invocation.clearArguments(); // the encrypted copy goes with the rejection (discarded last, below)
        }
        execution.touch(now);
        audit.record(organizationId, user.userId(), approve ? "agent.approval.approved" : "agent.approval.rejected", "agent",
                approval.getAgentId(), AgentSupport.details("executionId", approval.getExecutionId(), "approvalId", approvalId,
                        "tool", approval.getToolCode(), "argumentsHash", approval.getArgumentsHash()));
        events.publishEvent(new ApprovalDecided(organizationId, approval.getExecutionId()));
        ApprovalView view = toViews(organizationId, List.of(approval)).getFirst();
        if (sealed != null) {
            vault.discard(organizationId, sealed); // last: SecretStore.delete clears the persistence context
        }
        return view;
    }
}
