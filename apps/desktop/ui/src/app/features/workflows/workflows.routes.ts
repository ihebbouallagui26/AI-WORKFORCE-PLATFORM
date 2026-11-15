import { Routes } from '@angular/router';

/** Workflow management (Sprint C3.5): overview, definitions, runs. Run pages follow a run live while it is open. */
export const WORKFLOWS_ROUTES: Routes = [
  { path: '', pathMatch: 'full', loadComponent: () => import('./workflows-page').then((m) => m.WorkflowsPage) },
  { path: 'new', title: 'New workflow', data: { title: 'New workflow' }, loadComponent: () => import('./workflow-editor').then((m) => m.WorkflowEditor) },
  { path: 'runs/:runId', title: 'Workflow run', data: { title: 'Workflow run' }, loadComponent: () => import('./run-page').then((m) => m.WorkflowRunPage) },
  { path: ':id/edit', title: 'Edit workflow', data: { title: 'Edit workflow' }, loadComponent: () => import('./workflow-editor').then((m) => m.WorkflowEditor) },
  { path: ':id', title: 'Workflow', data: { title: 'Workflow' }, loadComponent: () => import('./workflow-detail').then((m) => m.WorkflowDetail) },
  { path: '**', redirectTo: '' },
];
