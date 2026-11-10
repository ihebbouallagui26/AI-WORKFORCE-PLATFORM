import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Employee } from '../../../core/models';
import { OrganizationStore } from '../../../core/state/organization.store';
import { ProjectStore } from '../../../core/state/project.store';
import { Avatar } from '../../../shared/components/avatar/avatar';
import { Badge } from '../../../shared/components/badge/badge';
import { Member } from '../../../shared/components/member/member';
import { Modal } from '../../../shared/components/modal/modal';
import { SENIORITY, TASK_STATUS, WORK_STATUS } from '../../../shared/utils/presentation';

@Component({
  selector: 'app-employee-detail-modal',
  imports: [DatePipe, RouterLink, Modal, Avatar, Badge, Member],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ui-modal [open]="!!employee()" [heading]="fullName()" description="Human employee" (closed)="closed.emit()">
      @if (employee(); as e) {
        <div class="person">
          <ui-avatar size="lg" [name]="fullName()" [tone]="e.avatarTone" [status]="e.status" />
          <div>
            <p class="person__role">{{ role()?.title ?? 'Employee' }}</p>
            <p class="person__department">{{ department()?.name }}</p>
          </div>
          <ui-badge class="person__status" [presentation]="statuses[e.status]" />
        </div>

        <dl class="meta-list details">
          <dt>Email</dt>
          <dd>{{ e.email }}</dd>
          <dt>Location</dt>
          <dd>{{ e.location }}</dd>
          <dt>Seniority</dt>
          <dd>{{ role() ? seniority[role()!.level] : '—' }}</dd>
          <dt>Reports to</dt>
          <dd>
            @if (e.managerRef) {
              <ui-member [ref]="e.managerRef" size="xs" />
            } @else {
              <span class="text-tertiary">No manager</span>
            }
          </dd>
          <dt>Joined</dt>
          <dd>{{ e.joinedAt | date: 'longDate' }}</dd>
        </dl>

        <h3 class="section-title">Projects</h3>
        <ul class="links">
          @for (project of memberProjects(); track project.id) {
            <li><a [routerLink]="['/projects', project.id]" (click)="closed.emit()">{{ project.name }}</a></li>
          } @empty {
            <li class="text-tertiary">No project assignments.</li>
          }
        </ul>

        <h3 class="section-title">Assigned tasks</h3>
        <ul class="tasks">
          @for (task of memberTasks(); track task.id) {
            <li>
              <span class="mono text-tertiary">{{ task.key }}</span>
              <span class="tasks__title">{{ task.title }}</span>
              <ui-badge [presentation]="taskStatus[task.status]" />
            </li>
          } @empty {
            <li class="text-tertiary">No assigned tasks.</li>
          }
        </ul>
      }
    </ui-modal>
  `,
  styles: `
    .person {
      display: flex;
      align-items: center;
      gap: var(--space-3);
      margin-bottom: var(--space-5);
    }
    .person__role {
      font-weight: var(--weight-semibold);
    }
    .person__department {
      color: var(--color-text-tertiary);
      font-size: var(--text-xs);
    }
    .person__status {
      margin-left: auto;
    }
    .details {
      padding-bottom: var(--space-5);
      border-bottom: 1px solid var(--color-border-subtle);
    }
    .section-title {
      margin: var(--space-5) 0 var(--space-2);
      font-size: var(--text-sm);
    }
    .links {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-2) var(--space-4);
    }
    .tasks li {
      display: flex;
      align-items: center;
      gap: var(--space-3);
      padding: var(--space-2) 0;
      border-bottom: 1px solid var(--color-border-subtle);
    }
    .tasks__title {
      flex: 1;
      min-width: 0;
    }
  `,
})
export class EmployeeDetailModal {
  readonly employee = input<Employee | null>(null);
  readonly closed = output<void>();

  private readonly organization = inject(OrganizationStore);
  private readonly projects = inject(ProjectStore);
  protected readonly statuses = WORK_STATUS;
  protected readonly taskStatus = TASK_STATUS;
  protected readonly seniority = SENIORITY;

  protected readonly fullName = computed(() => {
    const e = this.employee();
    return e ? `${e.firstName} ${e.lastName}` : '';
  });
  protected readonly role = computed(() => this.organization.roleById().get(this.employee()?.roleId ?? '') ?? null);
  protected readonly department = computed(
    () => this.organization.departmentById().get(this.employee()?.departmentId ?? '') ?? null,
  );
  protected readonly memberProjects = computed(() => {
    const e = this.employee();
    return e ? this.projects.projectsForMember({ kind: 'human', id: e.id }) : [];
  });
  protected readonly memberTasks = computed(() => {
    const e = this.employee();
    return e ? this.projects.tasksForMember({ kind: 'human', id: e.id }).filter((t) => t.status !== 'done') : [];
  });
}
