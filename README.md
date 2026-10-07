# Shusher

A gentle reminder when voices get loud.

Shusher is an Android app for autistic kids who shout while using their tablet. It listens in the
background and, when it hears a scream, the screen dims softly and a friendly character appears going
“shh”. Two seconds later everything is back to normal. No scolding, no scares.

**Website:** https://gmoqa.github.io/shusher/ (English, Español, Português, Français)

## Features

- Works on top of any app (videos, games) and from a Quick Settings button.
- Live sensitivity meter: if the green bar reaches the orange zone, the reminder appears.
- Daily log of reminders for the last 7 days.
- Bring your child’s favorite characters as PNG images.
- The microphone rests while the screen is off.
- No internet permission: audio is only used to measure volume and is discarded right away.
- Android 8.0+ · English, Spanish, Portuguese and French.

## Build

Requires JDK 17+ and the Android SDK.

```sh
./gradlew installDebug   # build and install on a connected device
./gradlew test           # unit tests
```

The landing page lives in `docs/` and is served with GitHub Pages.

## Credits

- [Nunito](https://github.com/googlefonts/nunito) font, SIL Open Font License (`app/src/main/assets/fonts/OFL.txt`).
- Flags from [flag-icons](https://github.com/lipis/flag-icons) (MIT).
- The website shows Peppa Pig (© Entertainment One / Hasbro) and The Backyardigans (© Nickelodeon / Nelvana)
  only as examples of characters a family can add. Shusher is not affiliated with them.
