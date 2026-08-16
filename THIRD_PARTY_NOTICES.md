# Third-party notices

NovelForge bundles or derives from the projects below. This file is the
attribution record; it does not replace the individual licenses.

---

## QuickNovel — the reason this project is GPL-3.0

The scraper layer is derived from
[QuickNovel](https://github.com/LagradOst/QuickNovel) by LagradOst, licensed
**GPL-3.0**. `data/source/nf/` is a QuickNovel compatibility layer, built so
QuickNovel providers run essentially unmodified.

| Component                                                          |      Lines | Relationship                                 |
| ------------------------------------------------------------------ | ---------: | -------------------------------------------- |
| `data/source/nf/` — `MainAPI`, `NfHttp`, `NfCloudflare`, `NfTools` |      1,424 | Compatibility layer for QuickNovel providers |
| `data/source/providers/*Provider.kt` (17 files)                    |      5,655 | Ported providers                             |
| **Total**                                                          | **~7,100** | ≈17% of the codebase                         |

The seven `*Source.kt` providers (2,535 lines) are original work.

GPL-3.0 is copyleft: a derivative must be distributed under GPL-3.0. NovelForge
therefore is. **This project was previously labelled MIT, which was incorrect
for any release containing the ported source layer.** Fixed as of the license
change; releases prior to that carried the wrong LICENSE file.

Thanks to LagradOst and the QuickNovel contributors — a large part of what
makes NovelForge able to read anything at all came from their work.

---

## Bundled libraries

| Project                                                             | License    | Use                                                     |
| ------------------------------------------------------------------- | ---------- | ------------------------------------------------------- |
| [Sherpa-ONNX](https://github.com/k2-fsa/sherpa-onnx)                | Apache-2.0 | On-device neural TTS (bundled `.aar` + JNI native libs) |
| [OkHttp](https://square.github.io/okhttp/)                          | Apache-2.0 | HTTP client                                             |
| [Okio](https://square.github.io/okio/)                              | Apache-2.0 | I/O (via OkHttp)                                        |
| [Jsoup](https://jsoup.org/)                                         | MIT        | HTML parsing                                            |
| [Gson](https://github.com/google/gson)                              | Apache-2.0 | JSON serialization for backup/restore                   |
| [Coil](https://coil-kt.github.io/coil/)                             | Apache-2.0 | Image loading                                           |
| [AndroidX / Jetpack Compose](https://developer.android.com/jetpack) | Apache-2.0 | UI, Room, WorkManager, Glance, DataStore, Media         |
| [Kotlin & kotlinx.coroutines](https://kotlinlang.org/)              | Apache-2.0 | Language and concurrency                                |

Apache-2.0 requires preserving copyright and license notices, and forwarding
any `NOTICE` file the dependency ships. Sherpa-ONNX is the one to check
manually, since it is vendored as a local `.aar` rather than resolved from a
repository — Gradle's license tooling will not see it.

---

## Bundled fonts

Shipped in `res/font/`. Redistribution terms differ, so these are listed
individually:

| Font           | License                   |
| -------------- | ------------------------- |
| Literata       | SIL Open Font License 1.1 |
| Merriweather   | SIL Open Font License 1.1 |
| Lora           | SIL Open Font License 1.1 |
| Crimson Text   | SIL Open Font License 1.1 |
| Source Sans    | SIL Open Font License 1.1 |
| Noto Sans      | SIL Open Font License 1.1 |
| JetBrains Mono | SIL Open Font License 1.1 |
| Outfit         | SIL Open Font License 1.1 |
| Dancing Script | SIL Open Font License 1.1 |
| OpenDyslexic   | SIL Open Font License 1.1 |

The OFL requires the license text to accompany the fonts and forbids selling
the fonts on their own — both fine for a bundled app. It also requires that
any _modified_ version be renamed. If any of these were subset or otherwise
altered, confirm the Reserved Font Name rules still hold.

**Verify these before publishing.** The list is inferred from the font names,
not read from the files — several of these projects have changed license over
their history, and OpenDyslexic in particular has shipped under different
terms across versions.

---

## Downloaded TTS models

Piper and Kokoro voice models are fetched at runtime rather than bundled, so
they are not redistributed by the APK. Their licenses still govern use, and
they vary per voice — some Piper voices carry dataset restrictions that differ
from the Piper codebase itself. Worth surfacing the license of each voice on
the model download screen.

---

## Content

NovelForge does not host or distribute novel content. Text fetched from web
sources belongs to its authors and rightsholders. No license granted here
extends to it.

---

## What GPL-3.0 requires of this project

Practical checklist, not a restatement of the license:

- **`LICENSE` contains the full GPL-3.0 text.** Not a summary, not a link.
- **Source must accompany binaries.** Publishing the APK on GitHub Releases
  from a public repo satisfies this; the tag the APK was built from must stay
  available.
- **Modified files should say so.** §5(a) asks for prominent notices on changed
  files carrying a date. The ported providers already carry comments naming
  QuickNovel as the origin — worth making sure each one says it was modified.
- **The About screen must show the license and point to the source.** §5(d):
  an interactive program that displays legal notices has to keep displaying
  them. The Settings → About entry is how this is satisfied.
- **No adding restrictions.** Release notes and store listings can't impose
  extra terms on top of the GPL.
- **Contributions are GPL-3.0.** Worth stating in the README so contributors
  know what they're agreeing to.

One thing GPL-3.0 does _not_ require: making the app free of charge, or
accepting every contribution. It constrains distribution terms, not the
project's direction.
