#!/bin/bash
# AudioRelay Auto-Connect installer for Linux.
# Usage:  bash install.sh [SERVER_ADDRESS] [--no-persistent] [--app-dir /path/to/audiorelay]
#
# Supports:
#  - Flatpak (net.audiorelay.AudioRelay): adds a per-user flatpak override (one env var + read-only access
#    to the agent's folder). The app itself is untouched.
#  - .deb install (/opt/audiorelay) or the .tar.gz extracted anywhere (pass --app-dir): copies the agent
#    next to audiorelay.jar and adds one line to lib/app/AudioRelay.cfg (backup saved). Uses sudo only
#    if that folder isn't writable by you.
# Nothing is downloaded. Undo with: bash uninstall.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
JAR_SRC="$HERE/audiorelay-autoconnect.jar"
[ -f "$JAR_SRC" ] || JAR_SRC="$HERE/../../dist/audiorelay-autoconnect.jar"
FLATPAK_ID="net.audiorelay.AudioRelay"
HOST_RE='^[A-Za-z0-9[][][A-Za-z0-9._:%-]{0,252}$'
SYSTEM_DIRS="/opt/audiorelay /opt/AudioRelay /usr/lib/audiorelay /usr/local/lib/audiorelay"

die() { echo "Error: $*" >&2; exit 1; }
# Same lookup in install.sh and uninstall.sh: prints the AudioRelay.cfg under a given folder
cfg_under() {
  local c
  for c in "$1/lib/app/AudioRelay.cfg" "$1/app/AudioRelay.cfg" "$1/AudioRelay.cfg"; do
    if [ -f "$c" ]; then echo "$c"; return 0; fi
  done
  return 1
}
[ -f "$JAR_SRC" ] || die "audiorelay-autoconnect.jar not found next to install.sh"

SERVER="" PERSIST="" APP_DIR=""
while [ $# -gt 0 ]; do
  case "$1" in
    --no-persistent) PERSIST=",persistent=false" ;;
    --app-dir) [ $# -ge 2 ] || die "--app-dir needs a folder"; APP_DIR="$2"; shift ;;
    -h|--help) sed -n '2,11p' "$0"; exit 0 ;;
    -*) die "unknown option '$1' (see --help)" ;;
    *) [ -z "$SERVER" ] || die "more than one server address given"; SERVER="$1" ;;
  esac
  shift
done
if [ -z "$SERVER" ]; then read -r -p "IP address (or hostname) of the computer running the AudioRelay SERVER: " SERVER; fi
SERVER="$(printf '%s' "$SERVER" | tr -d '[:space:]')"
[[ "$SERVER" =~ $HOST_RE ]] || die "'$SERVER' doesn't look like an IP address or hostname"
OPTS="host=$SERVER$PERSIST"

# ---------- locate the install ----------
CFG="" MODE=""
if [ -n "$APP_DIR" ]; then
  CFG="$(cfg_under "$APP_DIR")" || die "no AudioRelay.cfg found under $APP_DIR"
elif command -v flatpak >/dev/null 2>&1 && flatpak info "$FLATPAK_ID" >/dev/null 2>&1; then
  MODE=flatpak
else
  for d in $SYSTEM_DIRS; do CFG="$(cfg_under "$d")" && break; CFG=""; done
  if [ -z "$CFG" ] && [ -f /usr/share/java/audiorelay/misc/AudioRelay.cfg ]; then
    die "the AUR package uses its own launcher, which this installer doesn't support yet. See README (manual setup)."
  fi
  [ -n "$CFG" ] || die "AudioRelay not found. If you extracted the .tar.gz somewhere, run: bash install.sh $SERVER --app-dir /path/to/that/folder"
fi

if pgrep -f 'com\.azefsw\.audioconnect|/AudioRelay( |$)' >/dev/null 2>&1; then
  echo "Note: AudioRelay is running. Quit and reopen it after installing for auto-connect to take effect."
fi

if [ "$MODE" = flatpak ]; then
  DATA_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/audiorelay-autoconnect"
  case "$DATA_DIR" in *[[:space:]]*) die "path '$DATA_DIR' contains a space, which JAVA_TOOL_OPTIONS can't handle";; esac
  existing="$( { flatpak info --show-metadata "$FLATPAK_ID"; flatpak override --user --show "$FLATPAK_ID"; } 2>/dev/null | grep '^JAVA_TOOL_OPTIONS=' || true)"
  if [ -n "$existing" ] && ! printf '%s' "$existing" | grep -q 'audiorelay-autoconnect'; then
    die "JAVA_TOOL_OPTIONS is already set for AudioRelay's flatpak ($existing); not overwriting it"
  fi
  mkdir -p "$DATA_DIR"
  install -m 644 "$JAR_SRC" "$DATA_DIR/audiorelay-autoconnect.jar"
  flatpak override --user "--filesystem=$DATA_DIR:ro" \
    "--env=JAVA_TOOL_OPTIONS=-javaagent:$DATA_DIR/audiorelay-autoconnect.jar=$OPTS" "$FLATPAK_ID"
  echo
  echo "Installed for the Flatpak version. Restart AudioRelay; it will auto-connect to $SERVER."
  echo "Log: ~/.var/app/$FLATPAK_ID/.local/state/audiorelay-autoconnect.log"
  exit 0
fi

# ---------- native install: edit AudioRelay.cfg ----------
APPDIR_REAL="$(dirname "$CFG")"
[ -f "$APPDIR_REAL/audiorelay.jar" ] || die "$APPDIR_REAL doesn't look like AudioRelay's app folder"
[ ! -L "$CFG" ] || die "$CFG is a symlink; refusing to edit it"
SUDO=""
if [ ! -w "$CFG" ] || [ ! -w "$APPDIR_REAL" ]; then
  SUDO="sudo"
  echo "AudioRelay is installed system-wide ($APPDIR_REAL); sudo is needed to add the agent there."
fi

TMP="$(mktemp)"; trap 'rm -f "$TMP"' EXIT
LINE='java-options=-javaagent:$APPDIR/audiorelay-autoconnect.jar='"$OPTS"
awk -v line="$LINE" '
  /audiorelay-autoconnect\.jar/ { next }
  { print }
  $0 ~ /^[[:space:]]*\[JavaOptions\][[:space:]]*$/ && !done { print line; done=1 }
  END { if (!done) { print "[JavaOptions]"; print line } }
' "$CFG" > "$TMP"

$SUDO install -m 644 "$JAR_SRC" "$APPDIR_REAL/audiorelay-autoconnect.jar"
[ -f "$CFG.bak-autoconnect" ] || $SUDO cp -p "$CFG" "$CFG.bak-autoconnect"
# shellcheck disable=SC2016  # $1/$2 are meant for the inner sh
$SUDO sh -c 'cat "$1" > "$2"' sh "$TMP" "$CFG"

echo
echo "Installed into $APPDIR_REAL. Restart AudioRelay; it will auto-connect to $SERVER."
echo "Log: ${XDG_STATE_HOME:-~/.local/state}/audiorelay-autoconnect.log"
echo "Note: updating AudioRelay (e.g. a new .deb) replaces AudioRelay.cfg - just run this again."
