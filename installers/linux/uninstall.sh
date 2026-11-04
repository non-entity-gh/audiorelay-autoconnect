#!/bin/bash
# Removes AudioRelay Auto-Connect on Linux.   Usage: bash uninstall.sh [--app-dir /path/to/audiorelay]
set -uo pipefail
FLATPAK_ID="net.audiorelay.AudioRelay"
SYSTEM_DIRS="/opt/audiorelay /opt/AudioRelay /usr/lib/audiorelay /usr/local/lib/audiorelay"
cfg_under() {
  local c
  for c in "$1/lib/app/AudioRelay.cfg" "$1/app/AudioRelay.cfg" "$1/AudioRelay.cfg"; do
    if [ -f "$c" ]; then echo "$c"; return 0; fi
  done
  return 1
}
APP_DIR=""
if [ "${1:-}" = "--app-dir" ]; then
  [ -n "${2:-}" ] || { echo "Error: --app-dir needs a folder" >&2; exit 1; }
  APP_DIR="$2"
fi
found=""

if command -v flatpak >/dev/null 2>&1 && flatpak override --user --show "$FLATPAK_ID" 2>/dev/null | grep -q audiorelay-autoconnect; then
  DATA_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/audiorelay-autoconnect"
  if flatpak override --user --unset-env=JAVA_TOOL_OPTIONS "--nofilesystem=$DATA_DIR" "$FLATPAK_ID"; then
    rm -rf "$DATA_DIR"
    echo "Removed from the Flatpak version."
    found=1
  else
    echo "Error: couldn't update the flatpak override; leaving the agent in place so AudioRelay still starts." >&2
    exit 1
  fi
fi

for d in ${APP_DIR:+"$APP_DIR"} $SYSTEM_DIRS; do
  CFG="$(cfg_under "$d")" || continue
  grep -q audiorelay-autoconnect "$CFG" || [ -f "$(dirname "$CFG")/audiorelay-autoconnect.jar" ] || continue
  D="$(dirname "$CFG")"; SUDO=""
  if [ ! -w "$CFG" ] || [ ! -w "$D" ]; then SUDO="sudo"; fi
  TMP="$(mktemp)"; grep -v 'audiorelay-autoconnect\.jar' "$CFG" > "$TMP"
  # shellcheck disable=SC2016  # $1/$2 are meant for the inner sh
  if ! $SUDO sh -c 'cat "$1" > "$2"' sh "$TMP" "$CFG"; then rm -f "$TMP"; echo "Error: couldn't edit $CFG" >&2; exit 1; fi
  rm -f "$TMP"
  $SUDO rm -f "$D/audiorelay-autoconnect.jar" "$CFG.bak-autoconnect"
  echo "Removed from $D."
  found=1
done
rm -f "${XDG_STATE_HOME:-$HOME/.local/state}/audiorelay-autoconnect.log"
AUTOSTART="${XDG_CONFIG_HOME:-$HOME/.config}/autostart/audiorelay-autoconnect.desktop"
if [ -f "$AUTOSTART" ] && grep -q 'X-AudioRelay-AutoConnect=true' "$AUTOSTART"; then
  rm -f "$AUTOSTART"
  echo "Removed the login autostart entry this add-on created."
fi
[ -n "$found" ] || echo "Auto-Connect isn't installed (or was installed with --app-dir: pass the same --app-dir here)."
echo "Restart AudioRelay for the change to take effect."
