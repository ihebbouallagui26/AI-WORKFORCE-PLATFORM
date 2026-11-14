import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { AgentsStore } from '../../core/agents/agents.store';
import { EmptyState } from '../../shared/components/empty-state/empty-state';
import { PageHeader } from '../../shared/components/page-header/page-header';
import { TabItem, Tabs } from '../../shared/components/tabs/tabs';
import { AgentsTab } from './agents-tab';
import { ProvidersTab } from './providers-tab';
import { SkillsTab } from './skills-tab';

/**
 * AI agents of the organization, backed by the Core API: agents and their configuration, model
 * provider connections and skills. Agents are configured here but never executed (Phase C).
 */
@Component({
  selector: 'app-ai-agents-page',
  imports: [PageHeader, Tabs, EmptyState, AgentsTab, ProvidersTab, SkillsTab],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <ui-page-header heading="AI agents"
        description="Configure AI employees, their model providers, skills and tool permissions. Agents run only when a person launches them, as text generation." />
      @if (!store.organizationId()) {
        <ui-empty-state icon="building" heading="No organization selected" description="Select or create an organization first." />
      } @else {
        <ui-tabs [items]="items()" [(active)]="active" label="AI agent sections" />
        <div class="content">
          @switch (active()) {
            @case ('agents') { <app-agents-tab /> }
            @case ('providers') { <app-providers-tab /> }
            @case ('skills') { <app-skills-tab /> }
          }
        </div>
      }
    </div>
  `,
  styles: `.content { margin: var(--space-4) 0; }`,
})
export class AiAgentsPage {
  protected readonly store = inject(AgentsStore);
  protected readonly active = signal<string>('agents');
  protected readonly items = computed<readonly TabItem[]>(() => [
    { id: 'agents', label: 'Agents', count: this.store.totalAgents() },
    { id: 'providers', label: 'Providers', count: this.store.connections().length },
    { id: 'skills', label: 'Skills', count: this.store.skills().length },
  ]);
}
