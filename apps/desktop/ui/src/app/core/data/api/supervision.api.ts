import { Observable } from 'rxjs';
import {
  ActivityEvent,
  AgentExecution,
  Approval,
  ApprovalDecision,
  Id,
  Meeting,
  Report,
} from '../../models';

/** Human supervision of agents: approvals, executions, activity feed, reports, meetings. */
export abstract class SupervisionApi {
  abstract listApprovals(organizationId: Id): Observable<Approval[]>;
  abstract decideApproval(decision: ApprovalDecision): Observable<Approval>;
  abstract listExecutions(organizationId: Id): Observable<AgentExecution[]>;
  abstract listActivity(organizationId: Id): Observable<ActivityEvent[]>;
  abstract markActivityRead(eventIds: readonly Id[]): Observable<void>;
  abstract listReports(organizationId: Id): Observable<Report[]>;
  abstract listMeetings(organizationId: Id): Observable<Meeting[]>;
}
