import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { API_CONFIG } from '../../../core/data/api-config';
import { PreferencesService } from '../../../core/services/preferences.service';
import { ToastService } from '../../../core/services/toast.service';
import { LayoutService } from '../../../layout/layout.service';
import { Panel } from '../../../shared/components/panel/panel';
import { Segmented, SegmentOption } from '../../../shared/components/segmented/segmented';

type AgentsView = 'grid' | 'table';

@Component({
  selector: 'app-general-settings',
  imports: [Panel, Segmented],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="stack">
      <ui-panel heading="Interface" subheading="Stored on this device only">
        <div class="setting">
          <div>
            <p class="setting__title">Collapse the sidebar</p>
            <p class="setting__description">Keeps more room for content. Toggle any time with Ctrl + B.</p>
          </div>
          <label class="checkbox">
            <input type="checkbox" [checked]="layout.sidebarPreference()" (change)="setCollapsed(collapseBox.checked)" #collapseBox />
            <span class="sr-only">Collapse the sidebar</span>
          </label>
        </div>
        <hr class="divider" />
        <div class="setting">
          <div>
            <p class="setting__title">Default agents layout</p>
            <p class="setting__description">How the Agents page lists AI employees.</p>
          </div>
          <ui-segmented label="Default agents layout" [options]="viewOptions" [value]="agentsView()" (valueChange)="setAgentsView($event)" />
        </div>
      </ui-panel>

      <ui-panel heading="Data source" subheading="Where the application reads and writes data">
        <dl class="meta-list">
          <dt>Mode</dt>
          <dd><span class="chip chip--accent">{{ api.dataSource === 'mock' ? 'Mock data (local session)' : 'Core API' }}</span></dd>
          <dt>Core API</dt>
          <dd class="mono">{{ api.baseUrl }} <span class="text-tertiary">— not connected in Phase A</span></dd>
          <dt>Realtime</dt>
          <dd class="mono">{{ api.realtimeUrl }} <span class="text-tertiary">— not connected in Phase A</span></dd>
        </dl>
        <p class="mock-notice notice">Changes you make (agents, tasks, messages, decisions) live in memory for the current session and are lost when the application restarts.</p>
      </ui-panel>
    </div>
  `,
  styles: `
    .setting {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-6);
      padding: var(--space-2) 0;
    }
    .setting__title {
      color: var(--color-text-primary);
      font-weight: var(--weight-medium);
    }
    .setting__description {
      color: var(--color-text-tertiary);
      font-size: var(--text-xs);
    }
    .divider {
      margin: var(--space-3) 0;
    }
    .notice {
      margin-top: var(--space-4);
    }
  `,
})
export class GeneralSettings {
  protected readonly layout = inject(LayoutService);
  protected readonly api = inject(API_CONFIG);
  private readonly preferences = inject(PreferencesService);
  private readonly toast = inject(ToastService);

  protected readonly viewOptions: readonly SegmentOption<AgentsView>[] = [
    { value: 'grid', label: 'Grid', icon: 'grid' },
    { value: 'table', label: 'Table', icon: 'list' },
  ];
  protected readonly agentsView = signal<AgentsView>(this.preferences.get('agentsView'));

  protected setCollapsed(collapsed: boolean): void {
    this.layout.setSidebarCollapsed(collapsed);
    this.toast.info(collapsed ? 'Sidebar collapsed' : 'Sidebar expanded');
  }

  protected setAgentsView(view: AgentsView): void {
    this.agentsView.set(view);
    this.preferences.set('agentsView', view);
    this.toast.info('Preference saved', `Agents will be shown as a ${view}.`);
  }
}
