import { AvatarTone, Id, IsoDateString, MemberRef, WorkStatus } from './common.model';

/** Human employee of an organization. */
export interface Employee {
  readonly id: Id;
  readonly organizationId: Id;
  readonly firstName: string;
  readonly lastName: string;
  readonly email: string;
  readonly departmentId: Id;
  readonly roleId: Id;
  readonly managerRef: MemberRef | null;
  readonly status: Extract<WorkStatus, 'online' | 'idle' | 'offline'>;
  readonly location: string;
  readonly joinedAt: IsoDateString;
  readonly avatarTone: AvatarTone;
}

/** Links an account to its employee record inside one organization. */
export interface Membership {
  readonly organizationId: Id;
  readonly employeeId: Id;
}

/** Authenticated account using the desktop application. */
export interface User {
  readonly id: Id;
  readonly email: string;
  readonly displayName: string;
  readonly title: string;
  readonly avatarTone: AvatarTone;
  readonly memberships: readonly Membership[];
}

export type UserProfileUpdate = Pick<User, 'displayName' | 'title'>;
