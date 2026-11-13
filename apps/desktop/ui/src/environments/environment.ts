import { AppEnvironment } from './environment.model';

/**
 * Production environment (packaged desktop application).
 * Never place credentials or provider secrets in this file: it is shipped to the client.
 */
export const environment: AppEnvironment = {
  production: true,
  dataSource: 'api',
  apiBaseUrl: 'http://localhost:8081/api/v1',
  realtimeUrl: 'ws://localhost:8081/ws',
};
