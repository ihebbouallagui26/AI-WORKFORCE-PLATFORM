import { ApplicationRef, provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { environment } from '../environments/environment';
import { provideDataLayer } from '../app/core/data/provide-data-layer';
import { MOCK_LATENCY_MS } from '../app/core/data/mock/mock-database';

/** Configures TestBed with the mock data layer and no simulated latency. */
export function configureMockWorkspace(): void {
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      provideRouter([]),
      provideDataLayer(environment),
      { provide: MOCK_LATENCY_MS, useValue: 0 },
    ],
  });
}

/** Waits until every pending resource (organization, then org-scoped lists) has loaded. */
export async function settle(): Promise<void> {
  const appRef = TestBed.inject(ApplicationRef);
  for (let i = 0; i < 3; i++) {
    TestBed.tick();
    await appRef.whenStable();
  }
}
