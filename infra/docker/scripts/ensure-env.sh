#!/usr/bin/env bash
# Creates or completes infra/docker/.env from .env.example.
#   - Existing keys and values are NEVER modified.
#   - Missing keys are appended with the example value, or a random secret when the
#     example value is empty (all *_PASSWORD keys; the AES-256 keys get 32 random bytes in base64).
#   - An existing ORCHESTRATOR_STATE_ENCRYPTION_KEY is validated (never printed, never replaced): a malformed or
#     empty one stops the script so an operator decides, because replacing it would make sealed state unreadable.
# Usage: bash infra/docker/scripts/ensure-env.sh
# Tests may point AIW_ENV_EXAMPLE / AIW_ENV_FILE at temporary files; the real .env is then never touched.
set -euo pipefail

dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
example="${AIW_ENV_EXAMPLE:-$dir/.env.example}"
target="${AIW_ENV_FILE:-$dir/.env}"

# base64 of exactly 32 bytes is 43 characters from the alphabet followed by a single '='.
valid_aes_key() { [[ "$1" =~ ^[A-Za-z0-9+/]{43}=$ ]]; }

if [ -f "$target" ] && grep -qE '^ORCHESTRATOR_STATE_ENCRYPTION_KEY=' "$target"; then
  existing="$(grep -E '^ORCHESTRATOR_STATE_ENCRYPTION_KEY=' "$target" | head -n 1 | cut -d= -f2-)"
  existing="${existing%$'\r'}"
  if ! valid_aes_key "$existing"; then
    echo "ORCHESTRATOR_STATE_ENCRYPTION_KEY in $target is empty or malformed (expected base64 of 32 bytes)." >&2
    echo "Nothing was changed. If no orchestrator state was ever sealed with it, remove that line and run this script again;" >&2
    echo "otherwise restore the original key from your backup: do NOT replace it." >&2
    exit 1
  fi
fi

touch "$target"
chmod 600 "$target" 2>/dev/null || true

added=()
while IFS= read -r line || [ -n "$line" ]; do
  line="${line%$'\r'}"   # tolerate CRLF checkouts (Windows): a trailing CR must never become part of a value
  [[ "$line" =~ ^[A-Z0-9_]+= ]] || continue
  key="${line%%=*}"
  value="${line#*=}"
  if grep -qE "^${key}=" "$target"; then
    continue
  fi
  if [ -z "$value" ]; then
    if [ "$key" = "SECRETS_MASTER_KEY" ] || [ "$key" = "ORCHESTRATOR_STATE_ENCRYPTION_KEY" ]; then
      value="$(openssl rand -base64 32)"   # 32 random bytes, base64 (AES-256 key), generated once and never printed
    else
      value="$(openssl rand -hex 24)"
    fi
  fi
  printf '%s=%s\n' "$key" "$value" >> "$target"
  added+=("$key")
done < "$example"

if [ ${#added[@]} -eq 0 ]; then
  echo "infra/docker/.env is complete (nothing added)."
else
  echo "Added to infra/docker/.env: ${added[*]}"
fi
