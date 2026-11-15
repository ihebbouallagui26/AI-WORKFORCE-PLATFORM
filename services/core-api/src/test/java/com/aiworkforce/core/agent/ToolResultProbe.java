package com.aiworkforce.core.agent;

import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Test access to the package-private tool result protection: the batch backfill and the envelope itself (the same bean as production). */
@TestConfiguration(proxyBeanMethods = false)
public class ToolResultProbe {

    public static final class Probe {

        private final ToolResultBackfill backfill;
        private final ToolResultProtector protector;

        Probe(ToolResultBackfill backfill, ToolResultProtector protector) {
            this.backfill = backfill;
            this.protector = protector;
        }

        /** Runs the backfill to completion. Returns {converted, failedVerification}. */
        public int[] backfill() {
            ToolResultBackfill.Counts counts = backfill.convertAll();
            return new int[] {counts.converted(), counts.failed()};
        }

        public byte[] seal(UUID organizationId, UUID executionId, UUID invocationId, String plaintext) {
            return protector.seal(organizationId, executionId, invocationId, plaintext);
        }

        public String open(UUID organizationId, UUID executionId, UUID invocationId, byte[] envelope) {
            return protector.open(organizationId, executionId, invocationId, envelope);
        }
    }

    @Bean
    Probe toolResultProbe(ToolResultBackfill backfill, ToolResultProtector protector) {
        return new Probe(backfill, protector);
    }
}
