import { AgentExecution, ToolInvocation } from '../../core/agents/agents.models';
import { MAX_RESULT_CHARS, bounded, buildTimeline, durationMs, formatDuration, maskSensitive } from './execution-timeline.model';

const T = (s: number) => new Date(Date.UTC(2026, 0, 1, 0, 0, s)).toISOString();

const run = (status: AgentExecution['status'], extra: Partial<AgentExecution> = {}): AgentExecution => ({
  id: 'run-1', organizationId: 'org', agentId: 'agent', requestedBy: 'user', task: 'task', status, providerType: 'ANTHROPIC', modelId: 'm',
  result: null, inputTokens: null, outputTokens: null, errorCode: null, errorMessage: null, createdAt: T(0), startedAt: T(1), completedAt: null, version: 1, ...extra,
});

const call = (sequence: number, extra: Partial<ToolInvocation> = {}): ToolInvocation => ({
  id: `call-${sequence}`, executionId: 'run-1', agentId: 'agent', toolCode: 'diagnostic.echo', toolName: 'Echo text', toolCallId: `toolu_${sequence}`,
  sequence, status: 'SUCCEEDED', policy: 'READ_ONLY', argumentsRedacted: '{"text":"hi"}', argumentsHash: 'a'.repeat(64), reason: null, result: 'hi',
  resultTruncated: false, errorCode: null, errorMessage: null, requestedAt: T(2), startedAt: T(3), completedAt: T(4), approval: null, ...extra,
});

describe('buildTimeline', () => {
  it('has the two execution facts for a bare run and never invents reasoning or resume events', () => {
    const titles = buildTimeline(run('RUNNING'), []).map((e) => e.title);
    expect(titles).toEqual(['Execution created', 'Execution started']);
    const all = buildTimeline(run('SUCCEEDED', { completedAt: T(9) }), [call(1)]).map((e) => e.title.toLowerCase()).join(' | ');
    expect(all).not.toMatch(/thinking|reasoning|resumed/);
  });

  it('orders events chronologically and keeps derivation order for equal instants', () => {
    const entries = buildTimeline(run('SUCCEEDED', { completedAt: T(9) }), [call(2, { requestedAt: T(6), startedAt: T(7), completedAt: T(8) }), call(1)]);
    expect(entries.map((e) => e.title)).toEqual([
      'Execution created', 'Execution started',
      'Tool requested: Echo text', 'Tool started: Echo text', 'Tool completed: Echo text',
      'Tool requested: Echo text', 'Tool started: Echo text', 'Tool completed: Echo text',
      'Execution completed',
    ]);
    expect(entries.map((e) => Date.parse(e.at))).toEqual([...entries.map((e) => Date.parse(e.at))].sort((a, b) => a - b));
  });

  it('shows the approval lifecycle of a call that needed a person', () => {
    const approved = call(1, {
      policy: 'APPROVAL_REQUIRED', toolName: 'Record a note',
      approval: { id: 'a1', status: 'APPROVED', requestedAt: T(2), expiresAt: T(900), decidedBy: 'u', decidedAt: T(5), rejectionReason: null },
      requestedAt: T(2), startedAt: T(6), completedAt: T(7),
    });
    expect(buildTimeline(run('SUCCEEDED', { completedAt: T(8) }), [approved]).map((e) => e.title)).toEqual([
      'Execution created', 'Execution started', 'Tool requested: Record a note', 'Approval requested: Record a note', 'Approval granted: Record a note',
      'Tool started: Record a note', 'Tool completed: Record a note', 'Execution completed',
    ]);
    const rejected = call(1, {
      status: 'REJECTED', policy: 'APPROVAL_REQUIRED', startedAt: null, completedAt: null,
      approval: { id: 'a1', status: 'REJECTED', requestedAt: T(2), expiresAt: T(900), decidedBy: 'u', decidedAt: T(5), rejectionReason: 'Not now' },
    });
    const entries = buildTimeline(run('RUNNING'), [rejected]);
    expect(entries.find((e) => e.title.startsWith('Approval rejected'))?.detail).toBe('Reason: Not now');
    expect(entries.some((e) => e.title.startsWith('Tool started'))).toBe(false);
  });

  it('marks a pending approval as waiting for a decision, and expired or cancelled ones as such', () => {
    const base = { policy: 'APPROVAL_REQUIRED' as const, status: 'AWAITING_APPROVAL' as const, startedAt: null, completedAt: null };
    const pending = buildTimeline(run('WAITING_APPROVAL'), [call(1, { ...base, approval: { id: 'a', status: 'PENDING', requestedAt: T(2), expiresAt: T(900), decidedBy: null, decidedAt: null, rejectionReason: null } })]);
    expect(pending.find((e) => e.kind === 'approval')).toMatchObject({ status: 'Pending decision', tone: 'warning' });
    const expired = buildTimeline(run('FAILED', { errorCode: 'APPROVAL_EXPIRED', errorMessage: 'The approval request expired before a person decided.', completedAt: T(901) }), [
      call(1, { ...base, status: 'EXPIRED', approval: { id: 'a', status: 'EXPIRED', requestedAt: T(2), expiresAt: T(900), decidedBy: null, decidedAt: null, rejectionReason: null } }),
    ]);
    expect(expired.map((e) => e.title)).toContain('Approval expired: Echo text');
    const cancelled = buildTimeline(run('FAILED', { completedAt: T(20) }), [
      call(1, { ...base, status: 'FAILED', completedAt: T(20), approval: { id: 'a', status: 'CANCELLED', requestedAt: T(2), expiresAt: T(900), decidedBy: null, decidedAt: null, rejectionReason: null } }),
    ]);
    expect(cancelled.find((e) => e.title.startsWith('Approval cancelled'))?.detail).toContain('execution ended');
  });

  it('reports denied and failed tools and the final outcome of the run with a safe error', () => {
    const denied = call(1, { status: 'DENIED', policy: 'DENIED', startedAt: null, completedAt: T(2), errorCode: 'NOT_GRANTED', errorMessage: 'The agent has no grant for this tool.' });
    const failed = call(2, { status: 'FAILED', requestedAt: T(3), startedAt: T(4), completedAt: T(5), errorCode: 'TOOL_TIMEOUT', errorMessage: 'The tool did not finish in time.' });
    const entries = buildTimeline(run('FAILED', { errorCode: 'TIMEOUT', errorMessage: 'The execution took too long.', completedAt: T(60) }), [denied, failed]);
    expect(entries.find((e) => e.title.startsWith('Tool call denied'))?.error).toContain('no grant');
    expect(entries.find((e) => e.title.startsWith('Tool failed'))).toMatchObject({ error: 'The tool did not finish in time.', durationMs: 1000 });
    expect(entries.at(-1)).toMatchObject({ title: 'Execution timed out', status: 'Timed out', tone: 'danger' });
    expect(buildTimeline(run('FAILED', { errorCode: 'PROVIDER_ERROR', completedAt: T(9) }), []).at(-1)?.title).toBe('Execution failed');
  });

  it('tolerates a missing or invalid instant without throwing', () => {
    expect(() => buildTimeline(run('SUCCEEDED', { startedAt: 'not-a-date', completedAt: null }), [call(1, { startedAt: null, completedAt: 'nope' })])).not.toThrow();
    expect(durationMs('x', T(1))).toBeNull();
    expect(durationMs(T(5), T(1))).toBeNull();
  });
});

