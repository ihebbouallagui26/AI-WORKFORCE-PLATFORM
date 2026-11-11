import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { Router } from '@angular/router';
import { ActivityEventType, Approval, RiskLevel } from '../../core/models';
import { ConfirmService } from '../../core/services/confirm.service';
import { ToastService } from '../../core/services/toast.service';
import { AgentStore } from '../../core/state/agent.store';
import { SupervisionStore } from '../../core/state/supervision.store';
import { ActivityList } from '../../shared/components/activity-list/activity-list';
import { EmptyState } from '../../shared/components/empty-state/empty-state';
import { Icon } from '../../shared/components/icon/icon';
import { PageHeader } from '../../shared/components/page-header/page-header';
import { Panel } from '../../shared/components/panel/panel';
import { Skeleton } from '../../shared/components/skeleton/skeleton';
import { StatCard } from '../../shared/components/stat-card/stat-card';
import { TabItem, Tabs } from '../../shared/components/tabs/tabs';
import { ACTIVITY_EVENT, RISK_LEVEL } from '../../shared/utils/presentation';
import { ApprovalCard } from './components/approval-card';
import { ExecutionTable } from './components/execution-table';

type CenterTab = 'approvals' | 'feed' | 'executions';
const TABS: readonly CenterTab[] = ['approvals', 'feed', 'executions'];
const RISKS = Object.keys(RISK_LEVEL) as RiskLevel[];
const EVENT_TYPES = Object.keys(ACTIVITY_EVENT) as ActivityEventType[];

@Component({
  selector: 'app-activity-center-page',
  imports: [PageHeader, StatCard, Tabs, Panel, Icon, Skeleton, EmptyState, ActivityList, ApprovalCard, ExecutionTable],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './activity-center-page.html',
  styleUrl: './activity-center-page.scss',
})
export class ActivityCenterPage {
  /** Query parameters bound by the router. */
  readonly tab = input<string | undefined>();
  readonly focus = input<string | undefined>();

  protected readonly store = inject(SupervisionStore);
  private readonly agents = inject(AgentStore);
  private readonly confirm = inject(ConfirmService);
  private readonly toast = inject(ToastService);
  private readonly router = inject(Router);

  protected readonly risks = RISK_LEVEL;
  protected readonly riskOptions = RISKS;
  protected readonly eventTypes = EVENT_TYPES;
  protected readonly eventPresentation = ACTIVITY_EVENT;

  protected readonly riskFilter = signal<RiskLevel | null>(null);
  protected readonly eventFilter = signal<ActivityEventType | null>(null);
  protected readonly deciding = signal<string | null>(null);

  protected readonly activeTab = computed<CenterTab>(() => {
    const tab = this.tab();
    return TABS.includes(tab as CenterTab) ? (tab as CenterTab) : 'approvals';
  });

  protected readonly pending = computed(() =>
    this.store.pendingApprovals().filter((a) => !this.riskFilter() || a.risk === this.riskFilter()),
  );

  protected readonly feed = computed(() =>
    this.store.activity().filter((e) => !this.eventFilter() || e.type === this.eventFilter()),
  );

  protected readonly summary = computed(() => {
    const executions = this.store.executions();
    const dayAgo = new Date(Date.now() - 86_400_000).toISOString();
    return {
      pending: this.store.pendingApprovals().length,
      critical: this.store.pendingApprovals().filter((a) => a.risk === 'critical' || a.risk === 'high').length,
      running: executions.filter((e) => e.status === 'running' || e.status === 'queued').length,
      failed: executions.filter((e) => e.status === 'failed' && e.startedAt >= dayAgo).length,
      attention: this.agents.agents().filter((a) => a.status === 'error' || a.status === 'needs-approval').length,
    };
  });

  protected readonly tabs = computed<readonly TabItem[]>(() => [
    { id: 'approvals', label: 'Approvals', icon: 'shield-check', count: this.store.pendingApprovals().length },
    { id: 'feed', label: 'Activity feed', icon: 'activity', count: this.store.unreadActivityCount() || null },
    { id: 'executions', label: 'Execution history', icon: 'cpu', count: this.store.executions().length },
  ]);

  protected selectTab(tab: string): void {
    void this.router.navigate([], { queryParams: { tab, focus: null }, queryParamsHandling: 'merge', replaceUrl: true });
  }

  protected setRisk(value: string): void {
    this.riskFilter.set(RISKS.includes(value as RiskLevel) ? (value as RiskLevel) : null);
  }

  protected toggleEventFilter(type: ActivityEventType): void {
    this.eventFilter.update((current) => (current === type ? null : type));
  }

  protected async approve(approval: Approval): Promise<void> {
    const agentName = this.agentName(approval.agentId);
    const result = await this.confirm.ask({
      title: 'Approve this operation?',
      message: `${agentName} requests: “${approval.action}”. ${RISK_LEVEL[approval.risk].label}.`,
      confirmLabel: 'Approve',
      tone: 'success',
      noteLabel: 'Note for the audit trail (optional)',
      notePlaceholder: 'Why this is acceptable',
    });
    if (result.confirmed) {
      await this.decide(approval, 'approved', result.note);
    }
  }

  protected async reject(approval: Approval): Promise<void> {
    const result = await this.confirm.ask({
      title: 'Reject this operation?',
      message: `“${approval.action}” will not be performed. ${this.agentName(approval.agentId)} keeps the task paused.`,
      confirmLabel: 'Reject',
      tone: 'danger',
      noteLabel: 'Reason (optional)',
      notePlaceholder: 'Explain what the agent should do instead',
    });
    if (result.confirmed) {
      await this.decide(approval, 'rejected', result.note);
    }
  }

  protected async markAllRead(): Promise<void> {
    try {
      await this.store.markAllActivityRead();
    } catch (error) {
      this.toast.error('Could not update the feed', error);
    }
  }

  private async decide(approval: Approval, decision: 'approved' | 'rejected', note: string | null): Promise<void> {
    this.deciding.set(approval.id);
    try {
      await this.store.decide(approval.id, decision, note);
      this.toast.success(
        decision === 'approved' ? 'Approval recorded' : 'Rejection recorded',
        'Saved in the local mock workspace. No agent was notified: the orchestration engine is not connected.',
      );
    } catch (error) {
      this.toast.error('Could not record the decision', error);
    } finally {
      this.deciding.set(null);
    }
  }

  private agentName(agentId: string): string {
    return this.agents.agentById().get(agentId)?.identity.fullName ?? 'The agent';
  }
}
