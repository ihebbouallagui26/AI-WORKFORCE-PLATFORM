/**
 * Preload script, running in a sandboxed, context-isolated renderer.
 * Exposes a minimal, typed desktop API. No Node.js, filesystem, process or raw IPC
 * access is ever handed to the page.
 *
 * Note: sandboxed preloads can only require('electron'); this file must not import
 * local runtime modules (type-only imports are erased at compile time).
 */
import { contextBridge, ipcRenderer } from 'electron';
import type { DesktopAppInfo, DesktopBridge, IpcChannel, OpenExternalResult } from './shared/desktop-api';

const GET_APP_INFO: IpcChannel = 'app:get-info';
const OPEN_EXTERNAL: IpcChannel = 'shell:open-external';

const bridge: DesktopBridge = {
  getAppInfo: (): Promise<DesktopAppInfo> => ipcRenderer.invoke(GET_APP_INFO),
  openExternal: (url: string): Promise<OpenExternalResult> =>
    ipcRenderer.invoke(OPEN_EXTERNAL, typeof url === 'string' ? url : ''),
};

contextBridge.exposeInMainWorld('desktop', Object.freeze(bridge));
