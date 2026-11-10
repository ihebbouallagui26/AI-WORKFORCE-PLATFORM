import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked, viewChild } from '@angular/core';
import { Router } from '@angular/router';
import { Id } from '../../core/models';
import { ToastService } from '../../core/services/toast.service';
import { CommunicationStore } from '../../core/state/communication.store';
import { DirectoryStore } from '../../core/state/directory.store';
import { WorkspaceStore } from '../../core/state/workspace.store';
import { AvatarStack } from '../../shared/components/avatar-stack/avatar-stack';
import { EmptyState } from '../../shared/components/empty-state/empty-state';
import { Icon } from '../../shared/components/icon/icon';
import { Skeleton } from '../../shared/components/skeleton/skeleton';
import { Tooltip } from '../../shared/directives/tooltip';
import { ConversationDetails } from './components/conversation-details';
import { ConversationList } from './components/conversation-list';
import { MessageComposer } from './components/message-composer';
import { MessageThread } from './components/message-thread';
import { conversationTitle, counterpart, hasAgentParticipant } from './conversation-display';

@Component({
  selector: 'app-communication-page',
  imports: [
    ConversationList,
    MessageThread,
    MessageComposer,
    ConversationDetails,
    AvatarStack,
    Icon,
    Tooltip,
    Skeleton,
    EmptyState,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './communication-page.html',
  styleUrl: './communication-page.scss',
})
export class CommunicationPage {
  /** Query parameter bound by the router. */
  readonly conversation = input<string | undefined>();

  protected readonly store = inject(CommunicationStore);
  private readonly directory = inject(DirectoryStore);
  private readonly workspace = inject(WorkspaceStore);
  private readonly toast = inject(ToastService);
  private readonly router = inject(Router);
  private readonly composer = viewChild(MessageComposer);

  protected readonly showDetails = signal(true);
  protected readonly sending = signal(false);

  protected readonly activeId = computed<Id | null>(() => {
    const requested = this.conversation();
    const summaries = this.store.summaries();
    if (requested && summaries.some((s) => s.conversation.id === requested)) {
      return requested;
    }
    return summaries[0]?.conversation.id ?? null;
  });

  protected readonly active = computed(() => this.store.conversationById().get(this.activeId() ?? '') ?? null);
  protected readonly messages = computed(() => {
    const id = this.activeId();
    return id ? this.store.messagesOf(id) : [];
  });

  protected readonly title = computed(() => {
    const conversation = this.active();
    if (!conversation) return '';
    const other = counterpart(conversation, this.workspace.currentMemberRef());
    return conversationTitle(conversation, other ? this.directory.resolve(other).name : null);
  });

  protected readonly includesAgents = computed(() => {
    const conversation = this.active();
    return conversation ? hasAgentParticipant(conversation) : false;
  });

  constructor() {
    // Opening a conversation marks it as read.
    effect(() => {
      const id = this.activeId();
      this.messages();
      if (id) {
        untracked(() => void this.store.markRead(id).catch(() => undefined));
      }
    });
  }

  protected select(conversationId: Id): void {
    void this.router.navigate([], { queryParams: { conversation: conversationId }, queryParamsHandling: 'merge' });
  }

  protected async send(body: string): Promise<void> {
    const id = this.activeId();
    if (!id) return;
    this.sending.set(true);
    try {
      await this.store.send(id, body);
      this.composer()?.reset();
    } catch (error) {
      this.toast.error('Message not sent', error);
    } finally {
      this.sending.set(false);
    }
  }
}
