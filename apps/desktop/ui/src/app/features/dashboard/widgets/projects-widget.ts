import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ProjectStore } from '../../../core/state/project.store';
import { Badge } from '../../../shared/components/badge/badge';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Member } from '../../../shared/components/member/member';
import { Panel } from '../../../shared/components/panel/panel';
import { ProgressBar } from '../../../shared/components/progress-bar/progress-bar';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { PROJECT_CATEGORY, PROJECT_STATUS } from '../../../shared/utils/presentation';

@Component({
  selector: 'app-projects-widget',
  imports: [DatePipe, RouterLink, Panel, Badge, Member, ProgressBar, Skeleton, EmptyState],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ui-panel heading="Active projects" subheading="Ordered by due date" [flush]="true">
      <a panelActions class="link-button" routerLink="/projects">All projects</a>
      @if (store.loading()) {
        <ui-skeleton [rows]="4" />
      } @else {
        <div class="table-wrap">
          <table class="table">
            <thead>
              <tr>
                <th scope="col">Project</th>
                <th scope="col">Manager</th>
                <th scope="col">Progress</th>
                <th scope="col">Due</th>
                <th scope="col">Status</th>
              </tr>
            </thead>
            <tbody>
              @for (project of projects(); track project.id) {
                <tr>
                  <td>
                    <a class="project-link" [routerLink]="['/projects', project.id]">
                      <span class="table__primary">{{ project.name }}</span>
                      <span class="project-link__meta">{{ project.code }} · {{ categories[project.category] }}</span>
                    </a>
                  </td>
                  <td class="nowrap"><ui-member [ref]="project.managerRef" size="xs" [showAiTag]="false" /></td>
                  <td class="progress-cell">
                    <ui-progress-bar [value]="store.progressOf(project.id).percent" [tone]="project.status === 'at-risk' ? 'warning' : 'accent'" [label]="project.name + ' progress'" />
                    <span class="tabular">{{ store.progressOf(project.id).percent }}%</span>
                  </td>
                  <td class="tabular nowrap">{{ project.dueDate | date: 'd MMM' }}</td>
                  <td><ui-badge [presentation]="statuses[project.status]" /></td>
                </tr>
              } @empty {
                <tr>
                  <td colspan="5"><ui-empty-state [compact]="true" icon="projects" heading="No active projects" /></td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
    </ui-panel>
  `,
  styles: `
    .project-link {
      display: flex;
      flex-direction: column;
      color: inherit;
    }
    .project-link:hover {
      text-decoration: none;
    }
    .project-link:hover .table__primary {
      color: var(--color-accent-text);
    }
    .project-link__meta {
      color: var(--color-text-tertiary);
      font-size: var(--text-xs);
      white-space: nowrap;
    }
    .project-link .table__primary {
      white-space: nowrap;
    }
    .nowrap {
      white-space: nowrap;
    }
    .progress-cell {
      min-width: 150px;
    }
    .progress-cell ui-progress-bar {
      display: inline-block;
      width: 90px;
      margin-right: var(--space-2);
      vertical-align: middle;
    }
  `,
})
export class ProjectsWidget {
  protected readonly store = inject(ProjectStore);
  protected readonly statuses = PROJECT_STATUS;
  protected readonly categories = PROJECT_CATEGORY;

  protected readonly projects = computed(() =>
    this.store
      .projects()
      .filter((p) => p.status !== 'completed')
      .sort((a, b) => a.dueDate.localeCompare(b.dueDate))
      .slice(0, 6),
  );
}
