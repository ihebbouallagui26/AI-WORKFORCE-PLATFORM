import { ChangeDetectionStrategy, Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { ConfirmService } from '../../core/services/confirm.service';
import { ToastService } from '../../core/services/toast.service';
import { orgResource } from '../../core/workforce/org-resource';
import { WorkforceApi } from '../../core/workforce/workforce.api';
import { PositionDto } from '../../core/workforce/workforce.models';
import { WorkforceStore } from '../../core/workforce/workforce.store';
import { Badge } from '../../shared/components/badge/badge';
import { EmptyState } from '../../shared/components/empty-state/empty-state';
import { Icon } from '../../shared/components/icon/icon';
import { Panel } from '../../shared/components/panel/panel';
import { Skeleton } from '../../shared/components/skeleton/skeleton';
import { WfPositionForm } from './position-form';
import { ARCHIVE_LABEL, ARCHIVE_TONE, EMPLOYMENT_LABEL, EMPLOYMENT_TONE } from './workforce-format';

/** Position catalog (job titles) and the employees holding the selected position. */
@Component({
  selector: 'app-wf-positions',
  imports: [Panel, Badge, EmptyState, Skeleton, Icon, WfPositionForm],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (!store.can('position.read')) {
      <ui-empty-state icon="lock" heading="No access" description="Your role does not allow viewing positions." />
    } @else {
      <div class="positions">
        <ui-panel heading="Positions" [subheading]="store.positions().length + ' position(s)'" [flush]="true">
          <ng-container panelActions>
            <label class="toggle"><input type="checkbox" [checked]="store.showArchived()" (change)="store.showArchived.set(!store.showArchived())" /> Archived</label>
            @if (store.can('position.manage')) {
              <button type="button" class="btn btn--primary btn--sm" (click)="creating.set(true)"><ui-icon name="plus" [size]="14" /> New position</button>
            }
          </ng-container>
          @if (store.error(); as message) {
            <ui-empty-state icon="circle-alert" tone="danger" heading="Positions unavailable" [description]="message" [compact]="true" />
          } @else if (store.loading() && store.positions().length === 0) {
            <ui-skeleton [rows]="5" />
          } @else if (store.positions().length === 0) {
            <ui-empty-state icon="agent" heading="No position yet" [compact]="true"
              description="Positions are professional job titles such as “Frontend Engineer”. They do not grant any permission." />
          } @else {
            <div class="table-wrap">
              <table class="table">
                <thead><tr><th>Title</th><th>Code</th><th>Department</th><th>Employees</th><th>Status</th><th><span class="sr-only">Actions</span></th></tr></thead>
                <tbody>
                  @for (position of store.positions(); track position.id) {
                    <tr class="row" [class.is-selected]="position.id === selectedId()" tabindex="0"
                      (click)="selectedId.set(position.id)" (keydown.enter)="selectedId.set(position.id)">
                      <td class="name">{{ position.title }}</td>
                      <td class="mono">{{ position.code }}</td>
                      <td>{{ position.department?.name ?? 'Any' }}</td>
                      <td>{{ position.employeeCount }}</td>
                      <td><ui-badge [label]="archiveLabel[position.status]" [tone]="archiveTone[position.status]" /></td>
                      <td class="actions">
                        @if (store.can('position.manage')) {
                          <button type="button" class="btn btn--ghost btn--sm" (click)="edit(position, $event)">Edit</button>
                          @if (position.status === 'ACTIVE') {
                            <button type="button" class="btn btn--ghost btn--sm" (click)="archive(position, $event)">Archive</button>
                          } @else {
                            <button type="button" class="btn btn--ghost btn--sm" (click)="restore(position, $event)">Restore</button>
                          }
                          <button type="button" class="btn btn--ghost btn--sm danger" (click)="remove(position, $event)">Delete</button>
                        }
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          }
        </ui-panel>

        @if (selected(); as position) {
          <ui-panel [heading]="position.title" [subheading]="'Employees holding this position'">
            @if (position.description) { <p>{{ position.description }}</p> }
            @if (!store.can('employee.read')) {
              <p class="text-tertiary">Your role does not allow viewing employees.</p>
            } @else if (holders.loading() && !holders.value()) {
              <ui-skeleton [rows]="3" />
            } @else {
              <ul class="plain">
                @for (employee of holders.value()?.content ?? []; track employee.id) {
                  <li>{{ employee.displayName }} <span class="text-tertiary">· {{ employee.department?.name ?? 'No department' }}</span>
                    <ui-badge [label]="employmentLabel[employee.status]" [tone]="employmentTone[employee.status]" [dot]="false" /></li>
                } @empty { <li class="text-tertiary">Nobody holds this position.</li> }
              </ul>
            }
          </ui-panel>
        } @else {
          <ui-empty-state icon="users" heading="Select a position" [compact]="true" description="Choose a position to see who holds it." />
        }
      </div>
    }
    <app-wf-position-form [open]="creating() || editing() !== null" [position]="editing()" (closed)="closeForm()" (saved)="onSaved()" />
  `,
  styles: `
    .positions { display: grid; grid-template-columns: minmax(0, 1.6fr) minmax(280px, 1fr); gap: var(--space-5); align-items: start; }
    .row { cursor: pointer; } .row:hover, .row.is-selected { background: var(--color-surface-hover); }
    .name { font-weight: var(--weight-medium); } .mono { font-family: var(--font-mono); }
    .actions { text-align: right; white-space: nowrap; }
    .toggle { display: inline-flex; align-items: center; gap: var(--space-1); font-size: var(--text-sm); color: var(--color-text-secondary); }
    .plain { list-style: none; margin: 0; padding: 0; } .plain li { padding: var(--space-1) 0; }
    .danger { color: var(--color-danger); }
    @media (max-width: 1000px) { .positions { grid-template-columns: minmax(0, 1fr); } }
  `,
})
export class WfPositions {
  protected readonly store = inject(WorkforceStore);
  private readonly api = inject(WorkforceApi);
  private readonly toast = inject(ToastService);
  private readonly confirm = inject(ConfirmService);

  protected readonly archiveLabel = ARCHIVE_LABEL;
  protected readonly archiveTone = ARCHIVE_TONE;
  protected readonly employmentLabel = EMPLOYMENT_LABEL;
  protected readonly employmentTone = EMPLOYMENT_TONE;

  protected readonly selectedId = signal<string | null>(null);
  protected readonly creating = signal(false);
  protected readonly editing = signal<PositionDto | null>(null);
  protected readonly selected = computed(() => this.store.positions().find((p) => p.id === this.selectedId()) ?? null);

  protected readonly holders = orgResource(
    this.store.organizationId,
    () => this.selected() !== null && this.store.can('employee.read'),
    () => this.selectedId(),
    (organizationId, positionId) => this.api.listEmployees(organizationId, { positionId: positionId ?? undefined, size: 100 }),
  );

  constructor() {
    effect(() => {
      this.store.organizationId();
      untracked(() => this.selectedId.set(null));
    });
  }

  protected edit(position: PositionDto, event: Event): void {
    event.stopPropagation();
    this.editing.set(position);
  }

  protected closeForm(): void {
    this.creating.set(false);
    this.editing.set(null);
  }

  protected onSaved(): void {
    this.closeForm();
    this.holders.reload();
  }

  protected archive(position: PositionDto, event: Event): Promise<void> {
    event.stopPropagation();
    return this.run('Position archived', (id) => this.api.archivePosition(id, position.id));
  }

  protected restore(position: PositionDto, event: Event): Promise<void> {
    event.stopPropagation();
    return this.run('Position restored', (id) => this.api.restorePosition(id, position.id));
  }

  protected async remove(position: PositionDto, event: Event): Promise<void> {
    event.stopPropagation();
    const answer = await this.confirm.ask({
      title: `Delete “${position.title}”?`,
      message: 'A position held by employees cannot be deleted: archive it instead.',
      confirmLabel: 'Delete',
      tone: 'danger',
    });
    if (answer.confirmed) {
      await this.run('Position deleted', (id) => this.api.deletePosition(id, position.id));
    }
  }

  private async run(success: string, change: (organizationId: string) => Promise<unknown>): Promise<void> {
    try {
      await this.store.mutate(change);
      this.holders.reload();
      this.toast.success(success);
    } catch (error) {
      this.toast.error('Action failed', error);
    }
  }
}
