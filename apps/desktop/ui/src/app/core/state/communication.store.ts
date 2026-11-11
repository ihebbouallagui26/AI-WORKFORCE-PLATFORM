import { computed, inject, Injectable } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { firstValueFrom } from 'rxjs';
import { MessagingApi } from '../data/api/messaging.api';
import { Conversation, Id, Message, sameMember, SharedFile } from '../models';
import { indexById, orgCollection, requireOrganization, upsertById } from './collection';
import { WorkspaceStore } from './workspace.store';

export interface ConversationSummary {
  readonly conversation: Conversation;
  readonly lastMessage: Message | null;
  readonly unread: number;
}

@Injectable({ providedIn: 'root' })
export class CommunicationStore {
  private readonly api = inject(MessagingApi);
  private readonly workspace = inject(WorkspaceStore);
  private readonly organizationId = this.workspace.currentOrganizationId;

  private readonly conversationList = orgCollection(this.organizationId, (id) => this.api.listConversations(id));
  private readonly messageList = orgCollection(this.organizationId, (id) => this.api.listMessages(id));
  private readonly fileList = orgCollection(this.organizationId, (id) => this.api.listSharedFiles(id));

  readonly conversations = this.conversationList.items;
  readonly loading = computed(() => this.conversationList.loading() || this.messageList.loading());
  readonly error = computed(() => this.conversationList.error() ?? this.messageList.error());
  readonly conversationById = computed(() => indexById(this.conversations()));

  readonly messagesByConversation = computed(() => {
    const map = new Map<Id, Message[]>();
    for (const message of this.messageList.items()) {
      const list = map.get(message.conversationId) ?? [];
      list.push(message);
      map.set(message.conversationId, list);
    }
    map.forEach((list) => list.sort((a, b) => a.sentAt.localeCompare(b.sentAt)));
    return map as ReadonlyMap<Id, readonly Message[]>;
  });

  readonly filesByConversation = computed(() => {
    const map = new Map<Id, SharedFile[]>();
    for (const file of this.fileList.items()) {
      map.set(file.conversationId, [...(map.get(file.conversationId) ?? []), file]);
    }
    return map as ReadonlyMap<Id, readonly SharedFile[]>;
  });

  /** Conversations ordered by most recent activity. */
  readonly summaries = computed<readonly ConversationSummary[]>(() => {
    const me = this.workspace.currentMemberRef();
    return this.conversations()
      .map((conversation) => {
        const messages = this.messagesByConversation().get(conversation.id) ?? [];
        return {
          conversation,
          lastMessage: messages.at(-1) ?? null,
          unread: messages.filter((m) => m.sentAt > conversation.lastReadAt && !sameMember(m.authorRef, me)).length,
        };
      })
      .sort((a, b) =>
        (b.lastMessage?.sentAt ?? b.conversation.createdAt).localeCompare(
          a.lastMessage?.sentAt ?? a.conversation.createdAt,
        ),
      );
  });

  readonly totalUnread = computed(() => this.summaries().reduce((sum, s) => sum + s.unread, 0));

  constructor() {
    // Seam for the future realtime gateway; the mock implementation never emits.
    // Messages for conversations outside the selected organization are ignored.
    this.api.incoming$.pipe(takeUntilDestroyed()).subscribe((message) => {
      const organizationId = this.organizationId();
      if (organizationId && this.conversationById().has(message.conversationId)) {
        this.messageList.mutate(organizationId, (items) => upsertById(items, message));
      }
    });
  }

  messagesOf(conversationId: Id): readonly Message[] {
    return this.messagesByConversation().get(conversationId) ?? [];
  }

  async send(conversationId: Id, body: string): Promise<Message> {
    const organizationId = requireOrganization(this.organizationId);
    const authorRef = this.workspace.currentMemberRef();
    const text = body.trim();
    if (!authorRef) {
      throw new Error('You are not a member of this organization.');
    }
    if (!this.conversationById().has(conversationId)) {
      throw new Error('This conversation does not belong to the current organization.');
    }
    if (!text) {
      throw new Error('Message is empty.');
    }
    const message = await firstValueFrom(this.api.sendMessage({ conversationId, authorRef, body: text }));
    this.messageList.mutate(organizationId, (items) => upsertById(items, message));
    return message;
  }

  async markRead(conversationId: Id): Promise<void> {
    const organizationId = requireOrganization(this.organizationId);
    const summary = this.summaries().find((s) => s.conversation.id === conversationId);
    if (!summary || summary.unread === 0) {
      return;
    }
    const updated = await firstValueFrom(this.api.markConversationRead(conversationId));
    this.conversationList.mutate(organizationId, (items) => upsertById(items, updated));
  }
}
