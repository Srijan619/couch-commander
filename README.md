# Couch Commander

A simple, colourful Android TV launcher: one row of apps over a public-domain painting that changes on a timer, and a matching ambient-art screensaver. Designs: https://claude.ai/artifact/R63R7V7Vt8nRFVEUK9eHHp (direction A was chosen).

Built from the [focus-launcher](../focus-launcher) setup (Gradle, versions, JSON settings file); the launcher code itself is new.

## Build and install

Requires JDK 17+ and the Android SDK (compileSdk 37). `local.properties` points at the SDK.

```sh
./gradlew :app:testDebugUnitTest     # unit tests
./gradlew :app:assembleRelease      # use release on the TV: the debug build is several times slower to navigate
adb connect <tv-ip>:5555             # TV: Developer options › ADB debugging (Bravia) / USB or network debugging
adb install -r app/build/outputs/apk/release/app-release.apk
```

## Make it the home screen

On TVs that offer a choice, press Home and pick **Couch Commander**, or choose it as the default home app in the TV's settings.

**Google TV (incl. Sony Bravia, TCL)** doesn't let you choose, and ignores `cmd package set-home-activity` (it prints Success, but Home doesn't change). Android ranks Home apps by priority, and a sideloaded app is capped at 0:

| Priority | Home handler |
|---|---|
| 2 | `com.google.android.apps.tv.launcherx`: the stock launcher |
| 1 | `com.google.android.tungsten.setupwraith/.RecoveryActivity`: Google's "launcher is missing" screen |
| 0 | `app.couchcommander/.MainActivity` |
| -1000 | `com.android.tv.settings/.system.FallbackHome`: the system's last resort |

So disabling only the stock launcher hands Home to the recovery screen, which can't bring the launcher back and relaunches itself in a loop on a black screen (seen on a Bravia, Android 12). Disable the recovery screen first, then the stock launcher, and Couch Commander becomes Home:

```sh
scripts/set-home.sh <tv-ip>:5555          # install Couch Commander first; then reboot the TV to check
scripts/set-home.sh <tv-ip>:5555 status   # list Home handlers and the current Home
scripts/set-home.sh <tv-ip>:5555 undo     # re-enable both
```

