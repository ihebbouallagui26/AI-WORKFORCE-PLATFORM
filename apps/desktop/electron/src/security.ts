import { app, session, shell, WebContents } from 'electron';
import { developmentCsp, FORBIDDEN_PACKAGED_SWITCHES, isAllowedExternalUrl, isAppUrl } from './policy';

/**
 * Packaged builds refuse to start with debugging switches (e.g. --remote-debugging-port),
 * which would otherwise expose a DevTools endpoint able to drive the renderer.
 * Must run before the `ready` event.
 */
export function refuseDebuggingSwitchesWhenPackaged(): void {
  if (!app.isPackaged) {
    return;
  }
  const forbidden = FORBIDDEN_PACKAGED_SWITCHES.filter((name) => app.commandLine.hasSwitch(name));
  if (forbidden.length > 0) {
    console.error(`Refusing to start: forbidden switch(es) ${forbidden.join(', ')}.`);
    app.exit(1);
  }
}

/** Session-wide hardening: deny every permission and apply the CSP to dev-server responses. */
export function hardenSession(devServer: URL | null): void {
  const defaultSession = session.defaultSession;
  defaultSession.setPermissionRequestHandler((_contents, _permission, callback) => callback(false));
  defaultSession.setPermissionCheckHandler(() => false);
  defaultSession.setDevicePermissionHandler(() => false);

  if (devServer) {
    const csp = developmentCsp(devServer);
    defaultSession.webRequest.onHeadersReceived({ urls: [`${devServer.origin}/*`] }, (details, callback) => {
      callback({
        responseHeaders: { ...details.responseHeaders, 'Content-Security-Policy': [csp] },
      });
    });
  }
}

/**
 * Applies navigation restrictions to every web contents created by the app:
 * no navigation away from the app (top frame or sub-frames), no new windows, no <webview>.
 */
export function restrictNavigation(devServer: URL | null): void {
  app.on('web-contents-created', (_event, contents: WebContents) => {
    const blockForeignNavigation = (event: Electron.Event, url: string) => {
      if (!isAppUrl(url, devServer)) {
        event.preventDefault();
      }
    };
    contents.on('will-navigate', blockForeignNavigation);
    contents.on('will-redirect', blockForeignNavigation);
    contents.on('will-frame-navigate', (details) => {
      if (!isAppUrl(details.url, devServer)) {
        details.preventDefault();
      }
    });
    contents.on('will-attach-webview', (event) => event.preventDefault());
    contents.setWindowOpenHandler(({ url }) => {
      if (isAllowedExternalUrl(url)) {
        void shell.openExternal(url);
      }
      return { action: 'deny' };
    });
  });
}
