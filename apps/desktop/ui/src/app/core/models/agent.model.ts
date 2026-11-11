import { AvatarTone, Id, IsoDateString, WorkStatus } from './common.model';

export type AgentStatus = WorkStatus;

export type PermissionLevel = 'read-only' | 'contributor' | 'maintainer' | 'admin';

export type AutonomyLevel = 'suggest-only' | 'supervised' | 'semi-autonomous';

/** Operations that always require an explicit human decision when listed. */
export type SensitiveOperation =
  | 'code-deployment'
  | 'external-communication'
  | 'data-deletion'
  | 'financial-transaction'
  | 'access-change'
  | 'document-publication';

export interface AutonomyConfig {
  readonly level: AutonomyLevel;
  readonly requireApprovalFor: readonly SensitiveOperation[];
  readonly maxConcurrentTasks: number;
}

export type ProviderDeployment = 'cloud' | 'local';

/**
 * Connection state as reported by the backend.
 * The desktop client never stores provider credentials; it only displays this state.
 */
export type ProviderConnectionStatus = 'not-configured' | 'mock' | 'connected' | 'error';

export interface AIProvider {
  readonly id: Id;
  readonly name: string;
  readonly vendor: string;
  readonly deployment: ProviderDeployment;
  readonly connectionStatus: ProviderConnectionStatus;
  readonly description: string;
}

export type ModelCapability = 'reasoning' | 'coding' | 'vision' | 'long-context' | 'tool-use' | 'fast';

export interface AIModel {
  readonly id: Id;
  readonly providerId: Id;
  readonly name: string;
  readonly contextWindowTokens: number;
  readonly capabilities: readonly ModelCapability[];
}

export type ProviderBindingPriority = 'primary' | 'fallback';

/** Links an agent to a provider/model pair. Swappable without touching the identity. */
export interface AgentProviderBinding {
  readonly providerId: Id;
  readonly modelId: Id;
  readonly priority: ProviderBindingPriority;
}

/**
 * Professional identity of an AI employee.
 * Deliberately independent from any AI provider so the underlying model can change.
 */
export interface AgentIdentity {
  readonly fullName: string;
  readonly departmentId: Id;
  readonly roleId: Id;
  readonly roleDescription: string;
  readonly responsibilities: readonly string[];
  readonly skills: readonly string[];
}

export interface Agent {
  readonly id: Id;
  readonly organizationId: Id;
  readonly identity: AgentIdentity;
  readonly providers: readonly AgentProviderBinding[];
  readonly systemInstructions: string;
  readonly permissionLevel: PermissionLevel;
  readonly autonomy: AutonomyConfig;
  readonly status: AgentStatus;
  readonly assignedProjectIds: readonly Id[];
  readonly lastActiveAt: IsoDateString | null;
  readonly createdAt: IsoDateString;
  readonly avatarTone: AvatarTone;
}

export interface AgentDraft {
  readonly identity: AgentIdentity;
  readonly providers: readonly AgentProviderBinding[];
  readonly systemInstructions: string;
  readonly permissionLevel: PermissionLevel;
  readonly autonomy: AutonomyConfig;
  readonly assignedProjectIds: readonly Id[];
}
