import { AppEnvironment } from './environment.model';

/** Development build served to Electron (`npm run dev`): real authentication and Core API. */
export const environment: AppEnvironment = {
  production: false,
  dataSource: 'api',
  apiBaseUrl: 'http://localhost:8081/api/v1',
  realtimeUrl: 'ws://localhost:8081/ws',
};
