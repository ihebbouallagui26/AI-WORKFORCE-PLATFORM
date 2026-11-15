import { computed, DestroyRef, inject, Injectable, InjectionToken, signal } from '@angular/core';
import { AgentsApi } from '../agents/agents.api';
import { ExecutionStatus, ToolApproval } from '../agents/agents.models';
import { AccessStore } from '../identity/access.store';
import { WorkspaceStore } from '../state/workspace.store';
import { TimelineApproval } from './workflow-view.model';
import { WorkflowsApi } from './workflows.api';
import { isLive } from './workflow-view.model';
import { isTerminalRun, RunSummary, WorkflowAction, WorkflowMessage, WorkflowRun, WorkflowStepView } from './workflows.models';

/** Base polling period. The Core API scheduler itself advances runs about every 3 seconds, so polling faster shows nothing new. */
export const RUN_POLL_MS = new InjectionToken<number>('RUN_POLL_MS', { providedIn: 'root', factory: () => 3000 });
/** Upper bound of the back-off after failures. */
export const RUN_POLL_MAX_MS = new InjectionToken<number>('RUN_POLL_MAX_MS', { providedIn: 'root', factory: () => 30_000 });
/** Auto-refresh pauses after this long even if the run is still open (the user can refresh by hand): bounded, never endless. */
export const RUN_POLL_LIMIT_MS = new InjectionToken<number>('RUN_POLL_LIMIT_MS', { providedIn: 'root', factory: () => 30 * 60 * 1000 });

/**
 * Follows ONE workflow run by polling the Core API (the only update mechanism that exists: there is no push channel). Rules:
 * only an open run is polled, at a bounded interval; a request is never started while the previous one is in flight; a temporary
 * failure keeps the last data on screen and backs off; polling stops on a terminal state (after one last refresh), on destroy, and
 * after a bounded time. Nothing here changes the run: it only reads.
 *
 * Provide it in the component that shows the run: it is stopped with it.
 */
@Injectable()
export class RunMonitor {
  private readonly api = inject(WorkflowsApi);
  private readonly agents = inject(AgentsApi);
  private readonly workspace = inject(WorkspaceStore);
  private readonly access = inject(AccessStore);
  private readonly baseMs = inject(RUN_POLL_MS);
  private readonly maxMs = inject(RUN_POLL_MAX_MS);
  private readonly limitMs = inject(RUN_POLL_LIMIT_MS);

  readonly run = signal<WorkflowRun | null>(null);
  readonly steps = signal<readonly WorkflowStepView[]>([]);
  readonly summary = signal<RunSummary | null>(null);
  readonly actions = signal<readonly WorkflowAction[]>([]);
  readonly messages = signal<readonly WorkflowMessage[]>([]);
  /** Child-execution status of the live workers (only when the user may read executions). */
  readonly live = signal<ReadonlyMap<string, ExecutionStatus>>(new Map());
  /** Pending approvals that belong to this run's executions (only when the user may read approvals). */
  readonly approvals = signal<readonly TimelineApproval[]>([]);
  readonly pendingApprovals = signal<readonly ToolApproval[]>([]);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly lastUpdated = signal<number | null>(null);
  readonly paused = signal(false);

  readonly terminal = computed(() => {
    const run = this.run();
    return run !== null && isTerminalRun(run.status);
  });
  /** True while a timer is armed (an open run, not paused). */
  readonly polling = signal(false);

