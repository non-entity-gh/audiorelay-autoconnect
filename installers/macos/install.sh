#!/bin/bash
# AudioRelay Auto-Connect installer for macOS.
# Usage (Terminal, in this folder):   bash install.sh [SERVER_ADDRESS] [--no-persistent]
#
# AudioRelay.app is signed by its developer, so this does NOT modify the app (that would break the
# signature and macOS could call the app "damaged"). Instead it installs a per-user login item
# (LaunchAgent) that starts AudioRelay with the auto-connect agent attached, plus a small
# "AudioRelay Auto-Connect" launcher in ~/Applications for starting it by hand.
# No sudo, nothing downloaded. Undo with: bash uninstall.sh
set -euo pipefail

LABEL="io.github.audiorelay-autoconnect"
DATA_DIR="$HOME/.audiorelay-autoconnect"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
LAUNCHER="$HOME/Applications/AudioRelay Auto-Connect.app"
HERE="$(cd "$(dirname "$0")" && pwd)"
JAR_SRC="$HERE/audiorelay-autoconnect.jar"
[ -f "$JAR_SRC" ] || JAR_SRC="$HERE/../../dist/audiorelay-autoconnect.jar"   # when run from a source checkout
HOST_RE='^[A-Za-z0-9[][][A-Za-z0-9._:%-]{0,252}$'
DOMAIN="gui/$(id -u)"

die() { echo "Error: $*" >&2; exit 1; }
[ "$(uname)" = "Darwin" ] || die "this installer is for macOS"
[ -f "$JAR_SRC" ] || die "audiorelay-autoconnect.jar not found next to install.sh"

SERVER="" PERSIST=""
while [ $# -gt 0 ]; do
  case "$1" in
    --no-persistent) PERSIST=",persistent=false" ;;
    -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
    -*) die "unknown option '$1' (see --help)" ;;
    *) [ -z "$SERVER" ] || die "more than one server address given"; SERVER="$1" ;;
  esac
  shift
done

APP=""
for c in "/Applications/AudioRelay.app" "$HOME/Applications/AudioRelay.app"; do
  if [ -x "$c/Contents/MacOS/AudioRelay" ]; then APP="$c"; break; fi
done
[ -n "$APP" ] || die "AudioRelay.app not found in /Applications or ~/Applications"
EXE="$APP/Contents/MacOS/AudioRelay"

case "$DATA_DIR" in *[[:space:]]*) die "your home folder path contains a space, which Java's JAVA_TOOL_OPTIONS can't handle";; esac

if [ -z "$SERVER" ]; then read -r -p "IP address (or hostname) of the computer running the AudioRelay SERVER: " SERVER; fi
SERVER="$(printf '%s' "$SERVER" | tr -d '[:space:]')"
[[ "$SERVER" =~ $HOST_RE ]] || die "'$SERVER' doesn't look like an IP address or hostname"
OPTS="host=$SERVER$PERSIST"

# AudioRelay allows only one running copy, so it must be closed for the auto-connect copy to start.
if pgrep -f "$EXE" >/dev/null 2>&1; then
  read -r -p "AudioRelay is running and must be closed so it can restart with auto-connect. Quit it now? (y/n) " ans
  [[ "$ans" =~ ^[yY] ]] || die "cancelled; nothing was changed"
  osascript -e 'quit app "AudioRelay"' >/dev/null 2>&1 || true
  for _ in $(seq 1 20); do pgrep -f "$EXE" >/dev/null 2>&1 || break; sleep 0.5; done
  if pgrep -f "$EXE" >/dev/null 2>&1; then pkill -f "$EXE" || true; sleep 1; fi
  pgrep -f "$EXE" >/dev/null 2>&1 && die "couldn't quit AudioRelay; quit it yourself and run this again"
fi

mkdir -p "$DATA_DIR" "$HOME/Library/LaunchAgents" "$HOME/Applications"
chmod 700 "$DATA_DIR"
cp "$JAR_SRC" "$DATA_DIR/audiorelay-autoconnect.jar"
chmod 644 "$DATA_DIR/audiorelay-autoconnect.jar"
xattr -d com.apple.quarantine "$DATA_DIR/audiorelay-autoconnect.jar" 2>/dev/null || true

# LaunchAgent. Values are validated above (no XML special characters possible), so no escaping is needed.
launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true
cat > "$PLIST" <<PL
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key>
  <array><string>$EXE</string></array>
  <key>EnvironmentVariables</key>
  <dict>
    <key>JAVA_TOOL_OPTIONS</key>
    <string>-javaagent:$DATA_DIR/audiorelay-autoconnect.jar=$OPTS</string>
  </dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><false/>
  <key>ProcessType</key><string>Interactive</string>
  <key>LimitLoadToSessionType</key><string>Aqua</string>
</dict>
</plist>
PL
plutil -lint "$PLIST" >/dev/null || die "generated LaunchAgent is invalid"

# Small launcher app for starting AudioRelay (with auto-connect) by hand
rm -rf "$LAUNCHER"
osacompile -o "$LAUNCHER" -e "do shell script \"/bin/launchctl kickstart $DOMAIN/$LABEL\"" >/dev/null

# Load it. Right after a bootout, bootstrap can briefly fail ("Bootstrap failed: 5"), so retry.
ok=""
for _ in 1 2 3 4 5; do
  if launchctl bootstrap "$DOMAIN" "$PLIST" 2>/dev/null; then ok=1; break; fi
  sleep 1
done
[ -n "$ok" ] || die "couldn't load the login item. Log out and back in, or run: launchctl bootstrap $DOMAIN \"$PLIST\""

echo
echo "Installed. AudioRelay is starting now and will start at every login, auto-connecting to $SERVER."
echo " - Start it by hand with 'AudioRelay Auto-Connect' in ~/Applications. Opening AudioRelay the normal way"
echo "   works too, just without auto-connect."
echo " - If AudioRelay is in System Settings > General > Login Items, remove it there. Only one copy can"
echo "   run, and if that one starts first, auto-connect won't be active."
echo " - Log: ~/Library/Logs/audiorelay-autoconnect.log"
echo " - Uninstall: bash uninstall.sh"
