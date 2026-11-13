/**
 * - `api`: identity, organizations, members, roles and invitations come from the Core API,
 *   through the Electron main process (which holds the tokens). Domains not implemented by the
 *   backend yet (agents, projects, communication, supervision, departments) stay on clearly
 *   labelled demo data.
 * - `mock`: everything is in-memory demo data (browser preview, UI development, unit tests).
 */
export type DataSourceMode = 'mock' | 'api';

export interface AppEnvironment {
  readonly production: boolean;
  readonly dataSource: DataSourceMode;
  /**
   * Informational only: in `api` mode the renderer never calls the API directly. The Electron
   * main process owns the API URL (AIW_API_BASE_URL) and attaches the access token.
   */
  readonly apiBaseUrl: string;
  /** Future WebSocket endpoint for real-time messaging and agent events. */
  readonly realtimeUrl: string;
}
