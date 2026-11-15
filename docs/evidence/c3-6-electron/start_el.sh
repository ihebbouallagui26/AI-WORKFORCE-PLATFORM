#!/usr/bin/env bash
# Starts the REAL Electron app through the repository's own launcher (apps/desktop/scripts/launch-electron.cjs, built renderer),
# with an isolated profile, remote debugging on 9333 and the login hook. Then starts the CDP daemon.
source "C:/Users/IHEBBO~1/AppData/Local/Temp/claude/2c7a9fa45d747dc7/3a0389bc-1f98-45b6-9a83-6ebfdf9bcf20/scratchpad/c36/common.sh"
EL="$SD/el"; WIN_EL="C:/Users/IHEBBO~1/AppData/Local/Temp/claude/2c7a9fa45d747dc7/3a0389bc-1f98-45b6-9a83-6ebfdf9bcf20/scratchpad/c36/el"
USER_EMAIL="${1:-$(grep '^OWNER=' "$SD/state.env" | cut -d= -f2)}"
PW="$(grep '^USER_PW=' "$SD/state.env" | cut -d= -f2)"
rm -f "$EL/hook.log" "$EL/main.log"
cd "$ROOT/apps/desktop"
C36_HOOK_LOG="$WIN_EL/hook.log" C36_USER="$USER_EMAIL" C36_PW="$PW" NODE_OPTIONS="--require $WIN_EL/hook.cjs" \
  nohup node scripts/launch-electron.cjs --remote-debugging-port=9333 --user-data-dir="$WIN_EL/profile" > "$EL/main.log" 2>&1 &
echo "launcher pid $!"
nohup node "$EL/cdpd.cjs" 9333 "$WIN_EL/shots" > "$EL/cdpd.log" 2>&1 &
for i in $(seq 1 40); do grep -q attached "$EL/cdpd.log" 2>/dev/null && break; sleep 1; done
cat "$EL/cdpd.log"; echo "--- hook log:"; cat "$EL/hook.log" 2>/dev/null | head -3
