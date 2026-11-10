import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { Id } from '../../core/models';
import { OrganizationSwitchService } from '../../core/services/organization-switch.service';
import { ToastService } from '../../core/services/toast.service';
import { CommunicationStore } from '../../core/state/communication.store';
import { SupervisionStore } from '../../core/state/supervision.store';
import { WorkspaceStore } from '../../core/state/workspace.store';
import { Dropdown } from '../../shared/components/dropdown/dropdown';
import { Icon } from '../../shared/components/icon/icon';
import { Tooltip } from '../../shared/directives/tooltip';
import { LayoutService } from '../layout.service';
import {
  MAIN_NAVIGATION,
  NavBadgeSource,
  ORGANIZATION_SETTINGS_ROUTE,
  PREFERENCES_NAVIGATION,
} from '../navigation';

@Component({
  selector: 'app-sidebar',
  imports: [RouterLink, RouterLinkActive, Icon, Tooltip, Dropdown],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[class.is-collapsed]': 'layout.sidebarCollapsed()' },
  templateUrl: './sidebar.html',
  styleUrl: './sidebar.scss',
})
export class Sidebar {
  protected readonly layout = inject(LayoutService);
  protected readonly workspace = inject(WorkspaceStore);
  private readonly supervision = inject(SupervisionStore);
  private readonly communication = inject(CommunicationStore);
  private readonly toast = inject(ToastService);
  private readonly organizationSwitch = inject(OrganizationSwitchService);

  protected readonly mainNavigation = MAIN_NAVIGATION;
  protected readonly preferencesNavigation = PREFERENCES_NAVIGATION;
  protected readonly organizationSettingsRoute = ORGANIZATION_SETTINGS_ROUTE;

  protected badgeValue(source: NavBadgeSource | undefined): number {
    switch (source) {
      case 'pending-approvals':
        return this.supervision.pendingApprovals().length;
      case 'unread-messages':
        return this.communication.totalUnread();
      default:
        return 0;
    }
  }

  protected switchOrganization(organizationId: Id): void {
    if (!this.organizationSwitch.switchTo(organizationId)) {
      return;
    }
    const organization = this.workspace.organizations().find((o) => o.id === organizationId);
    this.toast.info('Workspace switched', organization ? `Now viewing ${organization.name}.` : null);
  }
}
