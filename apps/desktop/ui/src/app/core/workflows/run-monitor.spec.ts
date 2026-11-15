import { Component, inject, provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FakeDesktop } from '../../../testing/fake-desktop';
import { REAL_API_C3 } from '../../../testing/real-api-c3';
import type { ApiRequest, ApiResponse } from '../desktop/desktop-api';
import { AccessStore } from '../identity/access.store';
import { PermissionCode } from '../identity/identity.models';
import { WorkspaceStore } from '../state/workspace.store';
import { RUN_POLL_LIMIT_MS, RUN_POLL_MAX_MS, RUN_POLL_MS, RunMonitor } from './run-monitor';
import { Page, WorkflowRun, WorkflowStepView } from './workflows.models';

const ORG = '0195f0a4-1c2d-7e3f-8a9b-00000000000a';
const real = <T>(name: string): T => REAL_API_C3[name] as T;
const page = <T>(content: readonly T[]) => ({ content, page: 0, size: 100, totalElements: content.length, totalPages: 1 });
const OPEN_RUN = real<WorkflowRun>('runStarted');
const OPEN_STEPS = real<Page<WorkflowStepView>>('stepsQueuedAndRunning').content;
const DONE_RUN = real<WorkflowRun>('runSucceeded');
const DONE_STEPS = real<Page<WorkflowStepView>>('stepsWithRetry').content;

@Component({ selector: 'app-host', template: '', providers: [RunMonitor] })
class Host {
  readonly monitor = inject(RunMonitor);
}

class Api {
  run: WorkflowRun = OPEN_RUN;
  steps: readonly WorkflowStepView[] = OPEN_STEPS;
  failing = false;
  delayMs = 0;
  runReads = 0;
  executionReads = 0;
  inFlightRuns = 0;
  maxInFlightRuns = 0;

  handle = async (request: ApiRequest): Promise<ApiResponse> => {
    const path = request.path;
    const isRun = /\/workflow-runs\/[^/]+$/.test(path);
    if (isRun) {
      this.runReads++;
      this.inFlightRuns++;
      this.maxInFlightRuns = Math.max(this.maxInFlightRuns, this.inFlightRuns);
    }
    try {
      if (this.delayMs) await new Promise((r) => setTimeout(r, this.delayMs));
      if (this.failing) return { status: 503, error: 'network' } as ApiResponse;
      if (isRun) return { status: 200, body: this.run };
      if (path.endsWith('/steps')) return { status: 200, body: page(this.steps) };
      if (path.endsWith('/summary')) return { status: 200, body: real('summaryParallelRunning') };
      if (path.endsWith('/messages') || path.endsWith('/actions')) return { status: 200, body: page([]) };
      if (/\/executions\/[^/]+$/.test(path)) { this.executionReads++; return { status: 200, body: { status: 'RUNNING' } }; }
      if (path.endsWith('/approvals')) return { status: 200, body: page([]) };
      return { status: 200, body: {} };
    } finally {
      if (isRun) this.inFlightRuns--;
    }
  };
}

const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));
async function until(condition: () => boolean, timeoutMs = 3000): Promise<void> {
  const end = Date.now() + timeoutMs;
  while (!condition() && Date.now() < end) await wait(5);
  expect(condition()).toBe(true);
}

function setup(options: { poll?: number; max?: number; limit?: number; permissions?: PermissionCode[] } = {}) {
  const desktop = new FakeDesktop();
  const api = new Api();
  desktop.handler = api.handle;
  desktop.install();
  const organizationId = signal<string | null>(ORG);
  const granted = new Set<PermissionCode>(options.permissions ?? ['workflow.read', 'agent.execution.read', 'approval.read']);
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: RUN_POLL_MS, useValue: options.poll ?? 10 },
      { provide: RUN_POLL_MAX_MS, useValue: options.max ?? 80 },
      { provide: RUN_POLL_LIMIT_MS, useValue: options.limit ?? 60_000 },
      { provide: WorkspaceStore, useValue: { currentOrganizationId: organizationId } },
      { provide: AccessStore, useValue: { can: (p: PermissionCode) => granted.has(p) } },
    ],
  });
  const fixture: ComponentFixture<Host> = TestBed.createComponent(Host);
  return { fixture, monitor: fixture.componentInstance.monitor, api, desktop, organizationId };
}

afterEach(() => {
  FakeDesktop.uninstall();
  TestBed.resetTestingModule();
});

