import { computed, inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { SupervisionApi } from '../data/api/supervision.api';
import { Approval, ApprovalStatus, Id, RiskLevel } from '../models';
import { orgCollection, requireOrganization, upsertById } from './collection';
import { WorkspaceStore } from './workspace.store';

const RISK_WEIGHT: Record<RiskLevel, number> = { critical: 0, high: 1, medium: 2, low: 3 };

@Injectable({ providedIn: 'root' })
export class SupervisionStore {
  private readonly api = inject(SupervisionApi);
  private readonly workspace = inject(WorkspaceStore);
  private readonly organizationId = this.workspace.currentOrganizationId;

  private readonly approvalList = orgCollection(this.organizationId, (id) => this.api.listApprovals(id));
  private readonly executionList = orgCollection(this.organizationId, (id) => this.api.listExecutions(id));
  private readonly activityList = orgCollection(this.organizationId, (id) => this.api.listActivity(id));
  private readonly reportList = orgCollection(this.organizationId, (id) => this.api.listReports(id));
  private readonly meetingList = orgCollection(this.organizationId, (id) => this.api.listMeetings(id));

  readonly approvals = this.approvalList.items;
  readonly executions = computed(() =>
    [...this.executionList.items()].sort((a, b) => b.startedAt.localeCompare(a.startedAt)),
  );
  readonly activity = computed(() =>
    [...this.activityList.items()].sort((a, b) => b.occurredAt.localeCompare(a.occurredAt)),
  );
  readonly reports = computed(() =>
    [...this.reportList.items()].sort((a, b) => b.createdAt.localeCompare(a.createdAt)),
  );
  readonly upcomingMeetings = computed(() => {
    const now = new Date().toISOString();
    return [...this.meetingList.items()]
      .filter((m) => m.startsAt >= now)
      .sort((a, b) => a.startsAt.localeCompare(b.startsAt));
  });

  readonly loading = computed(
    () => this.approvalList.loading() || this.executionList.loading() || this.activityList.loading(),
  );
  readonly error = computed(
    () => this.approvalList.error() ?? this.executionList.error() ?? this.activityList.error(),
  );

  /** Pending approvals, most risky and oldest first. */
  readonly pendingApprovals = computed(() =>
    this.approvals()
      .filter((a) => a.status === 'pending')
      .sort((a, b) => RISK_WEIGHT[a.risk] - RISK_WEIGHT[b.risk] || a.requestedAt.localeCompare(b.requestedAt)),
  );

  readonly decidedApprovals = computed(() =>
    this.approvals()
      .filter((a) => a.status !== 'pending')
      .sort((a, b) => (b.decidedAt ?? '').localeCompare(a.decidedAt ?? '')),
  );

  readonly unreadActivityCount = computed(() => this.activity().filter((e) => !e.read).length);

  async decide(approvalId: Id, decision: Exclude<ApprovalStatus, 'pending'>, note: string | null): Promise<Approval> {
    const organizationId = requireOrganization(this.organizationId);
    const user = this.workspace.user();
    if (!user) {
      throw new Error('You must be signed in to record a decision.');
    }
    const approval = this.approvals().find((a) => a.id === approvalId);
    if (!approval) {
      throw new Error('This approval request does not belong to the current organization.');
    }
    if (approval.status !== 'pending') {
      throw new Error('This request has already been decided.');
    }
    const updated = await firstValueFrom(
      this.api.decideApproval({ approvalId, decision, note: note?.trim() || null, decidedByUserId: user.id }),
    );
    this.approvalList.mutate(organizationId, (items) => upsertById(items, updated));
    return updated;
  }

  async markAllActivityRead(): Promise<void> {
    const organizationId = requireOrganization(this.organizationId);
    const unread = this.activity().filter((e) => !e.read).map((e) => e.id);
    if (!unread.length) {
      return;
    }
    await firstValueFrom(this.api.markActivityRead(unread));
    const ids = new Set(unread);
    this.activityList.mutate(organizationId, (items) =>
      items.map((e) => (ids.has(e.id) ? { ...e, read: true } : e)),
    );
  }

  reload(): void {
    this.approvalList.reload();
    this.executionList.reload();
    this.activityList.reload();
    this.reportList.reload();
    this.meetingList.reload();
  }
}
