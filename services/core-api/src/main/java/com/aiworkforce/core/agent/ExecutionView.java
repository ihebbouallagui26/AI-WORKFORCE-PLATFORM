package com.aiworkforce.core.agent;

import com.aiworkforce.core.provider.ProviderType;
import io.swagger.v3.oas.annotations.media.Schema;
import com.aiworkforce.core.agent.redaction.Redactor;
import java.time.Instant;
import java.util.UUID;

/**
 * Read model of an execution. It never contains a credential: only the provider type and model used.
 * {@code task} and {@code result} are business content, visible only to holders of
 * {@code agent.execution.read} (and to the launcher in the response of the start call).
 */
@Schema(name = "AgentExecution")
public record ExecutionView(
        UUID id,
        UUID organizationId,
        UUID agentId,
        UUID requestedBy,
        String task,
        ExecutionStatus status,
        ProviderType providerType,
        String modelId,
        String result,
        Integer inputTokens,
        Integer outputTokens,
        String errorCode,
        String errorMessage,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        long version) {

    static ExecutionView of(AgentExecution e) {
        return new ExecutionView(e.getId(), e.getOrganizationId(), e.getAgentId(), e.getRequestedBy(), e.getTask(),
                e.getStatus(), e.getProviderType(), e.getModelId(), displayResult(e.getResult()), e.getInputTokens(),
                e.getOutputTokens(), e.getErrorCode(), e.getErrorMessage(), e.getCreatedAt(), e.getStartedAt(),
                e.getCompletedAt(), e.getVersion());
    }

    /** The agent's final answer as shown to people: recognizable secrets masked (display only: the stored result is unchanged). */
    private static String displayResult(String result) {
        return result == null ? null : Redactor.maskText(result);
    }
}
