import { Id, IsoDateString, MemberRef } from './common.model';

export type ConversationKind = 'direct' | 'group' | 'project' | 'department';

export interface Conversation {
  readonly id: Id;
  readonly organizationId: Id;
  readonly kind: ConversationKind;
  /** Display name. For direct messages it is derived from the other participant. */
  readonly name: string;
  readonly topic: string;
  readonly participantRefs: readonly MemberRef[];
  readonly projectId: Id | null;
  readonly departmentId: Id | null;
  readonly createdAt: IsoDateString;
  readonly lastReadAt: IsoDateString;
}

/**
 * - `sent`: acknowledged by a server (only possible once the realtime gateway exists).
 * - `local`: stored in the local mock session only, never transmitted.
 */
export type MessageDelivery = 'sent' | 'local';

export interface SharedFile {
  readonly id: Id;
  readonly conversationId: Id;
  readonly name: string;
  readonly sizeBytes: number;
  readonly sharedByRef: MemberRef;
  readonly sharedAt: IsoDateString;
}

export interface Message {
  readonly id: Id;
  readonly conversationId: Id;
  readonly authorRef: MemberRef;
  readonly body: string;
  readonly sentAt: IsoDateString;
  readonly delivery: MessageDelivery;
}

export interface MessageDraft {
  readonly conversationId: Id;
  readonly authorRef: MemberRef;
  readonly body: string;
}
