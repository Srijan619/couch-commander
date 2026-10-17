# Couch Commander: notes for agents

## Installing on the TV

- Always install the **release** build: `./gradlew :app:assembleRelease`, then
  `adb install -r app/build/outputs/apk/release/app-release.apk`. Never install `app-debug.apk` on the TV:
  the debug build is several times slower to navigate there, which makes any change look like a slowdown.
- Release is signed with the debug key, so it installs over an existing build without uninstalling.
- Installing kills the running app. Check what's in front first
  (`adb shell dumpsys activity activities | grep -m1 mResumedActivity`) and don't install over a playing
  `PlayerActivity` unless the user says to; afterwards bring the launcher back with
  `adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME`.
- Measure on the release build too: a trace of the debug build mostly shows interpreted code.
