import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { AgentStore } from '../../../core/state/agent.store';
import { Badge } from '../../../shared/components/badge/badge';
import { Icon } from '../../../shared/components/icon/icon';
import { Panel } from '../../../shared/components/panel/panel';
import { PROVIDER_CONNECTION } from '../../../shared/utils/presentation';

@Component({
  selector: 'app-provider-settings',
  imports: [DecimalPipe, Panel, Badge, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="stack">
      <p class="mock-notice">
        <ui-icon name="lock" [size]="14" />
        Provider credentials are managed by the platform backend and never handled by the desktop client. Connection states below are mock values.
      </p>
      @for (provider of store.providers(); track provider.id) {
        <ui-panel [heading]="provider.name" [subheading]="provider.description" [flush]="true">
          <div panelActions class="provider__actions">
            <span class="chip">{{ provider.deployment === 'local' ? 'Local runner' : 'Cloud' }}</span>
            <ui-badge [presentation]="connection[provider.connectionStatus]" />
          </div>
          <table class="table">
            <thead>
              <tr>
                <th scope="col">Model</th>
                <th scope="col">Context window</th>
                <th scope="col">Capabilities</th>
                <th scope="col">Used by</th>
              </tr>
            </thead>
            <tbody>
              @for (model of store.modelsForProvider(provider.id); track model.id) {
                <tr>
                  <td class="table__primary">{{ model.name }}</td>
                  <td class="tabular">{{ model.contextWindowTokens | number }} tokens</td>
                  <td>
                    <div class="chip-list">
                      @for (capability of model.capabilities; track capability) {
                        <span class="chip">{{ capability }}</span>
                      }
                    </div>
                  </td>
                  <td class="tabular">{{ usage(model.id) }} {{ usage(model.id) === 1 ? 'agent' : 'agents' }}</td>
                </tr>
              }
            </tbody>
          </table>
        </ui-panel>
      }
    </div>
  `,
  styles: `
    .provider__actions {
      display: flex;
      align-items: center;
      gap: var(--space-2);
    }
  `,
})
export class ProviderSettings {
  protected readonly store = inject(AgentStore);
  protected readonly connection = PROVIDER_CONNECTION;

  protected usage(modelId: string): number {
    return this.store.agents().filter((a) => a.providers.some((p) => p.modelId === modelId)).length;
  }
}
