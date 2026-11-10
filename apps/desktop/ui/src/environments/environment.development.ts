import { AppEnvironment } from './environment.model';

export const environment: AppEnvironment = {
  production: false,
  dataSource: 'mock',
  apiBaseUrl: 'http://localhost:8080/api/v1',
  realtimeUrl: 'ws://localhost:8080/ws',
};
