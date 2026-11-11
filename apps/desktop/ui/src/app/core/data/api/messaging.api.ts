import { Observable } from 'rxjs';
import { Conversation, Id, Message, MessageDraft, SharedFile } from '../../models';

/**
 * Messaging between humans and agents.
 * `incoming$` is the seam for the future WebSocket gateway: the realtime implementation
 * will push server-sent messages through it, the mock never emits on it.
 */
export abstract class MessagingApi {
  abstract readonly incoming$: Observable<Message>;
  abstract listConversations(organizationId: Id): Observable<Conversation[]>;
  abstract listMessages(organizationId: Id): Observable<Message[]>;
  abstract listSharedFiles(organizationId: Id): Observable<SharedFile[]>;
  abstract sendMessage(draft: MessageDraft): Observable<Message>;
  abstract markConversationRead(conversationId: Id): Observable<Conversation>;
}
