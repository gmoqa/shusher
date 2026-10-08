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

The landing page is generated into `docs/` (served by GitHub Pages) from `site/`:

```sh
python3 tools/build_site.py        # one static page per language + sitemap
python3 tools/build_site.py --og   # also the social preview images (needs Chrome and ImageMagick)
```

## Google Play

Store texts and graphics are in `fastlane/metadata/android/`, and [fastlane/README.md](fastlane/README.md) has
the Play Console checklist and the answers for each form.

## License

The code and the original illustrations, icon and sound are released under the [MIT License](LICENSE).

### Third-party files

| Files | Project | License |
|---|---|---|
| `app/src/main/assets/fonts/Nunito.ttf`, `docs/fonts/nunito-latin.woff2` (latin subset) | [Nunito](https://github.com/googlefonts/nunito), © 2014 The Nunito Project Authors | SIL Open Font License 1.1 (`OFL.txt` next to each copy) |
| `site/icons/*.svg`, inlined in `docs/**/index.html` | [Material Symbols](https://github.com/google/material-design-icons), © Google | Apache License 2.0 (`site/icons/LICENSE`) |
| `docs/img/flags/*.webp` (rasterized) | [flag-icons](https://github.com/lipis/flag-icons), © 2013 Panayiotis Lipiridis | MIT (`docs/img/flags/LICENSE`) |
| `app/src/main/res/drawable/ic_shush.xml` (the “shh” letters, converted to outlines) | [Inter](https://github.com/rsms/inter), © The Inter Project Authors | SIL Open Font License 1.1 |
| `gradle/wrapper/*`, `gradlew`, `gradlew.bat` | [Gradle](https://gradle.org) | Apache License 2.0 |

### Not covered by the MIT license

`docs/img/characters/*` show Peppa Pig and Daddy Pig (© Entertainment One / Hasbro) and Pablo, Tyrone
and Uniqua from The Backyardigans (© Nickelodeon / Nelvana). They appear on the website only as examples
of characters a family can add. They are not part of the app, Shusher is not affiliated with their
owners, and no license to them is granted.
