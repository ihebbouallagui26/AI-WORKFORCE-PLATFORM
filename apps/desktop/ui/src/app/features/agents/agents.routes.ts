import { Routes } from '@angular/router';

export const AGENTS_ROUTES: Routes = [
  { path: '', loadComponent: () => import('./agents-page').then((m) => m.AgentsPage) },
  { path: ':agentId', loadComponent: () => import('./agent-detail-page').then((m) => m.AgentDetailPage) },
];
