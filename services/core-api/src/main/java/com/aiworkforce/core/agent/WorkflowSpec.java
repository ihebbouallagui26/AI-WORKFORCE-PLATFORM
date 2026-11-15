package com.aiworkforce.core.agent;

import com.aiworkforce.core.common.exception.BadRequestException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The static description of a pipeline and its validation. The same shapes are stored in the definition (steps) and
 * copied, with the limits, into the immutable snapshot of a run.
 */
final class WorkflowSpec {

    static final int MAX_STEPS = 8;
    static final int MAX_NAME = 120;
    static final int MAX_INSTRUCTION = 2000;
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private WorkflowSpec() {
    }

    /**
     * One static step. {@code inputsFrom} lists the EARLIER ranks whose bounded result summaries this step receives:
     * nothing else from the run is ever given to the agent.
     */
    record Step(int rank, String name, UUID agentId, String instruction, List<Integer> inputsFrom) {
    }

    /** Everything a run needs, frozen when it starts. */
    record Snapshot(UUID definitionId, int version, String name, List<Step> steps, List<UUID> participants, int maxSteps,
            int maxRetriesPerStep, int tokenBudget, int deadlineSeconds, String mode, UUID coordinatorAgentId,
            List<Member> members, String failurePolicy, String recoveryMode, Integer stepTimeoutSeconds) {

        /** Snapshots written before C3.3 carry no policy: they are FAIL_FAST with no manual recovery and no step timeout. */
        WorkflowFailurePolicy policy() {
            return failurePolicy == null ? WorkflowFailurePolicy.FAIL_FAST : WorkflowFailurePolicy.valueOf(failurePolicy);
        }

        boolean manualRecovery() {
            return WorkflowRecoveryMode.MANUAL.name().equals(recoveryMode);
        }


        /** Snapshots written by C3.1 carry no mode: they are pipelines. */
        boolean coordinated() {
            return WorkflowMode.COORDINATED.name().equals(mode);
        }

        Member member(String ref) {
            if (members == null || ref == null) {
                return null;
            }
            return members.stream().filter(m -> m.ref().equalsIgnoreCase(ref.strip())).findFirst().orElse(null);
        }

        Member memberOf(UUID agentId) {
            if (members == null) {
                return null;
            }
            return members.stream().filter(m -> m.agentId().equals(agentId)).findFirst().orElse(null);
        }
    }

    /**
     * A participant of a coordinated workflow as the COORDINATOR sees it: an opaque, workflow-scoped reference and safe
     * descriptions. The internal agent id never reaches the model; the Core API maps the reference back.
     */
    record Member(String ref, UUID agentId, String name, String title, String description, boolean coordinator) {
    }

    static String toJson(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    static List<Step> parseSteps(String json) {
        return MAPPER.readValue(json, new TypeReference<List<Step>>() {
        });
    }

    static Snapshot parseSnapshot(String json) {
        return MAPPER.readValue(json, Snapshot.class);
    }

    /**
     * Validates and normalizes the steps a person submitted: contiguous ranks from 1, bounded text, every agent a
     * participant, inputs only from earlier ranks (the previous step when not specified). Returns the stored form.
     */
    static List<Step> normalize(List<StepInput> submitted, Set<UUID> participantAgents, int maxSteps) {
        if (submitted == null || submitted.isEmpty()) {
            throw new BadRequestException("A workflow needs at least one step.");
        }
        if (submitted.size() > Math.min(maxSteps, MAX_STEPS)) {
            throw new BadRequestException("A workflow may have at most " + Math.min(maxSteps, MAX_STEPS) + " steps.");
        }
        List<Step> steps = new ArrayList<>();
        int rank = 0;
        for (StepInput input : submitted) {
            rank++;
            String name = input.name() == null ? "" : input.name().strip();
            String instruction = input.instruction() == null ? "" : input.instruction().strip();
            if (name.isEmpty() || name.length() > MAX_NAME) {
                throw new BadRequestException("Step " + rank + " needs a name of at most " + MAX_NAME + " characters.");
            }
            if (instruction.isEmpty() || instruction.length() > MAX_INSTRUCTION) {
                throw new BadRequestException("Step " + rank + " needs an instruction of at most " + MAX_INSTRUCTION + " characters.");
            }
            if (input.agentId() == null || !participantAgents.contains(input.agentId())) {
                throw new BadRequestException("Step " + rank + " must use an agent that was explicitly added as a participant.");
            }
            List<Integer> inputs = input.inputsFrom();
            if (inputs == null) {
                inputs = rank == 1 ? List.of() : List.of(rank - 1);
            }
            Set<Integer> seen = new HashSet<>();
            for (Integer from : inputs) {
                if (from == null || from < 1 || from >= rank || !seen.add(from)) {
                    throw new BadRequestException("Step " + rank + " can only take inputs from earlier steps, each at most once.");
                }
            }
            steps.add(new Step(rank, name, input.agentId(), instruction, inputs.stream().sorted().toList()));
        }
        return List.copyOf(steps);
    }

    /** The submitted form of a step (before normalization). */
    record StepInput(String name, UUID agentId, String instruction, List<Integer> inputsFrom) {
    }
}
