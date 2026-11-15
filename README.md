# gview

A light, simple Android viewer for your Garmin data: sleep, recovery,
activities and trends, synced from Garmin Connect and kept on the phone.

| Tab | What's there |
| --- | --- |
| **Today** | Body Battery, sleep score, HRV and resting heart rate, each with its last two weeks. Below: last night's sleep and its stages, Body Battery through the day, steps and movement, stress, and the day's activities. Swipe (or use the arrows) for earlier days. |
| **Activities** | Everything synced, by week with weekly totals, filterable by sport. Each one shows its route's shape. Tap for the route on a map, pace, heart rate and elevation charts, kilometre splits (5 km for rides), and the rest of Garmin's numbers. |
| **Map** | Tap an activity's map for it full screen: pinch and pan, with one chart along the bottom (pace, heart rate or elevation). The route is coloured by whichever is shown, and dragging along the chart moves a dot along the route. |
| **Trends** | Training volume by week or day, and sleep, HRV (with your balanced range), Body Battery, resting heart rate, steps, stress, VO₂ max and weight over 4 weeks, 3 months, a year or everything synced. Drag across any chart to read values; tap a card for more. |

**Analysis**, on Trends and each run's page:

- **Fitness & fatigue.** Six-week training load (fitness) against the last
  week's (fatigue), and the gap between (form: building, steady, fresh or
  overreaching). Uses Garmin's training load per activity, or a heart-rate
  estimate where it has none.
- **Aerobic efficiency.** Each run's *grade-adjusted pace* (pace as if the
  hills were flat) scaled to your typical running heart rate, with its
  six-run trend: faster over the months means fitter. Long runs also show
  **heart-rate drift**: how much HR rose for the same effort in the second
  half (under 5% means the distance is within your endurance). Each run's
  page shows its grade-adjusted pace, the same efficiency figure and its
  drift, and the pace chart gains a grade-adjusted line.
- **Recovery.** Plain findings from your own data: how far HRV drops after
  hard days (your top quarter by load) and how many nights it takes to come
  back, what rest days do, resting HR the morning after, and what goes with
  better or worse sleep (evening workouts, stressful days, hard days). Only
  what the data clearly shows is listed.

- **Heart-rate zones.** Each activity shows its time in zones 1–5, and
  Trends adds up the range: how much was easy (zones 1–2) against the
  usual ~80% easy for endurance training.
- **Personal bests.** Garmin's fastest 1 km, mile, 5 km, 10 km (and longer)
  within any run, all time and within the range shown; tap one to open the
  run. Each run marks the ones that are your best.
- **Temperature** for each activity, and the drift note says when heat, not
  fitness, is the likelier reason heart rate climbed.

Efficiency and drift need each run's file, so after each sync the app works
through older runs, 40 at a time, in the background.

There's also a **home screen widget** with Body Battery, sleep, HRV and steps.

## Connecting Garmin

gview uses the same **Garmin Connect Personal API key** as the Workouts app.
On first launch tap **Connect Garmin** and paste a key (Garmin Connect →
Settings → Personal API token → Create Key; Garmin shows a key only once, so
reuse the one you saved for Workouts, or make another). Say when it expires
and Settings warns you as that gets close.

It's read-only. The key never leaves the phone and isn't included in
backups.

## How it syncs

- The first sync pulls a year; **Settings → History** goes back 2, 3 or 5
  years (the older stretch is fetched once). After that each sync fetches
  the last week or so: when the app opens (at most every 15 minutes), when you pull down on
  Today or Activities, and every few hours in the background.
- Each kind of data is a separate Garmin call. If one Garmin service fails,
  the rest still sync, and Settings lists what was missed.
- Garmin's Personal API has no route or stream endpoint, so routes, charts
  and splits come from the activity's original **FIT file**, decoded on the
  phone. The last 45 days' routes download after each sync. Older ones
  download when you open them. All are kept for offline use.
- Maps are OpenStreetMap's own tiles, drawn softened to suit the app's
  look. Tiles are cached once seen.
- Everything is in one JSON file in the app's storage, so the app opens
  instantly and works without signal.

The endpoints and field names come from Garmin's Personal API OpenAPI spec
(developer.garmin.com/gc-developer-program/personal). Units are metric.

## Install

Every push to `main` builds the app on GitHub Actions and publishes it as a
release. On the phone, open this repo's **Releases** page, tap the newest
`gview-1.0.X.apk` and install it.

Builds from other branches aren't released. Their APK is attached to the run
on the Actions tab, e.g. `gview-1.0.3-12.apk`. Every build is signed with the
same checked-in key and carries a higher hidden build number, so a new APK
installs over the old one and keeps your data.

Needs Android 16 or newer; targets Android 17. Kotlin, Jetpack Compose,
Material 3, Glance for the widget, WorkManager for background sync. No
other libraries: the charts, map and FIT decoder are small and in-house.

## Building

GitHub Actions (`.github/workflows/build.yml`) runs on every push. It runs
the tests and builds the release APK. Then it installs that APK on an Android
16 emulator, opens it, and checks the app is still running 20 seconds later.
Only then does it publish a release (on `main`), keeping the ten newest.

Locally, with JDK 21 and the Android SDK:

```bash
./gradlew testDebugUnitTest assembleRelease
```

The tests parse responses shaped like Garmin's spec and decode FIT files.
`AppTest` connects to a fake Garmin with a year of made-up data and drives
every screen under Robolectric. It saves a screenshot of each to
`app/build/screenshots/`, and CI uploads those as the `screenshots`
artifact.
