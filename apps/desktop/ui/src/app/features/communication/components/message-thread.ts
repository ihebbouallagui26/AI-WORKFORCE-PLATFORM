import { DatePipe } from '@angular/common';
import {
  afterRenderEffect,
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  inject,
  input,
  viewChild,
} from '@angular/core';
import { Message, sameMember } from '../../../core/models';
import { DirectoryStore, MemberProfile } from '../../../core/state/directory.store';
import { WorkspaceStore } from '../../../core/state/workspace.store';
import { Avatar } from '../../../shared/components/avatar/avatar';
import { Icon } from '../../../shared/components/icon/icon';
import { Tooltip } from '../../../shared/directives/tooltip';

interface MessageGroup {
  readonly key: string;
  readonly author: MemberProfile;
  readonly mine: boolean;
  readonly messages: readonly Message[];
}

interface DaySection {
  readonly day: string;
  readonly groups: readonly MessageGroup[];
}

/** Messages sent by the same author within this window are visually grouped. */
const GROUP_WINDOW_MS = 5 * 60_000;

@Component({
  selector: 'app-message-thread',
  imports: [DatePipe, Avatar, Icon, Tooltip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './message-thread.html',
  styleUrl: './message-thread.scss',
})
export class MessageThread {
  readonly messages = input.required<readonly Message[]>();

  private readonly directory = inject(DirectoryStore);
  private readonly workspace = inject(WorkspaceStore);
  private readonly scroller = viewChild.required<ElementRef<HTMLElement>>('scroller');

  protected readonly sections = computed<readonly DaySection[]>(() => {
    const me = this.workspace.currentMemberRef();
    const sections: { day: string; groups: { key: string; author: MemberProfile; mine: boolean; messages: Message[] }[] }[] = [];
    let previous: Message | null = null;

    for (const message of this.messages()) {
      const day = localDayKey(new Date(message.sentAt));
      let section = sections.at(-1);
      if (!section || section.day !== day) {
        section = { day, groups: [] };
        sections.push(section);
        previous = null;
      }
      const sameAuthor =
        previous &&
        sameMember(previous.authorRef, message.authorRef) &&
        new Date(message.sentAt).getTime() - new Date(previous.sentAt).getTime() < GROUP_WINDOW_MS;
      if (sameAuthor) {
        section.groups.at(-1)?.messages.push(message);
      } else {
        section.groups.push({
          key: message.id,
          author: this.directory.resolve(message.authorRef),
          mine: sameMember(message.authorRef, me),
          messages: [message],
        });
      }
      previous = message;
    }
    return sections;
  });

  constructor() {
    // Keep the latest message in view whenever the thread changes.
    afterRenderEffect(() => {
      this.messages();
      const element = this.scroller().nativeElement;
      element.scrollTop = element.scrollHeight;
    });
  }

  protected dayLabel(day: string): string {
    if (day === localDayKey(new Date())) return 'Today';
    if (day === localDayKey(new Date(Date.now() - 86_400_000))) return 'Yesterday';
    return new Date(`${day}T12:00:00`).toLocaleDateString('en-GB', { weekday: 'long', day: 'numeric', month: 'long' });
  }
}

function localDayKey(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}
