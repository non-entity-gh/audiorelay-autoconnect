#!/bin/sh
# Builds the jar and the three release zips into dist/, plus SHA256SUMS.txt.
# Zips use fixed timestamps and ordering so the same source + JDK give the same bytes.
set -eu
cd "$(dirname "$0")"
./build.sh
export TZ=UTC
for os in windows macos linux; do
  d="build/pkg/audiorelay-autoconnect-$os"
  rm -rf "$d" && mkdir -p "$d"
  cp installers/$os/* "$d/"
  cp dist/audiorelay-autoconnect.jar LICENSE README.md "$d/"
  find "$d" -exec touch -h -d '2026-01-01 00:00:00' {} +
  rm -f "dist/audiorelay-autoconnect-$os.zip"
  (cd build/pkg && find "audiorelay-autoconnect-$os" | LC_ALL=C sort | zip -qX -@ "../../dist/audiorelay-autoconnect-$os.zip")
done
(cd dist && sha256sum audiorelay-autoconnect.jar audiorelay-autoconnect-*.zip > SHA256SUMS.txt && cat SHA256SUMS.txt)
