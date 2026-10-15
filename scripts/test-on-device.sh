#!/usr/bin/env bash
# Builds the app, installs it on the phone connected over adb, makes it the default
# SMS app, grants permissions, opens it, and watches for crashes.
# Usage: scripts/test-on-device.sh [device-serial]
set -euo pipefail

PKG=co.hkapps.messages
cd "$(dirname "$0")/.."

ADB=(adb)
if [ $# -ge 1 ]; then ADB=(adb -s "$1"); fi

step() { printf '\n== %s\n' "$*"; }

step "Checking the phone"
if ! "${ADB[@]}" get-state >/dev/null 2>&1; then
  echo "No phone found. Plug it in, unlock it, and accept the 'Allow USB debugging' prompt."
  adb devices -l
  exit 1
fi
MODEL=$("${ADB[@]}" shell getprop ro.product.model | tr -d '\r')
SDK=$("${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r')
MIUI=$("${ADB[@]}" shell getprop ro.miui.ui.version.name | tr -d '\r')
echo "Phone: $MODEL, Android API $SDK${MIUI:+, MIUI/HyperOS $MIUI}"
if [ "$SDK" -lt 26 ]; then echo "The app needs Android 8.0 (API 26) or newer."; exit 1; fi

step "Building"
./gradlew --quiet assembleDebug
APK=app/build/outputs/apk/debug/app-debug.apk

step "Installing"
if ! "${ADB[@]}" install -r -t "$APK"; then
  echo
  echo "Install failed. On Xiaomi phones, turn on Settings > Additional settings >"
  echo "Developer options > 'Install via USB' (needs a Mi account and a SIM), then run again."
  exit 1
fi

step "Making it the default SMS app"
# Holding the SMS role also grants the SMS permissions, which adb can't grant directly.
if "${ADB[@]}" shell cmd role add-role-holder android.app.role.SMS "$PKG" 2>/dev/null; then
  echo "Done."
else
  echo "Couldn't set it from adb. The app will ask you on screen: tap 'Set as default'."
fi

step "Granting other permissions"
GRANT_OK=1
for p in READ_CONTACTS RECORD_AUDIO; do
  "${ADB[@]}" shell pm grant "$PKG" android.permission.$p 2>/dev/null || GRANT_OK=0
done
if [ "$SDK" -ge 33 ]; then
  "${ADB[@]}" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || GRANT_OK=0
fi
if [ "$GRANT_OK" = 0 ]; then
  echo "Some grants failed. On Xiaomi, also turn on Developer options >"
  echo "'USB debugging (Security settings)', or just tap Allow when the app asks."
fi
# Lets scheduled texts go out on the exact minute (Android 12+).
"${ADB[@]}" shell appops set "$PKG" SCHEDULE_EXACT_ALARM allow 2>/dev/null || true

step "Opening the app"
"${ADB[@]}" logcat -c
"${ADB[@]}" shell am start -n "$PKG/.MainActivity" >/dev/null

cat <<'LIST'

Try these on the phone (press Ctrl+C here when done):
  1. Conversation list loads, names and unread counts look right
  2. Open a chat, send a text, see "Sending…" turn into a time
  3. From another phone: text this phone; check the notification, reply from it
  4. Attach a photo (paperclip); send; check it arrives on the other phone
  5. Ask the other phone to send a picture; check it downloads and shows
  6. Hold the mic button area: tap mic, record, send a voice memo
  7. Long-press a message > React > ❤️
  8. Type text, hold Send, schedule it 2 minutes ahead; lock the phone; wait
  9. Start a group: New message, hold two contacts, send
 10. Settings: switch Dark/Black and accent colors

Crashes and app errors appear below.
LIST

PID=$("${ADB[@]}" shell pidof "$PKG" | tr -d '\r' || true)
if [ -n "$PID" ]; then
  "${ADB[@]}" logcat -v time -b main -b crash "*:W" | grep --line-buffered -E "AndroidRuntime|FATAL|$PKG|\($PID\)|MmsService|SmsManager"
else
  "${ADB[@]}" logcat -v time -b crash
fi
