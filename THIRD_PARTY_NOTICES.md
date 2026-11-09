# Third-party notices

NovelForge bundles or derives from the projects below. This file is the
attribution record; it does not replace the individual licenses.

---

## Source plugins

The plugins bundled in the APK (`app/src/main/assets/plugins/`: Royal Road,
Primordial Translation, WTR-Lab, WuxiaClick) are NovelForge's own code. The
WTR-Lab plugin's encrypted-chapter handling follows the approach of
[LNReader](https://github.com/LNReader/lnreader-plugins)'s WTR-Lab plugin
(MIT).

Every other source plugin lives only in
[NovelForge-plugins](https://github.com/AbhinavXT/NovelForge-plugins), is
installed separately by the user, and carries its own license and notices
there.

Releases up to 2.7.x bundled plugins and network helpers derived from
third-party GPL-3.0 code and were licensed GPL-3.0; that still applies to
them. From 2.8.0 the app contains none of that code and is MIT.

---

## Bundled libraries

| Project                                                             | License    | Use                                                     |
| ------------------------------------------------------------------- | ---------- | ------------------------------------------------------- |
| [Sherpa-ONNX](https://github.com/k2-fsa/sherpa-onnx)                | Apache-2.0 | On-device neural TTS (bundled `.aar` + JNI native libs) |
| [OkHttp](https://square.github.io/okhttp/)                          | Apache-2.0 | HTTP client                                             |
| [Okio](https://square.github.io/okio/)                              | Apache-2.0 | I/O (via OkHttp)                                        |
| [Jsoup](https://jsoup.org/)                                         | MIT        | HTML parsing                                            |
| [Rhino](https://github.com/mozilla/rhino)                           | MPL-2.0    | JavaScript engine that runs source plugins              |
| [Gson](https://github.com/google/gson)                              | Apache-2.0 | JSON serialization for backup/restore                   |
| [Anthropic Java SDK](https://github.com/anthropics/anthropic-sdk-java) | MIT | Translation with Claude |
| [ML Kit Translation](https://developers.google.com/ml-kit/language/translation) | Google ML Kit Terms (proprietary) | On-device translation |
| [desugar_jdk_libs](https://github.com/google/desugar_jdk_libs) | GPL-2.0 with Classpath Exception | `java.time` on Android 7 for the Anthropic SDK |
| [Coil](https://coil-kt.github.io/coil/)                             | Apache-2.0 | Image loading                                           |
| [AndroidX / Jetpack Compose](https://developer.android.com/jetpack) | Apache-2.0 | UI, Room, WorkManager, Glance, DataStore, Media         |
| [Kotlin & kotlinx.coroutines](https://kotlinlang.org/)              | Apache-2.0 | Language and concurrency                                |

Rhino is used unmodified, as the published `org.mozilla:rhino` jar; MPL-2.0
asks that its source stays available, which it is at the link above.

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

## What the licenses require of this project

- **MIT:** keep the copyright and license notice (`LICENSE`) with copies of
  the source and the APK. The About screen links to it.
- **Apache-2.0 libraries:** keep their notices; see above.
- **ML Kit and Google Play services:** proprietary Google libraries under the
  Android SDK and ML Kit terms. Fine in an MIT app; forks that redistribute
  the APK take on the same terms.
