package com.aiworkforce.core.agent;

import java.util.Set;

/**
 * Maps the outcome of a failed child execution to a {@link FailureCategory} and decides whether repeating it is PROVABLY
 * safe. An error is never retried just because it is an error: the step must not have started any external action.
 */
final class WorkflowFailureClassifier {

    /** Outcome of the classification. {@code retrySafe}: nothing external started, so a new attempt cannot repeat an effect. */
    record Failure(FailureCategory category, boolean retrySafe) {

        /** Eligible for the bounded AUTOMATIC retry. */
        boolean autoRetryable() {
            return retrySafe && category == FailureCategory.TRANSIENT_PROVIDER_FAILURE;
        }

        /** Eligible for MANUAL recovery: a known outcome with no external effect, whose cause a person may have fixed. */
        boolean manuallyRecoverable() {
            return retrySafe && (category == FailureCategory.TRANSIENT_PROVIDER_FAILURE
                    || category == FailureCategory.PERMANENT_PROVIDER_FAILURE);
        }

        /** CONTINUE may go past a failure unless it is a revoked permission or a workflow limit. */
        boolean continuable() {
            return category != FailureCategory.AUTHORIZATION_FAILURE && category != FailureCategory.WORKFLOW_LIMIT_EXCEEDED;
        }
    }

    private static final Set<String> TRANSIENT = Set.of("PROVIDER_TIMEOUT", "PROVIDER_UNAVAILABLE", "PROVIDER_RATE_LIMIT",
            "ORCHESTRATOR_UNAVAILABLE", "DISPATCH_CAPACITY_EXCEEDED", "TIMEOUT", "EXECUTION_TIMEOUT", "CORE_API_UNAVAILABLE",
            "COORDINATION_UNAVAILABLE");
    private static final Set<String> PERMANENT = Set.of("PROVIDER_AUTHENTICATION", "PROVIDER_BAD_REQUEST", "PROVIDER_INVALID_RESPONSE",
            "PROVIDER_NOT_ENABLED", "PROVIDER_NOT_CONFIGURED", "CREDENTIAL_UNAVAILABLE", "INVALID_REQUEST");
    private static final Set<String> TOOL = Set.of("TOOL_FAILED", "TOOL_ROUND_LIMIT");
    private static final Set<String> AUTHORIZATION = Set.of("AGENT_NOT_ELIGIBLE", "REQUESTER_NOT_AUTHORIZED", "STEP_NOT_RUNNABLE",
            "COORDINATION_REJECTED", "EXECUTION_MISSING");
    private static final Set<String> LIMIT = Set.of("WORKFLOW_TIMEOUT", "WORKFLOW_CANCELLED", "STEP_TIMEOUT", "DEADLINE_EXCEEDED", "TOKEN_BUDGET_EXCEEDED",
            "STEP_LIMIT", "APPROVAL_EXPIRED");

    private WorkflowFailureClassifier() {
    }

    /** Manual recovery rule for a stored classification (used by the retry endpoint). */
    static boolean recoverable(FailureCategory category, Boolean retrySafe) {
        return category != null && new Failure(category, Boolean.TRUE.equals(retrySafe)).manuallyRecoverable();
    }

    /**
     * @param errorCode      the code the child execution ended with
     * @param toolStarted    a tool call of the execution ever started (an external effect may exist)
     * @param outcomeUnknown a tool call of the execution ended OUTCOME_UNKNOWN
     */
    static Failure classify(String errorCode, boolean toolStarted, boolean outcomeUnknown) {
        if (outcomeUnknown) {
            return new Failure(FailureCategory.OUTCOME_UNKNOWN, false);
        }
        String code = errorCode == null ? "" : errorCode;
        FailureCategory category;
        if (LIMIT.contains(code)) {
            category = FailureCategory.WORKFLOW_LIMIT_EXCEEDED;
        } else if (AUTHORIZATION.contains(code)) {
            category = FailureCategory.AUTHORIZATION_FAILURE;
        } else if (TOOL.contains(code)) {
            category = FailureCategory.TOOL_FAILURE;
        } else if (PERMANENT.contains(code)) {
            category = FailureCategory.PERMANENT_PROVIDER_FAILURE;
        } else if (TRANSIENT.contains(code)) {
            category = FailureCategory.TRANSIENT_PROVIDER_FAILURE;
        } else {
            category = FailureCategory.UNCLASSIFIED;
        }
        boolean retrySafe = !toolStarted && category != FailureCategory.TOOL_FAILURE && category != FailureCategory.UNCLASSIFIED;
        return new Failure(category, retrySafe);
    }
}
