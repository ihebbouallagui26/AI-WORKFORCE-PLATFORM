import { inject, Injectable, InjectionToken } from '@angular/core';
import { delay, Observable, of, throwError } from 'rxjs';
import { FixtureClock } from './fixtures/fixture-helpers';
import { createCatalogSeed } from './fixtures/catalog.fixtures';
import { createHelixSeed } from './fixtures/helix.fixtures';
import { createNorthwindSeed } from './fixtures/northwind.fixtures';
import { CatalogSeed, OrganizationSeed } from './fixtures/mock-seed';

/** Simulated network latency, so loading states are exercised during development. */
export const MOCK_LATENCY_MS = new InjectionToken<number>('MOCK_LATENCY_MS', {
  providedIn: 'root',
  factory: () => 220,
});

type Tables = Omit<OrganizationSeed, 'organization'> & {
  organizations: OrganizationSeed['organization'][];
};

/**
 * In-memory dataset backing every mock API.
 * Mutations live for the current session only: nothing is persisted or transmitted.
 */
@Injectable({ providedIn: 'root' })
export class MockDatabase {
  private readonly latency = inject(MOCK_LATENCY_MS);

  readonly catalog: CatalogSeed;
  readonly tables: Tables;

  constructor() {
    const clock = new FixtureClock();
    const seeds = [createNorthwindSeed(clock), createHelixSeed(clock)];
    this.catalog = createCatalogSeed();
    this.tables = {
      organizations: seeds.map((s) => s.organization),
      departments: seeds.flatMap((s) => s.departments),
      roles: seeds.flatMap((s) => s.roles),
      teams: seeds.flatMap((s) => s.teams),
      employees: seeds.flatMap((s) => s.employees),
      agents: seeds.flatMap((s) => s.agents),
      projects: seeds.flatMap((s) => s.projects),
      tasks: seeds.flatMap((s) => s.tasks),
      conversations: seeds.flatMap((s) => s.conversations),
      messages: seeds.flatMap((s) => s.messages),
      sharedFiles: seeds.flatMap((s) => s.sharedFiles),
      approvals: seeds.flatMap((s) => s.approvals),
      executions: seeds.flatMap((s) => s.executions),
      activity: seeds.flatMap((s) => s.activity),
      reports: seeds.flatMap((s) => s.reports),
      meetings: seeds.flatMap((s) => s.meetings),
    };
  }

  /** Returns a deep copy so callers can never mutate the dataset by reference. */
  respond<T>(value: T): Observable<T> {
    return of(structuredClone(value)).pipe(delay(this.latency));
  }

  notFound(entity: string, id: string): Observable<never> {
    return throwError(() => new Error(`${entity} "${id}" was not found in the mock dataset.`));
  }

  /** Replaces the item with the same id inside a table and returns the stored value. */
  replace<T extends { readonly id: string }>(table: T[], next: T): T {
    const index = table.findIndex((item) => item.id === next.id);
    if (index >= 0) {
      table[index] = next;
    }
    return next;
  }

  nextId(prefix: string): string {
    return `${prefix}-${crypto.randomUUID().slice(0, 8)}`;
  }

  now(): string {
    return new Date().toISOString();
  }
}
