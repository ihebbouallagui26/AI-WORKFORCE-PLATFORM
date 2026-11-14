import { AgentStatus, ConnectionStatus, ProviderType, ToolLevel } from '../../core/agents/agents.models';
import { Tone } from '../../shared/utils/presentation';

export const AGENT_STATUS_LABEL: Readonly<Record<AgentStatus, string>> = {
  DRAFT: 'Draft',
  ACTIVE: 'Active',
  PAUSED: 'Paused',
  ARCHIVED: 'Archived',
};

export const AGENT_STATUS_TONE: Readonly<Record<AgentStatus, Tone>> = {
  DRAFT: 'neutral',
  ACTIVE: 'success',
  PAUSED: 'warning',
  ARCHIVED: 'neutral',
};

export const CONNECTION_STATUS_LABEL: Readonly<Record<ConnectionStatus, string>> = {
  PENDING: 'Not validated',
  VALID: 'Valid',
  INVALID: 'Invalid',
  REVOKED: 'Revoked',
};

export const CONNECTION_STATUS_TONE: Readonly<Record<ConnectionStatus, Tone>> = {
  PENDING: 'warning',
  VALID: 'success',
  INVALID: 'danger',
  REVOKED: 'neutral',
};

export const PROVIDER_LABEL: Readonly<Record<ProviderType, string>> = {
  ANTHROPIC: 'Anthropic Claude',
  OPENAI: 'OpenAI',
  GEMINI: 'Google Gemini',
};

export const TOOL_LEVELS: readonly ToolLevel[] = ['READ', 'WRITE', 'EXECUTE'];

/** Whether `level` is allowed by a tool whose highest grantable level is `max`. */
export function levelAllowed(level: ToolLevel, max: ToolLevel): boolean {
  return TOOL_LEVELS.indexOf(level) <= TOOL_LEVELS.indexOf(max);
}

/** Initials shown in an agent avatar. */
export function initials(name: string): string {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]!.toUpperCase())
    .join('');
}
