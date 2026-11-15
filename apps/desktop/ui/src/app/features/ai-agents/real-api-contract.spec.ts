import { ApplicationRef, provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { FakeDesktop } from '../../../testing/fake-desktop';
import { REAL_API } from '../../../testing/real-api-c2';
import { AgentExecution, ApprovalStatus, ApprovalSummary, ExecutionStatus, InvocationStatus, ToolApproval, ToolInvocation, ToolPolicy } from '../../core/agents/agents.models';
import { AUTH_REQUIRED } from '../../core/auth/session.service';
import type { ApiRequest, ApiResponse } from '../../core/desktop/desktop-api';
import { AccessStore } from '../../core/identity/access.store';
import { PermissionCode } from '../../core/identity/identity.models';
import { WorkspaceStore } from '../../core/state/workspace.store';
import { ExecutionActivity } from './execution-activity';

/**
 * The reference here is NOT a hand-written fixture: `REAL_API` holds responses captured from the running Core API
 * (see testing/real-api-c2.ts). If a backend DTO gains, loses or renames a field, or an enum gains a value, these tests
 * fail and the Angular model must be reviewed. The `satisfies` clauses make the key lists complete at compile time.
 */
const EXECUTION_KEYS = {
  id: 1, organizationId: 1, agentId: 1, requestedBy: 1, task: 1, status: 1, providerType: 1, modelId: 1, result: 1, inputTokens: 1, outputTokens: 1,
  errorCode: 1, errorMessage: 1, createdAt: 1, startedAt: 1, completedAt: 1, version: 1,
} satisfies Record<keyof AgentExecution, 1>;
const INVOCATION_KEYS = {
  id: 1, executionId: 1, agentId: 1, toolCode: 1, toolName: 1, toolCallId: 1, sequence: 1, status: 1, policy: 1, argumentsRedacted: 1, argumentsHash: 1, reason: 1,
  result: 1, resultTruncated: 1, errorCode: 1, errorMessage: 1, requestedAt: 1, startedAt: 1, completedAt: 1, approval: 1,
} satisfies Record<keyof ToolInvocation, 1>;
const APPROVAL_KEYS = {
  id: 1, executionId: 1, executionStatus: 1, agentId: 1, agentName: 1, invocationId: 1, toolCode: 1, toolName: 1, mcpServer: 1, toolCallId: 1, risk: 1, description: 1,
  reason: 1, argumentsRedacted: 1, argumentsHash: 1, policyVersion: 1, status: 1, requestedAt: 1, expiresAt: 1, decidedBy: 1, decidedByName: 1, decidedAt: 1, rejectionReason: 1,
  invocationStatus: 1,
} satisfies Record<keyof ToolApproval, 1>;
const SUMMARY_KEYS = { id: 1, status: 1, requestedAt: 1, expiresAt: 1, decidedBy: 1, decidedByName: 1, decidedAt: 1, rejectionReason: 1 } satisfies Record<keyof ApprovalSummary, 1>;

const EXECUTION_STATUS: readonly ExecutionStatus[] = ['PENDING', 'RUNNING', 'WAITING_APPROVAL', 'SUCCEEDED', 'FAILED'];
const INVOCATION_STATUS: readonly InvocationStatus[] = ['DENIED', 'AWAITING_APPROVAL', 'APPROVED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'REJECTED', 'EXPIRED'];
const APPROVAL_STATUS: readonly ApprovalStatus[] = ['PENDING', 'APPROVED', 'REJECTED', 'EXPIRED', 'CANCELLED'];
const POLICY: readonly ToolPolicy[] = ['READ_ONLY', 'APPROVAL_REQUIRED', 'DENIED'];

const real = <T>(name: string): T => REAL_API[name] as T;
const keys = (value: object) => Object.keys(value).sort();
const expected = (map: object) => Object.keys(map).sort();

const executions = ['executionSucceeded', 'executionWaiting', 'executionApproved', 'executionExpired'].map((n) => ({ name: n, value: real<AgentExecution>(n) }));
const approvals = ['approvalPending', 'approvalApproved', 'approvalRejected', 'approvalExpired', 'approvalCancelled'].map((n) => ({ name: n, value: real<ToolApproval>(n) }));
const pages = ['toolCallsReadOnly', 'toolCallsApproved', 'pendingPage'].map((n) => ({ name: n, value: real<{ content: unknown[] }>(n) }));

describe('Angular models match the real Core API', () => {
  it.each(executions)('execution %# has exactly the modelled fields', ({ value }) => {
    expect(keys(value)).toEqual(expected(EXECUTION_KEYS));
    expect(EXECUTION_STATUS).toContain(value.status);
  });

  it.each(approvals)('approval %# has exactly the modelled fields and known enum values', ({ value }) => {
    expect(keys(value)).toEqual(expected(APPROVAL_KEYS));
    expect(APPROVAL_STATUS).toContain(value.status);
    expect(['LOW', 'MEDIUM', 'HIGH']).toContain(value.risk);
    if (value.invocationStatus) expect(INVOCATION_STATUS).toContain(value.invocationStatus);
    if (value.executionStatus) expect(EXECUTION_STATUS).toContain(value.executionStatus);
  });

  it.each(pages)('list %# is a page whose content is an array', ({ value }) => {
    expect(Array.isArray(value.content)).toBe(true);
    expect(keys(value)).toEqual(['content', 'page', 'size', 'totalElements', 'totalPages']);
  });

  it('tool calls have exactly the modelled fields, known enums and, when present, a modelled approval summary', () => {
    const calls = [...real<{ content: ToolInvocation[] }>('toolCallsReadOnly').content, ...real<{ content: ToolInvocation[] }>('toolCallsApproved').content];
    expect(calls.length).toBeGreaterThanOrEqual(2);
    for (const call of calls) {
      expect(keys(call)).toEqual(expected(INVOCATION_KEYS));
      expect(INVOCATION_STATUS).toContain(call.status);
      expect(POLICY).toContain(call.policy);
      if (call.approval) expect(keys(call.approval)).toEqual(expected(SUMMARY_KEYS));
    }
  });

  it('never carries a credential, a token or an authorization header in any captured response', () => {
    const text = JSON.stringify(REAL_API);
    expect(text).not.toMatch(/sk-ant-|Bearer |eyJ[A-Za-z0-9_-]{10,}|authorization|api[_-]?key"/i);
  });
});

const ORG = '0195f0a4-1c2d-7e3f-8a9b-00000000000a';
const READ_ALL: PermissionCode[] = ['organization.read', 'agent.read', 'agent.execution.read', 'skill.read', 'approval.read', 'approval.decide'];

function mount(permissions: PermissionCode[], handler: (request: ApiRequest) => ApiResponse) {
  const desktop = new FakeDesktop();
  desktop.handler = handler;
  desktop.install();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      provideRouter([]),
      { provide: AUTH_REQUIRED, useValue: true },
      { provide: WorkspaceStore, useValue: { currentOrganizationId: signal<string | null>(ORG) } },
      { provide: AccessStore, useValue: { can: (p: PermissionCode) => permissions.includes(p), organization: signal({ name: 'Acme' }), reload: () => undefined } },
    ],
  });
}

