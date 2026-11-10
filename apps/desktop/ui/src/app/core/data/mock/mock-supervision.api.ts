import { inject, Injectable } from '@angular/core';
import { Observable, throwError } from 'rxjs';
import {
  ActivityEvent,
  AgentExecution,
  Approval,
  ApprovalDecision,
  Id,
  Meeting,
  Report,
} from '../../models';
import { SupervisionApi } from '../api/supervision.api';
import { MockDatabase } from './mock-database';

/**
 * Mock supervision data. Recording a decision only updates the local dataset:
 * no agent receives it and no paused execution is resumed.
 */
@Injectable()
export class MockSupervisionApi extends SupervisionApi {
  private readonly db = inject(MockDatabase);

  listApprovals(organizationId: Id): Observable<Approval[]> {
    return this.db.respond(this.db.tables.approvals.filter((a) => a.organizationId === organizationId));
  }

  decideApproval(decision: ApprovalDecision): Observable<Approval> {
    const current = this.db.tables.approvals.find((a) => a.id === decision.approvalId);
    if (!current) {
      return this.db.notFound('Approval', decision.approvalId);
    }
    if (current.status !== 'pending') {
      // Mirrors the expected Core API behaviour (409 Conflict): decisions are final.
      return throwError(() => new Error('This request has already been decided.'));
    }
    const next: Approval = {
      ...current,
      status: decision.decision,
      decisionNote: decision.note,
      decidedByUserId: decision.decidedByUserId,
      decidedAt: this.db.now(),
    };
    return this.db.respond(this.db.replace(this.db.tables.approvals, next));
  }

  listExecutions(organizationId: Id): Observable<AgentExecution[]> {
    return this.db.respond(this.db.tables.executions.filter((e) => e.organizationId === organizationId));
  }

  listActivity(organizationId: Id): Observable<ActivityEvent[]> {
    return this.db.respond(this.db.tables.activity.filter((e) => e.organizationId === organizationId));
  }

  markActivityRead(eventIds: readonly Id[]): Observable<void> {
    const ids = new Set(eventIds);
    this.db.tables.activity.forEach((event, index, table) => {
      if (ids.has(event.id)) {
        table[index] = { ...event, read: true };
      }
    });
    return this.db.respond(undefined);
  }

  listReports(organizationId: Id): Observable<Report[]> {
    return this.db.respond(this.db.tables.reports.filter((r) => r.organizationId === organizationId));
  }

  listMeetings(organizationId: Id): Observable<Meeting[]> {
    return this.db.respond(this.db.tables.meetings.filter((m) => m.organizationId === organizationId));
  }
}