The script stops without changing anything if another Home handler (a TV maker's own launcher, say) outranks Couch Commander, since that package may also run inputs or settings. Re-enable both packages (`undo`) before a factory reset, because `setupwraith` also runs first-time setup. A firmware update may re-enable the stock launcher; run the script again.

By hand, the script does:

```sh
adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith   # first, or Home loops
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
```

If you'd rather not disable anything, turn on the Home button service instead. It opens Couch Commander whenever the stock launcher comes to the front, but the stock launcher shows for a moment first.

- On the TV: Launcher settings › **Open Couch Commander on Home**, which opens Accessibility; turn on **Couch Commander Home button**.
- Or over adb (this replaces the list of enabled accessibility services; append with `:` if the TV already has some):

```sh
adb shell settings put secure enabled_accessibility_services app.couchcommander/app.couchcommander.system.HomeButtonService
adb shell settings put secure accessibility_enabled 1
```

## Screensaver

Google TV's settings only offer Google's Ambient mode and never list other screensavers, so set it over adb:

```sh
scripts/set-screensaver.sh <tv-ip>:5555          # use the art screensaver
scripts/set-screensaver.sh <tv-ip>:5555 status   # show the current one
scripts/set-screensaver.sh <tv-ip>:5555 undo     # back to Google's Ambient mode
```

The script also grants the app `WRITE_SECURE_SETTINGS` (as does `scripts/set-home.sh`). Only adb can grant it, and with it Settings › **Screensaver** › **Use art** in the launcher switches to the art screensaver by itself.

When the art screensaver is on, pressing **Screensaver** opens the system page, where picking Ambient mode switches back to Google's. The screensaver shows the followed teams' next match or live score top-right, as home does; turn it off with **Football in screensaver**.

## Using it

- Home starts empty: on first run a picker asks which apps go on the home row (in the order you pick them).
- **OK** opens an app. **Hold OK** on an app to move it left or right, or remove it from home.
- **Launcher settings › Inputs** lists only the TV's inputs with something plugged in (HDMI and others, not tuners), and opens one in the TV's own live TV app. A device that announces itself over HDMI-CEC shows under its own name when the TV lists it as a separate input; otherwise you see the port ("HDMI 2"), or the name you gave the input in the TV's settings. **Android settings** opens the TV's system settings.
- The apps wait in a dock below the screen. Down or OK on **Apps ⌄** slides it up; Up or Back hides it, and it hides by itself after a minute without a key press (Launcher settings › **Hide apps**: 15 s to 5 min, or never).
- **Launcher settings**: press Up while the dock is hidden (or the Menu key, on remotes that have one): choose apps, next painting, how often it changes, colour boost, when the dock hides, screensaver clock and football, which screensaver runs.
- In the screensaver, any key shows the painting's details with **Next painting** and **Go home**; Back leaves.

## Football

Settings › Football › **Teams**: search a club and follow it. Home then shows its next match top right, a countdown in the last three hours, the live score and minute during the match, and the result for a while after.

The data side (Footy API, caching, live polling, which match to feature) is the shared [`football-core`](../football-core) library, also used by focus-launcher's widget. Both apps pull it in with `includeBuild("../football-core")`, so keep the three folders side by side. Its tests run from either app: `./gradlew :football-core:test`. The data host is kept out of git: set `FOOTY_BASE_URL` in the environment or in `football-core/.env` (copy `.env.example`), or the build stops and says so. Only the wording and look live here (`domain/FootballText.kt`, `ui/home/HomeScreen.kt`).

### Where to watch (IPTV)

With your IPTV playlist set, the launcher finds which channel shows each followed team's match: a channel named after the match ("EPL 03: Arsenal v Chelsea"), or a guide (XMLTV) entry naming the teams around kickoff. TiviMate can't be opened on a given channel, so the plan is to show the channel number to type.

Add the playlist in Settings › Football › **IPTV playlist** (the TV's keyboard, or its voice input). Or set it over adb, so the URL and login don't have to be typed with the remote. An Xtream `get.php?username=…&password=…` link is recognised and used through `player_api.php` (sports categories only); anything else is read as an M3U playlist. Run it in your own terminal, since the URL contains the login:

```sh
adb shell am broadcast -n app.couchcommander/.data.iptv.IptvSourceReceiver -a app.couchcommander.SET_IPTV --es source '<playlist url>' [--es epg '<guide url>']
adb shell am broadcast -n app.couchcommander/.data.iptv.IptvSourceReceiver -a app.couchcommander.SYNC_IPTV    # sync now
adb shell am broadcast -n app.couchcommander/.data.iptv.IptvSourceReceiver -a app.couchcommander.CLEAR_IPTV   # forget it
adb logcat -s Iptv                                                                                            # result, without URLs
```

How it stays light (`data/iptv/`): the sync runs twice a day, and also when the playlist or the followed teams change, on one low-priority thread. The playlist and guide are streamed and filtered while they download (live sports channels only, guide entries naming a followed team within the next 8 days), so neither file is ever held in memory or stored. Only the result goes into `iptv.json`. The source is encrypted with an Android Keystore key, and both files are left out of backups. Downloads stop at a size cap or after 5 minutes.

## Paintings

`data/art/Artworks.kt` is the curated list: public-domain works only, fetched as 1920 px copies from Wikimedia Commons and cached on the TV. One painting is downloaded per change (plus the next one ahead), and the Great Wave is bundled for when the TV is offline. Square-ish paintings are shown whole in a mat on a wall coloured from the painting, instead of being cropped.

Font: Bricolage Grotesque, SIL Open Font License (`licenses/`).

## Performance notes

TV chips are slow, so home is built to draw as little as possible per frame: the painting's colour boost is baked into the bitmap once, everything behind the tiles is one cached layer, the slow pan pauses while the remote is in use, and only the focused tile casts a shadow. Measure with `adb shell dumpsys gfxinfo app.couchcommander` after a fresh start. Don't use `gfxinfo ... reset` on Android 12 TVs: it can abort the app (a platform bug in HWUI's JankTracker).
