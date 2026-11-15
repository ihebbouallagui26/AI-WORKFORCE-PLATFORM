/** Core API DTOs of multi-agent workflows (Sprints C3.1 to C3.4; contracts/openapi/core-api.v1.json). */
import type { Page } from '../identity/identity.models';

export type { Page };

export type WorkflowMode = 'PIPELINE' | 'COORDINATED';
export type WorkflowDefinitionStatus = 'ACTIVE' | 'ARCHIVED';
export type FailurePolicy = 'FAIL_FAST' | 'CONTINUE' | 'RETRY';
export type RecoveryMode = 'NONE' | 'MANUAL';

/** The run states. WAITING_RECOVERY is a failed step held for a person; the others are terminal or in flight. */
export type RunStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'WAITING_APPROVAL'
  | 'WAITING_RECOVERY'
  | 'CANCEL_REQUESTED'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED'
  | 'TIMEOUT';

/** QUEUED = accepted and persisted, no child execution yet. RUNNING = the child exists (dispatched), not proof of work. */
export type StepStatus = 'QUEUED' | 'RUNNING' | 'WAITING_APPROVAL' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED';
export type StepRole = 'WORKER' | 'COORDINATOR';
export type LaunchReason = 'INITIAL' | 'AUTO_RETRY' | 'MANUAL_RETRY';

export type FailureCategory =
  | 'TRANSIENT_PROVIDER_FAILURE'
  | 'PERMANENT_PROVIDER_FAILURE'
  | 'TOOL_FAILURE'
  | 'OUTCOME_UNKNOWN'
  | 'AUTHORIZATION_FAILURE'
  | 'WORKFLOW_LIMIT_EXCEEDED'
  | 'UNCLASSIFIED';

export type MessageKind = 'OBJECTIVE' | 'ASSIGNMENT' | 'RESULT' | 'STATUS';
export type GroupStatus = 'QUEUED' | 'RUNNING' | 'WAITING_FOR_APPROVAL' | 'COMPLETED' | 'PARTIALLY_FAILED' | 'FAILED' | 'CANCELLED';

export interface WorkflowParticipant {
  readonly agentId: string;
  readonly agentName: string | null;
  readonly agentStatus: string | null;
  readonly coordinator: boolean;
  readonly addedBy: string;
  readonly addedAt: string;
}

export interface StepDefinition {
  readonly rank: number;
  readonly name: string;
  readonly agentId: string;
  readonly instruction: string;
  readonly inputsFrom: readonly number[];
  /** Steps with the same stage run in parallel; null = a stage of its own (sequential). */
  readonly stage: number | null;
}

export interface WorkflowDefinition {
  readonly id: string;
  readonly organizationId: string;
  readonly name: string;
  readonly description: string;
  readonly status: WorkflowDefinitionStatus;
  readonly mode: WorkflowMode;
  readonly coordinatorAgentId: string | null;
  readonly version: number;
  readonly participants: readonly WorkflowParticipant[];
  readonly steps: readonly StepDefinition[];
  readonly maxSteps: number;
  readonly maxRetriesPerStep: number;
  readonly tokenBudget: number;
  readonly deadlineSeconds: number;
  readonly failurePolicy: FailurePolicy;
  readonly recoveryMode: RecoveryMode;
  readonly stepTimeoutSeconds: number | null;
  readonly maxParallelWorkers: number;
  readonly createdBy: string;
  readonly createdAt: string;
  readonly updatedAt: string;
  readonly archivedAt: string | null;
}

export interface WorkflowRun {
  readonly id: string;
  readonly organizationId: string;
  readonly definitionId: string;
  readonly definitionVersion: number;
  readonly mode: WorkflowMode;
  readonly maxParallelWorkers: number;
  readonly status: RunStatus;
  readonly requestedBy: string;
  readonly objective: string;
  readonly totalSteps: number;
  readonly nextRank: number;
  readonly tokensUsed: number;
  readonly tokenBudget: number;
  readonly deadlineAt: string;
  readonly result: string | null;
  readonly errorCode: string | null;
  readonly errorMessage: string | null;
  readonly cancelRequestedAt: string | null;
  readonly createdAt: string;
  readonly startedAt: string | null;
  readonly completedAt: string | null;
}