async function settle(fixture: ComponentFixture<unknown>): Promise<void> {
  const appRef = TestBed.inject(ApplicationRef);
  for (let i = 0; i < 12; i++) {
    await new Promise((resolve) => setTimeout(resolve, 5));
    TestBed.tick();
    fixture.detectChanges();
    await appRef.whenStable();
  }
}

const strict = (calls: unknown, approvals: unknown) => (request: ApiRequest): ApiResponse => {
  if (request.method === 'GET' && request.path.endsWith('/tool-calls')) return { status: 200, body: calls };
  if (request.method === 'GET' && request.path.endsWith('/approvals')) return { status: 200, body: approvals };
  if (request.method === 'GET' && request.path.endsWith('/tools')) return { status: 200, body: [] };
  return { status: 404, body: { detail: `unexpected ${request.method} ${request.path}` } };
};

describe('The execution UI renders the real API payloads', () => {
  afterEach(() => FakeDesktop.uninstall());

  it('shows the real approved flow: read-only call, approval granted, tool completed, execution completed', async () => {
    mount(READ_ALL, strict(real('toolCallsApproved'), { content: [], page: 0, size: 20, totalElements: 0, totalPages: 1 }));
    const fixture = TestBed.createComponent(ExecutionActivity);
    fixture.componentRef.setInput('run', real<AgentExecution>('executionApproved'));
    fixture.detectChanges();
    await settle(fixture);
    const titles = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('[data-testid=timeline-entry] .title')).map((e) => e.textContent?.trim());
    expect(titles[0]).toBe('Execution created');
    expect(titles.at(-1)).toBe('Execution completed');
    expect(titles).toContain('Tool requested: Record a note');
    expect(titles).toContain('Tool completed: Record a note');
    expect(titles.some((t) => t?.startsWith('Approval requested'))).toBe(true);
    expect(titles.some((t) => t?.startsWith('Approval granted'))).toBe(true);
  });

  it('shows the real pending approval as a decision card', async () => {
    mount(READ_ALL, strict({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 1 }, real('pendingPage')));
    const fixture = TestBed.createComponent(ExecutionActivity);
    fixture.componentRef.setInput('run', real<AgentExecution>('executionWaiting'));
    fixture.detectChanges();
    await settle(fixture);
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('app-approval-card')).not.toBeNull();
    const card = (el.textContent ?? '').replace(/\s+/g, ' ');
    expect(card).toContain('Record a note');
    expect(card).toContain('MEDIUM');
    expect(card).toContain('Pending decision');
  });

  it('shows the real expired run as a failure with its safe message, not as pending', async () => {
    mount(READ_ALL, strict({ content: [], page: 0, size: 50, totalElements: 0, totalPages: 1 }, { content: [], page: 0, size: 20, totalElements: 0, totalPages: 1 }));
    const fixture = TestBed.createComponent(ExecutionActivity);
    const run = real<AgentExecution>('executionExpired');
    fixture.componentRef.setInput('run', run);
    fixture.detectChanges();
    await settle(fixture);
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid=decision-needed]')).toBeNull();
    expect(run.errorCode).toBe('APPROVAL_EXPIRED');
    expect((el.textContent ?? '')).toContain('Execution failed');
    expect((el.textContent ?? '')).toContain(run.errorMessage ?? 'expired');
  });
});
