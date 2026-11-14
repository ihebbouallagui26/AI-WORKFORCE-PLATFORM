package com.aiworkforce.core.support;

import com.aiworkforce.core.agent.OrchestratorGateway;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Stands in for the Python orchestrator in Core API tests: it records what the Core API asks it to
 * run (nothing is executed) and can simulate an unreachable orchestrator, an orchestrator that
 * accepts the work but whose answer is lost (hook + failure), or a slow hand-over (latch). The tests
 * then play the orchestrator's part by calling the internal endpoints with a service-account token.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RecordingOrchestrator {

    public record Dispatched(UUID organizationId, UUID executionId) {
    }

    public static final class Gateway implements OrchestratorGateway {

        private final List<Dispatched> dispatched = new CopyOnWriteArrayList<>();
        private final List<String> threads = new CopyOnWriteArrayList<>();
        private final AtomicInteger attempts = new AtomicInteger();
        private final AtomicBoolean unreachable = new AtomicBoolean();
        private final AtomicReference<Consumer<Dispatched>> onArrival = new AtomicReference<>();
        private final AtomicReference<CountDownLatch> gate = new AtomicReference<>();

        @Override
        public void dispatch(UUID organizationId, UUID executionId) {
            Dispatched request = new Dispatched(organizationId, executionId);
            threads.add(Thread.currentThread().getName());
            attempts.incrementAndGet();
            CountDownLatch latch = gate.get();
            if (latch != null) {
                try {
                    latch.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            // The orchestrator "receives" the request (and may act on it) before the HTTP answer is lost.
            Consumer<Dispatched> hook = onArrival.get();
            if (hook != null) {
                hook.accept(request);
            }
            if (unreachable.get()) {
                throw new IllegalStateException("orchestrator unreachable");
            }
            dispatched.add(request);
        }

        public List<Dispatched> dispatched() {
            return dispatched;
        }

        /** Names of the threads that executed hand-overs (never a request thread). */
        public List<String> threads() {
            return threads;
        }

        /** Number of hand-over attempts, successful or not. */
        public int attempts() {
            return attempts.get();
        }

        /** Every following hand-over throws (after the arrival hook, if any ran). */
        public void unreachable(boolean value) {
            unreachable.set(value);
        }

        /** Runs when a hand-over reaches the orchestrator, before its answer: simulates it starting the run. */
        public void onArrival(Consumer<Dispatched> hook) {
            onArrival.set(hook);
        }

        /** Hand-overs wait on this latch (null removes the gate). */
        public void gate(CountDownLatch latch) {
            gate.set(latch);
        }

        public void reset() {
            dispatched.clear();
            threads.clear();
            attempts.set(0);
            unreachable.set(false);
            onArrival.set(null);
            CountDownLatch latch = gate.getAndSet(null);
            if (latch != null) {
                latch.countDown();
            }
        }
    }

    @Bean
    @Primary
    Gateway recordingGateway() {
        return new Gateway();
    }
}
