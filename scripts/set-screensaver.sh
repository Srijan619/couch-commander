#!/usr/bin/env bash
# Makes Couch Commander's art the screensaver on a Google TV, whose own settings only offer Google's Ambient mode.
# Also grants the permission that lets Settings › Screensaver in the launcher switch it from then on.
#
#   scripts/set-screensaver.sh <tv-ip>:5555          use the art screensaver
#   scripts/set-screensaver.sh <tv-ip>:5555 status   show the current screensaver
#   scripts/set-screensaver.sh <tv-ip>:5555 undo     go back to Google's Ambient mode
set -euo pipefail

APP=app.couchcommander
OURS=$APP/.dream.ArtDream
GOOGLE=com.google.android.apps.tv.dreamx/com.google.android.apps.tv.dreamx.service.Backdrop

[[ $# -ge 1 ]] || { sed -n '5,7p' "$0" | sed 's/^# *//'; exit 1; }
DEVICE=$1
MODE=${2:-set}

adb connect "$DEVICE" >/dev/null || true
a() { adb -s "$DEVICE" shell "$@" | tr -d '\r'; }

installed() { a pm list packages | grep -qx "package:$1"; }

status() {
  echo "Screensaver: $(a settings get secure screensaver_components)"
  echo "Enabled:     $(a settings get secure screensaver_enabled)"
}

case $MODE in
  status)
    status
    ;;
  undo)
    a settings put secure screensaver_components "$GOOGLE"
    status
    ;;
  set)
    installed "$APP" || { echo "Install Couch Commander on $DEVICE first." >&2; exit 1; }
    # Only adb can grant this; with it the launcher's settings can switch screensavers itself.
    a pm grant "$APP" android.permission.WRITE_SECURE_SETTINGS
    a settings put secure screensaver_components "$OURS"
    a settings put secure screensaver_enabled 1
    status
    echo "Done. Preview it with: adb -s $DEVICE shell am start -n com.android.systemui/.Somnambulator"
    ;;
  *)
    echo "Unknown mode: $MODE (use set, status or undo)" >&2; exit 1
    ;;
esac
