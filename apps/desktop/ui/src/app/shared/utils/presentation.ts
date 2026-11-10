import {
  ActivityEventType,
  ApprovalStatus,
  AutonomyLevel,
  ConversationKind,
  ExecutionStatus,
  PermissionLevel,
  ProjectCategory,
  ProjectStatus,
  ProviderConnectionStatus,
  ReportKind,
  RiskLevel,
  SeniorityLevel,
  SensitiveOperation,
  TaskPriority,
  TaskStatus,
  WorkStatus,
} from '../../core/models';
import { IconName } from '../components/icon/icon-registry';

/** Semantic colour of a badge or indicator, mapped to design tokens. */
export type Tone = 'neutral' | 'accent' | 'success' | 'warning' | 'danger' | 'info' | 'ai';

export interface Presentation {
  readonly label: string;
  readonly tone: Tone;
}

export const WORK_STATUS: Record<WorkStatus, Presentation> = {
  online: { label: 'Online', tone: 'success' },
  working: { label: 'Working', tone: 'accent' },
  idle: { label: 'Idle', tone: 'neutral' },
  offline: { label: 'Offline', tone: 'neutral' },
  'needs-approval': { label: 'Needs approval', tone: 'warning' },
  error: { label: 'Error', tone: 'danger' },
};

export const PROJECT_STATUS: Record<ProjectStatus, Presentation> = {
  planning: { label: 'Planning', tone: 'info' },
  active: { label: 'Active', tone: 'success' },
  'at-risk': { label: 'At risk', tone: 'warning' },
  'on-hold': { label: 'On hold', tone: 'neutral' },
  completed: { label: 'Completed', tone: 'accent' },
};

export const TASK_STATUS: Record<TaskStatus, Presentation> = {
  backlog: { label: 'Backlog', tone: 'neutral' },
  todo: { label: 'To Do', tone: 'info' },
  'in-progress': { label: 'In Progress', tone: 'accent' },
  'in-review': { label: 'In Review', tone: 'ai' },
  blocked: { label: 'Blocked', tone: 'danger' },
  done: { label: 'Done', tone: 'success' },
};

export const TASK_STATUS_ORDER: readonly TaskStatus[] = [
  'backlog',
  'todo',
  'in-progress',
  'in-review',
  'blocked',
  'done',
];

export const TASK_PRIORITY: Record<TaskPriority, Presentation> = {
  low: { label: 'Low', tone: 'neutral' },
  medium: { label: 'Medium', tone: 'info' },
  high: { label: 'High', tone: 'warning' },
  urgent: { label: 'Urgent', tone: 'danger' },
};

export const RISK_LEVEL: Record<RiskLevel, Presentation> = {
  low: { label: 'Low risk', tone: 'neutral' },
  medium: { label: 'Medium risk', tone: 'info' },
  high: { label: 'High risk', tone: 'warning' },
  critical: { label: 'Critical risk', tone: 'danger' },
};

export const APPROVAL_STATUS: Record<ApprovalStatus, Presentation> = {
  pending: { label: 'Pending', tone: 'warning' },
  approved: { label: 'Approved', tone: 'success' },
  rejected: { label: 'Rejected', tone: 'danger' },
};

export const EXECUTION_STATUS: Record<ExecutionStatus, Presentation> = {
  queued: { label: 'Queued', tone: 'neutral' },
  running: { label: 'Running', tone: 'accent' },
  succeeded: { label: 'Succeeded', tone: 'success' },
  failed: { label: 'Failed', tone: 'danger' },
  cancelled: { label: 'Cancelled', tone: 'neutral' },
  'awaiting-approval': { label: 'Awaiting approval', tone: 'warning' },
};

export const PROJECT_CATEGORY: Record<ProjectCategory, string> = {
  software: 'Software',
  design: 'Design',
  marketing: 'Marketing',
  research: 'Research',
  operations: 'Operations',
  'human-resources': 'Human Resources',
  finance: 'Finance',
};

export const PERMISSION_LEVEL: Record<PermissionLevel, { label: string; description: string }> = {
  'read-only': { label: 'Read only', description: 'Can read assigned resources, cannot change anything.' },
  contributor: { label: 'Contributor', description: 'Can create and update work items in assigned projects.' },
  maintainer: { label: 'Maintainer', description: 'Can manage tasks and members of assigned projects.' },
  admin: { label: 'Administrator', description: 'Full access to organization resources. Use sparingly.' },
};

export const AUTONOMY_LEVEL: Record<AutonomyLevel, { label: string; description: string }> = {
  'suggest-only': { label: 'Suggest only', description: 'Proposes actions; a human executes every step.' },
  supervised: { label: 'Supervised', description: 'Executes routine work, asks approval for sensitive operations.' },
  'semi-autonomous': {
    label: 'Semi-autonomous',
    description: 'Executes most work; sensitive operations still require approval.',
  },
};

export const SENSITIVE_OPERATION: Record<SensitiveOperation, string> = {
  'code-deployment': 'Code deployment',
  'external-communication': 'External communication',
  'data-deletion': 'Data deletion',
  'financial-transaction': 'Financial transaction',
  'access-change': 'Access change',
  'document-publication': 'Document publication',
};

export const ACTIVITY_EVENT: Record<ActivityEventType, { icon: IconName; tone: Tone; label: string }> = {
  'task-started': { icon: 'zap', tone: 'accent', label: 'Task started' },
  'task-completed': { icon: 'circle-check', tone: 'success', label: 'Task completed' },
  'approval-requested': { icon: 'shield-alert', tone: 'warning', label: 'Approval requested' },
  'approval-decided': { icon: 'shield-check', tone: 'neutral', label: 'Approval decided' },
  'task-failed': { icon: 'circle-x', tone: 'danger', label: 'Task failed' },
  'report-generated': { icon: 'file', tone: 'info', label: 'Report generated' },
  'project-updated': { icon: 'projects', tone: 'neutral', label: 'Project updated' },
};

export const CONVERSATION_KIND: Record<ConversationKind, { label: string; icon: IconName }> = {
  direct: { label: 'Direct messages', icon: 'user' },
  group: { label: 'Groups', icon: 'users' },
  project: { label: 'Project channels', icon: 'hash' },
  department: { label: 'Department channels', icon: 'building' },
};

export const REPORT_KIND: Record<ReportKind, string> = {
  status: 'Status',
  analysis: 'Analysis',
  quality: 'Quality',
  financial: 'Financial',
  research: 'Research',
};

export const PROVIDER_CONNECTION: Record<ProviderConnectionStatus, Presentation> = {
  'not-configured': { label: 'Not configured', tone: 'neutral' },
  mock: { label: 'Mock', tone: 'info' },
  connected: { label: 'Connected', tone: 'success' },
  error: { label: 'Error', tone: 'danger' },
};

export const SENIORITY: Record<SeniorityLevel, string> = {
  junior: 'Junior',
  mid: 'Mid-level',
  senior: 'Senior',
  lead: 'Lead',
  head: 'Head',
};
