import { ChangeDetectionStrategy, Component, effect, inject, signal, untracked } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { AgentsApi } from '../../core/agents/agents.api';
import { AgentStatus } from '../../core/agents/agents.models';
import { AgentsStore } from '../../core/agents/agents.store';
import { ToastService } from '../../core/services/toast.service';
import { WorkforceStore } from '../../core/workforce/workforce.store';
import { Badge } from '../../shared/components/badge/badge';
import { EmptyState } from '../../shared/components/empty-state/empty-state';
import { Icon } from '../../shared/components/icon/icon';
import { Modal } from '../../shared/components/modal/modal';
import { Panel } from '../../shared/components/panel/panel';
import { Skeleton } from '../../shared/components/skeleton/skeleton';
import { errorMessage, notBlank } from '../../shared/utils/validators';
import { AgentDetailPanel } from './agent-detail';
import { AGENT_STATUS_LABEL, AGENT_STATUS_TONE, initials } from './ai-agents-format';

/** Agent list with search and status filter, a creation dialog and the selected agent's detail. */
@Component({
  selector: 'app-agents-tab',
  imports: [ReactiveFormsModule, Panel, Badge, EmptyState, Skeleton, Icon, Modal, AgentDetailPanel],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (!store.can('agent.read')) {
      <ui-empty-state icon="lock" heading="No access" description="Your role does not allow viewing AI agents." />
    } @else {
      <div class="agents">
        <ui-panel heading="Agents" [subheading]="store.totalAgents() + ' agent(s)'">
          <ng-container panelActions>
            @if (store.can('agent.create')) {
              <button type="button" class="btn btn--primary btn--sm" (click)="openCreate()"><ui-icon name="plus" [size]="14" /> New agent</button>
            }
          </ng-container>
          <div class="filters">
            <label class="sr-only" for="ag-search">Search agents</label>
            <input id="ag-search" class="input" type="search" placeholder="Search by name, handle or title" [value]="store.search()"
              (input)="store.search.set(text($event))" />
            <label class="sr-only" for="ag-status">Status</label>
            <select id="ag-status" class="select" (change)="store.statusFilter.set(status($event))">
              <option value="">All statuses</option>
              @for (entry of statuses; track entry[0]) { <option [value]="entry[0]" [selected]="store.statusFilter() === entry[0]">{{ entry[1] }}</option> }
            </select>
          </div>
          @if (store.error(); as message) {
            <ui-empty-state icon="circle-alert" tone="danger" heading="Agents unavailable" [description]="message" [compact]="true" />
          } @else if (store.loading() && store.agents().length === 0) {
            <ui-skeleton [rows]="4" />
          } @else if (store.agents().length === 0) {
            <ui-empty-state icon="users" heading="No agent found" [compact]="true"
              description="Create an AI employee, then bind it to a provider and activate it." />
          } @else {
            <ul class="list">
              @for (agent of store.agents(); track agent.id) {
                <li>
                  <button type="button" class="item" [class.is-selected]="agent.id === selectedId()" (click)="selectedId.set(agent.id)">
                    <span class="avatar" [attr.data-tone]="agent.avatarTone" aria-hidden="true">{{ initials(agent.name) }}</span>
                    <span class="item__text">
                      <span class="item__name">{{ agent.name }}</span>
                      <span class="text-tertiary">&#64;{{ agent.handle }} · {{ agent.title || 'No title' }}</span>
                    </span>
                    <ui-badge [label]="statusLabel[agent.status]" [tone]="statusTone[agent.status]" />
                  </button>
                </li>
              }
            </ul>
          }
        </ui-panel>

        @if (selectedId(); as id) {
          <app-agent-detail [agentId]="id" (changed)="store.reload()" />
        } @else {
          <ui-empty-state icon="users" heading="Select an agent" [compact]="true" description="Choose an agent to configure it." />
        }
      </div>
    }

    <ui-modal heading="New AI agent" description="An agent is not a user account and not an employee. It starts as a draft."
      [open]="creating()" [locked]="saving()" (closed)="creating.set(false)">
      <form id="agent-form" class="form-grid" [formGroup]="form" (ngSubmit)="submit()" novalidate>
        <div class="field">
          <label class="field__label" for="ag-name">Name<span class="field__required" aria-hidden="true">*</span></label>
          <input id="ag-name" class="input" formControlName="name" maxlength="100" autocomplete="off" />
          @if (error('name', 'Name'); as message) { <span class="field__error">{{ message }}</span> }
        </div>
        <div class="field">
          <label class="field__label" for="ag-handle">Handle</label>
          <input id="ag-handle" class="input" formControlName="handle" maxlength="40" autocomplete="off" />
          <span class="field__hint">Unique in the organization. Generated from the name when empty.</span>
          @if (error('handle', 'Handle'); as message) { <span class="field__error">{{ message }}</span> }
        </div>
        <div class="field">
          <label class="field__label" for="ag-title">Title</label>
          <input id="ag-title" class="input" formControlName="title" maxlength="100" autocomplete="off" />
        </div>
        <div class="field">
          <label class="field__label" for="ag-department">Department</label>
          <select id="ag-department" class="select" formControlName="departmentId">
            <option value="">None</option>
            @for (row of workforce.activeDepartments(); track row.department.id) {
              <option [value]="row.department.id">{{ '— '.repeat(row.depth) }}{{ row.department.name }}</option>
            }
          </select>
        </div>
        <div class="field field--full">
          <label class="field__label" for="ag-description">Description</label>
          <textarea id="ag-description" class="textarea" rows="3" maxlength="1000" formControlName="description"></textarea>
        </div>
      </form>
      <ng-container modalFooter>
        <button type="button" class="btn btn--secondary" [disabled]="saving()" (click)="creating.set(false)">Cancel</button>
        <button type="submit" form="agent-form" class="btn btn--primary" [disabled]="saving()">Create agent</button>
      </ng-container>
    </ui-modal>
  `,
  styles: `
    .agents { display: grid; grid-template-columns: minmax(280px, 400px) minmax(0, 1fr); gap: var(--space-5); align-items: start; }
    .filters { display: flex; gap: var(--space-2); margin-bottom: var(--space-3); }
    .filters .input { flex: 1; min-width: 0; }
    .list { list-style: none; margin: 0; padding: 0; }
    .item { display: flex; align-items: center; gap: var(--space-3); width: 100%; padding: var(--space-2) var(--space-3); border: 0; border-radius: var(--radius-md);
      background: transparent; color: var(--color-text-primary); text-align: left; cursor: pointer; }
    .item:hover { background: var(--color-surface-hover); }
    .item.is-selected { background: var(--color-surface-2); box-shadow: inset 2px 0 0 var(--color-accent); }
    .item__text { display: flex; flex-direction: column; flex: 1; min-width: 0; }
    .item__name { font-weight: var(--weight-medium); }
    .avatar { display: inline-grid; place-items: center; width: 32px; height: 32px; border-radius: 50%; font-size: var(--text-sm);
      background: var(--color-surface-2); color: var(--color-text-secondary); }
    @media (max-width: 900px) { .agents { grid-template-columns: minmax(0, 1fr); } }
  `,
})
export class AgentsTab {
  protected readonly store = inject(AgentsStore);
  protected readonly workforce = inject(WorkforceStore);
  private readonly api = inject(AgentsApi);
  private readonly toast = inject(ToastService);

  protected readonly statusLabel = AGENT_STATUS_LABEL;
  protected readonly statusTone = AGENT_STATUS_TONE;
  protected readonly statuses = Object.entries(AGENT_STATUS_LABEL);
  protected readonly initials = initials;

  protected readonly selectedId = signal<string | null>(null);
  protected readonly creating = signal(false);
  protected readonly saving = signal(false);

  protected readonly form = inject(NonNullableFormBuilder).group({
    name: ['', [Validators.required, notBlank, Validators.minLength(2), Validators.maxLength(100)]],
    handle: ['', Validators.pattern(/^$|^[a-z0-9][a-z0-9-]{1,39}$/)],
    title: ['', Validators.maxLength(100)],
    departmentId: [''],
    description: ['', Validators.maxLength(1000)],
  });

  constructor() {
    // A selection never survives an organization switch.
    effect(() => {
      this.store.organizationId();
      untracked(() => this.selectedId.set(null));
    });
  }

  protected text(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected status(event: Event): AgentStatus | '' {
    return (event.target as HTMLSelectElement).value as AgentStatus | '';
  }

  protected openCreate(): void {
    this.form.reset({ name: '', handle: '', title: '', departmentId: '', description: '' });
    this.creating.set(true);
  }

  protected error(name: 'name' | 'handle', label: string): string | null {
    return errorMessage(this.form.controls[name], label);
  }

  protected async submit(): Promise<void> {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.saving()) {
      return;
    }
    const value = this.form.getRawValue();
    this.saving.set(true);
    try {
      const created = await this.store.mutate((organizationId) =>
        this.api.createAgent(organizationId, {
          name: value.name.trim(),
          ...(value.handle ? { handle: value.handle } : {}),
          ...(value.title.trim() ? { title: value.title.trim() } : {}),
          ...(value.description.trim() ? { description: value.description.trim() } : {}),
          ...(value.departmentId ? { departmentId: value.departmentId } : {}),
        }),
      );
      this.toast.success('Agent created', `${created.agent.name} is a draft.`);
      this.creating.set(false);
      this.selectedId.set(created.agent.id);
    } catch (error) {
      this.toast.error('Could not create the agent', error);
    } finally {
      this.saving.set(false);
    }
  }
}
