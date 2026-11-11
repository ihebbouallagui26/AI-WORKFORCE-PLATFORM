import { AppEnvironment } from './environment.model';

/**
 * Production environment.
 * The data source stays on `mock` until the Spring Boot Core API is integrated.
 * Never place credentials or provider secrets in this file: it is shipped to the client.
 */
export const environment: AppEnvironment = {
  production: true,
  dataSource: 'mock',
  apiBaseUrl: 'http://localhost:8080/api/v1',
  realtimeUrl: 'ws://localhost:8080/ws',
};
