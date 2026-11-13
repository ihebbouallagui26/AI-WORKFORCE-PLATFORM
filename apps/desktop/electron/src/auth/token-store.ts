/**
 * Storage of the refresh token — the only long-lived credential. It is encrypted with the
 * operating system's protection (Electron `safeStorage`: DPAPI on Windows, Keychain on macOS,
 * libsecret/kwallet on Linux) and written to the user data directory. When no OS protection is
 * available, the token is kept in memory only (the session ends when the app closes).
 * Access tokens are never persisted. Nothing here is reachable from the renderer.
 */
import fs from 'node:fs';
import path from 'node:path';

/** Abstraction of `safeStorage`, injectable for tests. */
export interface TokenCipher {
  isAvailable(): boolean;
  encrypt(plain: string): Buffer;
  decrypt(encrypted: Buffer): string;
}

export type StorageMode = 'secure' | 'session-only';

export interface TokenStore {
  readonly mode: StorageMode;
  load(): string | null;
  save(refreshToken: string): void;
  clear(): void;
}

const FILE_NAME = 'session.bin';
const MAX_FILE_BYTES = 64 * 1024;

export function createTokenStore(directory: string, cipher: TokenCipher): TokenStore {
  return cipher.isAvailable() ? new EncryptedFileTokenStore(directory, cipher) : new MemoryTokenStore();
}

export class MemoryTokenStore implements TokenStore {
  readonly mode: StorageMode = 'session-only';
  private token: string | null = null;

  load(): string | null {
    return this.token;
  }

  save(refreshToken: string): void {
    this.token = refreshToken;
  }

  clear(): void {
    this.token = null;
  }
}

export class EncryptedFileTokenStore implements TokenStore {
  readonly mode: StorageMode = 'secure';
  private readonly file: string;

  constructor(private readonly directory: string, private readonly cipher: TokenCipher) {
    this.file = path.join(directory, FILE_NAME);
  }

  load(): string | null {
    try {
      const stat = fs.statSync(this.file);
      if (!stat.isFile() || stat.size === 0 || stat.size > MAX_FILE_BYTES) {
        this.clear();
        return null;
      }
      const token = this.cipher.decrypt(fs.readFileSync(this.file));
      return token.length > 0 ? token : null;
    } catch {
      // Missing, unreadable or encrypted by another OS user/profile: start signed out.
      this.clear();
      return null;
    }
  }

  save(refreshToken: string): void {
    fs.mkdirSync(this.directory, { recursive: true, mode: 0o700 });
    const temporary = `${this.file}.${process.pid}.tmp`;
    fs.writeFileSync(temporary, this.cipher.encrypt(refreshToken), { mode: 0o600 });
    fs.renameSync(temporary, this.file);
  }

  clear(): void {
    fs.rmSync(this.file, { force: true });
  }
}
