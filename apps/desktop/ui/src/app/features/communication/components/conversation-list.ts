import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { ConversationKind, Id, MemberRef, sameMember } from '../../../core/models';
import { ConversationSummary } from '../../../core/state/communication.store';
import { DirectoryStore, MemberProfile } from '../../../core/state/directory.store';
import { WorkspaceStore } from '../../../core/state/workspace.store';
import { Avatar } from '../../../shared/components/avatar/avatar';
import { Icon } from '../../../shared/components/icon/icon';
import { SearchInput } from '../../../shared/components/search-input/search-input';
import { RelativeTimePipe } from '../../../shared/pipes/relative-time.pipe';
import { CONVERSATION_KIND } from '../../../shared/utils/presentation';
import { conversationTitle, counterpart } from '../conversation-display';

const KIND_ORDER: readonly ConversationKind[] = ['direct', 'group', 'project', 'department'];

interface ListEntry {
  readonly summary: ConversationSummary;
  readonly title: string;
  readonly preview: string;
  /** Set for direct messages: the other participant. */
  readonly avatar: MemberProfile | null;
}

@Component({
  selector: 'app-conversation-list',
  imports: [Avatar, Icon, SearchInput, RelativeTimePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './conversation-list.html',
  styleUrl: './conversation-list.scss',
})
export class ConversationList {
  readonly summaries = input.required<readonly ConversationSummary[]>();
  readonly selectedId = input<Id | null>(null);
  readonly selected = output<Id>();

  private readonly directory = inject(DirectoryStore);
  private readonly workspace = inject(WorkspaceStore);
  protected readonly kinds = CONVERSATION_KIND;
  protected readonly query = signal('');

  protected readonly groups = computed(() => {
    const me = this.workspace.currentMemberRef();
    const query = this.query().trim().toLowerCase();
    const entries: ListEntry[] = this.summaries().map((summary) => {
      const other = counterpart(summary.conversation, me);
      const profile = other ? this.directory.resolve(other) : null;
      return {
        summary,
        title: conversationTitle(summary.conversation, profile?.name ?? null),
        preview: this.preview(summary, me),
        avatar: profile,
      };
    });

    return KIND_ORDER.map((kind) => ({
      kind,
      entries: entries.filter(
        (e) => e.summary.conversation.kind === kind && (!query || `${e.title} ${e.preview}`.toLowerCase().includes(query)),
      ),
    })).filter((group) => group.entries.length > 0);
  });

  private preview(summary: ConversationSummary, me: MemberRef | null): string {
    const last = summary.lastMessage;
    if (!last) {
      return summary.conversation.topic || 'No messages yet';
    }
    if (sameMember(last.authorRef, me)) {
      return `You: ${last.body}`;
    }
    if (summary.conversation.kind === 'direct') {
      return last.body;
    }
    return `${this.directory.resolve(last.authorRef).name.split(' ')[0]}: ${last.body}`;
  }
}
