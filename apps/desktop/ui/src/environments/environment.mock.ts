import { AppEnvironment } from './environment.model';

/**
 * Demo build: in-memory data only, no sign-in (`ng serve --configuration mock`, browser preview).
 * Also used by the unit tests.
 */
export const environment: AppEnvironment = {
  production: false,
  dataSource: 'mock',
  apiBaseUrl: 'http://localhost:8081/api/v1',
  realtimeUrl: 'ws://localhost:8081/ws',
};
