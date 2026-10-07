# Publishing on Google Play

Checklist and ready-to-paste answers for Play Console. Store texts and graphics live in
`fastlane/metadata/android/<locale>/` (en-US, es-419, pt-BR, fr-FR).

## 1. Before you start

- [ ] Google Play developer account (one-time USD 25 fee, identity verification).
- [ ] **New personal accounts** must run a **closed test with at least 12 testers opted in for 14 consecutive
      days** before applying for production access. Organization accounts are exempt.
- [ ] Back up the signing key: `~/keystores/shusher-release.jks` and the `SHUSHER_*` entries in
      `~/.gradle/gradle.properties`.

## 2. Build

```sh
./gradlew bundleRelease   # app/build/outputs/bundle/release/app-release.aab
```

- `targetSdk 36` meets the requirement for new apps from August 31, 2026 (Android 16).
- Bump `versionCode` (currently 2, `1.0.1`) in `app/build.gradle.kts` for every upload.

### App signing

Choose **"Use existing app signing key from Java keystore"** and upload the current key (Play Console gives
the PEPK tool and the exact command). This keeps the Play build and the GitHub release APK signed with the
same certificate, so users can switch between them and updates keep working.

Certificate SHA-256: `9F:CB:D4:A3:7F:28:32:29:A3:BD:37:3A:1E:53:D7:52:40:23:B1:4D:EC:AF:36:9E:13:8D:45:18:EE:03:01:C7`

If you let Google generate the app signing key instead, the Play build and the GitHub APK will not be
interchangeable (installing one over the other fails).

## 3. Store listing

| Field | Value |
|---|---|
| App name | `title.txt` (≤ 30 chars) |
| Short description | `short_description.txt` (≤ 80 chars) |
| Full description | `full_description.txt` (≤ 4000 chars) |
| App icon | `images/icon.png` (512 × 512) |
| Feature graphic | `images/featureGraphic.jpg` (1024 × 500) |
| Phone screenshots | `images/phoneScreenshots/` |
| 10-inch tablet screenshots | `images/tenInchScreenshots/` |
| Category | Parenting (alternative: Lifestyle) |
| Website | https://gmoqa.github.io/shusher/ |
| Privacy policy | https://gmoqa.github.io/shusher/privacy/ |
| Contact email | required by Play; it is shown publicly on the listing |

Avoid medical claims (“treats”, “therapy”, “reduces autism symptoms”). The texts describe a visual reminder,
not a treatment.

## 4. App content (Policy → App content)

**Privacy policy:** https://gmoqa.github.io/shusher/privacy/

**App access:** All functionality is available without special access (no login).

**Ads:** The app does not contain ads.

**Content rating (IARC questionnaire):** Category “Utility, Productivity, Communication or Other”. Answer *No*
to violence, sexuality, language, controlled substances, gambling, user-generated content sharing and user
interaction. Users can’t communicate; the app has no internet access. Expected result: Everyone / PEGI 3.

**Target audience and content:**
- Recommended: **18 and over**. The app is installed and configured by parents, caregivers or therapists.
  The child only sees the reminder.
- If you include ages under 13, the **Families policy** applies. The app already meets its main points (no
  ads, no data collection, no internet), but review is stricter and the app may be checked for appeal to
  children. Answer honestly: the illustrations are child-friendly and the reminder is shown to a child.

**News app:** No.

**Health apps declaration:** The app does not offer health features. The form must still be completed by
every app.

**Government app / Financial features:** No.

**Data safety:**
- Does the app collect or share any required user data types? **No.**
- Reason: audio is processed only on the device, in memory, to compute a volume level, and is never stored or
  transmitted. The app has no `INTERNET` permission and Android backup is disabled
  (`android:allowBackup="false"`), so no data leaves the device.
- Is data encrypted in transit? Not applicable (no data is transmitted).
- Can users request deletion? Uninstalling the app removes all its data.

### Foreground service declaration (microphone)

Required because the app targets Android 14+ and uses `FOREGROUND_SERVICE_MICROPHONE`.

- **Type:** Microphone.
- **Use case:** Other (enter manually):
  > Detects screams while the user watches or plays other apps. While the user has turned on listening, the
  > app keeps the microphone open in a foreground service and measures the loudness of the audio in memory
  > every 50 ms. When a sustained loud sound is detected, it shows a calm visual reminder over the current
  > app. Audio is never recorded, stored or transmitted, and the microphone is released while the screen is
  > off. Listening is started and stopped by the user from the app or a Quick Settings tile, and a persistent
  > notification is shown while it runs.
- **User impact if deferred:** The reminder would not appear while the child shouts, which is the app’s only
  purpose; there is no way to perform the task later.
- **User impact if interrupted:** Listening stops and the reminder stops working until the user turns it on
  again.
- **Video:** an unlisted YouTube link (or Google Drive) of about 30–60 s showing:
  1. Opening Shusher and tapping **Start** (permissions are granted).
  2. The ongoing notification in the shade.
  3. Leaving the app and opening another app (e.g., a video).
  4. Making a loud sound and the reminder appearing over the other app.
  5. Turning listening off from the Quick Settings tile.

  It can be recorded from a device with `adb shell screenrecord /sdcard/shusher.mp4`.

### Permissions that need no separate form

- `RECORD_AUDIO`: covered by the privacy policy and the data safety form.
- `SYSTEM_ALERT_WINDOW` (display over other apps): requested at runtime from Settings and explained in the
  store description. Play has no declaration form for it, but reviewers check that the use is core to the
  app.
- `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`: standard.

## 5. Testing and release

1. Create the app in Play Console (default language English (US), free app).
2. Upload `app-release.aab` to the **Closed testing** track and add the testers' emails (at least 12).
3. Keep at least 12 testers opted in for 14 consecutive days.
4. Apply for production access from the Dashboard and answer the questions about the test.
5. Promote the release to Production.
