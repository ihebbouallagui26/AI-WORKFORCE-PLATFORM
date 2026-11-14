/** Core API DTOs for identity, organizations and access control (contracts/openapi/core-api.v1.json). */

export type PermissionCode =
  | 'organization.read'
  | 'organization.update'
  | 'member.read'
  | 'member.manage'
  | 'member.roles.assign'
  | 'role.read'
  | 'role.manage'
  | 'invitation.read'
  | 'invitation.create'
  | 'invitation.revoke'
  | 'audit.read'
  | 'ownership.manage'
  | 'employee.read'
  | 'employee.create'
  | 'employee.manage'
  | 'employee.deactivate'
  | 'department.read'
  | 'department.manage'
  | 'position.read'
  | 'position.manage'
  | 'team.read'
  | 'team.manage'
  | 'agent.read'
  | 'agent.create'
  | 'agent.manage'
  | 'agent.activate'
  | 'agent.archive'
  | 'agent.tool.manage'
  | 'provider.read'
  | 'provider.connect'
  | 'provider.manage'
  | 'provider.test'
  | 'skill.read'
  | 'skill.manage'
  | 'agent.execute'
  | 'agent.execution.read';

export type BuiltInRoleCode = 'OWNER' | 'ADMIN' | 'MEMBER';

export interface Page<T> {
  readonly content: readonly T[];
  readonly page: number;
  readonly size: number;
  readonly totalElements: number;
  readonly totalPages: number;
}

export interface UserProfileDto {
  readonly id: string;
  readonly email: string;
  readonly emailVerified: boolean;
  readonly displayName: string;
}

export interface OrganizationSummaryDto {
  readonly id: string;
  readonly name: string;
  readonly description: string;
  readonly timezone: string;
  readonly createdAt: string;
}

export interface RoleRefDto {
  readonly id: string;
  readonly code: BuiltInRoleCode | null;
  readonly name: string;
}

export interface OrganizationDto extends OrganizationSummaryDto {
  readonly updatedAt: string;
  readonly myMembershipId: string;
  readonly myRoles: readonly RoleRefDto[];
  readonly myPermissions: readonly PermissionCode[];
}

export interface OrganizationDraft {
  readonly name: string;
  readonly description?: string;
  readonly timezone?: string;
}

export type MembershipStatus = 'ACTIVE' | 'SUSPENDED' | 'REMOVED';

export interface MemberDto {
  readonly membershipId: string;
  readonly userId: string;
  readonly email: string | null;
  readonly displayName: string | null;
  readonly status: MembershipStatus;
  readonly joinedAt: string;
  readonly statusChangedAt: string;
  readonly roles: readonly RoleRefDto[];
}

export interface RoleDto {
  readonly id: string;
  readonly code: BuiltInRoleCode | null;
  readonly name: string;
  readonly description: string;
  readonly builtIn: boolean;
  readonly permissions: readonly PermissionCode[];
}

export interface RoleDraft {
  readonly name: string;
  readonly description: string;
  readonly permissions: readonly PermissionCode[];
}

export interface PermissionDefinitionDto {
  readonly code: PermissionCode;
  readonly description: string;
}

export interface InvitationDto {
  readonly id: string;
  readonly email: string;
  readonly roleId: string;
  readonly status: 'PENDING' | 'ACCEPTED' | 'REVOKED';
  readonly expiresAt: string;
  readonly invitedBy: string;
  readonly createdAt: string;
}

export interface InvitationAcceptanceDto {
  readonly organizationId: string;
  readonly organizationName: string;
  readonly membershipId: string;
}
