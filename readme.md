# NovelForge

A reading app for people who actually read.

NovelForge is a privacy-respecting Android reader for web novels and EPUBs. No account. No subscription. No analytics. The book stays on your phone.

| Home | Library | Novel details | Reader | Audio | Quick settings |
| --- | --- | --- | --- | --- | --- |
| <img src="docs/images/homescreen.jpg" alt="Home screen" width="140"> | <img src="docs/images/libraryscreen.jpg" alt="Library screen" width="140"> | <img src="docs/images/noveldetailscreen.jpg" alt="Novel details screen" width="140"> | <img src="docs/images/readerscreen.jpg" alt="Reader screen" width="140"> | <img src="docs/images/audioscreen.jpg" alt="Audio screen" width="140"> | <img src="docs/images/quicksetting.jpg" alt="Quick settings screen" width="140"> |

## What it does

- Reads web novels from 7+ sources, with offline downloads
- Imports `.epub` files from your device
- 11 themes, 8 reading fonts, configurable margins and line spacing
- Scroll mode, paged mode, and a teleprompter-style auto-scroll
- Neural text-to-speech with Piper, Kokoro, and your device's TTS
- Generates M4B audiobooks with chapter markers
- Tracks reading stats locally (streaks, words, chapters, time)
- Bookmarks and highlights with notes, exportable as JSON
- Backup and restore as a single ZIP

[Full feature list →](docs/FEATURES.md)

## Install

[Download the latest APK from Releases](https://github.com/abhinavxt/novelforge/releases/latest).

Requires Android 8.0 (API 26) or later. Not on the Play Store.

```
1. Download the APK
2. Open it — Android asks once for install permission
3. Open the app
```

That's it. No account, no setup.

## Build from source

```bash
git clone https://github.com/abhinavxt/novelforge.git
cd novelforge
./gradlew assembleRelease
```

The signed APK lands in `app/build/outputs/apk/release/`.

Requirements: Android Studio Hedgehog or later, JDK 17, Android SDK 34.

## Stack

Kotlin · Jetpack Compose · Room · Hilt · Coroutines/Flow · WorkManager · Coil · Sherpa-ONNX

MVVM with a repository layer. See [`docs/FEATURES.md`](docs/FEATURES.md) for what each feature covers, and the source itself for how the layers connect.

## Contributing

Bug reports and feature suggestions go in [Issues](https://github.com/abhinavxt/novelforge/issues). Code contributions welcome — open a PR against `main`.

The project is built on weekends. Reviews aren't instant. Be patient.

## A note on web novel sources

NovelForge is a reading tool, not a piracy tool. It connects to publicly-accessible sites you'd otherwise visit in a browser, fetches the page, and renders it cleanly. No content is hosted by NovelForge.

If a site has a paid tier or Patreon, please support the author. Web novelists rely on it.

## License

[MIT](LICENSE) — do what you want with the code.

The novel content fetched by the app belongs to its authors. The MIT license covers NovelForge itself, not anything it reads.
