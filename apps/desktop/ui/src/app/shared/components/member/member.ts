import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { MemberRef } from '../../../core/models';
import { DirectoryStore } from '../../../core/state/directory.store';
import { Avatar, AvatarSize } from '../avatar/avatar';

/** Avatar + name (+ role) for any member reference, human or AI. */
@Component({
  selector: 'ui-member',
  imports: [Avatar],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ui-avatar
      [name]="profile().name"
      [tone]="profile().tone"
      [kind]="profile().kind"
      [size]="size()"
      [status]="showStatus() ? profile().status : null"
    />
    @if (!avatarOnly()) {
      <span class="member__text">
        <span class="member__name">
          {{ profile().name }}
          @if (profile().kind === 'agent' && showAiTag()) {
            <span class="member__ai">AI</span>
          }
        </span>
        @if (showRole() && profile().roleTitle) {
          <span class="member__role">{{ profile().roleTitle }}</span>
        }
      </span>
    }
  `,
  styles: `
    :host {
      display: inline-flex;
      align-items: center;
      gap: var(--space-2);
      min-width: 0;
    }
    .member__text {
      display: flex;
      flex-direction: column;
      min-width: 0;
    }
    .member__name {
      display: inline-flex;
      align-items: center;
      gap: var(--space-1-5);
      overflow: hidden;
      color: var(--color-text-primary);
      font-weight: var(--weight-medium);
      white-space: nowrap;
      text-overflow: ellipsis;
    }
    .member__ai {
      padding: 0 4px;
      border-radius: 3px;
      background: var(--color-ai-subtle);
      color: var(--color-ai);
      font-size: 10px;
      font-weight: var(--weight-semibold);
      line-height: 15px;
      letter-spacing: 0.04em;
    }
    .member__role {
      overflow: hidden;
      color: var(--color-text-tertiary);
      font-size: var(--text-xs);
      white-space: nowrap;
      text-overflow: ellipsis;
    }
  `,
})
export class Member {
  readonly ref = input.required<MemberRef | null>();
  readonly size = input<AvatarSize>('sm');
  readonly showRole = input(false);
  readonly showStatus = input(false);
  readonly showAiTag = input(true);
  readonly avatarOnly = input(false);

  private readonly directory = inject(DirectoryStore);
  protected readonly profile = computed(() => this.directory.resolve(this.ref()));
}
