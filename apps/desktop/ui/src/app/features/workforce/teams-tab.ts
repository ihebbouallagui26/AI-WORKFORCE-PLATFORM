import { ChangeDetectionStrategy, Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { ConfirmService } from '../../core/services/confirm.service';
import { ToastService } from '../../core/services/toast.service';
import { orgResource } from '../../core/workforce/org-resource';
import { WorkforceApi } from '../../core/workforce/workforce.api';
import { TeamDto, TeamMemberDto } from '../../core/workforce/workforce.models';
import { WorkforceStore } from '../../core/workforce/workforce.store';
import { Badge } from '../../shared/components/badge/badge';
import { EmptyState } from '../../shared/components/empty-state/empty-state';
import { Icon } from '../../shared/components/icon/icon';
import { Panel } from '../../shared/components/panel/panel';
import { Skeleton } from '../../shared/components/skeleton/skeleton';
import { WfTeamForm } from './team-form';
import { ARCHIVE_LABEL, ARCHIVE_TONE, idOrNull } from './workforce-format';

/** Teams: members, leader and department association. */
@Component({
  selector: 'app-wf-teams',
  imports: [Panel, Badge, EmptyState, Skeleton, Icon, WfTeamForm],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (!store.can('team.read')) {
      <ui-empty-state icon="lock" heading="No access" description="Your role does not allow viewing teams." />
    } @else {
      <div class="teams">
        <ui-panel heading="Teams" [subheading]="store.teams().length + ' team(s)'">
          <ng-container panelActions>
            <label class="toggle"><input type="checkbox" [checked]="store.showArchived()" (change)="store.showArchived.set(!store.showArchived())" /> Archived</label>
            @if (store.can('team.manage')) {
              <button type="button" class="btn btn--primary btn--sm" (click)="creating.set(true)"><ui-icon name="plus" [size]="14" /> New team</button>
            }
          </ng-container>
          @if (store.error(); as message) {
            <ui-empty-state icon="circle-alert" tone="danger" heading="Teams unavailable" [description]="message" [compact]="true" />
          } @else if (store.loading() && store.teams().length === 0) {
            <ui-skeleton [rows]="4" />
          } @else if (store.teams().length === 0) {
            <ui-empty-state icon="users" heading="No team yet" [compact]="true"
              description="Teams group employees across departments to work together." />
          } @else {
            <ul class="list">
              @for (team of store.teams(); track team.id) {
                <li>
                  <button type="button" class="item" [class.is-selected]="team.id === selectedId()" (click)="selectedId.set(team.id)">
                    <span class="item__name">{{ team.name }}</span>
                    <span class="text-tertiary">{{ team.department?.name ?? 'No department' }} · {{ team.memberCount }} member(s)</span>
                    @if (team.status === 'ARCHIVED') { <ui-badge label="Archived" tone="neutral" [dot]="false" /> }
                  </button>
                </li>
              }
            </ul>
          }
        </ui-panel>

        @if (selected(); as team) {
          <ui-panel [heading]="team.name" [subheading]="team.department?.name ?? 'No department'">
            <ng-container panelActions>
              <ui-badge [label]="archiveLabel[team.status]" [tone]="archiveTone[team.status]" />
              @if (store.can('team.manage')) {
                <button type="button" class="btn btn--secondary btn--sm" (click)="editing.set(team)">Edit</button>
                @if (team.status === 'ACTIVE') {
                  <button type="button" class="btn btn--ghost btn--sm" (click)="archive(team)">Archive</button>
                } @else {
                  <button type="button" class="btn btn--ghost btn--sm" (click)="restore(team)">Restore</button>
                }
              }
            </ng-container>
            @if (team.description) { <p>{{ team.description }}</p> }
            <p><strong>Leader:</strong> {{ team.leader?.displayName ?? 'No leader' }}</p>

            @if (store.can('team.manage') && team.status === 'ACTIVE') {
              <div class="controls">
                <label class="sr-only" for="t-department">Department</label>
                <select id="t-department" class="select" (change)="changeDepartment(team, $event)">
                  <option value="" [selected]="!team.department">No department</option>
                  @for (row of store.activeDepartments(); track row.department.id) {
                    <option [value]="row.department.id" [selected]="row.department.id === team.department?.id">{{ '— '.repeat(row.depth) }}{{ row.department.name }}</option>
                  }
                </select>
                <label class="sr-only" for="t-leader">Leader</label>
                <select id="t-leader" class="select" (change)="assignLeader(team, $event)">
                  <option value="" [selected]="!team.leader">No leader</option>
                  @for (member of members(); track member.employee.id) {
                    <option [value]="member.employee.id" [selected]="member.employee.id === team.leader?.id">{{ member.employee.displayName }}</option>
                  }
                </select>
              </div>
              <div class="controls">
                <label class="sr-only" for="t-add">Add a member</label>
                <select id="t-add" class="select" #candidate>
                  <option value="">Add a member…</option>
                  @for (employee of candidates(); track employee.id) { <option [value]="employee.id">{{ employee.displayName }}</option> }
                </select>
                <button type="button" class="btn btn--secondary btn--sm" (click)="addMember(team, candidate.value); candidate.value = ''">Add</button>
              </div>
            }

            <h3 class="section">Members ({{ members().length }})</h3>
            @if (detail.loading() && !detail.value()) {
              <ui-skeleton [rows]="3" />
            } @else {
              <ul class="plain">
                @for (member of members(); track member.employee.id) {
                  <li>
                    <span>{{ member.employee.displayName }} @if (member.leader) { <ui-badge label="Leader" tone="accent" [dot]="false" /> }</span>
                    @if (store.can('team.manage') && team.status === 'ACTIVE') {
                      <button type="button" class="btn btn--ghost btn--sm danger" [disabled]="member.leader"
                        [attr.title]="member.leader ? 'Assign another leader first' : null" (click)="removeMember(team, member)">Remove</button>
                    }
                  </li>
                } @empty { <li class="text-tertiary">This team has no member yet.</li> }
              </ul>
            }
          </ui-panel>
        } @else {
          <ui-empty-state icon="users" heading="Select a team" [compact]="true" description="Choose a team to see and manage its members." />
        }
      </div>
    }
    <app-wf-team-form [open]="creating() || editing() !== null" [team]="editing()" (closed)="closeForm()" (saved)="onSaved($event)" />
  `,
  styles: `
    .teams { display: grid; grid-template-columns: minmax(260px, 380px) minmax(0, 1fr); gap: var(--space-5); align-items: start; }
    .list, .plain { list-style: none; margin: 0; padding: 0; }
    .item { display: flex; flex-direction: column; gap: 2px; width: 100%; padding: var(--space-2) var(--space-3); border: 0; border-radius: var(--radius-md);
      background: transparent; color: var(--color-text-primary); text-align: left; cursor: pointer; }
    .item:hover { background: var(--color-surface-hover); }
    .item.is-selected { background: var(--color-surface-2); box-shadow: inset 2px 0 0 var(--color-accent); }
    .item__name { font-weight: var(--weight-medium); }
    .controls { display: flex; gap: var(--space-2); margin: var(--space-2) 0; flex-wrap: wrap; }
    .section { margin: var(--space-4) 0 var(--space-2); font-size: var(--text-md); }
    .plain li { display: flex; align-items: center; justify-content: space-between; padding: var(--space-1) 0; }
    .toggle { display: inline-flex; align-items: center; gap: var(--space-1); font-size: var(--text-sm); color: var(--color-text-secondary); }
    .danger { color: var(--color-danger); }
    @media (max-width: 900px) { .teams { grid-template-columns: minmax(0, 1fr); } }
  `,
})
export class WfTeams {
  protected readonly store = inject(WorkforceStore);
  private readonly api = inject(WorkforceApi);
  private readonly toast = inject(ToastService);
  private readonly confirm = inject(ConfirmService);

  protected readonly archiveLabel = ARCHIVE_LABEL;
  protected readonly archiveTone = ARCHIVE_TONE;

  protected readonly selectedId = signal<string | null>(null);
  protected readonly creating = signal(false);
  protected readonly editing = signal<TeamDto | null>(null);
  protected readonly selected = computed(() => this.store.teams().find((t) => t.id === this.selectedId()) ?? null);

  protected readonly detail = orgResource(
    this.store.organizationId,
    () => this.selected() !== null && this.store.can('team.read'),
    () => this.selectedId(),
    (organizationId, teamId) => this.api.getTeam(organizationId, teamId ?? ''),
  );
  protected readonly members = computed<readonly TeamMemberDto[]>(() => this.detail.value()?.members ?? []);
  /** Current employees who are not yet in the team. */
  protected readonly candidates = computed(() => {
    const inTeam = new Set(this.members().map((m) => m.employee.id));
    return this.store.currentEmployees().filter((e) => !inTeam.has(e.id));
  });

  constructor() {
    effect(() => {
      this.store.organizationId();
      untracked(() => this.selectedId.set(null));
    });
  }

  protected closeForm(): void {
    this.creating.set(false);
    this.editing.set(null);
  }

  protected onSaved(team: TeamDto): void {
    const wasCreating = this.creating();
    this.closeForm();
    this.detail.reload();
    if (wasCreating) {
      this.selectedId.set(team.id);
    }
  }

  protected changeDepartment(team: TeamDto, event: Event): Promise<void> {
    return this.run('Department updated', (id) => this.api.changeTeamDepartment(id, team.id, idOrNull(value(event))));
  }

  protected assignLeader(team: TeamDto, event: Event): Promise<void> {
    return this.run('Leader updated', (id) => this.api.assignTeamLeader(id, team.id, idOrNull(value(event))));
  }

  protected addMember(team: TeamDto, employeeId: string): Promise<void> {
    return employeeId ? this.run('Member added', (id) => this.api.addTeamMember(id, team.id, employeeId)) : Promise.resolve();
  }

  protected async removeMember(team: TeamDto, member: TeamMemberDto): Promise<void> {
    const answer = await this.confirm.ask({
      title: `Remove ${member.employee.displayName}?`,
      message: 'They leave the team. The membership history is kept.',
      confirmLabel: 'Remove',
      tone: 'danger',
    });
    if (answer.confirmed) {
      await this.run('Member removed', (id) => this.api.removeTeamMember(id, team.id, member.employee.id));
    }
  }

  protected archive(team: TeamDto): Promise<void> {
    return this.run('Team archived', (id) => this.api.archiveTeam(id, team.id));
  }

  protected restore(team: TeamDto): Promise<void> {
    return this.run('Team restored', (id) => this.api.restoreTeam(id, team.id));
  }

  private async run(success: string, change: (organizationId: string) => Promise<unknown>): Promise<void> {
    try {
      await this.store.mutate(change);
      this.detail.reload();
      this.toast.success(success);
    } catch (error) {
      this.detail.reload();
      this.toast.error('Action failed', error);
    }
  }
}

function value(event: Event): string {
  return (event.target as HTMLSelectElement).value;
}
