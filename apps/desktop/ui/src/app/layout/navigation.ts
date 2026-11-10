import { IconName } from '../shared/components/icon/icon-registry';

export type NavBadgeSource = 'pending-approvals' | 'unread-messages';

export interface NavItem {
  readonly label: string;
  readonly icon: IconName;
  readonly route: string;
  readonly badge?: NavBadgeSource;
  /** Exact matching for the active state (only for routes that are prefixes of others). */
  readonly exact?: boolean;
}

export const MAIN_NAVIGATION: readonly NavItem[] = [
  { label: 'Overview', icon: 'dashboard', route: '/dashboard' },
  { label: 'Agents', icon: 'agent', route: '/agents' },
  { label: 'Organization', icon: 'organization', route: '/organization' },
  { label: 'Projects', icon: 'projects', route: '/projects' },
  { label: 'Communication', icon: 'messages', route: '/communication', badge: 'unread-messages' },
  { label: 'Activity Center', icon: 'activity', route: '/activity-center', badge: 'pending-approvals' },
];

export const PREFERENCES_NAVIGATION: readonly NavItem[] = [
  { label: 'Settings', icon: 'settings', route: '/settings/general' },
  { label: 'Profile', icon: 'user', route: '/profile' },
  { label: 'Help & support', icon: 'support', route: '/help' },
];

export const ORGANIZATION_SETTINGS_ROUTE = '/settings/organization';
