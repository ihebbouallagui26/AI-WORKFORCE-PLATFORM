import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink } from '@angular/router';
import { filter, map, startWith } from 'rxjs';
import { AUTH_REQUIRED } from '../../core/auth/session.service';
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
  imports: [RouterOutlet, RouterLink, Sidebar, Topbar, ToastHost, ConfirmHost, EmptyState],
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
        } @else if (needsOrganization()) {
          <ui-empty-state icon="building" heading="No organization yet" description="Create an organization, or join one with the code from an invitation e-mail.">
            <a class="btn btn--primary" routerLink="/organizations/new">Create an organization</a>
            <a class="btn btn--secondary" routerLink="/invitations/accept">Accept an invitation</a>
          </ui-empty-state>
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
  private readonly router = inject(Router);
  private readonly apiMode = inject(AUTH_REQUIRED);

  private readonly url = toSignal(
    this.router.events.pipe(
      filter((e) => e instanceof NavigationEnd),
      startWith(null),
      map(() => this.router.url),
    ),
    { initialValue: this.router.url },
  );

  /** Pages that work without any organization (onboarding, profile, device settings, help). */
  private static readonly ORGANIZATION_FREE = ['/organizations/new', '/invitations/accept', '/profile', '/help', '/settings/general', '/settings/about'];

  /** A signed-in user without any organization is guided to create or join one. */
  protected readonly needsOrganization = computed(
    () =>
      this.apiMode &&
      !this.workspace.loading() &&
      this.workspace.organizations().length === 0 &&
      !MainLayout.ORGANIZATION_FREE.some((path) => this.url().startsWith(path)),
  );

  protected onKeydown(event: KeyboardEvent): void {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'b') {
      event.preventDefault();
      this.layout.toggleSidebar();
    }
  }
}
