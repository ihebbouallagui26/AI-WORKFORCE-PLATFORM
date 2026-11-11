import { EnvironmentProviders, makeEnvironmentProviders } from '@angular/core';
import { AppEnvironment } from '../../../environments/environment.model';
import { AgentApi } from './api/agent.api';
import { MessagingApi } from './api/messaging.api';
import { OrganizationApi } from './api/organization.api';
import { ProjectApi } from './api/project.api';
import { SupervisionApi } from './api/supervision.api';
import { WorkspaceApi } from './api/workspace.api';
import { API_CONFIG } from './api-config';
import { MockAgentApi } from './mock/mock-agent.api';
import { MockMessagingApi } from './mock/mock-messaging.api';
import { MockOrganizationApi } from './mock/mock-organization.api';
import { MockProjectApi } from './mock/mock-project.api';
import { MockSupervisionApi } from './mock/mock-supervision.api';
import { MockWorkspaceApi } from './mock/mock-workspace.api';

/**
 * Binds the abstract data APIs to an implementation.
 * Phase A only ships the mock implementation. The HTTP implementations for the
 * Spring Boot Core API will be registered here, so no feature code needs to change.
 */
export function provideDataLayer(environment: AppEnvironment): EnvironmentProviders {
  if (environment.dataSource !== 'mock') {
    throw new Error(
      `Data source "${environment.dataSource}" is not available yet. ` +
        'Only the mock data source is implemented in Phase A.',
    );
  }

  return makeEnvironmentProviders([
    {
      provide: API_CONFIG,
      useValue: {
        dataSource: environment.dataSource,
        baseUrl: environment.apiBaseUrl,
        realtimeUrl: environment.realtimeUrl,
      },
    },
    { provide: WorkspaceApi, useClass: MockWorkspaceApi },
    { provide: OrganizationApi, useClass: MockOrganizationApi },
    { provide: AgentApi, useClass: MockAgentApi },
    { provide: ProjectApi, useClass: MockProjectApi },
    { provide: MessagingApi, useClass: MockMessagingApi },
    { provide: SupervisionApi, useClass: MockSupervisionApi },
  ]);
}
