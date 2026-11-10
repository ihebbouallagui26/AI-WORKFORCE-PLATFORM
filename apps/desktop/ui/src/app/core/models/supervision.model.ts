import { SensitiveOperation } from './agent.model';
import { Id, IsoDateString, MemberRef } from './common.model';

export type RiskLevel = 'low' | 'medium' | 'high' | 'critical';

export type ApprovalStatus = 'pending' | 'approved' | 'rejected';

/** An operation an agent cannot perform without a human decision. */
export interface Approval {
  readonly id: Id;
  readonly organizationId: Id;
  readonly agentId: Id;
  readonly action: string;
  readonly operation: SensitiveOperation;
  readonly description: string;
  readonly projectId: Id | null;
  readonly requestedAt: IsoDateString;
  readonly risk: RiskLevel;
  readonly status: ApprovalStatus;
  readonly decidedAt: IsoDateString | null;
  readonly decidedByUserId: Id | null;
  readonly decisionNote: string | null;
}

export interface ApprovalDecision {
  readonly approvalId: Id;
  readonly decision: Exclude<ApprovalStatus, 'pending'>;
  readonly note: string | null;
  readonly decidedByUserId: Id;
}

export type ExecutionStatus = 'queued' | 'running' | 'succeeded' | 'failed' | 'cancelled' | 'awaiting-approval';

/** Record of an agent run, as reported by the AI orchestration engine. */
export interface AgentExecution {
  readonly id: Id;
  readonly organizationId: Id;
  readonly agentId: Id;
  readonly taskId: Id | null;
  readonly projectId: Id | null;
  readonly title: string;
  readonly status: ExecutionStatus;
  readonly startedAt: IsoDateString;
  readonly finishedAt: IsoDateString | null;
  readonly resultSummary: string;
}

export type ActivityEventType =
  | 'task-started'
  | 'task-completed'
  | 'approval-requested'
  | 'approval-decided'
  | 'task-failed'
  | 'report-generated'
  | 'project-updated';

export interface ActivityEvent {
  readonly id: Id;
  readonly organizationId: Id;
  readonly type: ActivityEventType;
  readonly actorRef: MemberRef;
  readonly title: string;
  readonly detail: string;
  readonly projectId: Id | null;
  readonly occurredAt: IsoDateString;
  readonly read: boolean;
}

export type ReportKind = 'status' | 'analysis' | 'quality' | 'financial' | 'research';

export interface Report {
  readonly id: Id;
  readonly organizationId: Id;
  readonly title: string;
  readonly kind: ReportKind;
  readonly authorRef: MemberRef;
  readonly projectId: Id | null;
  readonly summary: string;
  readonly createdAt: IsoDateString;
}

export interface Meeting {
  readonly id: Id;
  readonly organizationId: Id;
  readonly title: string;
  readonly startsAt: IsoDateString;
  readonly durationMinutes: number;
  readonly participantRefs: readonly MemberRef[];
  readonly projectId: Id | null;
}
