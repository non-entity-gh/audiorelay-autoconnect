#!/bin/sh
# Builds dist/audiorelay-autoconnect.jar. Needs JDK 17+ (CI uses exactly 17, matching AudioRelay's runtime).
set -eu
cd "$(dirname "$0")"
rm -rf build && mkdir -p build/classes dist
javac -nowarn -source 17 -target 17 \
  --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED \
  -d build/classes src/arautoconnect/*.java
cat > build/MANIFEST.MF <<MF
Premain-Class: arautoconnect.Agent
Implementation-Title: AudioRelay Auto-Connect
Implementation-Version: 1.0.0
MF
# fixed timestamps so the jar is reproducible
jar --create --file dist/audiorelay-autoconnect.jar --manifest build/MANIFEST.MF --date=2026-01-01T00:00:00Z -C build/classes .
echo "built dist/audiorelay-autoconnect.jar"
