# Publishing

## Release checklist

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Add `fastlane/metadata/android/{en-US,es-ES}/changelogs/<versionCode>.txt` (500 characters max).
3. `./gradlew bundleRelease assembleRelease`
4. Commit, tag `v<versionName>` and push the tag (F-Droid builds from tags).
5. Upload `app/build/outputs/bundle/release/app-release.aab` to Google Play.

## Signing key

The upload key lives outside the repository, at `~/.android/dfx-photowidget-upload.jks`, and
`keystore.properties` (also untracked) points to it. **Back up both.** With Play App Signing,
Google keeps the real signing key, and a lost upload key can be reset from the Play Console,
but that takes days.

## Google Play

Store listing material is in `fastlane/metadata/android/`:

| Play Console field | File |
|---|---|
| App name | `<lang>/title.txt` |
| Short description | `<lang>/short_description.txt` |
| Full description | `<lang>/full_description.txt` |
| App icon (512×512) | `en-US/images/icon.png` |
| Feature graphic (1024×500) | `<lang>/images/featureGraphic.png` |
| Phone screenshots (1080×2160) | `<lang>/images/phoneScreenshots/` |

Graphic sources are in `tools/graphics/` (render with Inkscape). The screenshots were taken on the
Android 14 emulator with public-domain photos (see `tools/graphics/PHOTO_CREDITS.md`).

Answers for the App content section:

- **Privacy policy URL:** https://github.com/davefx/dfx-photowidget/blob/main/PRIVACY.md
- **Data safety:** no data collected, no data shared. No network permission.
- **Ads:** no. **Target audience:** 13+ (avoids the Families policy). **Content rating:** no
  objectionable content (IARC questionnaire: utility, no user interaction or sharing).
- **App category:** Personalization.

The developer account predates November 2023, so the closed-test requirement for new personal
accounts (12 testers for 14 days) doesn't apply: the app can go straight to production. An
internal test track is still handy to check the Play-signed build on a real phone first.

## F-Droid

`tools/fdroid/com.davefx.photowidget.yml` is the draft metadata. To submit it:

1. Push the `v1.0` tag.
2. Fork https://gitlab.com/fdroid/fdroiddata, add the file as
   `metadata/com.davefx.photowidget.yml`, and check it with `fdroid readmeta`,
   `fdroid lint com.davefx.photowidget` and `fdroid build -v -l com.davefx.photowidget`.
3. Open a merge request using the "App inclusion" template.

The app has no proprietary dependencies, and the release build comes out unsigned when there's
no `keystore.properties`, so F-Droid signs it with its own key. Users can't switch between the
Play and F-Droid builds without uninstalling, because the signatures differ.
