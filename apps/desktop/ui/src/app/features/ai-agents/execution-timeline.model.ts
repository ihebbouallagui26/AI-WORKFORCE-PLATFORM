import { AgentExecution, InvocationStatus, ToolInvocation, ToolPolicy } from '../../core/agents/agents.models';
import { Tone } from '../../shared/utils/presentation';

/**
 * Presentation logic of the execution timeline. It is built ONLY from what the Core API really records:
 * the execution (created / started / completed) and its tool calls (requested / approval / started / completed).
 * Nothing here pretends to observe the model's reasoning, and no event is invented: for example the platform
 * does not timestamp "execution resumed", so that moment is not shown.
 */

export type TimelineKind = 'execution' | 'tool' | 'approval';

export interface TimelineEntry {
  readonly key: string;
  /** ISO instant used for ordering and display. */
  readonly at: string;
  readonly kind: TimelineKind;
  readonly tone: Tone;
  readonly title: string;
  readonly detail: string | null;
  readonly status: string | null;
  readonly toolName: string | null;
  readonly durationMs: number | null;
  readonly error: string | null;
}

export const POLICY_LABEL: Readonly<Record<ToolPolicy, string>> = {
  READ_ONLY: 'Runs automatically',
  APPROVAL_REQUIRED: 'Needs approval',
  DENIED: 'Not allowed',
};

export const INVOCATION_LABEL: Readonly<Record<InvocationStatus, string>> = {
  DENIED: 'Denied',
  AWAITING_APPROVAL: 'Awaiting approval',
  APPROVED: 'Approved',
  RUNNING: 'Running',
  SUCCEEDED: 'Succeeded',
  FAILED: 'Failed',
  REJECTED: 'Rejected',
  EXPIRED: 'Expired',
};

export const INVOCATION_TONE: Readonly<Record<InvocationStatus, Tone>> = {
  DENIED: 'danger',
  AWAITING_APPROVAL: 'warning',
  APPROVED: 'info',
  RUNNING: 'info',
  SUCCEEDED: 'success',
  FAILED: 'danger',
  REJECTED: 'danger',
  EXPIRED: 'neutral',
};

/** Display limits: the platform already bounds what it stores, this bounds what is rendered. */
export const MAX_ARGUMENT_CHARS = 2_000;
export const MAX_RESULT_CHARS = 4_000;

const SENSITIVE_PATTERNS: readonly [RegExp, string][] = [
  [/\bBearer\s+[A-Za-z0-9._~+/=-]{8,}/gi, 'Bearer [REDACTED]'],
  [/\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]{4,}/g, '[REDACTED]'],
  [/\bsk-[A-Za-z0-9_-]{8,}/g, '[REDACTED]'],
  [/(["']?(?:api[_-]?key|secret|password|passwd|token|authorization|credential)["']?\s*[:=]\s*)(?:"[^"]*"|'[^']*'|[^\s,}]+)/gi, '$1"[REDACTED]"'],
];

/**
 * Defense in depth. The server already redacts arguments and never stores credentials in a tool call; this masks
 * anything that still looks like a credential before it reaches the screen.
 */
export function maskSensitive(text: string | null | undefined): string {
  let masked = text ?? '';
  for (const [pattern, replacement] of SENSITIVE_PATTERNS) {
    masked = masked.replace(pattern, replacement);
  }
  return masked;
}

export interface BoundedText {
  readonly text: string;
  /** True when this display cut the value (separate from the platform's own truncation flag). */
  readonly clipped: boolean;
}

export function bounded(text: string | null | undefined, max: number): BoundedText {
  const masked = maskSensitive(text);
  return masked.length > max ? { text: masked.slice(0, max), clipped: true } : { text: masked, clipped: false };
}

function time(iso: string | null): number | null {
  if (!iso) {
    return null;
  }
  const value = Date.parse(iso);
  return Number.isNaN(value) ? null : value;
}

export function durationMs(from: string | null, to: string | null): number | null {
  const start = time(from);
  const end = time(to);
  return start === null || end === null || end < start ? null : end - start;
}

export function formatDuration(ms: number | null): string {
  if (ms === null) {
    return '';
  }
  if (ms < 1000) {
    return `${ms} ms`;
  }
  const seconds = ms / 1000;
  return seconds < 60 ? `${seconds.toFixed(seconds < 10 ? 1 : 0)} s` : `${Math.floor(seconds / 60)} min ${Math.round(seconds % 60)} s`;
}

function safeError(code: string | null, message: string | null): string | null {
  const text = maskSensitive(message ?? '').trim();
  if (!text && !code) {
    return null;
  }
  return text ? text.slice(0, 300) : `Error ${code}`;
}

function entry(partial: Omit<TimelineEntry, 'detail' | 'status' | 'toolName' | 'durationMs' | 'error'> & Partial<TimelineEntry>): TimelineEntry {
  return { detail: null, status: null, toolName: null, durationMs: null, error: null, ...partial };
}

