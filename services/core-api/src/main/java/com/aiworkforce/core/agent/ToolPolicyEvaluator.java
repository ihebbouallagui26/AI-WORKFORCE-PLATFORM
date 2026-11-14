package com.aiworkforce.core.agent;

import com.aiworkforce.core.agent.AgentParts.ToolDefinition;
import com.aiworkforce.core.agent.AgentParts.ToolGrant;

/**
 * The server-side decision about a tool call. A pure function of the registry entry and the agent's
 * explicit grant: nothing the model says (names, "permissions", arguments) is an input, so an LLM can
 * never grant itself anything, and a human approval can never replace a missing grant (the grant is
 * evaluated again at approval time and immediately before execution).
 *
 * <p>Order of the checks (the first that fails wins): known tool, enabled, policy not DENIED, an MCP
 * server and tool are configured, an explicit grant exists, the grant level covers the tool's required
 * level. A call is then automatic only when the tool is {@code READ_ONLY}, the grant does not ask for
 * approval, the risk is not HIGH and no write or execute level is needed; every other case needs a human.
 */
final class ToolPolicyEvaluator {

    enum Decision {
        DENIED,
        AUTO,
        APPROVAL_REQUIRED
    }

    record Evaluation(Decision decision, String code, String message) {

        static Evaluation denied(String code, String message) {
            return new Evaluation(Decision.DENIED, code, message);
        }

        boolean allowed() {
            return decision != Decision.DENIED;
        }
    }

    private ToolPolicyEvaluator() {
    }

    static Evaluation evaluate(ToolDefinition tool, ToolGrant grant) {
        if (tool == null) {
            return Evaluation.denied("UNKNOWN_TOOL", "This tool does not exist.");
        }
        if (!tool.isEnabled()) {
            return Evaluation.denied("TOOL_DISABLED", "This tool is disabled.");
        }
        if (tool.getExecutionPolicy() == ToolPolicy.DENIED) {
            return Evaluation.denied("TOOL_DENIED", "This tool cannot be executed.");
        }
        if (tool.getMcpServer() == null || tool.getMcpTool() == null) {
            return Evaluation.denied("TOOL_NOT_EXECUTABLE", "This tool has no execution backend.");
        }
        if (grant == null) {
            return Evaluation.denied("NOT_GRANTED", "The agent has no grant for this tool.");
        }
        if (!tool.getRequiredLevel().atMost(grant.getLevel())) {
            return Evaluation.denied("INSUFFICIENT_LEVEL", "The grant of the agent does not cover this operation.");
        }
        boolean automatic = tool.getExecutionPolicy() == ToolPolicy.READ_ONLY && !grant.isApprovalRequired()
                && tool.getRisk() != ToolRisk.HIGH && tool.getRequiredLevel() == ToolLevel.READ;
        return new Evaluation(automatic ? Decision.AUTO : Decision.APPROVAL_REQUIRED, "OK", "Allowed.");
    }
}
