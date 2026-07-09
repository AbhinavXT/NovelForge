# Features

This is the long-form documentation for everything NovelForge does. It's organized by surface — reader, library, codex, audio, stats, settings — rather than by version, so it stays useful as a reference even after several releases.

If you just want a quick summary, see the [README](../README.md).

---

## Table of contents

- [Features](#features)
  - [Table of contents](#table-of-contents)
  - [The reader](#the-reader)
    - [Saving your position](#saving-your-position)
    - [Tap zones](#tap-zones)
    - [Volume key navigation](#volume-key-navigation)
    - [Keep screen on](#keep-screen-on)
  - [Reading modes](#reading-modes)
    - [Scroll mode](#scroll-mode)
    - [Paged mode](#paged-mode)
  - [Themes and typography](#themes-and-typography)
    - [Themes](#themes)
    - [Reader fonts](#reader-fonts)
    - [Sizing](#sizing)
  - [Bookmarks and highlights](#bookmarks-and-highlights)
    - [Bookmarks](#bookmarks)
    - [Highlights](#highlights)
  - [Search](#search)
    - [Full-text library search](#full-text-library-search)
    - [In-book find](#in-book-find)
  - [The codex](#the-codex)
    - [Character codex](#character-codex)
    - [The spoiler guard](#the-spoiler-guard)
    - [Relationship graph](#relationship-graph)
    - [Scanning](#scanning)
    - [Honest limitations](#honest-limitations)
  - [Library](#library)
  - [Sources and downloads](#sources-and-downloads)
    - [Currently supported sources](#currently-supported-sources)
    - [Downloading chapters for offline](#downloading-chapters-for-offline)
    - [Update checking](#update-checking)
  - [EPUB import and export](#epub-import-and-export)
    - [Import](#import)
    - [Export](#export)
  - [Text-to-speech](#text-to-speech)
    - [System TTS](#system-tts)
    - [Piper](#piper)
    - [Kokoro](#kokoro)
    - [Controls](#controls)
    - [Foreground service](#foreground-service)
  - [Pronunciation dictionary](#pronunciation-dictionary)
  - [Audiobook generation](#audiobook-generation)
  - [Reading stats](#reading-stats)
    - [What's tracked](#whats-tracked)
    - [Display](#display)
    - [Share card](#share-card)
    - [Privacy](#privacy)
  - [Continue-reading widget](#continue-reading-widget)
  - [Backup and restore](#backup-and-restore)
  - [Settings reference](#settings-reference)
    - [Reader](#reader)
    - [Downloads](#downloads)
    - [Updates](#updates)
    - [TTS](#tts)
    - [Stats](#stats)
    - [Backup](#backup)
    - [About](#about)
  - [Things that aren't features](#things-that-arent-features)

---

## The reader

The reader is where you'll spend most of your time. The screen has three layers:

**The text** — paragraphs flow vertically (scroll mode) or horizontally as pages (paged mode). Long-press any paragraph to bookmark it or pull a highlight from it. Select text the normal way to copy or look it up in the inline dictionary.

**The chrome** — top bar (back, chapter title, find, fullscreen toggle, auto-scroll, TTS) and bottom bar (previous chapter, settings, brightness, scroll-to-top, next chapter). Both can be hidden via the dedicated fullscreen button in the top bar.

**Quick Settings** — a bottom sheet reachable from the palette icon in the bottom bar. This is where you change reading mode, font, theme, font size, line spacing, margins, page-turn animation, tap zones, and a few toggles. See [Settings reference](#settings-reference) for the full list.

### Saving your position

Reading position saves continuously as you scroll. Close the app mid-chapter, come back a week later, you'll be on the same paragraph. Position is per-novel, not per-chapter, so if you re-open a novel from the Library it goes to where you stopped.

The reader also tracks WPM (words per minute) over time and uses it to estimate "minutes left in this chapter" in the top bar. Estimates get more accurate the more you read.

### Tap zones

What a tap on the text does is configurable. Quick Settings → Tap Zones, four layouts:

- **Sides** (default, the classic behavior) — left 30% goes back, right 30% goes forward, the middle toggles the chrome.
- **Forward** — tap anywhere to advance; a center box toggles the chrome. Made for one-handed reading with either thumb.
- **L-shaped** — the top strip goes back, everything else goes forward, with a chrome toggle punched through the center. Familiar if you've used manga readers.
- **Off** — taps only show or hide the chrome, for swipe-and-volume-key readers.

The same zone geometry applies in both scroll and paged mode, so switching modes doesn't reshuffle your muscle memory. Every layout keeps some path to the chrome — you can't strand yourself in fullscreen. Buttons inside the text (and long-press selection) are separate gesture paths and always work regardless of layout.

### Volume key navigation

Off by default. Turn it on in Quick Settings → More → Volume Key Navigation. With it on:

- **Volume up** → scroll up (scroll mode) / previous page (paged mode)
- **Volume down** → scroll down / next page

Audio focus is handled correctly so phone calls, podcasts, and music keep working normally when you're not actively in the reader.

### Keep screen on

Quick Settings → More → Keep Screen On. The screen won't dim or sleep while the reader is open. Auto-disabled when you leave the reader.

---

## Reading modes

Two modes. Switch between them in Quick Settings → Reading Mode.

### Scroll mode

Continuous vertical scroll, the default. The whole chapter is one long page; you scroll. Best for long reading sessions where you don't want to be interrupted by page-turns.

**Auto-scroll.** Tap the play icon in the top bar (next to the headphones icon) to start a continuous, teleprompter-style drift. The text scrolls down on its own at a configurable speed.

- **Tap anywhere on the text area** to pause. Tap again to resume.
- **Tap the play icon again** to stop entirely.
- **Speed** is configurable in Quick Settings → Auto-Scroll Speed (20–200 px/sec). Last-used speed is remembered across sessions.
- **Chapter end behavior:** auto-advances to the next chapter, then pauses for 2 seconds at the top of the new chapter before resuming. If there is no next chapter, auto-scroll stops.
- **Mutually exclusive with TTS** — starting TTS stops auto-scroll, and the auto-scroll button is disabled while TTS is playing.
- **Backgrounding the app** pauses auto-scroll. It does not resume automatically when you return — tap the screen to resume.

### Paged mode

Horizontal page-turn, like a real book. The chapter is split into discrete pages.

- **Swipe left/right** to turn pages, or tap according to your [tap-zone layout](#tap-zones).
- **Page-turn animation** is configurable in Quick Settings → Page Turn Animation: slide, fade, page curl, or none (instant).

Auto-scroll is not available in paged mode (the button is hidden when paged is active).

---

## Themes and typography

11 themes, 8 fonts, all switchable from Quick Settings. Changes apply immediately and are saved per-app, not per-novel.

### Themes

Three light:

- **Paper** — warm off-white, the default
- **Sepia** — beige with brown text, classic e-reader feel
- **Solari** — bright yellow paper

Eight dark:

- **Dark** — dark grey background, white text
- **AMOLED** — pure black, true-black for OLED screens
- **Nord** — cool blue-grey, popular dev color scheme
- **Dracula** — dark purple-tinged
- **Gruvbox** — muted retro
- **Catppuccin** — soft pastel-on-dark
- **Navy** — deep navy blue
- **Grey** — neutral mid-grey

Each theme defines its own background, ink, and accent colors. The reading-stats share card uses its own pinned color scheme that doesn't follow the reader theme — by design, so shared cards look consistent across users.

### Reader fonts

Eight fonts in three categories:

**Serif**

- Literata (default) — designed by Google for long-form reading
- Lora — calligraphic feel
- Merriweather — high-contrast, designed for screens
- Crimson Text — book-typography style

**Sans-serif**

- Source Sans 3 — Adobe's open-source sans
- Noto Sans — Google's universal sans

**Accessibility**

- OpenDyslexic — heavier bottoms on letters, designed for readers with dyslexia

**Monospace**

- JetBrains Mono — for the hell of it; some users like reading code-shaped text

### Sizing

Quick Settings → Typography section:

- **Font size:** 12–32 pt, ±1 step
- **Line spacing:** 1.2× to 2.4×, slider
- **Horizontal margins:** 8–40 dp, slider

All three are saved per-app. New novels open with your last-used settings.

---

## Bookmarks and highlights

### Bookmarks

Long-press any paragraph in the reader → "Bookmark" in the menu. A bookmark records:

- The novel and chapter
- The paragraph index
- A snippet of the paragraph text (for previews)
- An optional note you can edit later

Open the bookmark list from the novel detail screen. Tap a bookmark to jump to that paragraph in the reader.

Bookmarks are stored in the local database and included in [backup/restore](#backup-and-restore).

### Highlights

Select text the normal way (long-press → drag handles), then tap "Highlight" in the toolbar. Choose from 6 colors. The highlight persists across reads and is searchable from the novel detail screen.

Highlights are tied to the paragraph + offset within it. If a source updates a chapter and the text changes, highlights on changed paragraphs are preserved but may shift visually — the underlying offsets don't auto-correct because we can't tell whether the change is meaningful or cosmetic.

Highlights are included in backup/restore and exportable to JSON. They're also cross-referenced by [the codex](#the-codex) — open a character and see every line you've marked about them.

---

## Search

Two ranges: your whole library, or the chapter in front of you.

### Full-text library search

**Open it:** Library → the search-in-books icon in the header (distinct from the title filter field below it).

Every downloaded chapter is indexed on-device the moment it's downloaded — imports, deletions, and re-downloads keep the index current automatically, with no background service. Type a name, a phrase, half a memory:

- Results are grouped by book, with the matching text bolded in a snippet of context.
- The last word you type matches as a prefix, so results appear while you're still typing ("cultiv" matches "cultivation").
- Tap a result and the reader opens **at that paragraph**, not just that chapter.

Only downloaded chapters are searchable — the index is built from what's on your device, which is also why it works on a plane. Results are capped at 200 hits per query; if a common word maxes that out, add a second word.

### In-book find

**Open it:** the find icon in the reader's top bar. The top bar swaps for a find bar: a text field, a match counter, next/previous arrows, and close.

- Every occurrence on the page gets a subtle mark; the active match is emphasized so it's visible on any theme, light or AMOLED.
- Next/previous wrap around, and the IME's search key also advances.
- Works in both reading modes. In scroll mode it searches everything currently loaded — including next chapters that infinite scroll has stitched in. In paged mode it searches the current chapter and turns pages to each match.
- Minimum two characters; closing the bar clears all marks.

One quirk to know: while TTS is playing, the paragraph currently being read aloud shows the spoken-sentence highlight instead of find marks. Everywhere else on screen, find wins.

For the whole book at once, use library search — in-book find is deliberately close-range.

---

## The codex

The feature for the question every long-series reader asks: _"wait — who is this again?"_

**Open it:** novel detail screen → the codex icon in the top bar.

### Character codex

NovelForge scans your downloaded chapters and builds an index of every recurring name — characters, but also places and factions ("Azure Cloud Sect" is codex-worthy too). The list is ordered by how often each name appears, with a filter field on top.

Each entry shows:

- **First appearance** — "since Ch. 4", so you know how long they've been around
- **Mention counts** — total occurrences and how many distinct chapters they appear in
- **A presence sparkline** — the shape of their presence across the whole book at a glance: introduced early, vanished for 400 chapters, back for the finale arc
- **Every mention** — chapter by chapter, each with a snippet. Tap one and the reader opens at that exact paragraph.
- **Your highlights** — any highlight whose text mentions the name is surfaced on their entry

Detection is heuristic and runs entirely on your phone: capitalization patterns, multi-word name merging ("Elder Chen", "Old Man Zhao"), stop-word and generic-title filtering. No cloud, no model download, no network.

### The spoiler guard

The codex cannot spoil you, and that's structural rather than a filter you have to trust:

- The list hides any entry whose first appearance is past your current reading position — a character you haven't met **doesn't exist yet**.
- Mention lookups are capped at your position in the query itself — a character you have met can't leak future scenes.
- The guard is on by default whenever you have reading progress, and toggleable when you _want_ to peek. The chip shows exactly where the cutoff sits ("up to Ch. 312").

### Relationship graph

**Open it:** the graph icon in the codex top bar.

Who appears with whom, drawn as a live map. Characters are nodes sized by how often they appear; two characters are connected when they share chapters, with the line's weight showing how many. The layout is force-drawn, so factions cluster together and loners drift to the edges — the protagonist's web is usually unmistakable at a glance.

- **Tap a character** to select them: their bonds light up, everything else dims.
- **Tap a second character** for the pair view: every chapter the two share, each one tap from reading the scene (the reader opens at a paragraph mentioning both, when one exists).
- **Pinch to zoom, drag to pan.** Node and label sizes stay constant while zoom spreads the layout, which keeps dense books readable.

The graph is built only from chapters behind your bookmark — the same spoiler guard as the codex — so it literally grows as you read. Finishing an arc redraws the map.

To stay a map rather than a hairball, the graph shows the top 25 characters and the 60 strongest bonds, and ignores pairs that share fewer than 2 chapters.

### Scanning

The first visit to a novel's codex offers a scan. Scans are:

- **On-device and streaming** — chapters are processed one at a time, so even a 2,000-chapter novel never strains memory. A progress bar shows chapter N of M.
- **Incremental** — the app remembers the highest chapter scanned per novel. Hitting refresh after downloading new chapters processes only the new ones.
- **Rebuildable** — a full rebuild is offered from the empty state, useful after re-downloading chapters with fixed text.

Mentions, snippets, and the graph's bonds aren't stored — they're served live by the same full-text index that powers library search, so there's nothing to go stale.

### Honest limitations

- Name detection is English-oriented. Romanized translations (the typical web novel case) are the sweet spot; untranslated CJK text won't tokenize.
- Name variants are separate entries: if a translator writes "Xiulan" in some chapters and "Xiu Lan" in others, the codex sees two people. Merging variants is on the roadmap.
- Detection is heuristic, so the occasional non-name sneaks in (chapter-title words, frequently capitalized phrases). The mention list makes these easy to identify — and they tend to rank low.

---

## Library

The library is your collection of novels you've added. Find it in the bottom navigation bar.

**Adding a novel:** browse a source (Search tab → pick a source → search/browse), tap a novel, tap "Add to Library". Or import an EPUB (see below).

**Sorting:** sort by last read, title, chapters, or recently added. The control is in the library top bar.

**Searching inside books:** the search-in-books icon in the header opens [full-text search](#full-text-library-search) across everything you've downloaded. The filter field below it matches titles only.

**Update badges:** when a source has new chapters since you last opened a novel, the library card shows an "N new" badge. Tap into the novel and the new chapters are at the bottom of the chapter list.

**Continue reading card:** the home screen shows the most-recently-read novel at the top with a single tap to resume. Below it, a 3-cover strip of your library.

---

## Sources and downloads

NovelForge connects to publicly-accessible web novel sites. The app fetches the page, parses out the chapter content, and renders it in your chosen reader settings.

### Currently supported sources

- RoyalRoad
- ReadNovelFull
- FreeWebNovel
- LibRead
- NovelFullNet
- PawRead
- PrimordialTranslation

The list grows when users request sources and the scrapers prove stable. The most up-to-date list is in the in-app source browser.

### Downloading chapters for offline

From a novel's detail screen → "Downloads" tab → "Download all" or pick a range. Chapters are saved locally and read offline.

You can also bulk-download from the Library → long-press novel → Downloads. The download manager runs in the background via WorkManager and survives app restarts. Network type can be restricted to wifi-only in Settings → Downloads.

Downloading also feeds the rest of the app: downloaded chapters are what full-text search indexes and what the codex scans. More downloads, smarter app.

### Update checking

Quietly checks for new chapters in the background when you're on wifi. Off by default. Turn it on in Settings → Updates. Updates show up as badges in the library and as a banner on the novel detail screen.

---

## EPUB import and export

### Import

Drag any `.epub` file into the app, or use the system file picker (Library → top bar → "Import EPUB"). The app extracts:

- Cover image
- Chapters (split by EPUB's navigation, not by manual splitting)
- Author and title metadata
- Description (if present in the EPUB metadata)

EPUBs and online novels live side-by-side in the same library. There's no separate "local books" section. The app distinguishes them internally for chapter-update logic (EPUBs don't get update checks).

### Export

Any novel can leave the same way it came in. Library → long-press a novel → **Export as EPUB** → share sheet. What you get:

- A standard **EPUB 3** package with an EPUB 2 table of contents included, so it opens correctly in old and new readers alike (tested against Moon+ Reader, KOReader, and Calibre's expectations).
- The cover image embedded, when the novel has a local one.
- One `<p>` per paragraph, split exactly the way the reader displays them.
- A stable book identifier derived from the novel, so re-exports are recognizably the same book to readers that track identifiers.

Only downloaded chapters are exported (the export works fully offline). Chapters stream into the file one at a time, so exporting a 2,000-chapter novel doesn't strain memory. And it round-trips: an exported EPUB re-imports into NovelForge with the same chapters, text, and cover — useful for archiving before pruning downloads, or for moving a book to a dedicated e-reader.

---

## Text-to-speech

Three engine options. Pick from the headphones icon in the reader → settings cog → Voice.

### System TTS

Uses your device's built-in text-to-speech engine. Comes with whatever voices Android shipped on your phone — usually 1–2 per language. Quality varies wildly by manufacturer.

Pros: zero download, works offline, supports any language Android does.
Cons: sounds robotic on most devices.

### Piper

Neural TTS engine. Voices are downloaded individually in-app (Settings → Voice models). Each voice is 20–60 MB.

Pros: natural-sounding, works offline once downloaded, low latency.
Cons: download size, English-heavy voice library.

### Kokoro

Higher-quality neural engine (also via Sherpa-ONNX). Larger model (~80–250 MB depending on voice), better prosody.

Pros: closest to human-quality.
Cons: bigger download, slower on older phones.

### Controls

In the TTS panel:

- **Play / pause / skip paragraph** — standard transport controls
- **Speed** — 0.5× to 2.0×
- **Pitch** — engine-dependent; system TTS often ignores this
- **Volume** — independent of system media volume
- **Sentence pause** — extra silence between sentences (0–500 ms)
- **Paragraph pause** — extra silence between paragraphs (0–2000 ms)
- **Sleep timer** — end of chapter, 15/30/45/60 minutes

The reader auto-scrolls to keep the currently-spoken paragraph in view, and highlights the current sentence in the accent color.

### Foreground service

TTS runs as a foreground service so it keeps playing when the screen is off or you switch apps. The notification has standard media controls (play/pause/skip). Audio focus is requested properly so phone calls pause TTS, and TTS pauses when other media (podcasts, music) starts.

---

## Pronunciation dictionary

Neural TTS engines sometimes mangle proper nouns — character names, place names, fictional terms. The pronunciation dictionary lets you fix them.

**Open it:** Quick Settings → More → Pronunciation Dictionary.

**Add an entry:**

- _Word_ — what's in the text
- _Pronunciation_ — how the engine should say it (try phonetic spellings, e.g. "AY-lin-druh" for "Aelindra")

The dictionary applies before text reaches the TTS engine. Replacements are case-insensitive but preserve word boundaries (so "Lin" doesn't get replaced inside "Linda").

**Per-engine override:** some entries work better for system TTS than for Piper, or vice versa. The dictionary is currently shared across engines — entries you add affect all of them. If this becomes a problem in practice, per-engine dictionaries are on the roadmap.

**Export/Import:** the whole dictionary serializes to a single JSON file. Useful for sharing across devices, or sharing pronunciations for a specific novel with other readers (the JSON is portable).

---

## Audiobook generation

Generate an `.m4b` audiobook from any novel in your library.

**Start:** novel detail screen → menu → "Export as M4B".

You'll be asked:

- **Chapter range** — all, or a contiguous range
- **Voice** — any installed Piper or Kokoro voice (system TTS not supported here; the audio quality wouldn't be worth the file size)
- **Speed and pitch** — same controls as the reader's TTS

The export runs in the background. You can keep using the app. When done, the M4B lands in `Downloads/NovelForge/`.

**M4B specifics:**

- Container is MP4, audio is AAC (LC profile), 128 kbps mono, 22050 Hz sample rate. This is what audiobook players expect.
- Chapter markers (Nero `chpl` atoms) are written, so apps like Smart Audiobook Player and BookFunnel show the correct chapter boundaries.
- File size depends on total runtime. As a rough rule: ~1 MB per minute of audio. A 200-chapter novel can be 8+ hours = 500+ MB. The app warns before generating very large files.

The export uses Android's `MediaCodec` for AAC encoding and `MediaMuxer` for the MP4 container — no FFmpeg dependency, no native binaries beyond what Sherpa-ONNX needs for the voice model itself.

---

## Reading stats

Find the stats screen via Settings → Reading Stats, or tap the streak indicator on the home screen.

### What's tracked

- **Words read** — counted as you scroll past each paragraph (not as you load the chapter, so just-opened-and-closed doesn't count)
- **Chapters finished** — a chapter counts as finished when you scroll to the bottom or advance to the next chapter
- **Time spent** — measured as foreground time with the reader screen visible
- **Daily streak** — consecutive days where you read at least one chapter or paragraph

### Display

The stats screen shows:

- **This week** — words, time, chapters, with week-over-week percentage deltas
- **Today** — words, time, chapters
- **12-week heatmap** — one cell per day, intensity scales with that day's word count
- **Personal records** — longest session, best day, longest streak

### Share card

Tap the share icon in the top right of the stats screen. Generates a 1080×1920 PNG (story-shaped) showing total words read, WPM, current streak, total chapters, and a 14-day activity grid. Pinned to a calm slate-blue accent regardless of which reader theme you have active.

The card is rendered with a fixed density so it looks identical on every device. No watermarks beyond a small "NOVELFORGE" wordmark at the bottom. Save to gallery or share via the system share sheet.

### Privacy

Everything's computed locally in your phone's Room database. There's no analytics service, no server, no remote storage. If you delete the app, the stats are gone (back them up first if you care).

---

## Continue-reading widget

A 2×1 home-screen widget that shows the most-recently-read novel and a "tap to continue" surface. Long-press your home screen → Widgets → NovelForge → Continue Reading.

The widget shows:

- The novel's cover (downsampled to fit the RemoteViews bundle limit)
- The novel title
- Current chapter title
- Reading progress

Tap the widget to jump straight back into the reader at the saved paragraph.

The widget refreshes itself when you save reading position, so it's always current. It does _not_ poll periodically — battery cost is essentially zero.

---

## Backup and restore

**Backup:** Settings → Backup → Export. Produces a single ZIP containing:

- Reader settings (theme, font, sizes, modes, tap zones, toggles)
- TTS settings (per-engine: speed, pitch, voice, pauses)
- Library (novels, chapter metadata — but not chapter _content_; downloads are not in the backup)
- Reading progress (per-novel paragraph positions)
- Bookmarks and highlights with notes
- Pronunciation dictionary
- Reading stats history

The ZIP can be saved to Drive, Dropbox, anywhere.

**Restore:** Settings → Backup → Import. Pick the ZIP. Confirms before overwriting current data. Restore is destructive — it replaces your current library and settings, doesn't merge.

**What's not in the backup:**

- Downloaded chapter content (re-download after restore — the metadata knows what to fetch)
- The full-text search index and the codex (both are derived from downloaded content, so they rebuild after you re-download — the search index automatically, the codex with one tap of the scan button)
- Voice models (re-download from Settings → Voice models)
- Generated audiobooks (regenerate if needed)

These are excluded because they're large and re-derivable. Including voice models would push backup ZIPs to 500 MB+.

**Cross-device:** backup ZIPs are platform-portable across Android versions. The internal schema is versioned, so restoring a v1.6 backup on v1.8 works (forward-compatible). Backwards compatibility (v1.8 backup on v1.6) is not guaranteed; downgrades are not a supported flow.

---

## Settings reference

The full settings screen is reachable from the bottom navigation bar.

### Reader

Most reader settings live in the in-reader Quick Settings sheet (palette icon). The system Settings → Reader section is for things that need to persist before you've opened a novel:

- Default reading mode (scroll/paged)
- Default font
- Default theme
- Default font size, line spacing, margins

Whatever's set here is the starting point for new novels; per-novel changes are saved separately. Tap-zone layout is set from Quick Settings and applies app-wide.

### Downloads

- **Download on wifi only** — toggle
- **Concurrent downloads** — 1–5
- **Download path** — internal storage (default) or external SD card if available

### Updates

- **Check for new chapters** — toggle (off by default)
- **Update check interval** — daily / every 3 days / weekly
- **Update notifications** — toggle (default on if check is on)

### TTS

- **Default engine** — system / Piper / Kokoro
- **Voice models** — opens the model downloader screen
- **Default voice per engine** — picker for each
- **Default speed, pitch, volume** — sliders
- **Sentence and paragraph pauses** — sliders

### Stats

- **Reset stats** — destructive, asks for confirmation. Wipes the stats database but doesn't touch reading progress or library.

### Backup

- **Export backup** — generate a ZIP, see [Backup and restore](#backup-and-restore)
- **Import backup** — pick a ZIP

### About

- **Version** — current version number, tap for changelog
- **Source code** — opens the GitHub repo
- **Report a bug** — opens GitHub Issues
- **Pronunciation Dictionary** — separate screen for managing entries (also reachable from Quick Settings)
- **License** — MIT

---

## Things that aren't features

Some explicit non-features so expectations match reality:

- **No cloud sync.** The app is offline-first and stays that way. Backup/restore is the closest equivalent — manual, on your terms.
- **No cloud AI.** The codex, the relationship graph, and search are local heuristics plus a local index. Nothing you read is sent anywhere to be "analyzed."
- **No account system.** Never. There is no NovelForge server.
- **No iOS version.** App Store rules conflict with the way this app is built.
- **No Play Store distribution.** Sideload only, see the [README](../README.md) for install steps.
- **No analytics, no telemetry, no crash reporting.** If the app crashes, it crashes. You can file a bug with the steps to reproduce; that's the feedback channel.
- **No premium tier, no donations.** Open-source, free, no paid features. If you want to support the project, support a web novelist.

---

For changes between versions, see the in-app Changelog (Settings → About → Changelog) or the [GitHub Releases page](https://github.com/abhinavxt/novelforge/releases).
