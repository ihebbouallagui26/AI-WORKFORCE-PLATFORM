import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { AgentExecutions } from './agent-executions';
import { AgentsApi } from '../../core/agents/agents.api';
import {
  AgentBinding,
  AgentDetail,
  BindingRole,
  MemoryAccess,
  MemoryScope,
  ToolDefinition,
  ToolGrantInput,
  ToolLevel,
} from '../../core/agents/agents.models';
import { AgentsStore } from '../../core/agents/agents.store';
import { ConfirmService } from '../../core/services/confirm.service';
import { ToastService } from '../../core/services/toast.service';
import { orgResource } from '../../core/workforce/org-resource';
import { WorkforceStore } from '../../core/workforce/workforce.store';
import { Badge } from '../../shared/components/badge/badge';
import { EmptyState } from '../../shared/components/empty-state/empty-state';
import { Panel } from '../../shared/components/panel/panel';
import { Skeleton } from '../../shared/components/skeleton/skeleton';
import {
  AGENT_STATUS_LABEL,
  AGENT_STATUS_TONE,
  CONNECTION_STATUS_LABEL,
  CONNECTION_STATUS_TONE,
  levelAllowed,
  PROVIDER_LABEL,
  TOOL_LEVELS,
} from './ai-agents-format';

interface ToolDraft {
  readonly enabled: boolean;
  readonly level: ToolLevel;
  readonly approvalRequired: boolean;
}

/**
 * Configuration of one agent: identity, lifecycle, provider bindings (primary and fallbacks),
 * skills, tool permissions (least privilege: everything is off by default), memory settings,
 * teams and configuration history. Nothing here runs the agent.
 */
