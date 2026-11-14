package com.aiworkforce.core.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiworkforce.core.support.TestIdentityProvider;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * An execution that nobody finishes (orchestrator crashed, request lost) must not stay open forever:
 * the reaper fails it with TIMEOUT through the same guarded transition, freeing the concurrency budget.
 */
@TestPropertySource(properties = {"app.executions.timeout=PT2S", "app.executions.reaper-interval-ms=300"})
class ExecutionReaperIntegrationTest extends AgentFlows {

    private JsonNode execution(TestUser user, UUID org, UUID id) throws Exception {
        return read(getAs(user, path(org, "/executions/" + id)).andExpect(status().isOk()));
    }

    @Test
    void stalePendingAndRunningExecutionsAreFailedWithATimeout() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Reaper");
        UUID agent = activeAgent(owner, org, "Atlas");

        UUID pending = id(read(postAs(owner, agents(org, "/" + agent + "/executions"), body("task", "never picked up"))
                .andExpect(status().isAccepted())));
        UUID running = id(read(postAs(owner, agents(org, "/" + agent + "/executions"), body("task", "picked up, then lost"))
                .andExpect(status().isAccepted())));
        String service = TestIdentityProvider.token("service-account-ai-orchestrator").serviceAccount("ai-orchestrator").sign();
        mvc.perform(post("/api/v1/internal/organizations/" + org + "/executions/" + running + "/start")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + service).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        for (int i = 0; i < 200; i++) {
            if ("FAILED".equals(execution(owner, org, pending).get("status").asString())
                    && "FAILED".equals(execution(owner, org, running).get("status").asString())) {
                break;
            }
            Thread.sleep(100);
        }
        for (UUID id : new UUID[] {pending, running}) {
            JsonNode view = execution(owner, org, id);
            assertThat(view.get("status").asString()).isEqualTo("FAILED");
            assertThat(view.get("errorCode").asString()).isEqualTo("TIMEOUT");
            assertThat(view.get("completedAt").isNull()).isFalse();
        }
        // A reaped execution is final: the orchestrator can no longer complete it.
        mvc.perform(post("/api/v1/internal/organizations/" + org + "/executions/" + running + "/complete")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + service).contentType(MediaType.APPLICATION_JSON)
                .content("{\"result\":\"too late\"}")).andExpect(status().isConflict());
    }

    private void service(UUID org, UUID execution, String action, String json) throws Exception {
        String token = TestIdentityProvider.token("service-account-ai-orchestrator").serviceAccount("ai-orchestrator").sign();
        mvc.perform(post("/api/v1/internal/organizations/" + org + "/executions/" + execution + "/" + action)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk());
    }

    @Test
    void closingOrphanedExecutionsReleasesTheOrganizationCapacityAndLeavesFinishedRunsAlone() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Capacity");
        UUID agent = activeAgent(owner, org, "Atlas");

        // A run that finishes normally before the timeout (must never be touched by the reaper).
        UUID finished = id(read(postAs(owner, agents(org, "/" + agent + "/executions"), body("task", "quick"))
                .andExpect(status().isAccepted())));
        service(org, finished, "start", "{}");
        service(org, finished, "complete", "{\"result\":\"done\"}");

        // Fill the organization's open-execution limit (3 by default) with orphans: one PENDING, one RUNNING.
        UUID pending = id(read(postAs(owner, agents(org, "/" + agent + "/executions"), body("task", "orphan 1")).andExpect(status().isAccepted())));
        UUID running = id(read(postAs(owner, agents(org, "/" + agent + "/executions"), body("task", "orphan 2")).andExpect(status().isAccepted())));
        service(org, running, "start", "{}");
        UUID third = id(read(postAs(owner, agents(org, "/" + agent + "/executions"), body("task", "orphan 3")).andExpect(status().isAccepted())));
        postAs(owner, agents(org, "/" + agent + "/executions"), body("task", "over the limit")).andExpect(status().isConflict());

        for (int i = 0; i < 200; i++) {
            if (List.of(pending, running, third).stream().allMatch(id -> {
                try {
                    return "FAILED".equals(execution(owner, org, id).get("status").asString());
                } catch (Exception e) {
                    return false;
                }
            })) {
                break;
            }
            Thread.sleep(100);
        }
        for (UUID id : List.of(pending, running, third)) {
            assertThat(execution(owner, org, id).get("errorCode").asString()).isEqualTo("TIMEOUT");
        }
        // The finished run kept its result.
        assertThat(execution(owner, org, finished).get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(execution(owner, org, finished).get("result").asString()).isEqualTo("done");

        // Capacity is available again: a new launch is accepted.
        postAs(owner, agents(org, "/" + agent + "/executions"), body("task", "after recovery")).andExpect(status().isAccepted());
    }

}
