# CLAUDE.md

Guidance for Claude when working in this repository.

## Building

The APK the user installs comes from GitHub Actions: every push runs
`.github/workflows/build.yml`, which runs the unit tests, builds the release
APK, launches it on an emulator and uploads it as an artifact. Pushes to
`main` also publish it as a GitHub release; other branches don't. After pushing,
check that workflow run and fix it if it's red.

Building locally to check a change before pushing is fine
(`./gradlew testDebugUnitTest assembleRelease`, JDK 21 + Android SDK), but
never ask the user to build or install anything by hand.

`AppTest` drives every screen under Robolectric against `FakeGarmin` and writes
PNGs to `app/build/screenshots/`. Look at them after UI changes.

## Garmin

The app reads Garmin Connect's Personal API (`data/GarminApi.kt`) with the
same kind of Personal API key as the sibling `workouts-app`. Field names come
from Garmin's OpenAPI spec, which is embedded in the page at
developer.garmin.com/gc-developer-program/personal/swagger-docs/. Check it
before adding an endpoint. Routes and streams come from FIT files
(`data/Fit.kt`), since the API has no stream endpoint.

## Design

Light and airy: warm paper background, white cards, one colour per metric
(`ui/theme/Theme.kt`, object `G`). Keep it simple. New things should fit an
existing card or tab before getting a new screen.
