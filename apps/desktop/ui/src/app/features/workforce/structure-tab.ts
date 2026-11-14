import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { ConfirmService } from '../../core/services/confirm.service';
import { ToastService } from '../../core/services/toast.service';
import { orgResource } from '../../core/workforce/org-resource';
import { WorkforceApi } from '../../core/workforce/workforce.api';
import { DepartmentDto } from '../../core/workforce/workforce.models';
import { WorkforceStore } from '../../core/workforce/workforce.store';
import { Badge } from '../../shared/components/badge/badge';
import { EmptyState } from '../../shared/components/empty-state/empty-state';
import { Icon } from '../../shared/components/icon/icon';
import { Panel } from '../../shared/components/panel/panel';
import { Skeleton } from '../../shared/components/skeleton/skeleton';
import { WfDepartmentForm } from './department-form';
import { ARCHIVE_LABEL, ARCHIVE_TONE, EMPLOYMENT_LABEL, EMPLOYMENT_TONE, idOrNull } from './workforce-format';

/** Department hierarchy with a detail panel: head, members, positions, teams and structural actions. */
@Component({
  selector: 'app-wf-structure',
  imports: [NgTemplateOutlet, Panel, Badge, EmptyState, Skeleton, Icon, WfDepartmentForm],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (!store.can('department.read')) {
      <ui-empty-state icon="lock" heading="No access" description="Your role does not allow viewing departments." />
    } @else {
      <div class="structure">
        <ui-panel heading="Departments" [subheading]="store.departments().length + ' department(s)'">
          <ng-container panelActions>
            <label class="toggle"><input type="checkbox" [checked]="store.showArchived()" (change)="store.showArchived.set(!store.showArchived())" /> Archived</label>
            @if (store.can('department.manage')) {
              <button type="button" class="btn btn--primary btn--sm" (click)="openCreate(null)"><ui-icon name="plus" [size]="14" /> New</button>
            }
          </ng-container>
          @if (store.loading() && store.departments().length === 0) {
            <ui-skeleton [rows]="5" />
          } @else if (store.departments().length === 0) {
            <ui-empty-state icon="organization" heading="No department yet" [compact]="true"
              description="Create departments (and nest them) to describe how the organization is structured." />
          } @else {
            <ul class="tree" role="tree" aria-label="Department hierarchy">
              <ng-container *ngTemplateOutlet="nodes; context: { $implicit: store.departmentTree(), depth: 0 }" />
            </ul>
          }
        </ui-panel>

        <ng-template #nodes let-list let-depth="depth">
          @for (node of list; track node.department.id) {
            <li role="treeitem" [attr.aria-selected]="node.department.id === selectedId()" [attr.aria-level]="depth + 1">
              <button type="button" class="tree__row" [class.is-selected]="node.department.id === selectedId()"
                [style.padding-left.px]="8 + depth * 18" (click)="selectedId.set(node.department.id)">
                <span class="tree__name">{{ node.department.name }}</span>
                <span class="tree__code">{{ node.department.code }}</span>
                @if (node.department.status === 'ARCHIVED') { <ui-badge label="Archived" tone="neutral" [dot]="false" /> }
                <span class="tree__count" [attr.title]="node.department.employeeCount + ' employee(s)'">{{ node.department.employeeCount }}</span>
              </button>
              @if (node.children.length > 0) {
                <ul role="group">
                  <ng-container *ngTemplateOutlet="nodes; context: { $implicit: node.children, depth: depth + 1 }" />
                </ul>
              }
            </li>
          }
        </ng-template>

        @if (selected(); as department) {
          <ui-panel [heading]="department.name" [subheading]="department.code">
            <ng-container panelActions>
              <ui-badge [label]="archiveLabel[department.status]" [tone]="archiveTone[department.status]" />
              @if (store.can('department.manage')) {
                <button type="button" class="btn btn--secondary btn--sm" (click)="editing.set(department)">Edit</button>
              }
            </ng-container>
            @if (department.description) { <p>{{ department.description }}</p> }
            <dl class="meta">
              <dt>Head</dt><dd>{{ department.head?.displayName ?? 'No head assigned' }}</dd>
              <dt>Parent</dt><dd>{{ parentName(department) }}</dd>
              <dt>Employees</dt><dd>{{ department.employeeCount }}</dd>
              <dt>Sub-departments</dt><dd>{{ department.childCount }}</dd>
            </dl>

            @if (store.can('department.manage')) {
              <div class="actions">
                <div class="inline">
                  <label class="sr-only" for="wf-move">Parent department</label>
                  <select id="wf-move" class="select" [value]="department.parentId ?? ''" #parent>
                    <option value="">Top level</option>
                    @for (row of store.activeDepartments(); track row.department.id) {
                      @if (row.department.id !== department.id) {
                        <option [value]="row.department.id" [selected]="row.department.id === department.parentId">{{ '— '.repeat(row.depth) }}{{ row.department.name }}</option>
                      }
                    }
                  </select>
                  <button type="button" class="btn btn--secondary btn--sm" (click)="move(department, parent.value)">Move</button>
                </div>
                <div class="inline">
                  <label class="sr-only" for="wf-head">Department head</label>
                  <select id="wf-head" class="select" #head>
                    <option value="">No head</option>
                    @for (employee of store.currentEmployees(); track employee.id) {
                      <option [value]="employee.id" [selected]="employee.id === department.head?.id">{{ employee.displayName }}</option>
                    }
                  </select>
                  <button type="button" class="btn btn--secondary btn--sm" (click)="assignHead(department, head.value)">Set head</button>
                </div>
                <div class="inline">
                  <button type="button" class="btn btn--ghost btn--sm" (click)="openCreate(department.id)">Add sub-department</button>
                  @if (department.status === 'ACTIVE') {
                    <button type="button" class="btn btn--ghost btn--sm" (click)="archive(department)">Archive</button>
                  } @else {
                    <button type="button" class="btn btn--ghost btn--sm" (click)="restore(department)">Restore</button>
                  }
                  <button type="button" class="btn btn--ghost btn--sm danger" (click)="remove(department)">Delete</button>
                </div>
              </div>
            }

            <h3 class="section">Employees</h3>
            @if (!store.can('employee.read')) {
              <p class="text-tertiary">Your role does not allow viewing employees.</p>
            } @else if (members.loading()) {
              <ui-skeleton [rows]="3" />
            } @else {
              <ul class="plain">
                @for (employee of members.value()?.content ?? []; track employee.id) {
                  <li>{{ employee.displayName }} <span class="text-tertiary">· {{ employee.position?.title ?? 'No position' }}</span>
                    <ui-badge [label]="employmentLabel[employee.status]" [tone]="employmentTone[employee.status]" [dot]="false" />
                  </li>
                } @empty { <li class="text-tertiary">Nobody is assigned to this department.</li> }
              </ul>
            }

            <h3 class="section">Positions and teams</h3>
            <p class="text-tertiary">
              {{ positionsHere().length }} position(s) · {{ teamsHere().length }} team(s) associated with this department
            </p>
            @if (positionsHere().length > 0 || teamsHere().length > 0) {
              <ul class="plain">
                @for (position of positionsHere(); track position.id) { <li>Position: {{ position.title }}</li> }
                @for (team of teamsHere(); track team.id) { <li>Team: {{ team.name }} <span class="text-tertiary">· {{ team.memberCount }} member(s)</span></li> }
              </ul>
            }
          </ui-panel>
        } @else {
          <ui-empty-state icon="organization" heading="Select a department" [compact]="true"
            description="Choose a department in the hierarchy to see its head, employees, positions and teams." />
        }
      </div>
    }
    <app-wf-department-form [open]="formOpen()" [department]="editing()" [defaultParentId]="newParent()"
      (closed)="closeForm()" (saved)="onSaved($event)" />
  `,
  styles: `
    .structure { display: grid; grid-template-columns: minmax(280px, 380px) minmax(0, 1fr); gap: var(--space-5); align-items: start; }
    .tree, .tree ul, .plain { list-style: none; margin: 0; padding: 0; }
    .tree__row { display: flex; align-items: center; gap: var(--space-2); width: 100%; height: 32px; padding-right: var(--space-2);
      border: 0; border-radius: var(--radius-md); background: transparent; color: var(--color-text-primary); text-align: left; cursor: pointer; }
    .tree__row:hover { background: var(--color-surface-hover); }
    .tree__row.is-selected { background: var(--color-surface-2); box-shadow: inset 2px 0 0 var(--color-accent); }
    .tree__name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .tree__code { font-family: var(--font-mono); font-size: var(--text-xs); color: var(--color-text-tertiary); }
    .tree__count { min-width: 22px; text-align: center; font-size: var(--text-xs); color: var(--color-text-secondary); }
    .toggle { display: inline-flex; align-items: center; gap: var(--space-1); font-size: var(--text-sm); color: var(--color-text-secondary); }
    .meta { display: grid; grid-template-columns: max-content 1fr; gap: var(--space-1) var(--space-4); margin: var(--space-3) 0; }
    .meta dt { color: var(--color-text-tertiary); } .meta dd { margin: 0; }
    .actions { display: flex; flex-direction: column; gap: var(--space-2); margin: var(--space-3) 0; }
    .inline { display: flex; align-items: center; gap: var(--space-2); flex-wrap: wrap; }
    .section { margin: var(--space-4) 0 var(--space-2); font-size: var(--text-md); }
    .plain li { padding: var(--space-1) 0; }
    .danger { color: var(--color-danger); }
    @media (max-width: 900px) { .structure { grid-template-columns: minmax(0, 1fr); } }
  `,
})
export class WfStructure {
  protected readonly store = inject(WorkforceStore);
  private readonly api = inject(WorkforceApi);
  private readonly toast = inject(ToastService);
  private readonly confirm = inject(ConfirmService);

  protected readonly archiveLabel = ARCHIVE_LABEL;
  protected readonly archiveTone = ARCHIVE_TONE;
  protected readonly employmentLabel = EMPLOYMENT_LABEL;
  protected readonly employmentTone = EMPLOYMENT_TONE;

  protected readonly selectedId = signal<string | null>(null);
  /** Only a department of the current organization can be selected. */
  protected readonly selected = computed<DepartmentDto | null>(() => {
    const id = this.selectedId();
    return id ? (this.store.departmentById().get(id) ?? null) : null;
  });
  protected readonly editing = signal<DepartmentDto | null>(null);
  protected readonly creating = signal(false);
  protected readonly newParent = signal<string | null>(null);
  protected readonly formOpen = computed(() => this.creating() || this.editing() !== null);

  protected readonly members = orgResource(
    this.store.organizationId,
    () => this.selected() !== null && this.store.can('employee.read'),
    () => this.selectedId(),
    (organizationId, departmentId) => this.api.listEmployees(organizationId, { departmentId: departmentId ?? undefined, size: 100 }),
  );
  protected readonly positionsHere = computed(() => this.store.positions().filter((p) => p.department?.id === this.selectedId()));
  protected readonly teamsHere = computed(() => this.store.teams().filter((t) => t.department?.id === this.selectedId()));

  protected parentName(department: DepartmentDto): string {
    return department.parentId ? (this.store.departmentById().get(department.parentId)?.name ?? '—') : 'Top level';
  }

  protected openCreate(parentId: string | null): void {
    this.newParent.set(parentId);
    this.editing.set(null);
    this.creating.set(true);
  }

  protected closeForm(): void {
    this.creating.set(false);
    this.editing.set(null);
  }

  protected onSaved(department: DepartmentDto): void {
    const wasCreating = this.creating();
    this.closeForm();
    this.members.reload();
    if (wasCreating) {
      this.selectedId.set(department.id);
    }
  }

  protected move(department: DepartmentDto, parentId: string): Promise<void> {
    return this.run('Department moved', () =>
      this.store.mutate((id) => this.api.moveDepartment(id, department.id, idOrNull(parentId))));
  }

  protected assignHead(department: DepartmentDto, employeeId: string): Promise<void> {
    return this.run('Department head updated', () =>
      this.store.mutate((id) => this.api.assignDepartmentHead(id, department.id, idOrNull(employeeId))));
  }

  protected archive(department: DepartmentDto): Promise<void> {
    return this.run('Department archived', () => this.store.mutate((id) => this.api.archiveDepartment(id, department.id)));
  }

  protected restore(department: DepartmentDto): Promise<void> {
    return this.run('Department restored', () => this.store.mutate((id) => this.api.restoreDepartment(id, department.id)));
  }

  protected async remove(department: DepartmentDto): Promise<void> {
    const answer = await this.confirm.ask({
      title: `Delete “${department.name}”?`,
      message: 'Only a department without employees or sub-departments can be deleted. Archive it to keep its history.',
      confirmLabel: 'Delete',
      tone: 'danger',
    });
    if (answer.confirmed) {
      await this.run('Department deleted', async () => {
        await this.store.mutate((id) => this.api.deleteDepartment(id, department.id));
        this.selectedId.set(null);
      });
    }
  }

  private async run(success: string, action: () => Promise<unknown>): Promise<void> {
    try {
      await action();
      this.members.reload();
      this.toast.success(success);
    } catch (error) {
      this.toast.error('Action failed', error);
    }
  }
}