describe('masking and limits', () => {
  it.each([
    ['sk-ant-api03-abcdefghijklmnop', 'sk-ant'],
    ['Authorization: Bearer abcdefghijklmnopqrstuvwxyz', 'abcdefghijklmnop'],
    ['eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r_wW1gFWFOEjXk', 'eyJhbGci'],
    ['{"password": "hunter2-value"}', 'hunter2'],
    ['api_key=very-secret-value', 'very-secret'],
  ])('masks credentials in %s', (input, leak) => {
    const masked = maskSensitive(input);
    expect(masked).not.toContain(leak);
    expect(masked).toContain('REDACTED');
  });

  it('leaves ordinary text alone and handles null', () => {
    expect(maskSensitive('Remember the demo')).toBe('Remember the demo');
    expect(maskSensitive(null)).toBe('');
  });

  it('bounds what is rendered', () => {
    const big = bounded('x'.repeat(MAX_RESULT_CHARS * 3), MAX_RESULT_CHARS);
    expect(big.clipped).toBe(true);
    expect(big.text.length).toBe(MAX_RESULT_CHARS);
    expect(bounded('short', 10)).toEqual({ text: 'short', clipped: false });
  });

  it('formats durations', () => {
    expect(formatDuration(null)).toBe('');
    expect(formatDuration(250)).toBe('250 ms');
    expect(formatDuration(1500)).toBe('1.5 s');
    expect(formatDuration(65_000)).toBe('1 min 5 s');
  });
});
