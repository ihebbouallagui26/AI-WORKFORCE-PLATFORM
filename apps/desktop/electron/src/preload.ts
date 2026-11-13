/**
 * Preload script, running in a sandboxed, context-isolated renderer.
 * Exposes a minimal, typed desktop API. No Node.js, filesystem, process, raw IPC or token
 * access is ever handed to the page.
 *
 * Note: sandboxed preloads can only require('electron'); this file must not import
 * local runtime modules (type-only imports are erased at compile time).
 */
import { contextBridge, ipcRenderer, type IpcRendererEvent } from 'electron';
import type {
  ApiRequest,
  ApiResponse,
  AuthSessionState,
  DesktopAppInfo,
  DesktopBridge,
  IpcChannel,
  IpcEvent,
  LoginResult,
  OpenExternalResult,
} from './shared/desktop-api';

const channel = (name: IpcChannel): IpcChannel => name;
const SESSION_CHANGED: IpcEvent = 'auth:session-changed';

const bridge: DesktopBridge = {
  getAppInfo: (): Promise<DesktopAppInfo> => ipcRenderer.invoke(channel('app:get-info')),
  openExternal: (url: string): Promise<OpenExternalResult> =>
    ipcRenderer.invoke(channel('shell:open-external'), typeof url === 'string' ? url : ''),
  auth: Object.freeze({
    getSession: (): Promise<AuthSessionState> => ipcRenderer.invoke(channel('auth:get-session')),
    login: (): Promise<LoginResult> => ipcRenderer.invoke(channel('auth:login')),
    cancelLogin: (): Promise<void> => ipcRenderer.invoke(channel('auth:cancel-login')),
    logout: (): Promise<void> => ipcRenderer.invoke(channel('auth:logout')),
    onSessionChanged: (listener: (state: AuthSessionState) => void): (() => void) => {
      // The IPC event object is never passed to the page, only the state payload.
      const wrapped = (_event: IpcRendererEvent, state: AuthSessionState) => listener(state);
      ipcRenderer.on(SESSION_CHANGED, wrapped);
      return () => ipcRenderer.removeListener(SESSION_CHANGED, wrapped);
    },
  }),
  api: Object.freeze({
    request: (request: ApiRequest): Promise<ApiResponse> => ipcRenderer.invoke(channel('api:request'), request),
  }),
};

contextBridge.exposeInMainWorld('desktop', Object.freeze(bridge));
