#!/usr/bin/env bash
# Stop, wait until the full space is released, launch, and wait for the map to be laid (or a crash).
#   tools/relaunch.sh [install]     # "install": adb install -r the debug APK first
set -u
DEV=${DEV:-emulator-5562}
PKG=io.embodit.flooratlas
export PATH="$PATH:$LOCALAPPDATA/Android/Sdk/platform-tools"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
[ "${1:-}" = "install" ] && adb -s $DEV install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk" | tail -1
adb -s $DEV shell am force-stop $PKG
for i in $(seq 1 20); do
  adb -s $DEV shell dumpsys window | grep -q "$PKG" || break
  sleep 1
done
sleep 3
for attempt in 1 2 3; do
  adb -s $DEV logcat -c
  adb -s $DEV shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 4
  if adb -s $DEV logcat -d | grep -q "don't allow start fullscreen"; then echo "refused (full space busy), retry $attempt"; sleep 5; continue; fi
  for i in $(seq 1 60); do
    adb -s $DEV logcat -d -s FloorAtlas:* | grep -q "MAP lay" && { echo "OK laid"; exit 0; }
    adb -s $DEV logcat -d | grep -q "FATAL EXCEPTION" && { adb -s $DEV logcat -d | grep -A15 "FATAL EXCEPTION" | head -30; exit 1; }
    sleep 3
  done
  echo "no MAP lay after 180 s"; exit 1
done
exit 1
