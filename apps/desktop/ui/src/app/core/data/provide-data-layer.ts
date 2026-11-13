import { EnvironmentProviders, makeEnvironmentProviders } from '@angular/core';
import { AppEnvironment } from '../../../environments/environment.model';
import { AgentApi } from './api/agent.api';
import { MessagingApi } from './api/messaging.api';
import { OrganizationApi } from './api/organization.api';
import { ProjectApi } from './api/project.api';
import { SupervisionApi } from './api/supervision.api';
import { WorkspaceApi } from './api/workspace.api';
import { AUTH_REQUIRED } from '../auth/session.service';
import { API_CONFIG } from './api-config';
import { BackendWorkspaceApi } from './backend/backend-workspace.api';
import { demoScoped } from './backend/demo-scope';
import { MockAgentApi } from './mock/mock-agent.api';
import { MockMessagingApi } from './mock/mock-messaging.api';
import { MockOrganizationApi } from './mock/mock-organization.api';
import { MockProjectApi } from './mock/mock-project.api';
import { MockSupervisionApi } from './mock/mock-supervision.api';
import { MockWorkspaceApi } from './mock/mock-workspace.api';

/**
 * Binds the abstract data APIs to an implementation.
 *
 * - `mock`: every domain uses the in-memory demo dataset; no sign-in.
 * - `api`: sign-in is required; the workspace (user, organizations) comes from the Core API, and
 *   the domains not implemented by the backend yet use the demo dataset through a demo scope.
 */
export function provideDataLayer(environment: AppEnvironment): EnvironmentProviders {
  const apiMode = environment.dataSource === 'api';
  return makeEnvironmentProviders([
    {
      provide: API_CONFIG,
      useValue: {
        dataSource: environment.dataSource,
        baseUrl: environment.apiBaseUrl,
        realtimeUrl: environment.realtimeUrl,
      },
    },
    { provide: AUTH_REQUIRED, useValue: apiMode },
    apiMode
      ? { provide: WorkspaceApi, useClass: BackendWorkspaceApi }
      : { provide: WorkspaceApi, useClass: MockWorkspaceApi },
    ...(apiMode
      ? [
          { provide: OrganizationApi, useFactory: () => demoScoped(new MockOrganizationApi(),
              ['listDepartments', 'createDepartment', 'listRoles', 'listTeams', 'listEmployees']) },
          { provide: AgentApi, useFactory: () => demoScoped(new MockAgentApi(), ['listAgents', 'createAgent']) },
          { provide: ProjectApi, useFactory: () => demoScoped(new MockProjectApi(), ['listProjects', 'listTasks']) },
          { provide: MessagingApi, useFactory: () => demoScoped(new MockMessagingApi(),
              ['listConversations', 'listMessages', 'listSharedFiles']) },
          { provide: SupervisionApi, useFactory: () => demoScoped(new MockSupervisionApi(),
              ['listApprovals', 'listExecutions', 'listActivity', 'listReports', 'listMeetings']) },
        ]
      : [
          { provide: OrganizationApi, useClass: MockOrganizationApi },
          { provide: AgentApi, useClass: MockAgentApi },
          { provide: ProjectApi, useClass: MockProjectApi },
          { provide: MessagingApi, useClass: MockMessagingApi },
          { provide: SupervisionApi, useClass: MockSupervisionApi },
        ]),
  ]);
}
