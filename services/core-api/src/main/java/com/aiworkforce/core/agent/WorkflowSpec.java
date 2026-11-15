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
    record Step(int rank, String name, UUID agentId, String instruction, List<Integer> inputsFrom, Integer stage) {

        /** Steps of the same stage run in parallel; a step without an explicit stage is a stage of its own (sequential). */
        int stageOrRank() {
            return stage == null ? rank : stage;
        }
    }

    /** Everything a run needs, frozen when it starts. */
    record Snapshot(UUID definitionId, int version, String name, List<Step> steps, List<UUID> participants, int maxSteps,
            int maxRetriesPerStep, int tokenBudget, int deadlineSeconds, String mode, UUID coordinatorAgentId,
            List<Member> members, String failurePolicy, String recoveryMode, Integer stepTimeoutSeconds, Integer maxParallelWorkers) {

        /** Snapshots written before C3.4 are sequential. */
        int parallelBound() {
            return maxParallelWorkers == null ? 1 : maxParallelWorkers;
        }

        /** True when workers may run at the same time: a coordinated run with a bound above 1, or a pipeline with a stage of several steps. */
        boolean parallel() {
            if (coordinated()) {
                return parallelBound() > 1;
            }
            java.util.Map<Integer, Long> sizes = steps.stream().collect(java.util.stream.Collectors.groupingBy(Step::stageOrRank, java.util.stream.Collectors.counting()));
            return sizes.values().stream().anyMatch(n -> n > 1);
        }

        /** The steps of a stage, in rank order. */
        List<Step> stage(int stageNo) {
            return steps.stream().filter(s -> s.stageOrRank() == stageNo).sorted(java.util.Comparator.comparingInt(Step::rank)).toList();
        }

        /** The stage numbers in execution order. */
        List<Integer> stageNumbers() {
            return steps.stream().map(Step::stageOrRank).distinct().sorted().toList();
        }

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
    static List<Step> normalize(List<StepInput> submitted, Set<UUID> participantAgents, int maxSteps, int maxParallel) {
        if (submitted == null || submitted.isEmpty()) {
            throw new BadRequestException("A workflow needs at least one step.");
        }
        if (submitted.size() > Math.min(maxSteps, MAX_STEPS)) {
            throw new BadRequestException("A workflow may have at most " + Math.min(maxSteps, MAX_STEPS) + " steps.");
        }
        // Stages (C3.4): either no step names one (the pipeline stays sequential) or every step does.
        long staged = submitted.stream().filter(s -> s.stage() != null).count();
        if (staged != 0 && staged != submitted.size()) {
            throw new BadRequestException("Either every step names its stage or none does.");
        }
        boolean parallelStages = staged != 0;
        List<Step> steps = new ArrayList<>();
        int rank = 0;
        int previousStage = 0;
        int stageSize = 0;
        for (StepInput input : submitted) {
            rank++;
            Integer stage = null;
            if (parallelStages) {
                stage = input.stage();
                if (rank == 1 ? stage != 1 : (stage != previousStage && stage != previousStage + 1)) {
                    throw new BadRequestException("Stages start at 1, never go back and never skip a number (step " + rank + ").");
                }
                stageSize = stage == previousStage ? stageSize + 1 : 1;
                if (stageSize > maxParallel) {
                    throw new BadRequestException("A stage may hold at most " + maxParallel + " parallel steps (the workflow's maxParallelWorkers).");
                }
                previousStage = stage;
            }
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
                // Default: the previous step, or ALL steps of the previous stage (a fan-in), in rank order.
                final Integer currentStage = stage;
                final int currentRank = rank;
                inputs = rank == 1 ? List.of() : parallelStages
                        ? steps.stream().filter(s -> s.stage() == currentStage - 1).map(Step::rank).toList()
                        : List.of(currentRank - 1);
            }
            Set<Integer> seen = new HashSet<>();
            for (Integer from : inputs) {
                if (from == null || from < 1 || from >= rank || !seen.add(from)) {
                    throw new BadRequestException("Step " + rank + " can only take inputs from earlier steps, each at most once.");
                }
                // A dependency must be a STRICTLY EARLIER stage: steps of one stage are independent (no cycle, no wait on a sibling).
                if (parallelStages && steps.get(from - 1).stage().equals(stage)) {
                    throw new BadRequestException("Step " + rank + " cannot take its input from step " + from + ": they run in parallel in the same stage.");
                }
            }
            steps.add(new Step(rank, name, input.agentId(), instruction, inputs.stream().sorted().toList(), stage));
        }
        return List.copyOf(steps);
    }

    /** The submitted form of a step (before normalization). */
    record StepInput(String name, UUID agentId, String instruction, List<Integer> inputsFrom, Integer stage) {
    }
}
