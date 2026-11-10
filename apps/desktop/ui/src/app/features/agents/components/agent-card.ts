import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Agent } from '../../../core/models';
import { AgentStore } from '../../../core/state/agent.store';
import { DirectoryStore } from '../../../core/state/directory.store';
import { ProjectStore } from '../../../core/state/project.store';
import { Avatar } from '../../../shared/components/avatar/avatar';
import { Badge } from '../../../shared/components/badge/badge';
import { Icon } from '../../../shared/components/icon/icon';
import { RelativeTimePipe } from '../../../shared/pipes/relative-time.pipe';
import { WORK_STATUS } from '../../../shared/utils/presentation';

@Component({
  selector: 'app-agent-card',
  imports: [RouterLink, Avatar, Badge, Icon, RelativeTimePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a class="card" [routerLink]="['/agents', agent().id]" [attr.aria-label]="agent().identity.fullName + ', ' + profile().roleTitle">
      <div class="card__head">
        <ui-avatar size="lg" kind="agent" [name]="agent().identity.fullName" [tone]="agent().avatarTone" [status]="agent().status" />
        <div class="card__identity">
          <span class="card__name">{{ agent().identity.fullName }}</span>
          <span class="card__role">{{ profile().roleTitle }}</span>
          <span class="card__department">{{ profile().departmentName }}</span>
        </div>
        <ui-badge class="card__status" [presentation]="statuses[agent().status]" />
      </div>

      <dl class="card__facts">
        <div>
          <dt><ui-icon name="cpu" [size]="13" /> Model</dt>
          <dd class="truncate">{{ binding().model?.name ?? '—' }} <span class="text-tertiary">· {{ binding().provider?.name ?? 'No provider' }}</span></dd>
        </div>
        <div>
          <dt><ui-icon name="projects" [size]="13" /> Project</dt>
          <dd class="truncate">
            {{ primaryProject()?.name ?? 'Unassigned' }}
            @if (agent().assignedProjectIds.length > 1) {
              <span class="text-tertiary">+{{ agent().assignedProjectIds.length - 1 }}</span>
            }
          </dd>
        </div>
        <div>
          <dt><ui-icon name="clock" [size]="13" /> Last active</dt>
          <dd>{{ agent().lastActiveAt ? (agent().lastActiveAt | relativeTime) : 'Never' }}</dd>
        </div>
      </dl>

      <div class="chip-list card__skills">
        @for (skill of visibleSkills(); track skill) {
          <span class="chip">{{ skill }}</span>
        }
        @if (agent().identity.skills.length > visibleSkills().length) {
          <span class="chip">+{{ agent().identity.skills.length - visibleSkills().length }}</span>
        }
      </div>
    </a>
  `,
  styles: `
    .card {
      display: flex;
      flex-direction: column;
      gap: var(--space-4);
      height: 100%;
      padding: var(--space-4);
      border: 1px solid var(--color-border-subtle);
      border-radius: var(--radius-lg);
      background: var(--color-surface-1);
      color: inherit;
      transition: border-color var(--duration-fast) var(--ease-standard), background-color var(--duration-fast) var(--ease-standard);
    }
    .card:hover {
      border-color: var(--color-border-strong);
      background: var(--color-surface-2);
      text-decoration: none;
    }
    .card__head {
      display: flex;
      align-items: flex-start;
      gap: var(--space-3);
    }
    .card__identity {
      display: flex;
      flex-direction: column;
      flex: 1;
      min-width: 0;
    }
    .card__name {
      color: var(--color-text-primary);
      font-size: var(--text-md);
      font-weight: var(--weight-semibold);
    }
    .card__role {
      color: var(--color-text-secondary);
    }
    .card__department {
      color: var(--color-text-tertiary);
      font-size: var(--text-xs);
    }
    .card__facts {
      display: flex;
      flex-direction: column;
      gap: var(--space-1-5);
      margin: 0;
      padding: var(--space-3);
      border-radius: var(--radius-md);
      background: var(--color-bg-app);
      font-size: var(--text-xs);
    }
    .card__facts div {
      display: grid;
      grid-template-columns: 96px minmax(0, 1fr);
      align-items: center;
    }
    .card__facts dt {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      color: var(--color-text-tertiary);
    }
    .card__facts dd {
      margin: 0;
      color: var(--color-text-primary);
    }
    .card__skills {
      margin-top: auto;
    }
  `,
})
export class AgentCard {
  readonly agent = input.required<Agent>();

  private readonly agents = inject(AgentStore);
  private readonly directory = inject(DirectoryStore);
  private readonly projects = inject(ProjectStore);
  protected readonly statuses = WORK_STATUS;

  protected readonly profile = computed(() => this.directory.resolve({ kind: 'agent', id: this.agent().id }));
  protected readonly binding = computed(() => this.agents.primaryBinding(this.agent()));
  protected readonly primaryProject = computed(() => {
    const id = this.agent().assignedProjectIds[0];
    return id ? (this.projects.projectById().get(id) ?? null) : null;
  });
  protected readonly visibleSkills = computed(() => this.agent().identity.skills.slice(0, 4));
}
