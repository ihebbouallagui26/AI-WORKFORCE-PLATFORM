import { inject, Injectable } from '@angular/core';
import { BackendClient } from './backend-client';
import {
  InvitationAcceptanceDto,
  InvitationDto,
  MemberDto,
  OrganizationDraft,
  OrganizationDto,
  OrganizationSummaryDto,
  Page,
  PermissionDefinitionDto,
  RoleDraft,
  RoleDto,
  UserProfileDto,
} from './identity.models';

/**
 * Typed client of the identity and access endpoints of the Core API. Organization ids passed
 * here only select an organization: the API checks membership and permissions on every call.
 */
@Injectable({ providedIn: 'root' })
export class IdentityApi {
  private readonly http = inject(BackendClient);

  me(): Promise<UserProfileDto> {
    return this.http.get('/api/v1/me');
  }

  updateMe(displayName: string): Promise<UserProfileDto> {
    return this.http.patch('/api/v1/me', { displayName });
  }

  listOrganizations(page = 0, size = 100): Promise<Page<OrganizationSummaryDto>> {
    return this.http.get('/api/v1/organizations', { page, size });
  }

  createOrganization(draft: OrganizationDraft): Promise<OrganizationDto> {
    return this.http.post('/api/v1/organizations', draft);
  }

  getOrganization(organizationId: string): Promise<OrganizationDto> {
    return this.http.get(org(organizationId));
  }

  updateOrganization(organizationId: string, update: Partial<OrganizationDraft>): Promise<OrganizationDto> {
    return this.http.patch(org(organizationId), update);
  }

  listMembers(organizationId: string, page = 0, size = 100, includeSuspended = true): Promise<Page<MemberDto>> {
    return this.http.get(`${org(organizationId)}/members`, { page, size, includeSuspended });
  }

  suspendMember(organizationId: string, membershipId: string): Promise<MemberDto> {
    return this.http.post(`${org(organizationId)}/members/${id(membershipId)}/suspend`);
  }

  reactivateMember(organizationId: string, membershipId: string): Promise<MemberDto> {
    return this.http.post(`${org(organizationId)}/members/${id(membershipId)}/reactivate`);
  }

  removeMember(organizationId: string, membershipId: string): Promise<void> {
    return this.http.delete(`${org(organizationId)}/members/${id(membershipId)}`);
  }

  setMemberRoles(organizationId: string, membershipId: string, roleIds: readonly string[]): Promise<MemberDto> {
    return this.http.put(`${org(organizationId)}/members/${id(membershipId)}/roles`, { roleIds });
  }

  listRoles(organizationId: string): Promise<RoleDto[]> {
    return this.http.get(`${org(organizationId)}/roles`);
  }

  createRole(organizationId: string, draft: RoleDraft): Promise<RoleDto> {
    return this.http.post(`${org(organizationId)}/roles`, draft);
  }

  updateRole(organizationId: string, roleId: string, draft: Partial<RoleDraft>): Promise<RoleDto> {
    return this.http.patch(`${org(organizationId)}/roles/${id(roleId)}`, draft);
  }

  deleteRole(organizationId: string, roleId: string): Promise<void> {
    return this.http.delete(`${org(organizationId)}/roles/${id(roleId)}`);
  }

  listPermissions(): Promise<PermissionDefinitionDto[]> {
    return this.http.get('/api/v1/permissions');
  }

  listInvitations(organizationId: string, page = 0, size = 100): Promise<Page<InvitationDto>> {
    return this.http.get(`${org(organizationId)}/invitations`, { page, size });
  }

  createInvitation(organizationId: string, email: string, roleId: string): Promise<InvitationDto> {
    return this.http.post(`${org(organizationId)}/invitations`, { email, roleId });
  }

  revokeInvitation(organizationId: string, invitationId: string): Promise<void> {
    return this.http.delete(`${org(organizationId)}/invitations/${id(invitationId)}`);
  }

  acceptInvitation(token: string): Promise<InvitationAcceptanceDto> {
    return this.http.post('/api/v1/invitations/accept', { token });
  }
}

const UUID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

/** Path segments are validated: an id can never inject another path. */
function id(value: string): string {
  if (!UUID.test(value)) {
    throw new Error('Invalid identifier');
  }
  return value;
}

function org(organizationId: string): string {
  return `/api/v1/organizations/${id(organizationId)}`;
}