function toolEntries(call: ToolInvocation): TimelineEntry[] {
  const name = call.toolName ?? call.toolCode;
  const out: TimelineEntry[] = [
    entry({
      key: `${call.id}:requested`, at: call.requestedAt, kind: 'tool', tone: 'neutral', title: `Tool requested: ${name}`,
      detail: POLICY_LABEL[call.policy], toolName: name,
    }),
  ];
  if (call.status === 'DENIED') {
    out.push(entry({
      key: `${call.id}:denied`, at: call.completedAt ?? call.requestedAt, kind: 'tool', tone: 'danger', title: `Tool call denied: ${name}`,
      status: INVOCATION_LABEL.DENIED, toolName: name, error: safeError(call.errorCode, call.errorMessage),
    }));
    return out;
  }
  const approval = call.approval;
  if (approval) {
    out.push(entry({
      key: `${call.id}:approval-requested`, at: approval.requestedAt, kind: 'approval', tone: 'warning', title: `Approval requested: ${name}`,
      detail: 'A person must review this exact action.', status: approval.status === 'PENDING' ? 'Pending decision' : null, toolName: name,
    }));
    if (approval.status === 'APPROVED' && approval.decidedAt) {
      out.push(entry({ key: `${call.id}:approved`, at: approval.decidedAt, kind: 'approval', tone: 'success', title: `Approval granted: ${name}`, status: 'Approved', toolName: name }));
    } else if (approval.status === 'REJECTED' && approval.decidedAt) {
      out.push(entry({
        key: `${call.id}:rejected`, at: approval.decidedAt, kind: 'approval', tone: 'danger', title: `Approval rejected: ${name}`, status: 'Rejected',
        detail: approval.rejectionReason ? `Reason: ${maskSensitive(approval.rejectionReason)}` : null, toolName: name,
      }));
    } else if (approval.status === 'EXPIRED') {
      out.push(entry({ key: `${call.id}:expired`, at: approval.expiresAt, kind: 'approval', tone: 'neutral', title: `Approval expired: ${name}`, status: 'Expired', toolName: name }));
    } else if (approval.status === 'CANCELLED') {
      out.push(entry({
        key: `${call.id}:cancelled`, at: call.completedAt ?? approval.expiresAt, kind: 'approval', tone: 'neutral', title: `Approval cancelled: ${name}`,
        status: 'Cancelled', detail: 'The execution ended before a decision.', toolName: name,
      }));
    }
  }
  if (call.startedAt) {
    out.push(entry({ key: `${call.id}:started`, at: call.startedAt, kind: 'tool', tone: 'info', title: `Tool started: ${name}`, status: 'Running', toolName: name }));
  }
  if (call.completedAt && call.status === 'SUCCEEDED') {
    out.push(entry({
      key: `${call.id}:completed`, at: call.completedAt, kind: 'tool', tone: 'success', title: `Tool completed: ${name}`, status: 'Succeeded', toolName: name,
      durationMs: durationMs(call.startedAt, call.completedAt),
    }));
  } else if (call.completedAt && call.status === 'FAILED') {
    out.push(entry({
      key: `${call.id}:failed`, at: call.completedAt, kind: 'tool', tone: 'danger', title: `Tool failed: ${name}`, status: 'Failed', toolName: name,
      durationMs: durationMs(call.startedAt, call.completedAt), error: safeError(call.errorCode, call.errorMessage),
    }));
  }
  return out;
}

/** Chronological entries (stable for equal instants: the order in which the facts are derived is kept). */
export function buildTimeline(run: AgentExecution, calls: readonly ToolInvocation[]): TimelineEntry[] {
  const entries: TimelineEntry[] = [
    entry({ key: `${run.id}:created`, at: run.createdAt, kind: 'execution', tone: 'neutral', title: 'Execution created' }),
  ];
  if (run.startedAt) {
    entries.push(entry({ key: `${run.id}:started`, at: run.startedAt, kind: 'execution', tone: 'info', title: 'Execution started' }));
  }
  for (const call of [...calls].sort((a, b) => a.sequence - b.sequence)) {
    entries.push(...toolEntries(call));
  }
  if (run.status === 'SUCCEEDED') {
    entries.push(entry({
      key: `${run.id}:completed`, at: run.completedAt ?? run.createdAt, kind: 'execution', tone: 'success', title: 'Execution completed', status: 'Succeeded',
      durationMs: durationMs(run.startedAt ?? run.createdAt, run.completedAt),
    }));
  } else if (run.status === 'FAILED') {
    const timedOut = run.errorCode === 'TIMEOUT';
    entries.push(entry({
      key: `${run.id}:failed`, at: run.completedAt ?? run.createdAt, kind: 'execution', tone: 'danger', title: timedOut ? 'Execution timed out' : 'Execution failed',
      status: timedOut ? 'Timed out' : 'Failed', error: safeError(run.errorCode, run.errorMessage),
      durationMs: durationMs(run.startedAt ?? run.createdAt, run.completedAt),
    }));
  }
  return entries
    .map((item, index) => ({ item, index, when: time(item.at) }))
    .sort((a, b) => (a.when ?? Number.MAX_SAFE_INTEGER) - (b.when ?? Number.MAX_SAFE_INTEGER) || a.index - b.index)
    .map(({ item }) => item);
}
