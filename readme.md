# NovelForge

A reading app for people who actually read.

NovelForge is a privacy-respecting Android reader for web novels and EPUBs. No account. No subscription. No analytics. The book stays on your phone - and now the app reads it too: a character codex, a relationship graph, and full-text search across your whole library, all computed on-device.

https://github.com/user-attachments/assets/ed53e37d-f37b-43c6-87b3-962cdc796f36

## What it does

- Imports `.epub`, `.txt`, and `.md` files - and exports any novel back out as a clean EPUB
- **Character codex** - every recurring character, place, and faction indexed on-device, spoiler-safe: it only ever shows you what you've read
- **Relationship graph** - who appears with whom, drawn as a live map that grows with your reading position
- Full-text search across every downloaded chapter, plus in-chapter find with match highlighting
- 11 themes, 8 reading fonts, configurable margins and line spacing
- Scroll mode, paged mode, a teleprompter-style auto-scroll, and four tap-zone layouts
- Neural text-to-speech with Piper, Kokoro, and your device's TTS
- A pronunciation dictionary that can also *silence* symbols and words the engine reads aloud
- Generates M4B audiobooks with chapter markers
- Tracks reading stats locally (streaks, words, chapters, time)
- Bookmarks and highlights with notes, exportable as JSON
- Backup and restore as a single ZIP

[Full feature list →](docs/FEATURES.md)

## ☕ Support NovelForge

NovelForge is built in my spare time and kept free, open-source, and ad-free.

If you find it useful, consider buying me a coffee. It helps keep development going.

[☕ Buy Me a Coffee](buymeacoffee.com/abhinavxt)

## Install

[Download the latest APK from Releases](https://github.com/abhinavxt/novelforge/releases/latest).

Requires Android 7.0 (API 24) or later. Not on the Play Store.

Note: the APK ships `arm64-v8a` and `x86_64` only, so 32-bit ARM devices are not supported despite the API level.

```
1. Download the APK
2. Open it - Android asks once for install permission
3. Open the app
```

That's it. No account, no setup.

## Build from source

```bash
git clone https://github.com/abhinavxt/novelforge.git
cd novelforge
./gradlew assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`. It is **unsigned** - there is
no release `signingConfig` in the build, so you'll need to sign it yourself
(or use `assembleDebug`) before it will install.

Requirements: Android Studio Ladybug or later, JDK 21, Android SDK 36.

Gradle 8.14 does not run on JDK 25 - if Android Studio picks it up, set
**Settings → Build Tools → Gradle → Gradle JDK** to 21.

## Stack

Kotlin · Jetpack Compose · Room (with FTS4) · Coroutines/Flow · WorkManager · Coil · Sherpa-ONNX

MVVM with a repository layer. See [`docs/FEATURES.md`](docs/FEATURES.md) for what each feature covers, and the source itself for how the layers connect.

## Contributing

Bug reports and feature suggestions go in [Issues](https://github.com/abhinavxt/novelforge/issues). Code contributions welcome - open a PR against `main`.

The project is built on weekends. Reviews aren't instant. Be patient.

Contributions are accepted under GPL-3.0, the same license as the project.

## Disclaimer 

NovelForge is a reader. It does not host, store, or distribute any content. It renders pages from sources you choose to add, the same pages your browser would load.
All content accessed through the app belongs to its respective authors and rightsholders. You are responsible for how you use the app and for complying with the terms of the sites you connect to and the laws of your jurisdiction.
If a work is available through an official platform, Patreon, or paid tier, please support the author there. Web novelists depend on it.

## License

[GPL-3.0](LICENSE).

Use it, read it, fork it, ship it. If you distribute a modified version, that
version has to be GPL-3.0 too, with its source available. That's the whole
deal - it keeps forks open rather than closing them off.

The scraper layer is derived from
[QuickNovel](https://github.com/LagradOst/QuickNovel) by LagradOst, which is
GPL-3.0; NovelForge inherits that license. Full attribution for every bundled
dependency is in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

The novel content fetched by the app belongs to its authors. The license
covers NovelForge itself, not anything it reads.
