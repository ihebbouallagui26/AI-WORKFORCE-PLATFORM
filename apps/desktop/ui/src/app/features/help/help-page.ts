import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { DesktopService } from '../../core/desktop/desktop.service';
import { ToastService } from '../../core/services/toast.service';
import { Icon } from '../../shared/components/icon/icon';
import { IconName } from '../../shared/components/icon/icon-registry';
import { PageHeader } from '../../shared/components/page-header/page-header';
import { Panel } from '../../shared/components/panel/panel';

const ISSUES_URL = 'https://github.com/ihebbouallagui26/AI-WORKFORCE-PLATFORM/issues';

interface GuideEntry {
  readonly icon: IconName;
  readonly title: string;
  readonly route: string;
  readonly text: string;
}

@Component({
  selector: 'app-help-page',
  imports: [RouterLink, PageHeader, Panel, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <ui-page-header heading="Help & support" description="How the platform is organized, keyboard shortcuts and what this preview does not do yet.">
        <button type="button" class="btn btn--secondary" (click)="reportIssue()"><ui-icon name="external" [size]="14" /> Report an issue</button>
      </ui-page-header>

      <ui-panel heading="Getting around" [flush]="true">
        <ul class="guide">
          @for (entry of guide; track entry.route) {
            <li>
              <a class="guide__item" [routerLink]="entry.route">
                <span class="guide__icon"><ui-icon [name]="entry.icon" [size]="16" /></span>
                <span>
                  <span class="guide__title">{{ entry.title }}</span>
                  <span class="guide__text">{{ entry.text }}</span>
                </span>
              </a>
            </li>
          }
        </ul>
      </ui-panel>

      <div class="grid grid--halves">
        <ui-panel heading="Keyboard shortcuts" id="shortcuts">
          <dl class="shortcuts">
            <dt><kbd class="kbd">Ctrl</kbd> <kbd class="kbd">K</kbd></dt>
            <dd>Search agents, people, projects and tasks</dd>
            <dt><kbd class="kbd">Ctrl</kbd> <kbd class="kbd">B</kbd></dt>
            <dd>Collapse or expand the sidebar</dd>
            <dt><kbd class="kbd">Enter</kbd></dt>
            <dd>Send a message (Shift + Enter for a new line)</dd>
            <dt><kbd class="kbd">Esc</kbd></dt>
            <dd>Close dialogs, menus and search results</dd>
            <dt><kbd class="kbd">←</kbd> <kbd class="kbd">→</kbd></dt>
            <dd>Move between tabs</dd>
          </dl>
        </ui-panel>

        <ui-panel heading="Current limitations (Phase A)">
          <ul class="limits">
            <li>All data is mock data held in memory; changes are lost on restart.</li>
            <li>AI agents do not execute tasks or reply to messages: the orchestration engine is not connected.</li>
            <li>Approval decisions are recorded locally and are not delivered to agents.</li>
            <li>Voice messages, calls and file attachments are planned for later phases.</li>
            <li>Authentication and provider credentials will be handled by the Core API.</li>
          </ul>
        </ui-panel>
      </div>
    </div>
  `,
  styles: `
    .guide {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
    .guide__item {
      display: flex;
      align-items: flex-start;
      gap: var(--space-3);
      padding: var(--space-4);
      border-bottom: 1px solid var(--color-border-subtle);
      color: inherit;
    }
    .guide__item:hover {
      background: var(--color-surface-hover);
      text-decoration: none;
    }
    .guide__icon {
      display: inline-flex;
      padding: 8px;
      border-radius: var(--radius-md);
      background: var(--color-accent-subtle);
      color: var(--color-accent-text);
    }
    .guide__title {
      display: block;
      color: var(--color-text-primary);
      font-weight: var(--weight-semibold);
    }
    .guide__text {
      display: block;
      color: var(--color-text-tertiary);
      font-size: var(--text-xs);
    }
    .shortcuts {
      display: grid;
      grid-template-columns: 120px minmax(0, 1fr);
      gap: var(--space-3) var(--space-4);
      margin: 0;
    }
    .shortcuts dd {
      margin: 0;
      color: var(--color-text-secondary);
    }
    .limits {
      display: flex;
      flex-direction: column;
      gap: var(--space-2);
      padding-left: var(--space-4);
      list-style: disc;
      color: var(--color-text-secondary);
    }
  `,
})
export class HelpPage {
  private readonly desktop = inject(DesktopService);
  private readonly toast = inject(ToastService);

  protected readonly guide: readonly GuideEntry[] = [
    { icon: 'dashboard', title: 'Overview', route: '/dashboard', text: 'Key figures, active projects, agent status and decisions waiting for you.' },
    { icon: 'agent', title: 'Agents', route: '/agents', text: 'Create AI employees, define their identity, skills, provider and permissions.' },
    { icon: 'organization', title: 'Organization', route: '/organization', text: 'Departments, teams, people and agents, with the reporting structure.' },
    { icon: 'projects', title: 'Projects', route: '/projects', text: 'Simultaneous projects with a Kanban board, task list and team view.' },
    { icon: 'messages', title: 'Communication', route: '/communication', text: 'Direct messages, groups and channels between people and agents.' },
    { icon: 'activity', title: 'Activity Center', route: '/activity-center', text: 'Supervise agent activity and approve or reject sensitive operations.' },
  ];

  protected async reportIssue(): Promise<void> {
    const result = await this.desktop.openExternal(ISSUES_URL);
    if (!result.ok) {
      this.toast.error('Link blocked', `The link could not be opened (${result.reason}).`);
    }
  }
}
