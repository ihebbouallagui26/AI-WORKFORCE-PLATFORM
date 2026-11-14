import { Routes } from '@angular/router';

/** Real, Core API backed AI agent management (api mode). */
export const AI_AGENTS_ROUTES: Routes = [
  { path: '', pathMatch: 'full', loadComponent: () => import('./ai-agents-page').then((m) => m.AiAgentsPage) },
  { path: '**', redirectTo: '' },
];
