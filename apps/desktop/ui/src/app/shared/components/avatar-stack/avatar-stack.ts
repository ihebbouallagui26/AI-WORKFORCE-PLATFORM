import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { MemberRef } from '../../../core/models';
import { DirectoryStore } from '../../../core/state/directory.store';
import { Tooltip } from '../../directives/tooltip';
import { Avatar } from '../avatar/avatar';

@Component({
  selector: 'ui-avatar-stack',
  imports: [Avatar, Tooltip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { role: 'group', '[attr.aria-label]': 'ariaLabel()' },
  template: `
    @for (member of visible(); track member.key) {
      <ui-avatar
        class="stack__item"
        size="xs"
        [name]="member.name"
        [tone]="member.tone"
        [kind]="member.kind"
        [showAgentBadge]="false"
        [uiTooltip]="member.name + (member.kind === 'agent' ? ' (AI)' : '')"
        tabindex="0"
      />
    }
    @if (overflow() > 0) {
      <span class="stack__more tabular">+{{ overflow() }}</span>
    }
  `,
  styles: `
    :host {
      display: inline-flex;
      align-items: center;
    }
    .stack__item {
      box-shadow: 0 0 0 2px var(--color-surface-1);
    }
    .stack__item:not(:first-child) {
      margin-left: -6px;
    }
    .stack__more {
      margin-left: 6px;
      color: var(--color-text-tertiary);
      font-size: var(--text-xs);
    }
  `,
})
export class AvatarStack {
  readonly refs = input.required<readonly MemberRef[]>();
  readonly max = input(4);

  private readonly directory = inject(DirectoryStore);
  private readonly profiles = computed(() => this.refs().map((r) => this.directory.resolve(r)));
  protected readonly visible = computed(() => this.profiles().slice(0, this.max()));
  protected readonly overflow = computed(() => Math.max(0, this.profiles().length - this.max()));
  protected readonly ariaLabel = computed(() => this.profiles().map((p) => p.name).join(', '));
}
