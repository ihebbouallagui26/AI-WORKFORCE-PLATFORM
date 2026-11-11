/**
 * Starts Electron with a clean environment.
 * Some hosts (e.g. terminals spawned by VS Code extensions) export ELECTRON_RUN_AS_NODE=1,
 * which makes Electron behave as plain Node.js and breaks the app. It is removed here.
 */
const { spawn } = require('node:child_process');
const electronBinary = require('electron');

const env = { ...process.env };
delete env.ELECTRON_RUN_AS_NODE;

const child = spawn(electronBinary, ['.', ...process.argv.slice(2)], {
  cwd: require('node:path').resolve(__dirname, '..'),
  env,
  stdio: 'inherit',
  windowsHide: false,
});

child.on('exit', (code, signal) => {
  process.exit(signal ? 1 : (code ?? 0));
});
