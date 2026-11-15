package com.aiworkforce.core.agent;

import com.aiworkforce.core.access.Permission;
import com.aiworkforce.core.access.TenantAccessService;
import com.aiworkforce.core.agent.AgentParts.ToolDefinition;
import com.aiworkforce.core.agent.AgentParts.ToolGrant;
import com.aiworkforce.core.agent.ToolPolicyEvaluator.Evaluation;
import com.aiworkforce.core.agent.ToolViews.AvailableTool;
import com.aiworkforce.core.agent.ToolViews.InvocationView;
import com.aiworkforce.core.common.exception.ResourceNotFoundException;
import com.aiworkforce.core.agent.redaction.Redactor;
import com.aiworkforce.core.identity.AuthenticatedUser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of tool activity: the timeline of an execution, the organization history and the tools of an agent. */
@Service
public class ToolActivityService {

    private final ToolInvocationRepository invocations;
    private final ToolApprovalRepository approvals;
    private final ToolDefinitionRepository toolDefinitions;
    private final ToolGrantRepository grants;
    private final ExecutionRepository executions;
    private final AgentSupport support;
    private final TenantAccessService access;
    private final ToolResultProtector resultProtector;
    private final ApproverNames approverNames;

    public ToolActivityService(ToolInvocationRepository invocations, ToolApprovalRepository approvals,
            ToolDefinitionRepository toolDefinitions, ToolGrantRepository grants, ExecutionRepository executions,
            AgentSupport support, TenantAccessService access, ToolResultProtector resultProtector, ApproverNames approverNames) {
        this.resultProtector = resultProtector;
        this.approverNames = approverNames;
        this.invocations = invocations;
        this.approvals = approvals;
        this.toolDefinitions = toolDefinitions;
        this.grants = grants;
        this.executions = executions;
        this.support = support;
        this.access = access;
    }

    /** Tool calls of one execution, in order. The execution must belong to the caller's organization. */
    @Transactional(readOnly = true)
    public Page<InvocationView> timeline(UUID organizationId, UUID executionId, AuthenticatedUser user, Pageable pageable) {
        access.require(organizationId, user, Permission.AGENT_EXECUTION_READ);
        executions.findByOrganizationIdAndId(organizationId, executionId)
                .orElseThrow(() -> new ResourceNotFoundException("execution"));
        return views(organizationId, invocations.pageByOrganizationAndExecution(organizationId, executionId, pageable));
    }

    @Transactional(readOnly = true)
    public Page<InvocationView> history(UUID organizationId, UUID agentId, InvocationStatus status, AuthenticatedUser user,
            Pageable pageable) {
        access.require(organizationId, user, Permission.AGENT_EXECUTION_READ);
        if (agentId != null) {
            support.load(organizationId, agentId);
        }
        return views(organizationId, invocations.searchByOrganization(organizationId, agentId, status, pageable));
    }

    /** Display form of a tool result: recognizable secrets are masked (the stored/encrypted result and what the model saw are unchanged). */
    private static String displayable(String result) {
        return result == null ? null : Redactor.maskText(result);
    }

    private Page<InvocationView> views(UUID organizationId, Page<ToolInvocation> page) {
        Map<String, String> names = toolDefinitions
                .findAllById(page.getContent().stream().map(ToolInvocation::getToolCode).distinct().toList()).stream()
                .collect(Collectors.toMap(ToolDefinition::getCode, ToolDefinition::getName));
        // Authorization was verified by the public method that called this one: only now is a result decrypted.
        Map<UUID, java.util.Optional<ToolApproval>> approvalOf = page.getContent().stream().collect(Collectors.toMap(ToolInvocation::getId,
                i -> approvals.findByOrganizationIdAndInvocationId(organizationId, i.getId())));
        Map<UUID, String> deciders = approverNames.resolve(organizationId,
                approvalOf.values().stream().flatMap(java.util.Optional::stream).map(ToolApproval::getDecidedBy).toList());
        List<InvocationView> content = page.getContent().stream().map(i -> {
            ToolApproval approval = approvalOf.get(i.getId()).orElse(null);
            return InvocationView.of(i, names.get(i.getToolCode()), approval, approval == null ? null : deciders.get(approval.getDecidedBy()),
                    resultOf(organizationId, i));
        }).toList();
        return new PageImpl<>(content, page.getPageable(), page.getTotalElements());
    }

    /**
     * The result to show: decrypted from the envelope, or the legacy plaintext of a row not yet converted. An envelope that cannot be
     * authenticated (wrong key, tampering, relocation) yields NO result, never the raw bytes and never a partial text; only the invocation
     * id is logged.
     */
    private String resultOf(UUID organizationId, ToolInvocation invocation) {
        byte[] envelope = invocation.getResultEnvelope();
        if (envelope == null) {
            return displayable(invocation.getLegacyResult());
        }
        try {
            return displayable(resultProtector.open(organizationId, invocation.getExecutionId(), invocation.getId(), envelope));
        } catch (ToolResultProtector.ToolResultUnavailableException e) {
            LoggerFactory.getLogger(ToolActivityService.class).warn("tool result of invocation {} could not be authenticated", invocation.getId());
            return null;
        }
    }

    /**
     * The tools granted to an agent and what the platform would do with a call right now: automatic, human
     * approval, or denied (with the reason). It is the same evaluation the execution uses; nothing the
     * model says can change it.
     */
    @Transactional(readOnly = true)
    public List<AvailableTool> availableTools(UUID organizationId, UUID agentId, AuthenticatedUser user) {
        access.require(organizationId, user, Permission.AGENT_READ);
        support.load(organizationId, agentId);
        List<ToolGrant> granted = grants.findByOrganizationIdAndIdAgentId(organizationId, agentId);
        Map<String, ToolDefinition> registry = toolDefinitions.findAllById(granted.stream().map(ToolGrant::getToolCode).toList())
                .stream().collect(Collectors.toMap(ToolDefinition::getCode, Function.identity()));
        return granted.stream().map(g -> {
            ToolDefinition tool = registry.get(g.getToolCode());
            Evaluation evaluation = ToolPolicyEvaluator.evaluate(tool, g);
            return new AvailableTool(g.getToolCode(), tool.getName(), tool.getDescription(), tool.getCategory(), tool.getRisk(),
                    tool.getRequiredLevel(), g.getLevel(), evaluation.decision().name(),
                    evaluation.allowed() ? null : evaluation.code(), tool.getMcpServer(), tool.getTimeoutSeconds(),
                    tool.getMaxOutputBytes());
        }).sorted(java.util.Comparator.comparing(AvailableTool::toolCode)).toList();
    }
}
