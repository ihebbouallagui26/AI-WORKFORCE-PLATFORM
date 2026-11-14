package com.aiworkforce.core.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * Structural invariants hold under concurrent requests: two moves that would each be valid alone
 * but form a cycle together can never both succeed (the organization row lock serialises them).
 */
class WorkforceConcurrencyIntegrationTest extends WorkforceFlows {

    private static final int ROUNDS = 8;

    @Test
    void concurrentDepartmentMovesNeverCreateACycle() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Concurrent departments");
        for (int round = 0; round < ROUNDS; round++) {
            UUID a = department(owner, org, "A" + round, "A" + round, null);
            UUID b = department(owner, org, "B" + round, "B" + round, null);
            List<Integer> statuses = race(
                    () -> putAs(owner, path(org, "/departments/" + a + "/parent"), body("parentId", b))
                            .andReturn().getResponse().getStatus(),
                    () -> putAs(owner, path(org, "/departments/" + b + "/parent"), body("parentId", a))
                            .andReturn().getResponse().getStatus());
            assertThat(statuses).as("round %d", round).containsExactlyInAnyOrder(200, 409);
        }
    }

    @Test
    void concurrentManagerAssignmentsNeverCreateAReportingCycle() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Concurrent managers");
        for (int round = 0; round < ROUNDS; round++) {
            UUID a = employee(owner, org, "Alpha " + round);
            UUID b = employee(owner, org, "Beta " + round);
            List<Integer> statuses = race(
                    () -> putAs(owner, path(org, "/employees/" + a + "/manager"), body("managerId", b))
                            .andReturn().getResponse().getStatus(),
                    () -> putAs(owner, path(org, "/employees/" + b + "/manager"), body("managerId", a))
                            .andReturn().getResponse().getStatus());
            assertThat(statuses).as("round %d", round).containsExactlyInAnyOrder(200, 409);
        }
    }

    @Test
    void concurrentDuplicateTeamAssignmentsKeepASingleCurrentMembership() throws Exception {
        TestUser owner = newUser("owner");
        UUID org = createOrganization(owner, "Concurrent teams");
        UUID worker = employee(owner, org, "Worker");
        UUID squad = team(owner, org, "Squad", null, null);
        List<Integer> statuses = race(
                () -> postAs(owner, path(org, "/teams/" + squad + "/members"), body("employeeId", worker))
                        .andReturn().getResponse().getStatus(),
                () -> postAs(owner, path(org, "/teams/" + squad + "/members"), body("employeeId", worker))
                        .andReturn().getResponse().getStatus(),
                () -> postAs(owner, path(org, "/teams/" + squad + "/members"), body("employeeId", worker))
                        .andReturn().getResponse().getStatus());
        assertThat(statuses).containsExactlyInAnyOrder(201, 409, 409);
    }

    @SafeVarargs
    private static List<Integer> race(Callable<Integer>... actions) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(actions.length);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> action : actions) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return action.call();
                }));
            }
            start.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
