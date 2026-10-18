# Roadmap

Planned work, roughly in the order it's likely to happen. Nothing here is built yet.

## Trakt: what's been watched

**Why:** the app doesn't record what was watched. Continue (`media-continue.json`) keeps only where each title was
left, one entry per film or series. "Mark watched" and "Reset progress" on a title page both just take the title off
Continue, and a film played to the end drops off it. TMDB can't fill the gap: its lists hold whole titles only (no
episodes or dates), and lists holding series need its v4 API. Trakt is built for this: watch history with dates,
progress per episode, and sync with other apps.

**What it would do:**

- **Sign in from the TV** with Trakt's device flow: the TV shows a short code (and a QR code for
  `trakt.tv/activate`), and the user approves it on the phone, the same way TMDB sign-in works now. Keep the token
  encrypted with Keystore, as `tmdb-session.json` is.
- **Scrobble from the VOD player:** start, pause and stop while playing. Trakt counts a stop past about 80% as
  watched, so films and episodes played through are recorded without any button.
- **Mark watched / unwatched** on a title page records it in Trakt history: a whole film, a whole series, a season,
  or a single episode (from its row).
- **Watched marks in the UI:** a tick on posters, "watched" on episode rows, and Play on a series picking the first
  unwatched episode instead of S1 E1.
- **Continue from Trakt:** started on another device, the title still shows here. Trakt's playback progress and
  "up next" could feed the Continue shelf alongside the local file.
- **Signed out**, everything keeps working from the local Continue file, as it does today.

**Notes:**

- Trakt accepts TMDB ids, so the titles already on screen map across without searching.
- Needs a Trakt API app registered (client id and secret), kept out of the repo like the TMDB key.
- Mind Trakt's rate limits: sync on change and on opening the page, never in bursts (the IPTV panel already blocked
  us once for that).
- Open questions: should watched marks show on every shelf or only on title pages? When the TV and Trakt disagree
  about where a title was left, does the later timestamp win?