@Component({
  selector: 'app-agent-detail',
  imports: [Panel, Badge, EmptyState, Skeleton, AgentExecutions],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (detail.error(); as message) {
      <ui-empty-state icon="circle-alert" tone="danger" heading="Agent unavailable" [description]="message" [compact]="true" />
    } @else if (!current()) {
      <ui-skeleton [rows]="6" />
    } @else {
      @let d = current()!;
      <ui-panel [heading]="d.agent.name" [subheading]="'@' + d.agent.handle + ' · configuration v' + d.agent.configVersion">
        <ng-container panelActions>
          <ui-badge [label]="statusLabel[d.agent.status]" [tone]="statusTone[d.agent.status]" />
          @if (d.agent.status === 'DRAFT' || d.agent.status === 'PAUSED') {
            @if (store.can('agent.activate')) {
              <button type="button" class="btn btn--primary btn--sm" [disabled]="busy()" (click)="lifecycle('activate')">Activate</button>
            }
          }
          @if (d.agent.status === 'ACTIVE' && store.can('agent.activate')) {
            <button type="button" class="btn btn--secondary btn--sm" [disabled]="busy()" (click)="lifecycle('pause')">Pause</button>
          }
          @if (d.agent.status !== 'ARCHIVED' && store.can('agent.archive')) {
            <button type="button" class="btn btn--ghost btn--sm danger" [disabled]="busy()" (click)="archive()">Archive</button>
          }
        </ng-container>
        <p class="text-tertiary">
          Active means the agent can be run on request. Runs are always launched by a person, one at a time, as text generation only.
        </p>
        @if (d.agent.description) { <p>{{ d.agent.description }}</p> }
        <p><strong>Title:</strong> {{ d.agent.title || '—' }}</p>
        @if (d.agent.responsibilities.length) {
          <ul class="plain">@for (item of d.agent.responsibilities; track item) { <li>{{ item }}</li> }</ul>
        }
        @if (editable() && d.agent.status !== 'ARCHIVED') {
          <label class="field__label" for="ad-department">Department</label>
          <select id="ad-department" class="select" (change)="changeDepartment($event)">
            <option value="" [selected]="!d.agent.department">None</option>
            @for (row of workforce.activeDepartments(); track row.department.id) {
              <option [value]="row.department.id" [selected]="row.department.id === d.agent.department?.id">{{ '— '.repeat(row.depth) }}{{ row.department.name }}</option>
            }
          </select>
        } @else {
          <p><strong>Department:</strong> {{ d.agent.department?.name ?? 'None' }}</p>
        }
        @if (d.systemInstructions !== null) {
          <details class="instructions">
            <summary>System instructions</summary>
            <pre>{{ d.systemInstructions || 'None' }}</pre>
          </details>
        }
      </ui-panel>

      <app-agent-executions [detail]="d" />

      <ui-panel heading="Model providers" subheading="One primary model; fallbacks are tried in priority order in a later phase">
        @if (d.providers.length === 0) {
          <p class="text-tertiary">No provider bound. An agent needs a primary binding on a valid connection to be activated.</p>
        }
        <ul class="plain">
          @for (binding of d.providers; track binding.id) {
            <li>
              <span>
                <strong>{{ binding.modelId }}</strong>
                <span class="text-tertiary"> · {{ binding.connection.displayName }} ({{ providerLabel[binding.connection.providerType] }})</span>
              </span>
              <span class="actions">
                <ui-badge [label]="binding.role === 'PRIMARY' ? 'Primary' : 'Fallback ' + binding.priority" [tone]="binding.role === 'PRIMARY' ? 'accent' : 'neutral'" [dot]="false" />
                <ui-badge [label]="connLabel[binding.connection.status]" [tone]="connTone[binding.connection.status]" />
                @if (editable()) {
                  @if (binding.role !== 'PRIMARY') {
                    <button type="button" class="btn btn--ghost btn--sm" (click)="makePrimary(binding)">Make primary</button>
                  }
                  <button type="button" class="btn btn--ghost btn--sm danger" (click)="removeBinding(binding)">Remove</button>
                }
              </span>
            </li>
          }
        </ul>
        @if (editable() && store.can('provider.read') && d.agent.status !== 'ARCHIVED') {
          <div class="controls">
            <label class="sr-only" for="b-conn">Connection</label>
            <select id="b-conn" class="select" (change)="bindingConnection.set(value($event))">
              <option value="">Connection…</option>
              @for (c of store.usableConnections(); track c.id) { <option [value]="c.id">{{ c.displayName }} ({{ providerLabel[c.providerType] }})</option> }
            </select>
            <label class="sr-only" for="b-model">Model</label>
            @if (models()?.length) {
              <select id="b-model" class="select" (change)="bindingModel.set(value($event))">
                <option value="">Model…</option>
                @for (m of models(); track m.id) { <option [value]="m.id">{{ m.displayName || m.id }}</option> }
              </select>
            } @else {
              <input id="b-model" class="input" placeholder="Model id" maxlength="120" autocomplete="off" (input)="bindingModel.set(value($event))" />
            }
            <label class="sr-only" for="b-role">Role</label>
            <select id="b-role" class="select" (change)="bindingRole.set(roleValue($event))">
              <option value="PRIMARY" [selected]="bindingRole() === 'PRIMARY'">Primary</option>
              <option value="FALLBACK" [selected]="bindingRole() === 'FALLBACK'">Fallback</option>
            </select>
            <button type="button" class="btn btn--secondary btn--sm" [disabled]="!bindingConnection() || !bindingModel().trim() || busy()" (click)="addBinding()">Bind</button>
          </div>
          @if (bindingConnection() && modelsLoading()) { <p class="text-tertiary">Loading models from the provider…</p> }
          @if (bindingConnection() && modelsError(); as message) { <p class="text-tertiary">Model list unavailable ({{ message }}). Enter a model id manually.</p> }
        }
      </ui-panel>

      <ui-panel heading="Skills">
        @if (store.skills().length === 0) {
          <p class="text-tertiary">No skill in the organization yet.</p>
        }
        <ul class="plain">
          @for (skill of store.skills(); track skill.id) {
            <li>
              <label class="check">
                <input type="checkbox" [checked]="skillIds().has(skill.id)" [disabled]="!editable() || busy()" (change)="toggleSkill(skill.id)" />
                {{ skill.name }} <span class="text-tertiary">{{ skill.category }}</span>
              </label>
            </li>
          }
        </ul>
      </ui-panel>

      <ui-panel heading="Tool permissions" subheading="Least privilege: every tool is disabled until granted. Tools are not executed in this version.">
        <ul class="plain">
          @for (tool of store.tools(); track tool.code) {
            @let draft = toolDrafts().get(tool.code);
            <li>
              <span>
                <label class="check">
                  <input type="checkbox" [checked]="draft?.enabled ?? false" [disabled]="!canTools() || busy() || !tool.enabled" (change)="toggleTool(tool)" />
                  <strong>{{ tool.name }}</strong>
                </label>
                <span class="text-tertiary">{{ tool.description }}</span>
              </span>
              <span class="actions">
                <ui-badge [label]="tool.risk + ' risk'" [tone]="tool.risk === 'HIGH' ? 'danger' : tool.risk === 'MEDIUM' ? 'warning' : 'neutral'" [dot]="false" />
                @if (draft?.enabled) {
                  <select class="select" [attr.aria-label]="'Access level for ' + tool.name" [disabled]="!canTools()" (change)="setLevel(tool, $event)">
                    @for (level of levels; track level) {
                      @if (allowed(level, tool.maxLevel)) { <option [value]="level" [selected]="draft?.level === level">{{ level }}</option> }
                    }
                  </select>
                  <label class="check">
                    <input type="checkbox" [checked]="draft?.approvalRequired || tool.risk === 'HIGH'" [disabled]="!canTools() || tool.risk === 'HIGH'" (change)="toggleApproval(tool)" />
                    Approval
                  </label>
                }
              </span>
            </li>
          } @empty { <li class="text-tertiary">No tool available.</li> }
        </ul>
        @if (canTools()) {
          <button type="button" class="btn btn--secondary btn--sm" [disabled]="busy()" (click)="saveTools()">Save tool permissions</button>
        }
      </ui-panel>

      <ui-panel heading="Memory" subheading="Configuration only: no memory is stored or retrieved in this version">
        <p>{{ d.memory.enabled ? 'Enabled' : 'Disabled' }} · {{ d.memory.scope === 'PROJECT' ? 'Project scope' : 'Organization scope' }} · {{ accessLabel[d.memory.accessMode] }}@if (d.memory.retentionDays) { · {{ d.memory.retentionDays }} days }</p>
        @if (editable() && d.agent.status !== 'ARCHIVED') {
          <div class="controls">
            <button type="button" class="btn btn--secondary btn--sm" [disabled]="busy()" (click)="toggleMemory()">
              {{ d.memory.enabled ? 'Disable memory' : 'Enable memory' }}
            </button>
          </div>
        }
      </ui-panel>

      <ui-panel heading="Teams">
        <ul class="plain">
          @for (team of d.teams; track team.id) { <li>{{ team.name }} @if (team.status === 'ARCHIVED') { <ui-badge label="Archived" tone="neutral" [dot]="false" /> }</li> }
          @empty { <li class="text-tertiary">Not in any team. Add the agent to a team from Organization → Teams.</li> }
        </ul>
      </ui-panel>

      <ui-panel heading="Configuration history" subheading="Who changed what (instructions and secrets are never recorded)">
        @if (history.value(); as page) {
          <ul class="plain">
            @for (entry of page.content; track entry.id) {
              <li><span>{{ entry.action }}</span><span class="text-tertiary">{{ entry.occurredAt }}</span></li>
            } @empty { <li class="text-tertiary">No change recorded.</li> }
          </ul>
        } @else if (history.error(); as message) {
          <p class="text-tertiary">{{ message }}</p>
        } @else {
          <ui-skeleton [rows]="2" />
        }
      </ui-panel>
    }
  `,
  styles: `
    :host { display: flex; flex-direction: column; gap: var(--space-4); min-width: 0; }
    .plain { list-style: none; margin: 0; padding: 0; }
    .plain li { display: flex; align-items: center; justify-content: space-between; gap: var(--space-3); padding: var(--space-1) 0; flex-wrap: wrap; }
    .actions { display: inline-flex; align-items: center; gap: var(--space-2); flex-wrap: wrap; }
    .controls { display: flex; gap: var(--space-2); margin: var(--space-3) 0 var(--space-1); flex-wrap: wrap; }
    .check { display: inline-flex; align-items: center; gap: var(--space-1); }
    .instructions pre { white-space: pre-wrap; margin: var(--space-2) 0 0; font: inherit; color: var(--color-text-secondary); }
    .danger { color: var(--color-danger); }
  `,
})
export class AgentDetailPanel {
  readonly agentId = input.required<string>();
  readonly changed = output<void>();

  protected readonly store = inject(AgentsStore);
  protected readonly workforce = inject(WorkforceStore);
  private readonly api = inject(AgentsApi);
  private readonly toast = inject(ToastService);
  private readonly confirm = inject(ConfirmService);

  protected readonly statusLabel = AGENT_STATUS_LABEL;
  protected readonly statusTone = AGENT_STATUS_TONE;
  protected readonly connLabel = CONNECTION_STATUS_LABEL;
  protected readonly connTone = CONNECTION_STATUS_TONE;
  protected readonly providerLabel = PROVIDER_LABEL;
  protected readonly levels = TOOL_LEVELS;
  protected readonly allowed = levelAllowed;
  protected readonly accessLabel: Readonly<Record<MemoryAccess, string>> = {
    AGENT_ONLY: 'Agent only',
    TEAM_MEMBERS: 'Team members',
    ORGANIZATION_MEMBERS: 'Organization members',
  };

  protected readonly busy = signal(false);
  protected readonly bindingConnection = signal('');
  protected readonly bindingModel = signal('');
  protected readonly bindingRole = signal<BindingRole>('PRIMARY');
  protected readonly toolDrafts = signal<ReadonlyMap<string, ToolDraft>>(new Map());

  protected readonly detail = orgResource<AgentDetail, string>(
    this.store.organizationId,
    () => this.store.can('agent.read'),
    () => this.agentId(),
    (organizationId, id) => this.api.getAgent(organizationId, id),
  );
  protected readonly history = orgResource(
    this.store.organizationId,
    () => this.store.can('agent.read'),
    () => this.agentId(),
    (organizationId, id) => this.api.agentHistory(organizationId, id),
  );
  private readonly modelCatalog = orgResource(
    this.store.organizationId,
    () => this.bindingConnection() !== '' && this.store.can('provider.read'),
    () => this.bindingConnection(),
    (organizationId, id) => this.api.connectionModels(organizationId, id),
  );

  protected readonly current = computed(() => {
    const value = this.detail.value();
    return value && value.agent.id === this.agentId() ? value : null;
  });
  protected readonly models = computed(() => (this.bindingConnection() ? this.modelCatalog.value() : null));
  protected readonly modelsLoading = this.modelCatalog.loading;
  protected readonly modelsError = this.modelCatalog.error;
  protected readonly editable = computed(() => this.store.can('agent.manage'));
  protected readonly canTools = computed(() => this.store.can('agent.tool.manage') && this.current()?.agent.status !== 'ARCHIVED');
  protected readonly skillIds = computed<ReadonlySet<string>>(() => new Set(this.current()?.skills.map((s) => s.id) ?? []));

  constructor() {
    // Reset the draft state whenever another agent is selected or fresh data arrives.
    effect(() => {
      const detail = this.current();
      untracked(() => {
        const next = new Map<string, ToolDraft>();
        detail?.tools.forEach((g) => next.set(g.toolCode, { enabled: true, level: g.level, approvalRequired: g.approvalRequired }));
        this.toolDrafts.set(next);
      });
    });
    effect(() => {
      this.agentId();
      untracked(() => {
        this.bindingConnection.set('');
        this.bindingModel.set('');
      });
    });
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement | HTMLSelectElement).value;
  }

  protected roleValue(event: Event): BindingRole {
    return this.value(event) === 'FALLBACK' ? 'FALLBACK' : 'PRIMARY';
  }

  // ------------------------------------------------------------ actions

  private async run(success: string, action: (organizationId: string, id: string) => Promise<unknown>): Promise<void> {
    const organizationId = this.store.organizationId();
    const id = this.agentId();
    if (!organizationId || this.busy()) {
      return;
    }
    this.busy.set(true);
    try {
      await action(organizationId, id);
      this.toast.success(success);
    } catch (error) {
      this.toast.error('Action failed', error);
    } finally {
      this.busy.set(false);
      this.detail.reload();
      this.history.reload();
      this.changed.emit();
    }
  }

  protected lifecycle(action: 'activate' | 'pause'): Promise<void> {
    return this.run(action === 'activate' ? 'Agent activated' : 'Agent paused', (o, id) =>
      action === 'activate' ? this.api.activateAgent(o, id) : this.api.pauseAgent(o, id),
    );
  }

  protected async archive(): Promise<void> {
    const answer = await this.confirm.ask({
      title: 'Archive this agent?',
      message: 'Archiving is final: the agent leaves its teams and can no longer be activated or edited.',
      confirmLabel: 'Archive',
      tone: 'danger',
    });
    if (answer.confirmed) {
      await this.run('Agent archived', (o, id) => this.api.archiveAgent(o, id));
    }
  }

  protected changeDepartment(event: Event): Promise<void> {
    const departmentId = this.value(event) || null;
    return this.run('Department updated', (o, id) => this.api.changeAgentDepartment(o, id, departmentId));
  }

  protected addBinding(): Promise<void> {
    const draft = { connectionId: this.bindingConnection(), modelId: this.bindingModel().trim(), role: this.bindingRole() };
    return this.run('Provider bound', (o, id) => this.api.addBinding(o, id, draft));
  }

  protected makePrimary(binding: AgentBinding): Promise<void> {
    return this.run('Primary model changed', (o, id) => this.api.updateBinding(o, id, binding.id, { makePrimary: true }));
  }

  protected removeBinding(binding: AgentBinding): Promise<void> {
    return this.run('Binding removed', (o, id) => this.api.removeBinding(o, id, binding.id));
  }

  protected toggleSkill(skillId: string): Promise<void> {
    const next = new Set(this.skillIds());
    if (!next.delete(skillId)) {
      next.add(skillId);
    }
    return this.run('Skills updated', (o, id) => this.api.setSkills(o, id, [...next]));
  }

  protected toggleMemory(): Promise<void> {
    const memory = this.current()?.memory;
    if (!memory) {
      return Promise.resolve();
    }
    return this.run(memory.enabled ? 'Memory disabled' : 'Memory enabled', (o, id) =>
      this.api.setMemory(o, id, {
        enabled: !memory.enabled,
        scope: memory.scope as MemoryScope,
        accessMode: memory.accessMode,
        retentionDays: memory.retentionDays,
      }),
    );
  }

  // ------------------------------------------------------------ tool drafts

  private patchDraft(code: string, change: (draft: ToolDraft) => ToolDraft | null): void {
    const next = new Map(this.toolDrafts());
    const draft = change(next.get(code) ?? { enabled: false, level: 'READ', approvalRequired: false });
    if (draft) {
      next.set(code, draft);
    } else {
      next.delete(code);
    }
    this.toolDrafts.set(next);
  }

  protected toggleTool(tool: ToolDefinition): void {
    this.patchDraft(tool.code, (draft) =>
      draft.enabled ? null : { enabled: true, level: 'READ', approvalRequired: tool.risk === 'HIGH' },
    );
  }

  protected setLevel(tool: ToolDefinition, event: Event): void {
    const level = this.value(event) as ToolLevel;
    this.patchDraft(tool.code, (draft) => ({ ...draft, level }));
  }

  protected toggleApproval(tool: ToolDefinition): void {
    this.patchDraft(tool.code, (draft) => ({ ...draft, approvalRequired: !draft.approvalRequired }));
  }

  protected saveTools(): Promise<void> {
    const grants: ToolGrantInput[] = [...this.toolDrafts()]
      .filter(([, draft]) => draft.enabled)
      .map(([toolCode, draft]) => ({ toolCode, level: draft.level, approvalRequired: draft.approvalRequired }));
    return this.run('Tool permissions saved', (o, id) => this.api.setTools(o, id, grants));
  }
}
