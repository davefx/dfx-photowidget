# DFX Photo Widget

[Leer en español](README.es.md)

Home screen widget for Android (10 or later) that shows photos from one or more folders, or a
hand-picked set of photos, with one goal above all: **don't drain the battery**.
Specifically: **if the home screen is not in view, no work is done.**

## What it does

- Shows a single photo, several picked photos or all the photos in one or more folders
  (with or without subfolders).
- Changes photo every 30 s, 1, 2, 5, 15 or 30 min, 1, 3, 6 or 12 h, once a day, or only on tap.
- Tapping the widget goes to the next photo.
- Appearance: crop to fill or whole photo, four corner radii, thin, medium or thick border in
  white, black, gray or cream.
- Goes through the photos in random order and repeats none until all have been shown.
- On Android 12 or later, long-press the widget to reconfigure it. On Android 10 and 11, use the
  app icon.
- The app's interface is available in English and Spanish (it follows the device language).

## How it avoids draining the battery

The widget is an `AdapterViewFlipper` drawn by the launcher. The app never "pushes" photos to it:
**the launcher asks** `SlideshowService` for them at the moment it draws them, and it only draws
while the home screen is in view. All the expensive work (scanning folders, decoding) happens
there and nowhere else.

- **Intervals of 5 min or less:** the launcher itself flips the photos (`autoStart`). The
  `AdapterViewFlipper` stops by itself when the screen is off, on the lock screen or with another
  app in front. The app has no timer, alarm or service.
- **Intervals of 15 min or more:** an inexact `RTC` alarm (**never** `RTC_WAKEUP`) that only
  records which photo is next, with a `partiallyUpdateAppWidget` call. It neither decodes nor
  reads from disk. The photo is prepared when the launcher draws the widget again. While the
  phone is asleep the alarm is not even delivered: it waits until the phone wakes up for some
  other reason.

### Android 15 and later

Since Android 15, `AppWidgetManager` no longer lets the launcher ask for photos one by one.
On every full update it calls `getViewAt()` for **every** position, in the app's process, and
sends all the bitmaps together. If they add up to more than the system limit (about 1.5 screens
in ARGB, ~20 MB on a 1220×2712 phone), the update is rejected and the widget stays blank.

So on these versions the adapter only exposes a **window** of the photos that always fits
(`PhotoWindow`): up to 12 photos, using at most 80 % of the limit. The launcher flips through
the window on its own, with no work from the app. Then:

- **Slideshow:** a non-wakeup alarm loads the next window once the launcher has had time to show
  the whole current one (window size × interval, at least 1 min). The alarm is never delivered
  early, so no photo is skipped. With the screen off, it does nothing.
- **Tapping the last photo of the window** loads the next window at once and restarts the timer.
- **Long intervals or tap only:** the next window is loaded when the next photo falls outside
  the current one, and only with the screen on.

Each window is decoded in one go, so each photo is still decoded once per round, and the CPU can
sleep between windows. If all the photos fit in one window, nothing else is ever decoded. The
one cost Android does not let the app avoid: Android gives an app no way to know whether the home
screen is in view, so a window change can happen while another app is in front (never with the
screen off).

| Typical problem with other widgets | What this one does |
|---|---|
| Wakes the phone to change the photo | Never uses wakeup alarms. `updatePeriodMillis="0"`. |
| Prepares photos nobody sees | Decoding only happens when the launcher asks for the photo to draw it. |
| Services, WorkManager, wakelocks | None. The only service is the one the launcher binds to ask for photos. |
| Cloud sync | No network permission. Local photos only. |
| Decoding the photo at 12-50 MP | `ImageDecoder` with a target size: subsamples straight to the widget size, with a maximum of about 2 MB per photo in memory. |
| Scanning folders often | Shuffled index on disk. Rebuilt at most once a day or when a round is complete, and always with the home screen in view. |
| Corners and border drawn on every frame | Painted only once into the bitmap. |
| A single photo | No alarm is scheduled. |
| Battery saver on | By default, alarm-driven changes are paused. |

Permissions: only `RECEIVE_BOOT_COMPLETED`, to reschedule the alarm after a reboot. Access to the
photos goes through the Storage Access Framework and the photo picker, which grant permission
only on what you choose. When a widget is deleted, the permissions no other widget uses are
released.

## Building

```sh
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the debug key so it can be installed directly. To distribute
it, change `signingConfig` in `app/build.gradle.kts`.

## Tests

Two levels, both on a connected device or emulator:

```sh
# 1. Instrumented tests (logic, no UI). They use a fake photo provider that only exists
#    in the debug variant.
./gradlew connectedDebugAndroidTest

# 2. End-to-end with the release build: adds the widget, configures it with the system
#    pickers and measures how many photos it prepares in each situation. About 10 minutes.
./gradlew assembleRelease
python3 tools/e2e.py -s emulator-5554
```

The instrumented tests cover the index (subfolders, depth limit, hidden folders, GIF,
duplicates, shuffling), decoding (size, cropping, never upscaling, corners and border), the
real system alarms (never wakeup, correct interval, none with a single photo) and the
photo-change logic (battery saver, full round, taps). They also check that no folder is scanned
when the index is up to date.

The end-to-end test checks that 0 photos are prepared with another app in front or with the
screen off, that taps and the alarm change the photo, that everything survives a reboot and that
batterystats records no wakelocks or wakeup alarms. It needs Pillow (`pip install pillow`).

## Structure

- `PhotoWidgetProvider.kt`: receives the events (tap, alarm, boot, resize, deletion).
- `WidgetUpdater.kt`: decides which photo is next and builds the widget. Never decodes.
- `SlideshowService.kt`: hands the launcher the photo it asks for. The only place where work is done.
- `Scheduler.kt`: non-wakeup alarm.
- `PhotoIndex.kt`: shuffled photo index on disk.
- `Renderer.kt`: decoding at just the right size, cropping, corners and border.
- `ConfigActivity.kt`: configuration.
- `WidgetConfig.kt` / `Grants.kt`: persistence and permissions.

## License

Copyright © 2026 David Marín Carreño (DaveFX).

This program is free software: you can redistribute it and/or modify it under the terms of the
[GNU General Public License, version 3](LICENSE) or (at your option) any later version, as
published by the Free Software Foundation. It is distributed WITHOUT ANY WARRANTY.
