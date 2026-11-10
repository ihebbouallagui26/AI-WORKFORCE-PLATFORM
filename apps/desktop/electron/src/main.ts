import { app, BrowserWindow, Menu } from 'electron';
import { APP_ORIGIN, preloadPath, rendererRoot, resolveDevServerUrl } from './config';
import { registerIpcHandlers } from './ipc';
import { registerAppScheme, serveRenderer } from './renderer-protocol';
import { hardenSession, refuseDebuggingSwitchesWhenPackaged, restrictNavigation } from './security';

const devServer = resolveDevServerUrl();
const isDevelopment = !app.isPackaged;

refuseDebuggingSwitchesWhenPackaged();
// Every renderer runs sandboxed, regardless of per-window settings.
app.enableSandbox();
registerAppScheme();
restrictNavigation(devServer);

/** False when another instance already runs: this one must not create any window. */
const isPrimaryInstance = app.requestSingleInstanceLock();
if (!isPrimaryInstance) {
  app.quit();
}

let mainWindow: BrowserWindow | null = null;

function createMainWindow(): BrowserWindow {
  const window = new BrowserWindow({
    width: 1440,
    height: 900,
    minWidth: 1024,
    minHeight: 640,
    show: false,
    title: 'AI Workforce Platform',
    backgroundColor: '#0f1115',
    autoHideMenuBar: true,
    webPreferences: {
      preload: preloadPath(),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      webSecurity: true,
      allowRunningInsecureContent: false,
      webviewTag: false,
      navigateOnDragDrop: false,
      spellcheck: false,
      devTools: isDevelopment,
    },
  });

  window.once('ready-to-show', () => window.show());

  if (isDevelopment) {
    // F12 / Ctrl+Shift+I toggle DevTools in development builds only.
    window.webContents.on('before-input-event', (event, input) => {
      const toggle = input.key === 'F12' || (input.control && input.shift && input.key.toUpperCase() === 'I');
      if (input.type === 'keyDown' && toggle) {
        window.webContents.toggleDevTools();
        event.preventDefault();
      }
    });
  }

  void window.loadURL(devServer ? devServer.toString() : `${APP_ORIGIN}/`);
  return window;
}

function buildMenu(): void {
  if (process.platform !== 'darwin') {
    Menu.setApplicationMenu(null);
    return;
  }
  // macOS needs an application menu for standard shortcuts (copy, paste, quit).
  Menu.setApplicationMenu(
    Menu.buildFromTemplate([{ role: 'appMenu' }, { role: 'editMenu' }, { role: 'windowMenu' }]),
  );
}

app.on('second-instance', () => {
  if (mainWindow) {
    if (mainWindow.isMinimized()) mainWindow.restore();
    mainWindow.focus();
  }
});

app.whenReady().then(() => {
  if (!isPrimaryInstance) {
    return;
  }
  if (process.platform === 'win32') {
    app.setAppUserModelId('com.aiworkforce.desktop');
  }
  hardenSession(devServer);
  serveRenderer(rendererRoot());
  registerIpcHandlers(devServer);
  buildMenu();
  mainWindow = createMainWindow();
  mainWindow.on('closed', () => (mainWindow = null));

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      mainWindow = createMainWindow();
    }
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit();
  }
});
