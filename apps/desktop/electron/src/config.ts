import { app } from 'electron';
import path from 'node:path';
import { parseDevServerUrl } from './policy';

export { ALLOWED_EXTERNAL_HOSTS, APP_HOST, APP_ORIGIN, APP_SCHEME } from './policy';

const DEV_SERVER_FLAG = '--dev-server-url=';

/**
 * Development server URL, honoured only for unpackaged builds and only when it points
 * to the local machine. Packaged applications always load the bundled renderer.
 */
export function resolveDevServerUrl(): URL | null {
  if (app.isPackaged) {
    return null;
  }
  const argument = process.argv.find((arg) => arg.startsWith(DEV_SERVER_FLAG));
  return argument ? parseDevServerUrl(argument.slice(DEV_SERVER_FLAG.length)) : null;
}

/** Directory containing the built Angular application. */
export function rendererRoot(): string {
  return app.isPackaged
    ? path.join(app.getAppPath(), 'renderer')
    : path.resolve(__dirname, '..', '..', 'ui', 'dist', 'ui', 'browser');
}

export function preloadPath(): string {
  return path.join(__dirname, 'preload.js');
}
