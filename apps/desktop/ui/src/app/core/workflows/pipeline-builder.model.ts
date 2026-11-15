/**
 * The model behind the visual pipeline builder. A pipeline is a list of STAGES; the steps of one stage run in parallel, a stage starts
 * when the one before it is resolved (fan-in). The structure makes dependency cycles and invalid stage ordering impossible by
 * construction; the validation below mirrors the Core API rules so that mistakes are shown BEFORE saving. The Core API validates again
 * (and always has the last word): nothing here is a substitute for it.
 */
import { StepDefinition, StepInput } from './workflows.models';

export const MAX_STEPS = 8;
export const MAX_PARALLEL = 3;

export interface BuilderStep {
  readonly key: string;
  readonly name: string;
  readonly agentId: string;
  readonly instruction: string;
}

export interface BuilderStage {
  readonly key: string;
  readonly steps: readonly BuilderStep[];
}

export interface BuilderIssue {
  readonly level: 'error' | 'warning';
  readonly message: string;
  /** Stage / step the issue refers to (1-based), when it does. */
  readonly stage?: number;
  readonly step?: number;
}

let counter = 0;
export const newKey = (prefix: string): string => `${prefix}-${++counter}`;

export const emptyStep = (): BuilderStep => ({ key: newKey('s'), name: '', agentId: '', instruction: '' });
export const emptyStage = (): BuilderStage => ({ key: newKey('g'), steps: [emptyStep()] });

/** Rebuilds the stages of a saved definition. Steps without a stage were sequential: each is a stage of its own. */
export function fromDefinition(steps: readonly StepDefinition[]): readonly BuilderStage[] {
  const stages: BuilderStage[] = [];
  let last: number | null = null;
  for (const step of [...steps].sort((a, b) => a.rank - b.rank)) {
    const item: BuilderStep = { key: newKey('s'), name: step.name, agentId: step.agentId, instruction: step.instruction };
    if (step.stage !== null && step.stage === last && stages.length > 0) {
      const current = stages[stages.length - 1];
      stages[stages.length - 1] = { ...current, steps: [...current.steps, item] };
    } else {
      stages.push({ key: newKey('g'), steps: [item] });
    }
    last = step.stage;
  }
  return stages;
}

export function stepCount(stages: readonly BuilderStage[]): number {
  return stages.reduce((n, s) => n + s.steps.length, 0);
}

export function widestStage(stages: readonly BuilderStage[]): number {
  return stages.reduce((n, s) => Math.max(n, s.steps.length), 0);
}

/** True when at least one stage has several steps: the workflow then needs `maxParallelWorkers` of 2 or more. */
export function isParallel(stages: readonly BuilderStage[]): boolean {
  return widestStage(stages) > 1;
}

/**
 * The steps to send. A pipeline whose stages all hold ONE step is saved exactly as before C3.4 (no `stage`): the existing sequential
 * behaviour is preserved. Otherwise every step names its stage (the API requires all or none).
 */
export function toStepInputs(stages: readonly BuilderStage[]): readonly StepInput[] {
  const staged = isParallel(stages);
  const out: StepInput[] = [];
  stages.forEach((stage, index) => {
    for (const step of stage.steps) {
      out.push({
        name: step.name.trim(),
        agentId: step.agentId,
        instruction: step.instruction.trim(),
        ...(staged ? { stage: index + 1 } : {}),
      });
    }
  });
  return out;
}

