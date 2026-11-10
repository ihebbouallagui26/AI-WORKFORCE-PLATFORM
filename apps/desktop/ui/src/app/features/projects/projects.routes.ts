import { Routes } from '@angular/router';

export const PROJECTS_ROUTES: Routes = [
  { path: '', loadComponent: () => import('./projects-page').then((m) => m.ProjectsPage) },
  { path: ':projectId', loadComponent: () => import('./project-detail-page').then((m) => m.ProjectDetailPage) },
];
