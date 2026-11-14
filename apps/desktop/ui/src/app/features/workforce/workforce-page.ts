import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { WorkforceStore } from '../../core/workforce/workforce.store';
import { EmptyState } from '../../shared/components/empty-state/empty-state';
import { PageHeader } from '../../shared/components/page-header/page-header';
import { Panel } from '../../shared/components/panel/panel';
import { TabItem, Tabs } from '../../shared/components/tabs/tabs';
import { WfEmployees } from './employees-tab';
import { WfPositions } from './positions-tab';
import { WfStructure } from './structure-tab';
import { WfTeams } from './teams-tab';

type WorkforceTab = 'structure' | 'employees' | 'positions' | 'teams';
const TABS: readonly WorkforceTab[] = ['structure', 'employees', 'positions', 'teams'];

/**
 * The organization's workforce, backed by the Core API: department hierarchy, employees,
 * positions and teams. AI agents are managed in their own section and listed apart in teams.
 */
@Component({
  selector: 'app-workforce-page',
  imports: [RouterLink, PageHeader, Panel, Tabs, EmptyState, WfStructure, WfEmployees, WfPositions, WfTeams],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <ui-page-header heading="Organization" [description]="workspaceName() + ' — structure, people, positions and teams.'">
        @if (store.can('invitation.create')) {
          <a class="btn btn--secondary" routerLink="/settings/invitations">Invite a collaborator</a>
        }
      </ui-page-header>

      @if (!store.organizationId()) {
        <ui-empty-state icon="building" heading="No organization selected" description="Select or create an organization first." />
      } @else {
        <ui-tabs [items]="items()" [(active)]="active" label="Organization sections" />
        <div class="content">
          @switch (active()) {
            @case ('structure') { <app-wf-structure /> }
            @case ('employees') { <app-wf-employees /> }
            @case ('positions') { <app-wf-positions /> }
            @case ('teams') { <app-wf-teams /> }
          }
        </div>
        <ui-panel heading="AI agents" subheading="Managed separately from employees">
          <p class="text-tertiary">
            AI agents are not employees: they are configured in <a routerLink="/agents">AI agents</a> and join teams through
            a dedicated relationship, listed apart from human members in the Teams tab.
          </p>
        </ui-panel>
      }
    </div>
  `,
  styles: `
    .content { margin: var(--space-4) 0; }
  `,
})
export class WorkforcePage {
  protected readonly store = inject(WorkforceStore);
  private readonly router = inject(Router);

  /** Query parameter bound by the router. */
  readonly tab = input<string | undefined>();

  protected readonly active = signal<string>('structure');
  protected readonly workspaceName = computed(() => this.store.access.organization()?.name ?? 'Your organization');
  protected readonly items = computed<readonly TabItem[]>(() => [
    { id: 'structure', label: 'Structure', count: this.store.departments().length },
    { id: 'employees', label: 'Employees', count: this.store.totalEmployees() },
    { id: 'positions', label: 'Positions', count: this.store.positions().length },
    { id: 'teams', label: 'Teams', count: this.store.teams().length },
  ]);

  constructor() {
    effect(() => {
      const requested = this.tab();
      if (requested && (TABS as readonly string[]).includes(requested)) {
        untracked(() => this.active.set(requested));
      }
    });
    effect(() => {
      const current = this.active();
      untracked(() => {
        if (current !== (this.tab() ?? 'structure')) {
          void this.router.navigate([], { queryParams: { tab: current }, queryParamsHandling: 'merge', replaceUrl: true });
        }
      });
    });
  }
}