export function validateBuilder(
  stages: readonly BuilderStage[],
  participantIds: readonly string[],
  maxParallelWorkers: number,
  maxSteps: number = MAX_STEPS,
): readonly BuilderIssue[] {
  const issues: BuilderIssue[] = [];
  if (stages.length === 0 || stepCount(stages) === 0) {
    return [{ level: 'error', message: 'A pipeline needs at least one step.' }];
  }
  if (stepCount(stages) > Math.min(maxSteps, MAX_STEPS)) {
    issues.push({ level: 'error', message: `A workflow may have at most ${Math.min(maxSteps, MAX_STEPS)} steps.` });
  }
  const used = new Set<string>();
  stages.forEach((stage, si) => {
    if (stage.steps.length === 0) {
      issues.push({ level: 'error', message: `Stage ${si + 1} is empty.`, stage: si + 1 });
    }
    if (stage.steps.length > maxParallelWorkers) {
      issues.push({
        level: 'error', stage: si + 1,
        message: maxParallelWorkers === 1
          ? `Stage ${si + 1} has ${stage.steps.length} steps but parallel execution is off: raise "Parallel workers" or split the stage.`
          : `Stage ${si + 1} has ${stage.steps.length} parallel steps; at most ${maxParallelWorkers} may run at once.`,
      });
    }
    const agentsInStage = new Map<string, number>();
    stage.steps.forEach((step, ti) => {
      const at = { stage: si + 1, step: ti + 1 };
      if (!step.name.trim()) issues.push({ level: 'error', message: `Stage ${si + 1}, step ${ti + 1}: a name is required.`, ...at });
      if (step.name.trim().length > 120) issues.push({ level: 'error', message: `Stage ${si + 1}, step ${ti + 1}: the name is too long (120 characters at most).`, ...at });
      if (!step.instruction.trim()) issues.push({ level: 'error', message: `Stage ${si + 1}, step ${ti + 1}: an instruction is required.`, ...at });
      if (step.instruction.trim().length > 2000) issues.push({ level: 'error', message: `Stage ${si + 1}, step ${ti + 1}: the instruction is too long (2000 characters at most).`, ...at });
      if (!step.agentId) {
        issues.push({ level: 'error', message: `Stage ${si + 1}, step ${ti + 1}: choose an agent.`, ...at });
      } else if (!participantIds.includes(step.agentId)) {
        issues.push({ level: 'error', message: `Stage ${si + 1}, step ${ti + 1}: the agent is not a participant of this workflow.`, ...at });
      } else {
        used.add(step.agentId);
        agentsInStage.set(step.agentId, (agentsInStage.get(step.agentId) ?? 0) + 1);
      }
    });
    for (const [, n] of agentsInStage) {
      if (n > 1) {
        issues.push({ level: 'warning', stage: si + 1, message: `Stage ${si + 1} gives several tasks to the same agent: they will run as separate executions at the same time.` });
        break;
      }
    }
  });
  for (const participant of participantIds) {
    if (!used.has(participant)) {
      issues.push({ level: 'error', message: 'Every participant must be used by at least one step.' });
      break;
    }
  }
  if (isParallel(stages) && maxParallelWorkers < 2) {
    issues.push({ level: 'error', message: 'Parallel stages need "Parallel workers" of 2 or more.' });
  }
  return issues;
}

export const hasErrors = (issues: readonly BuilderIssue[]): boolean => issues.some((i) => i.level === 'error');

// ---------------------------------------------------------------------------------------------------- immutable edits

export function addStage(stages: readonly BuilderStage[]): readonly BuilderStage[] {
  return [...stages, emptyStage()];
}

export function removeStage(stages: readonly BuilderStage[], stageKey: string): readonly BuilderStage[] {
  return stages.filter((s) => s.key !== stageKey);
}

export function moveStage(stages: readonly BuilderStage[], stageKey: string, delta: -1 | 1): readonly BuilderStage[] {
  const index = stages.findIndex((s) => s.key === stageKey);
  const target = index + delta;
  if (index < 0 || target < 0 || target >= stages.length) return stages;
  const next = [...stages];
  [next[index], next[target]] = [next[target], next[index]];
  return next;
}

export function addStep(stages: readonly BuilderStage[], stageKey: string): readonly BuilderStage[] {
  return stages.map((s) => (s.key === stageKey ? { ...s, steps: [...s.steps, emptyStep()] } : s));
}

export function removeStep(stages: readonly BuilderStage[], stageKey: string, stepKey: string): readonly BuilderStage[] {
  return stages
    .map((s) => (s.key === stageKey ? { ...s, steps: s.steps.filter((t) => t.key !== stepKey) } : s))
    .filter((s) => s.steps.length > 0);
}

export function updateStep(stages: readonly BuilderStage[], stepKey: string, change: Partial<Omit<BuilderStep, 'key'>>): readonly BuilderStage[] {
  return stages.map((s) => ({ ...s, steps: s.steps.map((t) => (t.key === stepKey ? { ...t, ...change } : t)) }));
}
