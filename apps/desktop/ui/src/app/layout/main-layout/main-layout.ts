import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { WorkspaceStore } from '../../core/state/workspace.store';
import { EmptyState } from '../../shared/components/empty-state/empty-state';
import { ConfirmHost } from '../confirm-host/confirm-host';
import { LayoutService } from '../layout.service';
import { Sidebar } from '../sidebar/sidebar';
import { ToastHost } from '../toast-host/toast-host';
import { Topbar } from '../topbar/topbar';

@Component({
  selector: 'app-main-layout',
  imports: [RouterOutlet, Sidebar, Topbar, ToastHost, ConfirmHost, EmptyState],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '(document:keydown)': 'onKeydown($event)' },
  template: `
    <a class="skip-link" href="#main-content">Skip to content</a>
    <app-sidebar />
    <div class="shell__main">
      <app-topbar />
      <main id="main-content" class="shell__content" tabindex="-1">
        @if (workspace.error(); as error) {
          <ui-empty-state icon="circle-alert" tone="danger" heading="Workspace unavailable" [description]="error" />
        } @else {
          <router-outlet />
        }
      </main>
    </div>
    <app-toast-host />
    <app-confirm-host />
  `,
  styles: `
    :host {
      display: flex;
      height: 100vh;
      overflow: hidden;
    }
    .shell__main {
      display: flex;
      flex-direction: column;
      flex: 1;
      min-width: 0;
    }
    .shell__content {
      flex: 1;
      min-height: 0;
      overflow-y: auto;
      outline: none;
    }
    .skip-link {
      position: absolute;
      left: var(--space-2);
      top: -40px;
      z-index: var(--z-toast);
      padding: var(--space-2) var(--space-3);
      border-radius: var(--radius-md);
      background: var(--color-accent);
      color: #fff;
    }
    .skip-link:focus {
      top: var(--space-2);
    }
  `,
})
export class MainLayout {
  protected readonly workspace = inject(WorkspaceStore);
  private readonly layout = inject(LayoutService);

  protected onKeydown(event: KeyboardEvent): void {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'b') {
      event.preventDefault();
      this.layout.toggleSidebar();
    }
  }
}
