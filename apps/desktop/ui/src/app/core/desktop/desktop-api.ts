/**
 * Renderer-side view of the API exposed by the Electron preload script.
 * Must stay in sync with `apps/desktop/electron/src/shared/desktop-api.ts`.
 */
export interface DesktopAppInfo {
  readonly name: string;
  readonly version: string;
  readonly platform: string;
  readonly arch: string;
  readonly electronVersion: string;
  readonly chromeVersion: string;
  readonly isPackaged: boolean;
}

export type OpenExternalResult =
  | { readonly ok: true }
  | { readonly ok: false; readonly reason: 'invalid-url' | 'not-allowed' | 'failed' };

export interface DesktopBridge {
  getAppInfo(): Promise<DesktopAppInfo>;
  openExternal(url: string): Promise<OpenExternalResult>;
}

declare global {
  interface Window {
    /** Present only when running inside the Electron shell. */
    readonly desktop?: DesktopBridge;
  }
}
