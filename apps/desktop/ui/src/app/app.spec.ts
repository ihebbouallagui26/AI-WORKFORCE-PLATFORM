import { TestBed } from '@angular/core/testing';
import { App } from './app';
import { configureMockWorkspace } from '../testing/mock-workspace';

describe('App', () => {
  beforeEach(() => configureMockWorkspace());

  it('creates the root component with a router outlet', async () => {
    await TestBed.configureTestingModule({ imports: [App] }).compileComponents();
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    expect(fixture.componentInstance).toBeTruthy();
    expect((fixture.nativeElement as HTMLElement).querySelector('router-outlet')).not.toBeNull();
  });
});
