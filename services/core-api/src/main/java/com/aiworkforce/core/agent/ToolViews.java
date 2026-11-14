package com.aiworkforce.core.agent;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * Read models of tool activity and approvals. They carry the REDACTED rendering of the arguments and the
 * argument hash, never the raw arguments, and never anything about credentials.
 */
public final class ToolViews {

    private ToolViews() {
    }

    /** Decision of a person about an approval, as shown in the timeline. */
    @Schema(name = "ToolApprovalSummary")
    public record ApprovalSummary(UUID id, ApprovalStatus status, Instant requestedAt, Instant expiresAt, UUID decidedBy,
            Instant decidedAt, String rejectionReason) {

        static ApprovalSummary of(ToolApproval a) {
            return new ApprovalSummary(a.getId(), a.getStatus(), a.getRequestedAt(), a.getExpiresAt(), a.getDecidedBy(),
                    a.getDecidedAt(), a.getRejectionReason());
        }
    }

    /** One tool call of an execution (an entry of its tool activity timeline). */
    @Schema(name = "ToolInvocation")
    public record InvocationView(UUID id, UUID executionId, UUID agentId, String toolCode, String toolName,
            String toolCallId, int sequence, InvocationStatus status, ToolPolicy policy, String argumentsRedacted,
            String argumentsHash, String reason, String result, boolean resultTruncated, String errorCode,
            String errorMessage, Instant requestedAt, Instant startedAt, Instant completedAt, ApprovalSummary approval) {

        static InvocationView of(ToolInvocation i, String toolName, ToolApproval approval) {
            return new InvocationView(i.getId(), i.getExecutionId(), i.getAgentId(), i.getToolCode(), toolName,
                    i.getToolCallId(), i.getSequence(), i.getStatus(), i.getPolicy(), i.getArgumentsRedacted(),
                    i.getArgumentsHash(), i.getReason(), i.getResult(), i.isResultTruncated(), i.getErrorCode(),
                    i.getErrorMessage(), i.getRequestedAt(), i.getStartedAt(), i.getCompletedAt(),
                    approval == null ? null : ApprovalSummary.of(approval));
        }
    }

    /**
     * A human approval request: exactly what would be authorized (one tool, one call, these arguments).
     * Approving it never authorizes any other call.
     */
    @Schema(name = "ToolApproval")
    public record ApprovalView(UUID id, UUID executionId, ExecutionStatus executionStatus, UUID agentId, String agentName,
            UUID invocationId, String toolCode, String toolName, String mcpServer, String toolCallId, String risk,
            String description, String reason, String argumentsRedacted, String argumentsHash, int policyVersion,
            ApprovalStatus status, Instant requestedAt, Instant expiresAt, UUID decidedBy, Instant decidedAt,
            String rejectionReason, InvocationStatus invocationStatus) {

        static ApprovalView of(ToolApproval a, String agentName, String toolName, ExecutionStatus executionStatus,
                InvocationStatus invocationStatus) {
            return new ApprovalView(a.getId(), a.getExecutionId(), executionStatus, a.getAgentId(), agentName,
                    a.getInvocationId(), a.getToolCode(), toolName, a.getMcpServer(), a.getToolCallId(), a.getRisk(),
                    a.getDescription(), a.getReason(), a.getArgumentsRedacted(), a.getArgumentsHash(), a.getPolicyVersion(),
                    a.getStatus(), a.getRequestedAt(), a.getExpiresAt(), a.getDecidedBy(), a.getDecidedAt(),
                    a.getRejectionReason(), invocationStatus);
        }
    }

    /** A tool granted to an agent, with what the platform would do with a call right now. */
    @Schema(name = "AvailableTool")
    public record AvailableTool(String toolCode, String name, String description, String category, ToolRisk risk,
            ToolLevel requiredLevel, ToolLevel grantedLevel, String decision, String reasonCode, String mcpServer,
            int timeoutSeconds, int maxOutputBytes) {
    }
}