describe('RunMonitor: the polling lifecycle', () => {
  it('follows an OPEN run repeatedly and keeps the last data on screen', async () => {
    const { monitor, api } = setup();
    monitor.start(OPEN_RUN.id);
    await until(() => api.runReads >= 4);
    expect(monitor.run()?.id).toBe(OPEN_RUN.id);
    expect(monitor.steps()).toHaveLength(3);
    expect(monitor.summary()?.peakConcurrency).toBeGreaterThan(0);
    expect(monitor.polling()).toBe(true);
    expect(monitor.terminal()).toBe(false);
    monitor.stop();
  });

  it('stops by itself after reading a terminal state: a finished run is read once, never polled', async () => {
    const { monitor, api } = setup();
    api.run = DONE_RUN;
    api.steps = DONE_STEPS;
    monitor.start(DONE_RUN.id);
    await until(() => monitor.run() !== null && !monitor.loading());
    await wait(120);                                   // many poll periods would have elapsed
    expect(api.runReads).toBe(1);
    expect(monitor.terminal()).toBe(true);
    expect(monitor.polling()).toBe(false);
  });

  it('stops polling when the run ends while it is being followed, after one last refresh', async () => {
    const { monitor, api } = setup();
    monitor.start(OPEN_RUN.id);
    await until(() => api.runReads >= 2);
    api.run = DONE_RUN;
    api.steps = DONE_STEPS;
    await until(() => monitor.terminal());
    const reads = api.runReads;
    await wait(120);
    expect(api.runReads).toBe(reads);
    expect(monitor.polling()).toBe(false);
  });

  it('never has two refresh cycles in flight at once, even when the API is slower than the polling period', async () => {
    const { monitor, api } = setup({ poll: 5 });
    api.delayMs = 40;
    monitor.start(OPEN_RUN.id);
    await until(() => api.runReads >= 3, 4000);
    expect(api.maxInFlightRuns).toBe(1);
    monitor.stop();
  });

  it('does not start a second poller when started again for the same run', async () => {
    const { monitor, api } = setup({ poll: 30 });
    monitor.start(OPEN_RUN.id);
    monitor.start(OPEN_RUN.id);
    monitor.start(OPEN_RUN.id);
    await until(() => api.runReads >= 1);
    await wait(100);
    expect(api.runReads).toBeLessThanOrEqual(5);        // about one read per period, not three
    monitor.stop();
  });

  it('keeps the last data on a temporary failure, backs off, and recovers when the API answers again', async () => {
    const { monitor, api } = setup({ poll: 10, max: 200 });
    monitor.start(OPEN_RUN.id);
    await until(() => monitor.run() !== null);
    api.failing = true;
    await until(() => monitor.error() !== null);
    expect(monitor.run()?.id).toBe(OPEN_RUN.id);        // the data already shown is not wiped
    expect(monitor.steps()).toHaveLength(3);
    const before = api.runReads;
    await wait(150);
    // With a back-off (10, 20, 40, 80 ms…) far fewer requests are made than one every 10 ms.
    expect(api.runReads - before).toBeLessThan(10);
    api.failing = false;
    await until(() => monitor.error() === null, 4000);
    monitor.stop();
  });

  it('is cleaned up with the page: no request is made after it is destroyed', async () => {
    const { monitor, api, fixture } = setup();
    monitor.start(OPEN_RUN.id);
    await until(() => api.runReads >= 2);
    fixture.destroy();
    await wait(30);                                      // let a request already in flight finish
    const reads = api.runReads;
    await wait(150);
    expect(api.runReads).toBe(reads);
    expect(monitor.polling()).toBe(false);
  });

  it('pauses auto-refresh after a bounded time instead of polling forever, and a manual refresh resumes it', async () => {
    const { monitor, api } = setup({ poll: 10, limit: 80 });
    monitor.start(OPEN_RUN.id);
    await until(() => monitor.paused(), 3000);
    const reads = api.runReads;
    await wait(100);
    expect(api.runReads).toBe(reads);
    expect(monitor.polling()).toBe(false);
    await monitor.refresh();
    expect(api.runReads).toBeGreaterThan(reads);
    expect(monitor.paused()).toBe(false);
    monitor.stop();
  });

  it('never shows an answer that arrives after the organization changed', async () => {
    const { monitor, api, organizationId } = setup({ poll: 1000 });
    api.delayMs = 60;
    monitor.start(OPEN_RUN.id);
    organizationId.set('0195f0a4-1c2d-7e3f-8a9b-00000000000b');      // the user switches organization while the request is in flight
    await wait(200);
    expect(monitor.run()).toBeNull();
    monitor.stop();
  });

  it('reads executions and approvals only when the user is allowed to, and not at all for a run without live workers', async () => {
    const limited = setup({ permissions: ['workflow.read'] });
    limited.monitor.start(OPEN_RUN.id);
    await until(() => limited.api.runReads >= 2);
    expect(limited.api.executionReads).toBe(0);
    expect(limited.monitor.live().size).toBe(0);
    limited.monitor.stop();
    TestBed.resetTestingModule();
    const allowed = setup();
    allowed.monitor.start(OPEN_RUN.id);
    await until(() => allowed.api.executionReads >= 2);
    expect(allowed.monitor.live().size).toBe(2);                     // the two live workers only; the queued one has no execution
    allowed.monitor.stop();
  });
});
