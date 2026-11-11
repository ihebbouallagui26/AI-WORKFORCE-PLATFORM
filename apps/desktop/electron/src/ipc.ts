import { app, ipcMain, IpcMainInvokeEvent, shell } from 'electron';
import { isAllowedExternalUrl, isAppUrl } from './policy';
import type { DesktopAppInfo, IpcChannel, OpenExternalResult } from './shared/desktop-api';

/**
 * Rejects IPC calls that do not originate from the main frame of an application page.
 * Sub-frames and foreign origins are never trusted.
 */
function assertTrustedSender(event: IpcMainInvokeEvent, devServer: URL | null): void {
  const frame = event.senderFrame;
  if (!frame || frame !== event.sender.mainFrame || !isAppUrl(frame.url, devServer)) {
    throw new Error('IPC request rejected: untrusted sender.');
  }
}

function handle<Args extends unknown[], Result>(
  channel: IpcChannel,
  devServer: URL | null,
  expectedArgs: number,
  handler: (...args: Args) => Result | Promise<Result>,
): void {
  ipcMain.handle(channel, (event, ...args: unknown[]) => {
    assertTrustedSender(event, devServer);
    if (args.length > expectedArgs) {
      throw new Error(`IPC request rejected: unexpected arguments for ${channel}.`);
    }
    return handler(...(args as Args));
  });
}

/** Registers the complete, explicit set of desktop capabilities. */
export function registerIpcHandlers(devServer: URL | null): void {
  handle<[], DesktopAppInfo>('app:get-info', devServer, 0, () => ({
    name: app.getName(),
    version: app.getVersion(),
    platform: process.platform,
    arch: process.arch,
    electronVersion: process.versions.electron,
    chromeVersion: process.versions.chrome,
    isPackaged: app.isPackaged,
  }));

  handle<[unknown], OpenExternalResult>('shell:open-external', devServer, 1, async (url) => {
    if (typeof url !== 'string' || !url) {
      return { ok: false, reason: 'invalid-url' };
    }
    if (!isAllowedExternalUrl(url)) {
      return { ok: false, reason: 'not-allowed' };
    }
    try {
      await shell.openExternal(url);
      return { ok: true };
    } catch {
      return { ok: false, reason: 'failed' };
    }
  });
}
