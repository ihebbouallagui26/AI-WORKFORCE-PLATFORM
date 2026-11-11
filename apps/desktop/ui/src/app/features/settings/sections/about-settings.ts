import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { from } from 'rxjs';
import { DesktopService } from '../../../core/desktop/desktop.service';
import { ToastService } from '../../../core/services/toast.service';
import { Icon } from '../../../shared/components/icon/icon';
import { Panel } from '../../../shared/components/panel/panel';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';

const REPOSITORY_URL = 'https://github.com/ihebbouallagui26/AI-WORKFORCE-PLATFORM';

@Component({
  selector: 'app-about-settings',
  imports: [Panel, Icon, Skeleton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="stack">
      <ui-panel heading="Application">
        <button panelActions type="button" class="btn btn--secondary btn--sm" (click)="openRepository()">
          <ui-icon name="external" [size]="14" /> Source repository
        </button>
        @if (info.hasValue()) {
          @let app = info.value();
          <dl class="meta-list">
            <dt>Runtime</dt>
            <dd>{{ desktop.isDesktop ? 'Desktop (Electron)' : 'Browser preview' }}</dd>
            <dt>Version</dt>
            <dd class="mono">{{ app.version }}</dd>
            <dt>Platform</dt>
            <dd class="mono">{{ app.platform }} · {{ app.arch }}</dd>
            <dt>Electron</dt>
            <dd class="mono">{{ app.electronVersion }}</dd>
            <dt>Chromium</dt>
            <dd class="mono">{{ app.chromeVersion }}</dd>
            <dt>Build</dt>
            <dd>{{ app.isPackaged ? 'Packaged' : 'Development' }}</dd>
          </dl>
        } @else if (info.error()) {
          <p class="text-tertiary">Application information is unavailable.</p>
        } @else {
          <ui-skeleton [rows]="3" />
        }
      </ui-panel>

      <ui-panel heading="Security model">
        <ul class="security">
          <li><ui-icon name="shield-check" [size]="15" /> The interface runs in an isolated, sandboxed renderer without Node.js access.</li>
          <li><ui-icon name="shield-check" [size]="15" /> Only a small typed desktop API is exposed: app information and opening approved links.</li>
          <li><ui-icon name="shield-check" [size]="15" /> Navigation and new windows are restricted; a Content Security Policy is enforced.</li>
          <li><ui-icon name="shield-check" [size]="15" /> No credentials or provider secrets are stored on this device.</li>
          <li><ui-icon name="shield-check" [size]="15" /> AI agents are supervised: sensitive operations require human approval.</li>
        </ul>
      </ui-panel>
    </div>
  `,
  styles: `
    .security {
      display: flex;
      flex-direction: column;
      gap: var(--space-3);
      color: var(--color-text-secondary);
    }
    .security li {
      display: flex;
      align-items: flex-start;
      gap: var(--space-2);
    }
    .security ui-icon {
      margin-top: 2px;
      color: var(--color-success);
    }
  `,
})
export class AboutSettings {
  protected readonly desktop = inject(DesktopService);
  private readonly toast = inject(ToastService);

  protected readonly info = rxResource({ stream: () => from(this.desktop.getAppInfo()) });

  protected async openRepository(): Promise<void> {
    const result = await this.desktop.openExternal(REPOSITORY_URL);
    if (!result.ok) {
      this.toast.error('Link blocked', `The link could not be opened (${result.reason}).`);
    }
  }
}
