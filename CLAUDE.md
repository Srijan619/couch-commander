# Couch Commander: notes for agents

## Installing on the TV

- Always install the **release** build: `./gradlew :app:assembleRelease`, then
  `adb install -r app/build/outputs/apk/release/app-release.apk`. Never install `app-debug.apk` on the TV:
  the debug build is several times slower to navigate there, which makes any change look like a slowdown.
- Release is signed with the debug key, so it installs over an existing build without uninstalling.
- After installing, compile it with its baseline profile, or the first hours run uncompiled (`adb install` leaves
  it at `status=verify` until the TV's overnight maintenance) and every page stutters on a cold start:
  `adb shell am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE app.couchcommander/androidx.profileinstaller.ProfileInstallReceiver`,
  then `adb shell cmd package compile -m speed-profile -f app.couchcommander` (restarts the app). Check with
  `adb shell dumpsys package dexopt | grep -A2 couchcommander`: it should say `status=speed-profile`.
- Installing kills the running app. Check what's in front first
  (`adb shell dumpsys activity activities | grep -m1 mResumedActivity`) and don't install over a playing
  `PlayerActivity` unless the user says to; afterwards bring the launcher back with
  `adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME`.
- Measure on the release build too: a trace of the debug build mostly shows interpreted code.
