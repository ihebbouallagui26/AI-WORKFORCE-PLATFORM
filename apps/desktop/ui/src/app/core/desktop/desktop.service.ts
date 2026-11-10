import { DOCUMENT } from '@angular/common';
import { inject, Injectable } from '@angular/core';
import { DesktopAppInfo, DesktopBridge, OpenExternalResult } from './desktop-api';

/** Hosts that may be opened from the UI. The Electron main process enforces its own copy. */
export const ALLOWED_EXTERNAL_HOSTS: readonly string[] = ['github.com', 'angular.dev', 'www.electronjs.org'];

/**
 * Single entry point to desktop capabilities. Falls back to browser behaviour so the UI
 * also runs in a plain browser during development.
 */
@Injectable({ providedIn: 'root' })
export class DesktopService {
  private readonly window = inject(DOCUMENT).defaultView;
  private readonly bridge: DesktopBridge | undefined = this.window?.desktop;

  readonly isDesktop = this.bridge !== undefined;

  async getAppInfo(): Promise<DesktopAppInfo> {
    if (this.bridge) {
      return this.bridge.getAppInfo();
    }
    return {
      name: 'AI Workforce Platform',
      version: 'web-preview',
      platform: 'browser',
      arch: '—',
      electronVersion: '—',
      chromeVersion: '—',
      isPackaged: false,
    };
  }

  async openExternal(url: string): Promise<OpenExternalResult> {
    if (!isAllowedExternalUrl(url)) {
      return { ok: false, reason: 'not-allowed' };
    }
    if (this.bridge) {
      return this.bridge.openExternal(url);
    }
    this.window?.open(url, '_blank', 'noopener,noreferrer');
    return { ok: true };
  }
}

export function isAllowedExternalUrl(value: string): boolean {
  try {
    const url = new URL(value);
    return url.protocol === 'https:' && ALLOWED_EXTERNAL_HOSTS.includes(url.hostname);
  } catch {
    return false;
  }
}
