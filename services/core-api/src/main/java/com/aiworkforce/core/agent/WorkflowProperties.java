package com.aiworkforce.core.agent;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration of multi-agent workflows ({@code app.workflows.*}).
 *
 * @param maxOpenRunsPerOrganization open (PENDING, RUNNING, WAITING_APPROVAL, CANCEL_REQUESTED) runs per organization;
 *                                   independent from the open-execution limit of {@link ExecutionProperties}
 * @param maintenanceMs              period of the scheduler pass that advances runs (it is also the recovery mechanism)
 * @param cancelGrace                how long a cancelled run waits for a RUNNING tool call to reach a known outcome
 *                                   before its child execution is ended (the call is then OUTCOME_UNKNOWN)
 * @param maxResultChars             results kept on a run are truncated to this length
 */
@ConfigurationProperties(prefix = "app.workflows")
public record WorkflowProperties(Integer maxOpenRunsPerOrganization, Long maintenanceMs, Duration cancelGrace,
        Integer maxResultChars) {

    public WorkflowProperties {
        maxOpenRunsPerOrganization = maxOpenRunsPerOrganization == null ? 3 : maxOpenRunsPerOrganization;
        maintenanceMs = maintenanceMs == null ? 3000L : maintenanceMs;
        cancelGrace = cancelGrace == null ? Duration.ofSeconds(30) : cancelGrace;
        maxResultChars = maxResultChars == null ? 20000 : maxResultChars;
        if (maxOpenRunsPerOrganization < 1 || maxOpenRunsPerOrganization > 50) {
            throw new IllegalArgumentException("app.workflows.max-open-runs-per-organization must be between 1 and 50");
        }
    }
}
