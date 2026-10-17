#!/usr/bin/env bash
# Makes Couch Commander the Home app on a Google TV by disabling the stock launcher
# and Google's recovery screen, which would otherwise take over Home and loop.
#
#   scripts/set-home.sh <tv-ip>:5555          replace the stock launcher
#   scripts/set-home.sh <tv-ip>:5555 status   list Home handlers and the current Home
#   scripts/set-home.sh <tv-ip>:5555 undo     re-enable the stock launcher
set -euo pipefail

APP=app.couchcommander
RECOVERY=com.google.android.tungsten.setupwraith
# Disabled after RECOVERY: with RECOVERY still on, it takes over Home and loops on a black screen.
LAUNCHERS=(com.google.android.apps.tv.launcherx com.google.android.tvlauncher)

[[ $# -ge 1 ]] || { sed -n '5,7p' "$0" | sed 's/^# *//'; exit 1; }
DEVICE=$1
MODE=${2:-set}

adb connect "$DEVICE" >/dev/null || true
a() { adb -s "$DEVICE" shell "$@"; }

handlers() { a cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME; }
current_home() { a cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1 | tr -d '\r'; }
installed() { a pm list packages | tr -d '\r' | grep -qx "package:$1"; }

# Home handlers that outrank Couch Commander (priority above 0), as package names.
outranking() {
  handlers | tr -d '\r' | awk '/priority=/ { split($1, p, "="); prio = p[2]; next }
    /\// && prio > 0 { split($1, c, "/"); print c[1] }'
}

status() {
  echo "Home handlers:"; handlers
  echo; echo "Current Home: $(current_home)"
}

case $MODE in
  status)
    status
    ;;
  undo)
    for pkg in "${LAUNCHERS[@]}" "$RECOVERY"; do
      installed "$pkg" && a pm enable "$pkg"
    done
    echo; echo "Current Home: $(current_home)"
    ;;
  set)
    installed "$APP" || { echo "Install Couch Commander on $DEVICE first." >&2; exit 1; }

    for pkg in $(outranking); do
      if [[ $pkg != "$RECOVERY" && ! " ${LAUNCHERS[*]} " =~ " $pkg " ]]; then
        echo "Unknown Home handler outranks Couch Commander: $pkg" >&2
        echo "It may also run inputs or settings, so it is not disabled automatically. Check it, then disable it by hand if safe:" >&2
        echo "  adb -s $DEVICE shell pm disable-user --user 0 $pkg" >&2
        exit 1
      fi
    done

    # Lets Settings › Screensaver switch to the art screensaver, which Google TV's own settings don't list.
    a pm grant "$APP" android.permission.WRITE_SECURE_SETTINGS

    for pkg in "$RECOVERY" "${LAUNCHERS[@]}"; do
      installed "$pkg" && a pm disable-user --user 0 "$pkg"
    done

    home=$(current_home)
    echo; echo "Current Home: $home"
    if [[ $home != $APP/* ]]; then
      echo "Couch Commander is not Home yet. Undo with: $0 $DEVICE undo" >&2
      exit 1
    fi
    a input keyevent KEYCODE_HOME
    echo "Done. Reboot the TV (adb -s $DEVICE reboot) to check it starts into Couch Commander."
    ;;
  *)
    echo "Unknown mode: $MODE (use set, status or undo)" >&2; exit 1
    ;;
esac
