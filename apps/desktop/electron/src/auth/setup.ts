/**
 * Wires the authentication services with Electron: OS-protected storage (safeStorage), the
 * system browser (restricted to the identity provider's origin) and the configuration.
 */
import { app, safeStorage, shell } from 'electron';
import path from 'node:path';
import { ApiProxy } from './api-proxy';
import { parseAuthConfig } from './auth-policy';
import { AuthSession } from './auth-session';
import { OpenIdProvider } from './openid-provider';
import { createTokenStore, type TokenCipher } from './token-store';
import type { AuthServices } from '../ipc';

const electronCipher: TokenCipher = {
  isAvailable: () => {
    if (!safeStorage.isEncryptionAvailable()) {
      return false;
    }
    // On Linux without a keyring, Electron falls back to a hard-coded key: not a protection.
    return process.platform !== 'linux' || safeStorage.getSelectedStorageBackend() !== 'basic_text';
  },
  encrypt: (plain) => safeStorage.encryptString(plain),
  decrypt: (encrypted) => safeStorage.decryptString(encrypted),
};

export function createAuthServices(): AuthServices | null {
  const parsed = parseAuthConfig(process.env);
  if (!parsed.ok) {
    console.error(`Authentication disabled: ${parsed.error}`);
    return null;
  }
  const { config } = parsed;
  const store = createTokenStore(path.join(app.getPath('userData'), 'auth'), electronCipher);
  const session = new AuthSession({
    provider: new OpenIdProvider(config),
    store,
    openBrowser: async (url) => {
      // Only the identity provider may be opened by the authentication flow.
      if (url.origin !== config.issuer.origin) {
        throw new Error('Refusing to open a non identity-provider URL');
      }
      await shell.openExternal(url.toString());
    },
  });
  return { session, api: new ApiProxy(config.apiBaseUrl, session) };
}
