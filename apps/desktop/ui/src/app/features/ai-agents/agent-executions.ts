import { ChangeDetectionStrategy, Component, DestroyRef, InjectionToken, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { AgentsApi } from '../../core/agents/agents.api';
import { AgentDetail, AgentExecution, isTerminal } from '../../core/agents/agents.models';
import { AgentsStore } from '../../core/agents/agents.store';
import { ToastService } from '../../core/services/toast.service';
import { orgResource } from '../../core/workforce/org-resource';
import { Badge } from '../../shared/components/badge/badge';
import { EmptyState } from '../../shared/components/empty-state/empty-state';
import { Panel } from '../../shared/components/panel/panel';
import { Skeleton } from '../../shared/components/skeleton/skeleton';
import { Tone } from '../../shared/utils/presentation';

export const MAX_TASK_LENGTH = 8000;
export const POLL_INTERVAL_MS = 2000;
/** Polling period (overridable so tests do not wait seconds). */
export const EXECUTION_POLL_MS = new InjectionToken<number>('EXECUTION_POLL_MS', { providedIn: 'root', factory: () => POLL_INTERVAL_MS });
/** Polling stops after this long even if the run is not final (the server fails stale runs by itself). */
export const POLL_LIMIT_MS = 10 * 60 * 1000;

const STATUS_LABEL: Readonly<Record<AgentExecution['status'], string>> = {
  PENDING: 'Queued',
  RUNNING: 'Running',
  SUCCEEDED: 'Succeeded',
  FAILED: 'Failed',
};
const STATUS_TONE: Readonly<Record<AgentExecution['status'], Tone>> = {
  PENDING: 'neutral',
  RUNNING: 'info',
  SUCCEEDED: 'success',
  FAILED: 'danger',
};

/**
 * Supervised execution of one agent: the user writes an instruction and explicitly launches it. The
 * run is text generation only, asynchronous (it is polled until final) and always initiated by a human.
 * No provider key is ever requested or shown here: the server holds the credential.
 */
@Component({
  selector: 'app-agent-executions',
  imports: [Panel, Badge, EmptyState, Skeleton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ui-panel heading="Run this agent" subheading="Supervised run: you give an instruction and launch it. Text only, no tools are used.">
      @if (!store.can('agent.execute')) {
        <p class="text-tertiary">Your role does not allow launching agent executions.</p>
      } @else if (blocker(); as reason) {
        <p class="text-tertiary" data-testid="exec-blocked">{{ reason }}</p>
      } @else {
        <label class="field__label" for="exec-task">Instruction</label>
        <textarea id="exec-task" class="textarea" rows="4" [attr.maxlength]="maxLength" [value]="task()"
          [disabled]="running()" (input)="task.set(text($event))"
          placeholder="Describe what the agent should write or analyze"></textarea>
        <div class="controls">
          <button type="button" class="btn btn--primary btn--sm" [disabled]="!canLaunch()" (click)="launch()">
            {{ starting() ? 'Launching…' : running() ? 'Running…' : 'Run agent' }}
          </button>
          <span class="text-tertiary">{{ task().length }} / {{ maxLength }}</span>
        </div>
        @if (launchError(); as message) { <p class="error" role="alert">{{ message }}</p> }
      }

      @if (current(); as run) {
        <section class="run" data-testid="exec-current" aria-live="polite">
          <header>
            <ui-badge [label]="statusLabel[run.status]" [tone]="statusTone[run.status]" />
            <span class="text-tertiary">{{ run.providerType }} · {{ run.modelId }}</span>
          </header>
          @if (run.status === 'PENDING' || run.status === 'RUNNING') {
            <p class="text-tertiary">The agent is working on your instruction…</p>
          } @else if (run.status === 'SUCCEEDED') {
            <pre class="result" data-testid="exec-result">{{ run.result }}</pre>
            <p class="text-tertiary">{{ usage(run) }}</p>
          } @else {
            <p class="error" role="alert" data-testid="exec-error">{{ run.errorMessage || 'The execution failed.' }}</p>
          }
        </section>
      }
    </ui-panel>

    @if (store.can('agent.execution.read')) {
      <ui-panel heading="Execution history" subheading="Most recent runs of this agent">
        @if (history.error(); as message) {
          <ui-empty-state icon="circle-alert" tone="danger" heading="History unavailable" [description]="message" [compact]="true" />
        } @else if (history.loading() && !history.value()) {
          <ui-skeleton [rows]="2" />
        } @else if (!history.value()?.content?.length) {
          <p class="text-tertiary" data-testid="exec-empty">No execution yet.</p>
        } @else {
          <ul class="plain">
            @for (item of history.value()?.content; track item.id) {
              <li>
                <button type="button" class="link" (click)="select(item)">
                  <span class="task">{{ item.task }}</span>
                </button>
                <span class="meta">
                  <ui-badge [label]="statusLabel[item.status]" [tone]="statusTone[item.status]" />
                  <span class="text-tertiary">{{ item.createdAt }}</span>
                </span>
              </li>
            }
          </ul>
        }
      </ui-panel>
    }
  `,
  styles: `
    :host { display: contents; }
    .controls { display: flex; align-items: center; gap: var(--space-3); margin: var(--space-2) 0; }
    .run { margin-top: var(--space-3); border-top: 1px solid var(--color-border); padding-top: var(--space-3); }
    .run header { display: flex; align-items: center; gap: var(--space-2); margin-bottom: var(--space-2); }
    .result { white-space: pre-wrap; overflow-wrap: anywhere; margin: 0; padding: var(--space-3); background: var(--color-surface-2); border-radius: var(--radius-md); font: inherit; }
    .error { color: var(--color-danger); }
    .plain { list-style: none; margin: 0; padding: 0; }
    .plain li { display: flex; justify-content: space-between; align-items: center; gap: var(--space-3); padding: var(--space-1) 0; }
    .link { background: none; border: 0; padding: 0; color: var(--color-text-primary); text-align: left; cursor: pointer; min-width: 0; }
    .task { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 36ch; }
    .meta { display: inline-flex; align-items: center; gap: var(--space-2); flex-shrink: 0; }
  `,
})
export class AgentExecutions {
  readonly detail = input.required<AgentDetail>();

  protected readonly store = inject(AgentsStore);
  private readonly api = inject(AgentsApi);
  private readonly toast = inject(ToastService);
  private readonly pollMs = inject(EXECUTION_POLL_MS);

  protected readonly maxLength = MAX_TASK_LENGTH;
  protected readonly statusLabel = STATUS_LABEL;
  protected readonly statusTone = STATUS_TONE;

  protected readonly task = signal('');
  protected readonly starting = signal(false);
  protected readonly launchError = signal<string | null>(null);
  protected readonly current = signal<AgentExecution | null>(null);
  private timer: ReturnType<typeof setTimeout> | null = null;
  private pollStartedAt = 0;

  protected readonly history = orgResource(
    this.store.organizationId,
    () => this.store.can('agent.execution.read'),
    () => this.detail().agent.id,
    (organizationId, agentId) => this.api.listExecutions(organizationId, agentId),
  );

  protected readonly running = computed(() => {
    const run = this.current();
    return run !== null && !isTerminal(run.status);
  });

  /** Why a launch is impossible (usability hint only: the server re-validates everything). */
  protected readonly blocker = computed<string | null>(() => {
    const { agent, providers } = this.detail();
    if (agent.status !== 'ACTIVE') {
      return 'Only an active agent can be run. Activate it first.';
    }
    const primary = providers.find((p) => p.role === 'PRIMARY');
    if (!primary) {
      return 'This agent has no primary provider.';
    }
    if (primary.connection.status !== 'VALID') {
      return 'The primary provider connection is not validated.';
    }
    if (primary.connection.providerType !== 'ANTHROPIC') {
      return 'Only Anthropic Claude agents can be run in this version.';
    }
    return null;
  });

  protected readonly canLaunch = computed(
    () => this.task().trim().length > 0 && this.task().length <= MAX_TASK_LENGTH && !this.starting() && !this.running(),
  );

  constructor() {
    // Switching agent or organization drops everything shown (no run, no draft, no polling of the previous one).
    effect(() => {
      this.detail().agent.id;
      this.store.organizationId();
      untracked(() => this.reset());
    });
    inject(DestroyRef).onDestroy(() => this.stopPolling());
  }

  protected text(event: Event): string {
    return (event.target as HTMLTextAreaElement).value;
  }

  protected usage(run: AgentExecution): string {
    return run.inputTokens === null && run.outputTokens === null
      ? ''
      : `Usage: ${run.inputTokens ?? 0} input / ${run.outputTokens ?? 0} output tokens`;
  }

  protected select(run: AgentExecution): void {
    this.stopPolling();
    this.current.set(run);
    if (!isTerminal(run.status)) {
      this.poll(run.id);
    }
  }

  protected async launch(): Promise<void> {
    const organizationId = this.store.organizationId();
    const agentId = this.detail().agent.id;
    if (!organizationId || !this.canLaunch()) {
      return;
    }
    this.starting.set(true);
    this.launchError.set(null);
    try {
      const run = await this.api.startExecution(organizationId, agentId, this.task().trim());
      if (!this.isCurrent(organizationId, agentId)) {
        return;
      }
      this.task.set('');
      this.current.set(run);
      this.history.reload();
      this.poll(run.id);
    } catch (error) {
      if (this.isCurrent(organizationId, agentId)) {
        this.launchError.set(error instanceof Error ? error.message : 'The execution could not be launched.');
        this.toast.error('Could not launch the execution', error);
      }
    } finally {
      this.starting.set(false);
    }
  }

  private isCurrent(organizationId: string, agentId: string): boolean {
    return this.store.organizationId() === organizationId && this.detail().agent.id === agentId;
  }

  private poll(executionId: string): void {
    this.stopPolling();
    this.pollStartedAt = Date.now();
    const tick = async (): Promise<void> => {
      const organizationId = this.store.organizationId();
      const agentId = this.detail().agent.id;
      if (!organizationId) {
        return;
      }
      try {
        const run = await this.api.getExecution(organizationId, executionId);
        // A late answer for another agent or organization, or for a run no longer shown, is dropped.
        if (!this.isCurrent(organizationId, agentId) || this.current()?.id !== executionId) {
          return;
        }
        this.current.set(run);
        if (isTerminal(run.status)) {
          this.history.reload();
          return;
        }
      } catch {
        // Transient failure: keep polling until the limit.
      }
      if (Date.now() - this.pollStartedAt < POLL_LIMIT_MS) {
        this.timer = setTimeout(() => void tick(), this.pollMs);
      }
    };
    this.timer = setTimeout(() => void tick(), this.pollMs);
  }

  private stopPolling(): void {
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
  }

  private reset(): void {
    this.stopPolling();
    this.task.set('');
    this.current.set(null);
    this.launchError.set(null);
    this.starting.set(false);
  }
}