  private runId: string | null = null;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private inFlight = false;
  private failures = 0;
  private startedAt = 0;
  private destroyed = false;

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      this.destroyed = true;
      this.stop();
    });
  }

  /** Loads the run now and keeps it fresh while it is open. Calling it again for the same run does nothing new. */
  start(runId: string): void {
    if (this.runId === runId && (this.polling() || this.loading())) {
      return;
    }
    this.stop();
    this.runId = runId;
    this.startedAt = Date.now();
    this.failures = 0;
    this.paused.set(false);
    void this.cycle();
  }

  stop(): void {
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
    this.polling.set(false);
  }

  /** A manual refresh (also resumes a paused auto-refresh). It never overlaps a request already in flight. */
  async refresh(): Promise<void> {
    if (this.runId === null) {
      return;
    }
    this.paused.set(false);
    this.startedAt = Date.now();
    this.stop();
    await this.cycle();
  }

  private async cycle(): Promise<void> {
    const organizationId = this.workspace.currentOrganizationId();
    const runId = this.runId;
    if (this.destroyed || !organizationId || runId === null || this.inFlight) {
      return;
    }
    this.inFlight = true;
    this.loading.set(true);
    try {
      const [run, steps, summary, actions, messages] = await Promise.all([
        this.api.getRun(organizationId, runId),
        this.api.runSteps(organizationId, runId),
        this.api.runSummary(organizationId, runId),
        this.api.runActions(organizationId, runId),
        this.api.runMessages(organizationId, runId),
      ]);
      if (this.destroyed || runId !== this.runId || organizationId !== this.workspace.currentOrganizationId()) {
        return; // the user left the page or switched organization: this answer is not shown
      }
      this.run.set(run);
      this.steps.set(steps.content);
      this.summary.set(summary);
      this.actions.set(actions.content);
      this.messages.set(messages.content);
      await this.enrich(organizationId, steps.content);
      this.error.set(null);
      this.failures = 0;
      this.lastUpdated.set(Date.now());
    } catch (e) {
      this.failures++;
      this.error.set(e instanceof Error ? e.message : 'The workflow could not be refreshed.');
    } finally {
      this.inFlight = false;
      this.loading.set(false);
    }
    this.schedule();
  }

  private schedule(): void {
    if (this.destroyed || this.runId === null) {
      return;
    }
    const run = this.run();
    if (run !== null && isTerminalRun(run.status) && this.error() === null) {
      this.polling.set(false);
      return; // terminal and read once more: no further requests
    }
    if (Date.now() - this.startedAt >= this.limitMs) {
      this.paused.set(true);
      this.polling.set(false);
      return;
    }
    const delay = Math.min(this.maxMs, this.baseMs * 2 ** Math.min(this.failures, 6));
    this.polling.set(true);
    this.timer = setTimeout(() => {
      this.timer = null;
      void this.cycle();
    }, delay);
  }

  /** Reads the child-execution status of the live workers and the pending approvals, when the user is allowed to. Failures here are not fatal. */
  private async enrich(organizationId: string, steps: readonly WorkflowStepView[]): Promise<void> {
    const liveSteps = steps.filter((s) => isLive(s.status) && s.executionId !== null);
    const executionIds = new Set(steps.map((s) => s.executionId).filter((id): id is string => id !== null));
    if (this.access.can('agent.execution.read') && liveSteps.length > 0) {
      const states = new Map<string, ExecutionStatus>();
      await Promise.all(
        liveSteps.map(async (s) => {
          try {
            states.set(s.executionId as string, (await this.agents.getExecution(organizationId, s.executionId as string)).status);
          } catch {
            /* unknown stays unknown: the step is then shown as dispatched, not as running */
          }
        }),
      );
      this.live.set(states);
    } else {
      this.live.set(new Map());
    }
    if (this.access.can('approval.read') && liveSteps.some((s) => s.status === 'WAITING_APPROVAL')) {
      try {
        const page = await this.agents.listApprovals(organizationId, { status: 'PENDING', size: 50 });
        const mine = page.content.filter((a) => executionIds.has(a.executionId));
        this.pendingApprovals.set(mine);
        this.approvals.set(mine.map((a) => ({ id: a.id, executionId: a.executionId, toolName: a.toolName, status: a.status, requestedAt: a.requestedAt, decidedAt: a.decidedAt, expiresAt: a.expiresAt })));
      } catch {
        /* the approvals area shows its own state */
      }
    } else {
      this.pendingApprovals.set([]);
      this.approvals.set([]);
    }
  }
}
