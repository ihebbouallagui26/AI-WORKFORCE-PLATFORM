import { Injectable } from '@angular/core';

/**
 * Non-sensitive UI preferences, persisted per device.
 * Never store credentials, tokens or provider secrets here.
 */
export interface UiPreferences {
  lastOrganizationId: string | null;
  sidebarCollapsed: boolean;
  agentsView: 'grid' | 'table';
}

const DEFAULTS: UiPreferences = {
  lastOrganizationId: null,
  sidebarCollapsed: false,
  agentsView: 'grid',
};

const STORAGE_KEY = 'awp.preferences.v1';

@Injectable({ providedIn: 'root' })
export class PreferencesService {
  private values: UiPreferences = this.read();

  get<K extends keyof UiPreferences>(key: K): UiPreferences[K] {
    return this.values[key];
  }

  set<K extends keyof UiPreferences>(key: K, value: UiPreferences[K]): void {
    this.values = { ...this.values, [key]: value };
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(this.values));
    } catch {
      // Storage can be unavailable (private mode, quota); preferences then last for the session.
    }
  }

  private read(): UiPreferences {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (!raw) {
        return { ...DEFAULTS };
      }
      const parsed: unknown = JSON.parse(raw);
      return typeof parsed === 'object' && parsed !== null
        ? { ...DEFAULTS, ...(parsed as Partial<UiPreferences>) }
        : { ...DEFAULTS };
    } catch {
      return { ...DEFAULTS };
    }
  }
}
