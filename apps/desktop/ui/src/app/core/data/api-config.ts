import { InjectionToken } from '@angular/core';
import { DataSourceMode } from '../../../environments/environment.model';

export interface ApiConfig {
  readonly dataSource: DataSourceMode;
  readonly baseUrl: string;
  readonly realtimeUrl: string;
}

/**
 * The only place where the Core API location is exposed to the application.
 * Future HTTP implementations must build URLs from this token, never from literals.
 */
export const API_CONFIG = new InjectionToken<ApiConfig>('API_CONFIG');
