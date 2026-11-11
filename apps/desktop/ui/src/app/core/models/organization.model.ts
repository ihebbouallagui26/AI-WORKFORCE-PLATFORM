import { Id, IsoDateString, MemberRef } from './common.model';

export type DeploymentMode = 'local' | 'cloud' | 'hybrid';
export type OrganizationPlan = 'starter' | 'business' | 'enterprise';

export interface Organization {
  readonly id: Id;
  readonly name: string;
  readonly slug: string;
  readonly industry: string;
  readonly description: string;
  readonly timezone: string;
  readonly plan: OrganizationPlan;
  readonly deployment: DeploymentMode;
  readonly createdAt: IsoDateString;
}

export type OrganizationUpdate = Pick<Organization, 'name' | 'industry' | 'description' | 'timezone'>;

export interface Department {
  readonly id: Id;
  readonly organizationId: Id;
  readonly name: string;
  readonly code: string;
  readonly description: string;
  /** Department head: a human or an AI agent. */
  readonly managerRef: MemberRef | null;
  /** Parent department for nested structures (null for top-level). */
  readonly parentId: Id | null;
  readonly createdAt: IsoDateString;
}

export interface DepartmentDraft {
  readonly name: string;
  readonly code: string;
  readonly description: string;
  readonly parentId: Id | null;
  readonly managerRef: MemberRef | null;
}

export type SeniorityLevel = 'junior' | 'mid' | 'senior' | 'lead' | 'head';

/** A position in the organization; can be held by humans or agents. */
export interface Role {
  readonly id: Id;
  readonly organizationId: Id;
  readonly departmentId: Id;
  readonly title: string;
  readonly level: SeniorityLevel;
  readonly description: string;
}

export interface Team {
  readonly id: Id;
  readonly organizationId: Id;
  readonly departmentId: Id;
  readonly name: string;
  readonly leadRef: MemberRef | null;
  readonly memberRefs: readonly MemberRef[];
}
