import { computed, inject, Injectable } from '@angular/core';
import { AvatarTone, Id, MemberKind, MemberRef, memberKey, WorkStatus } from '../models';
import { AgentStore } from './agent.store';
import { OrganizationStore } from './organization.store';

/** Display-ready view of any workforce member, human or AI. */
export interface MemberProfile {
  readonly ref: MemberRef;
  readonly key: string;
  readonly kind: MemberKind;
  readonly name: string;
  readonly roleTitle: string;
  readonly departmentId: Id | null;
  readonly departmentName: string;
  readonly status: WorkStatus;
  readonly tone: AvatarTone;
}

/**
 * Resolves member references across employees and agents so components never need
 * to know whether a participant is a human or an AI agent to display it.
 */
@Injectable({ providedIn: 'root' })
export class DirectoryStore {
  private readonly organization = inject(OrganizationStore);
  private readonly agentStore = inject(AgentStore);

  readonly members = computed<readonly MemberProfile[]>(() => {
    const departments = this.organization.departmentById();
    const roles = this.organization.roleById();

    const humans = this.organization.employees().map<MemberProfile>((e) => {
      const ref: MemberRef = { kind: 'human', id: e.id };
      return {
        ref,
        key: memberKey(ref),
        kind: 'human',
        name: `${e.firstName} ${e.lastName}`,
        roleTitle: roles.get(e.roleId)?.title ?? 'Employee',
        departmentId: e.departmentId,
        departmentName: departments.get(e.departmentId)?.name ?? '—',
        status: e.status,
        tone: e.avatarTone,
      };
    });

    const agents = this.agentStore.agents().map<MemberProfile>((a) => {
      const ref: MemberRef = { kind: 'agent', id: a.id };
      return {
        ref,
        key: memberKey(ref),
        kind: 'agent',
        name: a.identity.fullName,
        roleTitle: roles.get(a.identity.roleId)?.title ?? 'AI agent',
        departmentId: a.identity.departmentId,
        departmentName: departments.get(a.identity.departmentId)?.name ?? '—',
        status: a.status,
        tone: a.avatarTone,
      };
    });

    return [...humans, ...agents].sort((a, b) => a.name.localeCompare(b.name));
  });

  private readonly byKey = computed(() => new Map(this.members().map((m) => [m.key, m])));

  resolve(ref: MemberRef | null | undefined): MemberProfile {
    if (!ref) {
      return UNASSIGNED;
    }
    return (
      this.byKey().get(memberKey(ref)) ?? {
        ...UNASSIGNED,
        ref,
        key: memberKey(ref),
        kind: ref.kind,
        name: ref.kind === 'agent' ? 'Unknown agent' : 'Unknown member',
      }
    );
  }

  membersOfDepartment(departmentId: Id): readonly MemberProfile[] {
    return this.members().filter((m) => m.departmentId === departmentId);
  }
}

const UNASSIGNED: MemberProfile = {
  ref: { kind: 'human', id: '' },
  key: 'none',
  kind: 'human',
  name: 'Unassigned',
  roleTitle: '',
  departmentId: null,
  departmentName: '',
  status: 'offline',
  tone: 5,
};
