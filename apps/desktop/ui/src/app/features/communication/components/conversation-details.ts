import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Conversation } from '../../../core/models';
import { CommunicationStore } from '../../../core/state/communication.store';
import { OrganizationStore } from '../../../core/state/organization.store';
import { ProjectStore } from '../../../core/state/project.store';
import { Icon } from '../../../shared/components/icon/icon';
import { Member } from '../../../shared/components/member/member';
import { FileSizePipe } from '../../../shared/pipes/file-size.pipe';
import { RelativeTimePipe } from '../../../shared/pipes/relative-time.pipe';
import { CONVERSATION_KIND } from '../../../shared/utils/presentation';

@Component({
  selector: 'app-conversation-details',
  imports: [RouterLink, Icon, Member, FileSizePipe, RelativeTimePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="details__section">
      <h3 class="caps-label">About</h3>
      <p class="details__kind"><ui-icon [name]="kinds[conversation().kind].icon" [size]="13" /> {{ kinds[conversation().kind].label }}</p>
      @if (conversation().topic) {
        <p class="details__topic">{{ conversation().topic }}</p>
      }
      @if (project(); as project) {
        <a class="details__link" [routerLink]="['/projects', project.id]"><ui-icon name="projects" [size]="13" /> {{ project.name }}</a>
      }
      @if (department(); as department) {
        <a class="details__link" routerLink="/organization" [queryParams]="{ department: department.id }"><ui-icon name="building" [size]="13" /> {{ department.name }}</a>
      }
    </section>

    <section class="details__section">
      <h3 class="caps-label">Participants · {{ conversation().participantRefs.length }}</h3>
      <ul class="details__people">
        @for (ref of humans(); track ref.id) {
          <li><ui-member [ref]="ref" [showRole]="true" [showStatus]="true" /></li>
        }
        @for (ref of agents(); track ref.id) {
          <li>
            <a [routerLink]="['/agents', ref.id]" class="details__agent"><ui-member [ref]="ref" [showRole]="true" [showStatus]="true" /></a>
          </li>
        }
      </ul>
    </section>

    <section class="details__section">
      <h3 class="caps-label">Shared files</h3>
      <ul class="details__files">
        @for (file of files(); track file.id) {
          <li class="details__file">
            <span class="details__file-icon"><ui-icon name="file" [size]="14" /></span>
            <span class="details__file-body">
              <span class="details__file-name">{{ file.name }}</span>
              <span class="details__file-meta">{{ file.sizeBytes | fileSize }} · {{ file.sharedAt | relativeTime }}</span>
            </span>
          </li>
        } @empty {
          <li class="text-tertiary details__empty">No files shared yet.</li>
        }
      </ul>
    </section>
  `,
  styles: `
    :host {
      display: block;
      overflow-y: auto;
    }
    .details__section {
      display: flex;
      flex-direction: column;
      gap: var(--space-2);
      padding: var(--space-4);
      border-bottom: 1px solid var(--color-border-subtle);
    }
    .details__kind {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      color: var(--color-text-secondary);
      font-size: var(--text-xs);
    }
    .details__topic {
      color: var(--color-text-primary);
    }
    .details__link {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      font-size: var(--text-xs);
    }
    .details__people {
      display: flex;
      flex-direction: column;
      gap: var(--space-3);
    }
    .details__agent {
      color: inherit;
    }
    .details__agent:hover {
      text-decoration: none;
    }
    .details__file {
      display: flex;
      align-items: center;
      gap: var(--space-2);
      padding: var(--space-1-5) 0;
    }
    .details__file-icon {
      display: inline-flex;
      padding: 6px;
      border-radius: var(--radius-md);
      background: var(--color-surface-3);
      color: var(--color-text-secondary);
    }
    .details__file-body {
      display: flex;
      flex-direction: column;
      min-width: 0;
    }
    .details__file-name {
      overflow: hidden;
      color: var(--color-text-primary);
      font-size: var(--text-xs);
      white-space: nowrap;
      text-overflow: ellipsis;
    }
    .details__file-meta,
    .details__empty {
      color: var(--color-text-tertiary);
      font-size: var(--text-2xs);
    }
  `,
})
export class ConversationDetails {
  readonly conversation = input.required<Conversation>();

  private readonly communication = inject(CommunicationStore);
  private readonly projects = inject(ProjectStore);
  private readonly organization = inject(OrganizationStore);
  protected readonly kinds = CONVERSATION_KIND;

  protected readonly humans = computed(() => this.conversation().participantRefs.filter((p) => p.kind === 'human'));
  protected readonly agents = computed(() => this.conversation().participantRefs.filter((p) => p.kind === 'agent'));
  protected readonly files = computed(() => this.communication.filesByConversation().get(this.conversation().id) ?? []);
  protected readonly project = computed(() => {
    const id = this.conversation().projectId;
    return id ? (this.projects.projectById().get(id) ?? null) : null;
  });
  protected readonly department = computed(() => {
    const id = this.conversation().departmentId;
    return id ? (this.organization.departmentById().get(id) ?? null) : null;
  });
}
