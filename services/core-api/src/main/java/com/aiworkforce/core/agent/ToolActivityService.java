package com.aiworkforce.core.agent;

import com.aiworkforce.core.access.Permission;
import com.aiworkforce.core.access.TenantAccessService;
import com.aiworkforce.core.agent.AgentParts.ToolDefinition;
import com.aiworkforce.core.agent.AgentParts.ToolGrant;
import com.aiworkforce.core.agent.ToolPolicyEvaluator.Evaluation;
import com.aiworkforce.core.agent.ToolViews.AvailableTool;
import com.aiworkforce.core.agent.ToolViews.InvocationView;
import com.aiworkforce.core.common.exception.ResourceNotFoundException;
import com.aiworkforce.core.identity.AuthenticatedUser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
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

    public ToolActivityService(ToolInvocationRepository invocations, ToolApprovalRepository approvals,
            ToolDefinitionRepository toolDefinitions, ToolGrantRepository grants, ExecutionRepository executions,
            AgentSupport support, TenantAccessService access) {
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

    private Page<InvocationView> views(UUID organizationId, Page<ToolInvocation> page) {
        Map<String, String> names = toolDefinitions
                .findAllById(page.getContent().stream().map(ToolInvocation::getToolCode).distinct().toList()).stream()
                .collect(Collectors.toMap(ToolDefinition::getCode, ToolDefinition::getName));
        List<InvocationView> content = page.getContent().stream().map(i -> InvocationView.of(i, names.get(i.getToolCode()),
                approvals.findByOrganizationIdAndInvocationId(organizationId, i.getId()).orElse(null))).toList();
        return new PageImpl<>(content, page.getPageable(), page.getTotalElements());
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
