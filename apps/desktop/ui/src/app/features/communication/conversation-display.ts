import { Conversation, MemberRef, sameMember } from '../../core/models';

/** For direct messages, the participant who is not the current user. */
export function counterpart(conversation: Conversation, me: MemberRef | null): MemberRef | null {
  if (conversation.kind !== 'direct') {
    return null;
  }
  return conversation.participantRefs.find((p) => !sameMember(p, me)) ?? null;
}

export function conversationTitle(conversation: Conversation, counterpartName: string | null): string {
  if (conversation.kind === 'direct') {
    return counterpartName ?? conversation.name;
  }
  return conversation.kind === 'project' || conversation.kind === 'department'
    ? `#${conversation.name}`
    : conversation.name;
}

export function hasAgentParticipant(conversation: Conversation): boolean {
  return conversation.participantRefs.some((p) => p.kind === 'agent');
}
