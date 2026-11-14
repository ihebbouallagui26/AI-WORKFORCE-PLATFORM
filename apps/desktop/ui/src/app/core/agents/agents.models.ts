/** Core API DTOs of the AI agent domain (contracts/openapi/core-api.v1.json). */
import type { DepartmentRefDto, Page } from '../workforce/workforce.models';

export type { Page };

export type AgentStatus = 'DRAFT' | 'ACTIVE' | 'PAUSED' | 'ARCHIVED';
export type ProviderType = 'ANTHROPIC' | 'OPENAI' | 'GEMINI';
export type ConnectionStatus = 'PENDING' | 'VALID' | 'INVALID' | 'REVOKED';
export type BindingRole = 'PRIMARY' | 'FALLBACK';
export type ToolLevel = 'READ' | 'WRITE' | 'EXECUTE';
export type ToolRisk = 'LOW' | 'MEDIUM' | 'HIGH';
export type MemoryScope = 'PROJECT' | 'ORGANIZATION';
export type MemoryAccess = 'AGENT_ONLY' | 'TEAM_MEMBERS' | 'ORGANIZATION_MEMBERS';

export interface AgentSummary {
  readonly id: string;
  readonly handle: string;
  readonly name: string;
  readonly avatarTone: number;
  readonly title: string;
  readonly description: string;
  readonly department: DepartmentRefDto | null;
  readonly responsibilities: readonly string[];
  readonly status: AgentStatus;
  readonly configVersion: number;
  readonly skillCount: number;
  readonly createdAt: string;
  readonly updatedAt: string;
  readonly version: number;
}

/** Safe reference to a connection: never any credential material. */
export interface ConnectionRef {
  readonly id: string;
  readonly providerType: ProviderType;
  readonly displayName: string;
  readonly status: ConnectionStatus;
}

export interface AgentBinding {
  readonly id: string;
  readonly connection: ConnectionRef;
  readonly modelId: string;
  readonly role: BindingRole;
  readonly priority: number;
  readonly config: string;
}

export interface AgentSkillRef {
  readonly id: string;
  readonly name: string;
  readonly category: string;
  readonly active: boolean;
}

export interface AgentToolGrant {
  readonly toolCode: string;
  readonly toolName: string;
  readonly risk: ToolRisk;
  readonly maxLevel: ToolLevel;
  readonly level: ToolLevel;
  readonly approvalRequired: boolean;
}

export interface ToolDefinition {
  readonly code: string;
  readonly name: string;
  readonly description: string;
  readonly category: string;
  readonly risk: ToolRisk;
  readonly maxLevel: ToolLevel;
  readonly mcpServer: string | null;
  readonly mcpTool: string | null;
  readonly enabled: boolean;
}

export interface AgentMemoryConfig {
  readonly enabled: boolean;
  readonly scope: MemoryScope;
  readonly accessMode: MemoryAccess;
  readonly retentionDays: number | null;
  readonly updatedAt: string | null;
}

export interface TeamReference {
  readonly id: string;
  readonly name: string;
  readonly status: 'ACTIVE' | 'ARCHIVED';
}

/** An agent with its configuration. `systemInstructions` is null unless the caller may manage agents. */
export interface AgentDetail {
  readonly agent: AgentSummary;
  readonly systemInstructions: string | null;
  readonly providers: readonly AgentBinding[];
  readonly skills: readonly AgentSkillRef[];
  readonly tools: readonly AgentToolGrant[];
  readonly memory: AgentMemoryConfig;
  readonly teams: readonly TeamReference[];
}

export interface AgentDraft {
  readonly name: string;
  readonly handle?: string;
  readonly avatarTone?: number;
  readonly title?: string;
  readonly description?: string;
  readonly departmentId?: string | null;
  readonly responsibilities?: readonly string[];
  readonly systemInstructions?: string;
}

export interface AgentUpdate {
  readonly name?: string;
  readonly avatarTone?: number;
  readonly title?: string;
  readonly description?: string;
  readonly responsibilities?: readonly string[];
  readonly systemInstructions?: string;
  readonly expectedVersion?: number;
}

export interface AgentQuery {
  readonly q?: string;
  readonly status?: AgentStatus;
  readonly departmentId?: string;
  readonly page?: number;
  readonly size?: number;
}

export interface HistoryEntry {
  readonly id: string;
  readonly actorUserId: string | null;
  readonly action: string;
  readonly details: string;
  readonly occurredAt: string;
}

export interface BindingOptions {
  readonly temperature?: number;
  readonly maxOutputTokens?: number;
  readonly topP?: number;
}

export interface BindingDraft {
  readonly connectionId: string;
  readonly modelId: string;
  readonly role: BindingRole;
  readonly priority?: number;
  readonly options?: BindingOptions;
}

export interface ToolGrantInput {
  readonly toolCode: string;
  readonly level: ToolLevel;
  readonly approvalRequired: boolean;
}

export interface Skill {
  readonly id: string;
  readonly name: string;
  readonly category: string;
  readonly description: string;
  readonly active: boolean;
  readonly createdAt: string;
  readonly updatedAt: string;
  readonly version: number;
}

export interface TeamAgent {
  readonly agent: AgentSummary;
  readonly joinedAt: string;
  readonly leftAt: string | null;
}

/** A provider connection: safe metadata only (there is no credential field, ever). */
export interface ProviderConnection {
  readonly id: string;
  readonly providerType: ProviderType;
  readonly providerName: string;
  readonly displayName: string;
  readonly accountIdentifier: string | null;
  readonly status: ConnectionStatus;
  readonly statusDetail: string | null;
  readonly lastTestedAt: string | null;
  readonly createdAt: string;
  readonly updatedAt: string;
  readonly version: number;
}

export interface ProviderTypeInfo {
  readonly type: ProviderType;
  readonly displayName: string;
}

export interface ConnectionTestResult {
  readonly connection: ProviderConnection;
  readonly outcome: 'VALID' | 'INVALID' | 'UNAVAILABLE';
  readonly message: string;
}

export interface ProviderModel {
  readonly id: string;
  readonly displayName: string;
}
