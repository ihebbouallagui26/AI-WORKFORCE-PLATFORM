import { Routes } from '@angular/router';
import { MainLayout } from './layout/main-layout/main-layout';

/**
 * Every feature is lazy-loaded. `data.title` feeds the topbar breadcrumb and the window title.
 */
export const routes: Routes = [
  {
    path: '',
    component: MainLayout,
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
      {
        path: 'dashboard',
        title: 'Overview',
        data: { title: 'Overview' },
        loadComponent: () => import('./features/dashboard/dashboard-page').then((m) => m.DashboardPage),
      },
      {
        path: 'agents',
        title: 'Agents',
        data: { title: 'Agents' },
        loadChildren: () => import('./features/agents/agents.routes').then((m) => m.AGENTS_ROUTES),
      },
      {
        path: 'organization',
        title: 'Organization',
        data: { title: 'Organization' },
        loadComponent: () =>
          import('./features/organization/organization-page').then((m) => m.OrganizationPage),
      },
      {
        path: 'projects',
        title: 'Projects',
        data: { title: 'Projects' },
        loadChildren: () => import('./features/projects/projects.routes').then((m) => m.PROJECTS_ROUTES),
      },
      {
        path: 'communication',
        title: 'Communication',
        data: { title: 'Communication' },
        loadComponent: () =>
          import('./features/communication/communication-page').then((m) => m.CommunicationPage),
      },
      {
        path: 'activity-center',
        title: 'Activity Center',
        data: { title: 'Activity Center' },
        loadComponent: () =>
          import('./features/activity-center/activity-center-page').then((m) => m.ActivityCenterPage),
      },
      { path: 'settings', pathMatch: 'full', redirectTo: 'settings/general' },
      {
        path: 'settings/:section',
        title: 'Settings',
        data: { title: 'Settings' },
        loadComponent: () => import('./features/settings/settings-page').then((m) => m.SettingsPage),
      },
      {
        path: 'profile',
        title: 'Profile',
        data: { title: 'Profile' },
        loadComponent: () => import('./features/profile/profile-page').then((m) => m.ProfilePage),
      },
      {
        path: 'help',
        title: 'Help & support',
        data: { title: 'Help & support' },
        loadComponent: () => import('./features/help/help-page').then((m) => m.HelpPage),
      },
      {
        path: '**',
        title: 'Page not found',
        data: { title: 'Not found' },
        loadComponent: () => import('./features/not-found/not-found-page').then((m) => m.NotFoundPage),
      },
    ],
  },
];
