#!/usr/bin/env bash
# Creates or completes infra/docker/.env from .env.example.
#   - Existing keys and values are NEVER modified.
#   - Missing keys are appended with the example value, or a random secret when the
#     example value is empty (all *_PASSWORD keys).
# Usage: bash infra/docker/scripts/ensure-env.sh
set -euo pipefail

dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
example="$dir/.env.example"
target="$dir/.env"

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
    if [ "$key" = "SECRETS_MASTER_KEY" ]; then
      value="$(openssl rand -base64 32)"   # 32 random bytes, base64 (AES-256 key)
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
