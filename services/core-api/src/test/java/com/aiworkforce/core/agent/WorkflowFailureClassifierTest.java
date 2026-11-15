package com.aiworkforce.core.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiworkforce.core.agent.WorkflowFailureClassifier.Failure;
import org.junit.jupiter.api.Test;

/** The failure taxonomy: an error is never retried merely because it is an error. */
class WorkflowFailureClassifierTest {

    @Test
    void aTransientProviderFailureBeforeAnyExternalActionIsAutomaticallyRetryable() {
        for (String code : new String[] {"PROVIDER_TIMEOUT", "PROVIDER_UNAVAILABLE", "PROVIDER_RATE_LIMIT", "ORCHESTRATOR_UNAVAILABLE", "TIMEOUT"}) {
            Failure failure = WorkflowFailureClassifier.classify(code, false, false);
            assertThat(failure.category()).as(code).isEqualTo(FailureCategory.TRANSIENT_PROVIDER_FAILURE);
            assertThat(failure.autoRetryable()).as(code).isTrue();
        }
    }

    @Test
    void aTransientFailureAfterAToolStartedIsNeverRetriedBecauseAnEffectMayExist() {
        Failure failure = WorkflowFailureClassifier.classify("PROVIDER_UNAVAILABLE", true, false);
        assertThat(failure.retrySafe()).isFalse();
        assertThat(failure.autoRetryable()).isFalse();
        assertThat(failure.manuallyRecoverable()).isFalse();
    }

    @Test
    void anUnknownOutcomeWinsOverEveryCodeAndIsNeverRetriedOrRecovered() {
        for (String code : new String[] {"PROVIDER_UNAVAILABLE", "TIMEOUT", "STEP_TIMEOUT", "TOOL_FAILED", "WORKFLOW_CANCELLED"}) {
            Failure failure = WorkflowFailureClassifier.classify(code, true, true);
            assertThat(failure.category()).as(code).isEqualTo(FailureCategory.OUTCOME_UNKNOWN);
            assertThat(failure.autoRetryable() || failure.manuallyRecoverable()).as(code).isFalse();
        }
    }

    @Test
    void permanentProviderFailuresAreNotAutomaticButMayBeRecoveredByAPersonWhoFixedTheCause() {
        Failure failure = WorkflowFailureClassifier.classify("PROVIDER_AUTHENTICATION", false, false);
        assertThat(failure.category()).isEqualTo(FailureCategory.PERMANENT_PROVIDER_FAILURE);
        assertThat(failure.autoRetryable()).isFalse();
        assertThat(failure.manuallyRecoverable()).isTrue();
    }

    @Test
    void toolFailuresAuthorizationFailuresLimitsAndUnknownCodesAreNeverRetried() {
        assertThat(WorkflowFailureClassifier.classify("TOOL_FAILED", true, false).category()).isEqualTo(FailureCategory.TOOL_FAILURE);
        assertThat(WorkflowFailureClassifier.classify("TOOL_FAILED", false, false).retrySafe()).isFalse();
        assertThat(WorkflowFailureClassifier.classify("AGENT_NOT_ELIGIBLE", false, false).category()).isEqualTo(FailureCategory.AUTHORIZATION_FAILURE);
        assertThat(WorkflowFailureClassifier.classify("STEP_TIMEOUT", false, false).category()).isEqualTo(FailureCategory.WORKFLOW_LIMIT_EXCEEDED);
        assertThat(WorkflowFailureClassifier.classify("WORKFLOW_TIMEOUT", false, false).manuallyRecoverable()).isFalse();
        Failure unknown = WorkflowFailureClassifier.classify("SOMETHING_NEW", false, false);
        assertThat(unknown.category()).isEqualTo(FailureCategory.UNCLASSIFIED);
        assertThat(unknown.autoRetryable() || unknown.manuallyRecoverable()).isFalse();
        assertThat(WorkflowFailureClassifier.classify(null, false, false).category()).isEqualTo(FailureCategory.UNCLASSIFIED);
    }

    @Test
    void continueNeverGoesPastARevokedPermissionOrAWorkflowLimit() {
        assertThat(WorkflowFailureClassifier.classify("AGENT_NOT_ELIGIBLE", false, false).continuable()).isFalse();
        assertThat(WorkflowFailureClassifier.classify("STEP_TIMEOUT", false, false).continuable()).isFalse();
        assertThat(WorkflowFailureClassifier.classify("PROVIDER_BAD_REQUEST", false, false).continuable()).isTrue();
        assertThat(WorkflowFailureClassifier.classify("TOOL_FAILED", true, false).continuable()).isTrue();
    }
}
