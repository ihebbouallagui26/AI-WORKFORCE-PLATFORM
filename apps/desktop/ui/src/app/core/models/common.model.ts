/** Opaque entity identifier (UUID on the backend, prefixed strings in mocks). */
export type Id = string;

/** ISO-8601 timestamp, as exchanged with the Core API. */
export type IsoDateString = string;

/** A workforce member can be a human employee or an AI agent. */
export type MemberKind = 'human' | 'agent';

/** Polymorphic reference to a workforce member. */
export interface MemberRef {
  readonly kind: MemberKind;
  readonly id: Id;
}

/** Index of the deterministic avatar colour (1–8), see `--tone-*` tokens. */
export type AvatarTone = 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8;

/** Presence / operational status shared by humans and agents. */
export type WorkStatus = 'online' | 'working' | 'idle' | 'offline' | 'needs-approval' | 'error';

export function sameMember(a: MemberRef | null | undefined, b: MemberRef | null | undefined): boolean {
  return !!a && !!b && a.kind === b.kind && a.id === b.id;
}

export function memberKey(ref: MemberRef): string {
  return `${ref.kind}:${ref.id}`;
}
