import { REAL_API_C3 } from '../../../testing/real-api-c3';
import {
  addStage,
  addStep,
  BuilderStage,
  fromDefinition,
  hasErrors,
  isParallel,
  moveStage,
  removeStage,
  removeStep,
  stepCount,
  toStepInputs,
  updateStep,
  validateBuilder,
} from './pipeline-builder.model';
import { WorkflowDefinition } from './workflows.models';

const A = 'agent-a';
const B = 'agent-b';
const C = 'agent-c';
const stage = (key: string, ...steps: { agent: string; name?: string; task?: string }[]): BuilderStage => ({
  key,
  steps: steps.map((s, i) => ({ key: `${key}-${i}`, name: s.name ?? `Step ${key}${i}`, agentId: s.agent, instruction: s.task ?? 'Do it' })),
});
const messages = (issues: readonly { message: string }[]) => issues.map((i) => i.message).join(' | ');

describe('pipeline builder model', () => {
  it('is rebuilt from a REAL saved definition: stage numbers become parallel groups, in order', () => {
    const def = REAL_API_C3['definitionPipeline'] as WorkflowDefinition;
    const stages = fromDefinition(def.steps);
    expect(stages.map((s) => s.steps.length)).toEqual([3, 1]);
    expect(stages[0].steps.map((s) => s.name)).toEqual(['Competitor analysis', 'Pricing models', 'Customer segments']);
    expect(isParallel(stages)).toBe(true);
    expect(def.maxParallelWorkers).toBe(3);
    // Saving it again sends exactly the same structure.
    const inputs = toStepInputs(stages);
    expect(inputs.map((i) => i.stage)).toEqual([1, 1, 1, 2]);
    expect(inputs.map((i) => i.name)).toEqual(def.steps.map((s) => s.name));
  });

  it('keeps a sequential definition sequential: no stage is sent, exactly the pre-C3.4 payload', () => {
    const stages = [stage('1', { agent: A }), stage('2', { agent: B })];
    expect(isParallel(stages)).toBe(false);
    expect(toStepInputs(stages).every((s) => s.stage === undefined)).toBe(true);
    expect(fromDefinition([
      { rank: 1, name: 'One', agentId: A, instruction: 'x', inputsFrom: [], stage: null },
      { rank: 2, name: 'Two', agentId: B, instruction: 'y', inputsFrom: [1], stage: null },
    ]).map((s) => s.steps.length)).toEqual([1, 1]);       // steps without a stage were sequential: one stage each
  });

  it('accepts a valid parallel pipeline and a valid sequential one', () => {
    const parallel = [stage('1', { agent: A }, { agent: B }), stage('2', { agent: C })];
    expect(validateBuilder(parallel, [A, B, C], 3)).toEqual([]);
    expect(validateBuilder([stage('1', { agent: A }), stage('2', { agent: B })], [A, B], 1)).toEqual([]);
  });

  it('refuses what the Core API refuses: empty pipeline, too many steps, missing fields, strangers, unused participants', () => {
    expect(messages(validateBuilder([], [A], 1))).toContain('at least one step');
    const nine = Array.from({ length: 9 }, (_, i) => stage(`s${i}`, { agent: A }));
    expect(messages(validateBuilder(nine, [A], 1))).toContain('at most 8 steps');
    const blank = [{ key: 'g', steps: [{ key: 's', name: ' ', agentId: '', instruction: '' }] }];
    const text = messages(validateBuilder(blank, [A], 1));
    expect(text).toContain('a name is required');
    expect(text).toContain('an instruction is required');
    expect(text).toContain('choose an agent');
    expect(messages(validateBuilder([stage('1', { agent: 'stranger' })], [A], 1))).toContain('not a participant');
    expect(messages(validateBuilder([stage('1', { agent: A })], [A, B], 1))).toContain('Every participant must be used');
  });

  it('refuses parallel stages when parallel execution is off or the stage is wider than the bound', () => {
    const wide = [stage('1', { agent: A }, { agent: B }, { agent: C })];
    expect(hasErrors(validateBuilder(wide, [A, B, C], 1))).toBe(true);
    expect(messages(validateBuilder(wide, [A, B, C], 1))).toContain('parallel execution is off');
    expect(messages(validateBuilder(wide, [A, B, C], 2))).toContain('at most 2 may run at once');
    expect(validateBuilder(wide, [A, B, C], 3)).toEqual([]);
    expect(messages(validateBuilder([stage('1', { agent: A }, { agent: B })], [A, B], 1))).toContain('need "Parallel workers" of 2 or more');
  });

  it('warns (does not forbid) when one agent gets several parallel tasks, as the API allows it', () => {
    const issues = validateBuilder([stage('1', { agent: A }, { agent: A, name: 'Second' })], [A], 2);
    expect(hasErrors(issues)).toBe(false);
    expect(issues.some((i) => i.level === 'warning' && i.message.includes('same agent'))).toBe(true);
  });

  it('makes dependency cycles impossible by construction: reordering only swaps whole stages, dependencies stay "previous stage"', () => {
    const stages = [stage('1', { agent: A }), stage('2', { agent: B }), stage('3', { agent: C })];
    const moved = moveStage(stages, '3', -1);
    expect(moved.map((s) => s.key)).toEqual(['1', '3', '2']);
    expect(moveStage(stages, '1', -1)).toBe(stages);                  // cannot move past the start
    expect(moveStage(stages, '3', 1)).toBe(stages);                   // nor past the end
    expect(moveStage(stages, 'nope', 1)).toBe(stages);
    expect(toStepInputs(stages).every((s) => !('inputsFrom' in s))).toBe(true);   // the Core API default: the previous stage, never a sibling
  });

  it('edits immutably: adding, removing and updating stages and steps never mutate the original', () => {
    const base: readonly BuilderStage[] = [stage('1', { agent: A })];
    const withStep = addStep(base, '1');
    expect(base[0].steps).toHaveLength(1);
    expect(withStep[0].steps).toHaveLength(2);
    expect(stepCount(addStage(base))).toBe(2);
    expect(removeStep(withStep, '1', withStep[0].steps[1].key)[0].steps).toHaveLength(1);
    expect(removeStep(base, '1', base[0].steps[0].key)).toEqual([]);      // an emptied stage disappears
    expect(removeStage([...base, ...addStage(base).slice(1)], '1')).toHaveLength(1);
    expect(updateStep(base, base[0].steps[0].key, { name: 'Renamed' })[0].steps[0].name).toBe('Renamed');
    expect(base[0].steps[0].name).not.toBe('Renamed');
  });
});
