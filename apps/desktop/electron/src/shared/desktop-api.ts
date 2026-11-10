/**
 * Contract between the Electron main process, the preload script and the renderer.
 * Mirrored on the renderer side in `ui/src/app/core/desktop/desktop-api.ts`.
 * Only type declarations live here: the sandboxed preload cannot load local modules.
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

/** Every IPC channel the renderer may invoke. Nothing else is reachable. */
export type IpcChannel = 'app:get-info' | 'shell:open-external';
