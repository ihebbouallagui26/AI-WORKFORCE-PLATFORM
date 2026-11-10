import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { AvatarTone, MemberKind, WorkStatus } from '../../../core/models';
import { WORK_STATUS } from '../../utils/presentation';
import { Icon } from '../icon/icon';

export type AvatarSize = 'xs' | 'sm' | 'md' | 'lg' | 'xl';

/**
 * Avatar with initials. Humans are round; AI agents use a rounded square and an
 * agent badge so the two are never confused, even without colour perception.
 */
@Component({
  selector: 'ui-avatar',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class: 'avatar',
    '[class]': 'hostClasses()',
    role: 'img',
    '[attr.aria-label]': 'ariaLabel()',
  },
  template: `
    <span class="avatar__initials" aria-hidden="true">{{ initials() }}</span>
    @if (kind() === 'agent' && showAgentBadge()) {
      <span class="avatar__agent-badge" aria-hidden="true"><ui-icon name="agent" [size]="badgeIconSize()" [strokeWidth]="2.25" /></span>
    }
    @if (status(); as s) {
      <span class="avatar__status" [class]="'avatar__status avatar__status--' + s" aria-hidden="true"></span>
    }
  `,
  styleUrl: './avatar.scss',
})
export class Avatar {
  readonly name = input.required<string>();
  readonly tone = input<AvatarTone>(5);
  readonly kind = input<MemberKind>('human');
  readonly size = input<AvatarSize>('md');
  readonly status = input<WorkStatus | null>(null);
  readonly showAgentBadge = input(true);

  protected readonly initials = computed(() => {
    const initials = toInitials(this.name());
    // Tiny avatars only have room for one legible letter.
    return this.size() === 'xs' ? initials.charAt(0) : initials;
  });
  protected readonly badgeIconSize = computed(() => (this.size() === 'lg' || this.size() === 'xl' ? 11 : 8));
  protected readonly hostClasses = computed(
    () => `avatar avatar--${this.size()} avatar--${this.kind()} avatar--tone-${this.tone()}`,
  );
  protected readonly ariaLabel = computed(() => {
    const parts = [this.name(), this.kind() === 'agent' ? 'AI agent' : null];
    const status = this.status();
    if (status) {
      parts.push(WORK_STATUS[status].label);
    }
    return parts.filter(Boolean).join(', ');
  });
}

export function toInitials(name: string): string {
  const words = name.trim().split(/\s+/).filter(Boolean);
  if (words.length === 0) {
    return '?';
  }
  if (words.length === 1) {
    return words[0].slice(0, 2).toUpperCase();
  }
  return (words[0][0] + words[words.length - 1][0]).toUpperCase();
}
