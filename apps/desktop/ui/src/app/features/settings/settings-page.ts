import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { AUTH_REQUIRED } from '../../core/auth/session.service';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { EmptyState } from '../../shared/components/empty-state/empty-state';
import { Icon } from '../../shared/components/icon/icon';
import { IconName } from '../../shared/components/icon/icon-registry';
import { PageHeader } from '../../shared/components/page-header/page-header';
import { AboutSettings } from './sections/about-settings';
import { InvitationsSettings } from './sections/invitations-settings';
import { MembersSettings } from './sections/members-settings';
import { RolesSettings } from './sections/roles-settings';
import { GeneralSettings } from './sections/general-settings';
import { OrganizationSettings } from './sections/organization-settings';
import { ProviderSettings } from './sections/provider-settings';

type SettingsSection = 'general' | 'organization' | 'members' | 'roles' | 'invitations' | 'providers' | 'about';

/** Sections backed by the Core API: only in `api` mode. */
const API_SECTIONS: ReadonlySet<SettingsSection> = new Set(['members', 'roles', 'invitations']);

const SECTIONS: readonly { id: SettingsSection; label: string; icon: IconName }[] = [
  { id: 'general', label: 'General', icon: 'settings' },
  { id: 'organization', label: 'Organization', icon: 'building' },
  { id: 'members', label: 'Members', icon: 'users' },
  { id: 'roles', label: 'Roles & permissions', icon: 'lock' },
  { id: 'invitations', label: 'Invitations', icon: 'mail' },
  { id: 'providers', label: 'AI providers', icon: 'cpu' },
  { id: 'about', label: 'About & security', icon: 'shield-check' },
];

@Component({
  selector: 'app-settings-page',
  imports: [RouterLink, RouterLinkActive, PageHeader, Icon, EmptyState, GeneralSettings, OrganizationSettings, MembersSettings, RolesSettings, InvitationsSettings, ProviderSettings, AboutSettings],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <ui-page-header heading="Settings" description="Preferences for this device, organization details and platform information." />
      <div class="settings">
        <nav class="settings__nav" aria-label="Settings sections">
          @for (item of sections; track item.id) {
            <a class="settings__link" [routerLink]="['/settings', item.id]" routerLinkActive="is-active" ariaCurrentWhenActive="page">
              <ui-icon [name]="item.icon" [size]="15" /> {{ item.label }}
            </a>
          }
        </nav>
        <div class="settings__content">
          @switch (current()) {
            @case ('general') { <app-general-settings /> }
            @case ('organization') { <app-organization-settings /> }
            @case ('members') { <app-members-settings /> }
            @case ('roles') { <app-roles-settings /> }
            @case ('invitations') { <app-invitations-settings /> }
            @case ('providers') { <app-provider-settings /> }
            @case ('about') { <app-about-settings /> }
            @default {
              <ui-empty-state icon="help" heading="Unknown settings section">
                <a class="btn btn--secondary" routerLink="/settings/general">Open general settings</a>
              </ui-empty-state>
            }
          }
        </div>
      </div>
    </div>
  `,
  styles: `
    .settings {
      display: grid;
      grid-template-columns: 220px minmax(0, 1fr);
      gap: var(--space-6);
      align-items: start;
    }
    .settings__nav {
      display: flex;
      flex-direction: column;
      gap: 1px;
    }
    .settings__link {
      display: flex;
      align-items: center;
      gap: var(--space-2);
      height: 34px;
      padding: 0 var(--space-3);
      border-radius: var(--radius-md);
      color: var(--color-text-secondary);
      font-weight: var(--weight-medium);
    }
    .settings__link:hover {
      background: var(--color-surface-hover);
      color: var(--color-text-primary);
      text-decoration: none;
    }
    .settings__link.is-active {
      background: var(--color-surface-2);
      color: var(--color-text-primary);
      box-shadow: inset 2px 0 0 var(--color-accent);
    }
    .settings__content {
      max-width: 820px;
    }
    @media (max-width: 900px) {
      .settings {
        grid-template-columns: minmax(0, 1fr);
      }
      .settings__nav {
        flex-direction: row;
        flex-wrap: wrap;
      }
    }
  `,
})
export class SettingsPage {
  /** Route parameter bound by the router. */
  readonly section = input<string>('general');
  private readonly apiMode = inject(AUTH_REQUIRED);
  protected readonly sections = SECTIONS.filter((s) => this.apiMode || !API_SECTIONS.has(s.id));
  protected readonly current = computed<SettingsSection | null>(
    () => this.sections.find((s) => s.id === this.section())?.id ?? null,
  );
}
