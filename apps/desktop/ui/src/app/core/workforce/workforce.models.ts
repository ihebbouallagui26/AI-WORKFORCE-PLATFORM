/** Core API DTOs of the workforce domain (contracts/openapi/core-api.v1.json). */
import type { MembershipStatus, Page } from '../identity/identity.models';

export type { Page };

export type ArchiveStatus = 'ACTIVE' | 'ARCHIVED';
export type EmploymentStatus = 'ACTIVE' | 'ON_LEAVE' | 'INACTIVE';

export interface EmployeeRefDto {
  readonly id: string;
  readonly displayName: string;
  readonly status: EmploymentStatus;
}

export interface DepartmentRefDto {
  readonly id: string;
  readonly name: string;
  readonly code: string;
  readonly status: ArchiveStatus;
}

export interface PositionRefDto {
  readonly id: string;
  readonly title: string;
  readonly code: string;
  readonly status: ArchiveStatus;
}

export interface DepartmentDto {
  readonly id: string;
  readonly name: string;
  readonly code: string;
  readonly description: string;
  readonly parentId: string | null;
  readonly head: EmployeeRefDto | null;
  readonly status: ArchiveStatus;
  readonly employeeCount: number;
  readonly childCount: number;
  readonly createdAt: string;
  readonly updatedAt: string;
  readonly version: number;
}

export interface DepartmentTreeNodeDto {
  readonly department: DepartmentDto;
  readonly children: readonly DepartmentTreeNodeDto[];
}

/** A department placed in the hierarchy (used by lists and selects). */
export interface DepartmentRow {
  readonly department: DepartmentDto;
  readonly depth: number;
}

export interface DepartmentDraft {
  readonly name: string;
  readonly code: string;
  readonly description?: string;
  readonly parentId?: string | null;
  readonly headEmployeeId?: string | null;
}

export interface PositionDto {
  readonly id: string;
  readonly title: string;
  readonly code: string;
  readonly description: string;
  readonly department: DepartmentRefDto | null;
  readonly status: ArchiveStatus;
  readonly employeeCount: number;
  readonly createdAt: string;
  readonly updatedAt: string;
  readonly version: number;
}

export interface PositionDraft {
  readonly title: string;
  readonly code: string;
  readonly description?: string;
  readonly departmentId?: string | null;
}

export interface EmployeeDto {
  readonly id: string;
  readonly membershipId: string | null;
  readonly membershipStatus: MembershipStatus | null;
  readonly employeeNumber: string | null;
  readonly displayName: string;
  readonly professionalEmail: string | null;
  readonly position: PositionRefDto | null;
  readonly department: DepartmentRefDto | null;
  readonly manager: EmployeeRefDto | null;
  readonly status: EmploymentStatus;
  readonly startDate: string;
  readonly endDate: string | null;
  readonly description: string;
  readonly directReportCount: number;
  readonly createdAt: string;
  readonly updatedAt: string;
  readonly version: number;
}

export interface EmployeeDraft {
  readonly displayName: string;
  readonly employeeNumber?: string;
  readonly professionalEmail?: string;
  readonly membershipId?: string | null;
  readonly positionId?: string | null;
  readonly departmentId?: string | null;
  readonly managerId?: string | null;
  readonly startDate?: string | null;
  readonly description?: string;
}

export interface EmployeeUpdate {
  readonly displayName?: string;
  readonly employeeNumber?: string;
  readonly professionalEmail?: string;
  readonly startDate?: string;
  readonly description?: string;
  readonly expectedVersion?: number;
}

export interface EmployeeQuery {
  readonly q?: string;
  readonly status?: EmploymentStatus;
  readonly departmentId?: string;
  readonly positionId?: string;
  readonly managerId?: string;
  readonly page?: number;
  readonly size?: number;
}

export interface TeamDto {
  readonly id: string;
  readonly name: string;
  readonly description: string;
  readonly department: DepartmentRefDto | null;
  readonly leader: EmployeeRefDto | null;
  readonly status: ArchiveStatus;
  readonly memberCount: number;
  readonly createdAt: string;
  readonly updatedAt: string;
  readonly version: number;
}

export interface TeamMemberDto {
  readonly employee: EmployeeRefDto;
  readonly joinedAt: string;
  readonly leftAt: string | null;
  readonly leader: boolean;
}

export interface TeamDetailDto {
  readonly team: TeamDto;
  readonly members: readonly TeamMemberDto[];
}

export interface TeamDraft {
  readonly name: string;
  readonly description?: string;
  readonly departmentId?: string | null;
  readonly leaderEmployeeId?: string | null;
}