/** One ATTEMPT of one step (a retry is another row with a higher `attempt`; earlier attempts are kept). */
export interface WorkflowStepView {
  readonly id: string;
  readonly runId: string;
  readonly rank: number;
  readonly attempt: number;
  readonly name: string;
  readonly agentId: string;
  readonly agentName: string | null;
  readonly role: StepRole;
  readonly executionId: string | null;
  readonly status: StepStatus;
  readonly inputsFrom: string;
  readonly resultSummary: string | null;
  readonly errorCode: string | null;
  readonly errorMessage: string | null;
  readonly createdAt: string;
  readonly startedAt: string | null;
  readonly completedAt: string | null;
  readonly launchReason: LaunchReason;
  readonly failureCategory: FailureCategory | null;
  readonly retrySafe: boolean | null;
  readonly nextAttemptAt: string | null;
  readonly deadlineAt: string | null;
  readonly groupNo: number | null;
}

export interface WorkflowMessage {
  readonly id: string;
  readonly sequence: number;
  readonly stepRank: number | null;
  readonly kind: MessageKind;
  readonly body: string;
  readonly createdAt: string;
}

/** A coordinator action (delegation or finish), accepted or rejected by the Core API, in the order received. */
export interface WorkflowAction {
  readonly id: string;
  readonly stepId: string;
  readonly executionId: string;
  readonly toolCallId: string;
  readonly action: 'workflow.delegate' | 'workflow.finish';
  readonly status: 'ACCEPTED' | 'REJECTED';
  readonly code: string;
  readonly message: string;
  readonly participantRef: string | null;
  readonly task: string | null;
  readonly inputRanks: string;
  readonly resultStepId: string | null;
  readonly createdAt: string;
}

export interface AttemptUsage {
  readonly rank: number;
  readonly attempt: number;
  readonly role: StepRole;
  readonly launchReason: LaunchReason;
  readonly status: StepStatus;
  readonly errorCode: string | null;
  readonly failureCategory: FailureCategory | null;
  readonly retrySafe: boolean | null;
  readonly executionId: string | null;
  readonly durationMs: number | null;
  readonly inputTokens: number;
  readonly outputTokens: number;
  readonly groupNo: number | null;
  readonly queueWaitMs: number | null;
}

export interface GroupSummary {
  readonly groupNo: number;
  readonly status: GroupStatus;
  readonly workers: number;
  readonly queued: number;
  readonly running: number;
  readonly waitingApproval: number;
  readonly succeeded: number;
  readonly failed: number;
  readonly cancelled: number;
  readonly firstDispatchedAt: string | null;
  readonly finishedAt: string | null;
  readonly peakConcurrency: number;
}

/** Operational view of a run, computed by the Core API from persisted records. Tokens are NOT money. */
export interface RunSummary {
  readonly runId: string;
  readonly mode: WorkflowMode;
  readonly status: RunStatus;
  readonly failurePolicy: FailurePolicy;
  readonly createdAt: string;
  readonly startedAt: string | null;
  readonly completedAt: string | null;
  readonly deadlineAt: string;
  readonly durationMs: number;
  readonly attempts: number;
  readonly automaticRetries: number;
  readonly manualRetries: number;
  readonly failedAttempts: number;
  readonly failuresByCategory: Readonly<Record<string, number>>;
  readonly inputTokens: number;
  readonly outputTokens: number;
  readonly coordinatorTokens: number;
  readonly workerTokens: number;
  readonly tokenBudget: number;
  readonly tokensRemaining: number;
  readonly nextRetryAt: string | null;
  readonly waitingRecovery: boolean;
  readonly errorCode: string | null;
  readonly attemptList: readonly AttemptUsage[];
  readonly maxParallelWorkers: number;
  readonly peakConcurrency: number;
  readonly reservedTokens: number;
  readonly groups: readonly GroupSummary[];
}

// ---- requests

export interface WorkflowCreate {
  readonly name: string;
  readonly description?: string;
  readonly mode: WorkflowMode;
  readonly maxSteps?: number;
  readonly maxRetriesPerStep?: number;
  readonly tokenBudget?: number;
  readonly deadlineSeconds?: number;
  readonly failurePolicy?: FailurePolicy;
  readonly recoveryMode?: RecoveryMode;
  readonly stepTimeoutSeconds?: number;
  readonly maxParallelWorkers?: number;
}

export type WorkflowUpdate = Partial<Omit<WorkflowCreate, 'mode'>>;

export interface StepInput {
  readonly name: string;
  readonly agentId: string;
  readonly instruction: string;
  readonly inputsFrom?: readonly number[];
  readonly stage?: number;
}

export const TERMINAL_RUN: readonly RunStatus[] = ['SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMEOUT'];

export function isTerminalRun(status: RunStatus): boolean {
  return TERMINAL_RUN.includes(status);
}

export function isFinalStep(status: StepStatus): boolean {
  return status === 'SUCCEEDED' || status === 'FAILED' || status === 'CANCELLED';
}
