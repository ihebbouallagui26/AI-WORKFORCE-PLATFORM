/**
 * - `mock`: in-memory data shipped with the client, mutations stay local to the session.
 * - `api`: the future Spring Boot Core API (not implemented in Phase A).
 */
export type DataSourceMode = 'mock' | 'api';

export interface AppEnvironment {
  readonly production: boolean;
  readonly dataSource: DataSourceMode;
  /** Base URL of the Core API. Only read through the API_CONFIG token. */
  readonly apiBaseUrl: string;
  /** Future WebSocket endpoint for real-time messaging and agent events. */
  readonly realtimeUrl: string;
}
