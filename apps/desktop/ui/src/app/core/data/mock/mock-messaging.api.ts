import { inject, Injectable } from '@angular/core';
import { NEVER, Observable } from 'rxjs';
import { Conversation, Id, Message, MessageDraft, SharedFile } from '../../models';
import { MessagingApi } from '../api/messaging.api';
import { MockDatabase } from './mock-database';

/**
 * Local message simulation. Messages are stored in the session with `delivery: 'local'`
 * and are never transmitted. No agent replies are fabricated: agent responses will come
 * from the AI orchestration engine through the realtime gateway.
 */
@Injectable()
export class MockMessagingApi extends MessagingApi {
  private readonly db = inject(MockDatabase);

  readonly incoming$: Observable<Message> = NEVER;

  listConversations(organizationId: Id): Observable<Conversation[]> {
    return this.db.respond(this.db.tables.conversations.filter((c) => c.organizationId === organizationId));
  }

  listMessages(organizationId: Id): Observable<Message[]> {
    const ids = this.conversationIds(organizationId);
    return this.db.respond(this.db.tables.messages.filter((m) => ids.has(m.conversationId)));
  }

  listSharedFiles(organizationId: Id): Observable<SharedFile[]> {
    const ids = this.conversationIds(organizationId);
    return this.db.respond(this.db.tables.sharedFiles.filter((f) => ids.has(f.conversationId)));
  }

  sendMessage(draft: MessageDraft): Observable<Message> {
    const message: Message = {
      ...draft,
      id: this.db.nextId('msg'),
      sentAt: this.db.now(),
      delivery: 'local',
    };
    this.db.tables.messages.push(message);
    return this.db.respond(message);
  }

  markConversationRead(conversationId: Id): Observable<Conversation> {
    const current = this.db.tables.conversations.find((c) => c.id === conversationId);
    if (!current) {
      return this.db.notFound('Conversation', conversationId);
    }
    return this.db.respond(
      this.db.replace(this.db.tables.conversations, { ...current, lastReadAt: this.db.now() }),
    );
  }

  private conversationIds(organizationId: Id): Set<Id> {
    return new Set(
      this.db.tables.conversations.filter((c) => c.organizationId === organizationId).map((c) => c.id),
    );
  }
}
