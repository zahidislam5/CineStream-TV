#!/usr/bin/env bash
set -euo pipefail

adb install downloaded-apk/app-debug.apk
adb logcat -c
adb shell am start -n com.zahrus.tv/com.cinestream.tv.MainActivity
sleep 15

adb logcat -d -v brief -s AndroidRuntime:E > runtime-crashes.txt
if grep -Eq 'FATAL EXCEPTION|Process: com\.zahrus\.tv' runtime-crashes.txt; then
  cat runtime-crashes.txt
  echo "Android app runtime crash detected"
  exit 1
fi

if ! adb shell pidof com.zahrus.tv >/dev/null; then
  echo "ZAHRUS TV is no longer running. Additional logs:"
  adb logcat -d -v brief -t 250 | tail -n 150
  exit 1
fi

adb shell screencap -p /sdcard/zahrus-startup.png
adb pull /sdcard/zahrus-startup.png startup-screen.png >/dev/null
echo "ZAHRUS TV launch smoke test passed. PID is alive, no AndroidRuntime fatal errors."
