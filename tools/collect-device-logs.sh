#!/usr/bin/env bash
set -euo pipefail

OUT_DIR="${1:-device-test-logs/$(date -u +%Y%m%dT%H%M%SZ)}"
PACKAGE="${PHAIRPLAY_PACKAGE:-}"

mkdir -p "$OUT_DIR"

if ! command -v adb >/dev/null 2>&1; then
  echo "adb is not installed or not on PATH" >&2
  exit 1
fi

if [[ -z "$PACKAGE" ]]; then
  if adb shell pm path com.phairplay.googletv >/dev/null 2>&1; then
    PACKAGE="com.phairplay.googletv"
  elif adb shell pm path com.phairplay.firetv >/dev/null 2>&1; then
    PACKAGE="com.phairplay.firetv"
  else
    PACKAGE="com.phairplay.googletv"
  fi
fi

PID="$(adb shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' || true)"

adb devices -l > "$OUT_DIR/adb-devices.txt"
adb shell getprop > "$OUT_DIR/getprop.txt" || true
adb shell dumpsys package "$PACKAGE" > "$OUT_DIR/package.txt" || true
adb shell dumpsys meminfo "$PACKAGE" > "$OUT_DIR/meminfo.txt" || true

if [[ -n "$PID" ]]; then
  adb shell top -b -n 1 -p "$PID" > "$OUT_DIR/top.txt" || true
else
  echo "Package $PACKAGE is not running" > "$OUT_DIR/top.txt"
fi

adb logcat -d -v time \
  '*:W' \
  'PhairPlay:V' \
  'AirPlayReceiver:V' \
  'RtspHandler:V' \
  > "$OUT_DIR/logcat.txt" || true

{
  echo "package=$PACKAGE"
  echo "pid=${PID:-not-running}"
  echo "captured_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "out_dir=$OUT_DIR"
} > "$OUT_DIR/summary.txt"

echo "Wrote device test logs to $OUT_DIR"
