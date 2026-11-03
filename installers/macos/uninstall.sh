#!/bin/bash
# Removes AudioRelay Auto-Connect from macOS. AudioRelay.app itself was never modified.
set -u
LABEL="io.github.audiorelay-autoconnect"
# Note: this also closes AudioRelay if it was started by Auto-Connect.
launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
rm -f "$HOME/Library/LaunchAgents/$LABEL.plist"
rm -rf "$HOME/Applications/AudioRelay Auto-Connect.app" "$HOME/.audiorelay-autoconnect"
rm -f "$HOME/Library/Logs/audiorelay-autoconnect.log"
echo "Removed. If AudioRelay had been started by Auto-Connect it was closed; open it again normally."
