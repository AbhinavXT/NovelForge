# Features

This is the long-form documentation for everything NovelForge does. It's organized by surface - reader, library, codex, audio, stats, settings - rather than by version, so it stays useful as a reference even after several releases.

If you just want a quick summary, see the [README](../README.md).

---

## Table of contents

- [Features](#features)
  - [Table of contents](#table-of-contents)
  - [The reader](#the-reader)
    - [Saving your position](#saving-your-position)
    - [Previously in…](#previously-in)
    - [Tap zones](#tap-zones)
    - [Volume key navigation](#volume-key-navigation)
    - [Contents](#contents)
    - [Text replacement](#text-replacement)
    - [Offline dictionaries](#offline-dictionaries)
    - [Vocabulary](#vocabulary)
    - [Keep screen on](#keep-screen-on)
  - [Reading modes](#reading-modes)
    - [Scroll mode](#scroll-mode)
    - [Paged mode](#paged-mode)
  - [Themes and typography](#themes-and-typography)
    - [Themes](#themes)
    - [E-ink mode](#e-ink-mode)
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
  - [Importing and exporting books](#epub-import-and-export)
    - [Import](#import)
    - [Library folder](#library-folder)
    - [Archiving imported books](#archiving-imported-books)
    - [Export](#export)
  - [Translation](#translation)
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
  - [Privacy](#privacy-1)
    - [App lock](#app-lock)
    - [Incognito mode](#incognito-mode)
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

**The text** - paragraphs flow vertically (scroll mode) or horizontally as pages (paged mode). Long-press any paragraph to bookmark it or pull a highlight from it. Select text the normal way to copy or look it up in the inline dictionary, online or [offline](#offline-dictionaries).

**The chrome** - top bar (back, chapter title, contents, find, fullscreen toggle, auto-scroll, TTS) and bottom bar (previous chapter, settings, brightness, scroll-to-top, next chapter). Both can be hidden via the dedicated fullscreen button in the top bar.

**Quick Settings** - a bottom sheet reachable from the palette icon in the bottom bar. This is where you change reading mode, font, theme, font size, line spacing, margins, page-turn animation, tap zones, and a few toggles. See [Settings reference](#settings-reference) for the full list.

### Saving your position

Reading position saves continuously as you scroll. Close the app mid-chapter, come back a week later, you'll be on the same paragraph. Position is per-novel, not per-chapter, so if you re-open a novel from the Library it goes to where you stopped.

The reader also tracks WPM (words per minute) over time and uses it to estimate "minutes left in this chapter" in the top bar. Estimates get more accurate the more you read.

### Previously in…

Open a book you haven't read for a week or more and a sheet catches you up before you carry on:

- **Where you stopped**: the paragraph you were on.
- **Who's been around**: the [codex](#the-codex) names mentioned most in your last three chapters.
- **Your highlights** from those chapters.

It only looks back, never ahead: a name that first appears after where you are is never shown. It's built from text already on your phone (downloaded chapters, imported books), and a section with nothing to show is left out. With none of the three to show, there's no sheet. It doesn't appear when you open the book at a particular spot from search, the codex or a highlight. **Continue reading** closes it.

### Tap zones

What a tap on the text does is configurable. Quick Settings → Tap Zones, four layouts:

- **Sides** (default, the classic behavior) - left 30% goes back, right 30% goes forward, the middle toggles the chrome.
- **Forward** - tap anywhere to advance; a center box toggles the chrome. Made for one-handed reading with either thumb.
- **L-shaped** - the top strip goes back, everything else goes forward, with a chrome toggle punched through the center. Familiar if you've used manga readers.
- **Off** - taps only show or hide the chrome, for swipe-and-volume-key readers.

The same zone geometry applies in both scroll and paged mode, so switching modes doesn't reshuffle your muscle memory. Every layout keeps some path to the chrome - you can't strand yourself in fullscreen. Buttons inside the text (and long-press selection) are separate gesture paths and always work regardless of layout.

### Volume key navigation

Off by default. Turn it on in Quick Settings → More → Volume Key Navigation. With it on:

- **Volume up** → scroll up (scroll mode) / previous page (paged mode)
- **Volume down** → scroll down / next page

Audio focus is handled correctly so phone calls, podcasts, and music keep working normally when you're not actively in the reader.

### Contents

The list icon in the top bar opens the book's table of contents, or its chapter list when it has none (web novels, plain text, and EPUBs imported by earlier versions of the app). The entry you're reading is marked, and the list opens scrolled to it. Tap an entry to go there: within the chapter on screen it just scrolls or turns the page; other chapters open at the entry's place.

### Text replacement

Change what the reader shows without touching the book: fix a name a translation keeps spelling three ways, swap a term you'd rather read differently, or remove a site watermark pasted into every chapter.

**Add one:** select the text → **Replace**, then type what to show instead, or leave it empty to remove the text. Choose **This book** (the default) or **All books**. To see, edit, or delete your rules: Quick Settings → **Text Replacements**.

Matching works like the [pronunciation dictionary](#pronunciation-dictionary): whole words only ("Li" doesn't match inside "Lisa"), any case, longest rule first, and one pass, so a replacement is never replaced again. Symbols are matched anywhere. The replacement follows the case of what it replaces: a rule "he" → "she" shows "She" at the start of a sentence and "SHE" in capitals. When a book's own rule and an all-books rule match the same text, the book's rule wins. Removing text also removes the space beside it, and a paragraph that ends up empty takes no space on the page.

**What it affects:** the text in both reading modes, in-book find (it searches what you see), and TTS, which reads the replaced text and then applies the pronunciation dictionary. Highlights you make on replaced text save the text you saw, and still land in the right place if you later change or delete the rule.

**Changing a replacement:** rules work on the book's own text, so selecting a word you already replaced and tapping Replace edits that rule instead of adding a second one.

**What it doesn't:** the stored text, so [library search](#full-text-library-search), the codex, chapter titles, and [EPUB export](#export) still see the original. Rules are included in [backups](#backup-and-restore).

### Offline dictionaries

Select a word and tap **Define**. Your offline dictionaries are checked first, and if none has the word it's looked up online in your [dictionary language](#settings-reference). With a dictionary that covers your book, lookups need no connection and send nothing anywhere.

**Add one:** Settings → Offline Dictionaries → **Add dictionary**. NovelForge reads the StarDict format: pick a single `.zip`, `.tar.gz`, or `.tar`, or select the `.ifo`, `.idx`, and `.dict` files together (`.idx.gz` and `.dict.dz` are fine, and `.syn` too if there is one). `.tar.xz` and `.tar.bz2` archives have to be extracted first. No dictionaries come with the app; [FreeDict](https://freedict.org/downloads/) has free ones for many language pairs.

When several dictionaries have the word, the sheet shows up to five entries from each, under the dictionary's name. Matching ignores case and surrounding punctuation, but not word endings: "houses" is only found if the dictionary lists it or maps it to "house" in its `.syn` file. Images and sounds in entries are skipped.

Dictionaries stay on this device. They aren't in [backups](#backup-and-restore), so keep the original file if you'll want it again.

**Names from the codex.** When the word you Define is a character, place or faction [the codex](#the-codex) knows, the sheet shows that first: what kind of name it is and the chapter it first appears in, with **Open in codex** to go straight to its entry (mentions and your highlights of it). The same [spoiler guard](#the-spoiler-guard) applies, so a name you haven't reached yet never shows. Selecting part of a name works too: "Wei" finds "Li Wei". The codex has to have been scanned for this to work.


### Vocabulary

Keep the words you look up. In the Define sheet, tap **Save word**: the word is saved with its definition, the book and chapter, and a sentence from that chapter that uses it. Tap again (**Saved to vocabulary**) to remove it. Saving a word you already have keeps the original card.

Library → the translate icon opens **Vocabulary**: every saved word, newest first. Remove one with the bin icon (there's an Undo).

**Review** (top right, with how many are due) runs through the due words as flashcards: the word and its sentence, **Show meaning**, then **Again** or **Got it**. Got it moves the word further out each time: 1 day, 3, 7, 14, then every 30 days. Again sends it back to the start and shows it once more before the session ends. New words are due straight away.

The sentence is the first one in the chapter that uses the word as a whole word, which may not be the exact spot you selected. Vocabulary is in [backups](#backup-and-restore).

### Keep screen on

Quick Settings → More → Keep Screen On. The screen won't dim or sleep while the reader is open. Auto-disabled when you leave the reader.

---

## Reading modes

Two modes. Switch between them in Quick Settings → Reading Mode.

### Scroll mode

Continuous vertical scroll, the default. The whole chapter is one long page; you scroll. Reaching the end of a chapter carries straight on into the next, up to 50 chapters in a row; after that, or if the next chapter can't load (offline, say), the chapter ends with Next and Previous buttons. Best for long reading sessions where you don't want to be interrupted by page-turns.

**Auto-scroll.** Tap the play icon in the top bar (next to the headphones icon) to start a continuous, teleprompter-style drift. The text scrolls down on its own at a configurable speed.

- **Tap anywhere on the text area** to pause. Tap again to resume.
- **Tap the play icon again** to stop entirely.
- **Speed** is configurable in Quick Settings → Auto-Scroll Speed (20–200 px/sec). Last-used speed is remembered across sessions.
- **Chapter end behavior:** auto-advances to the next chapter, then pauses for 2 seconds at the top of the new chapter before resuming. If there is no next chapter, auto-scroll stops.
- **Mutually exclusive with TTS** - starting TTS stops auto-scroll, and the auto-scroll button is disabled while TTS is playing.
- **Backgrounding the app** pauses auto-scroll. It does not resume automatically when you return - tap the screen to resume.

### Paged mode

Horizontal page-turn, like a real book. The chapter is split into discrete pages.

- **Swipe left/right** to turn pages, or tap according to your [tap-zone layout](#tap-zones).
- **Page-turn animation** is configurable in Quick Settings → Page Turn Animation: slide, fade, page curl, or none. None turns pages instantly from taps and volume keys too.

Auto-scroll is not available in paged mode (the button is hidden when paged is active).

---

## Themes and typography

12 themes, 8 fonts plus your own, all switchable from Quick Settings. Changes apply immediately and are saved per-app, not per-novel.

### Themes

Four light:

- **Paper** - warm off-white, the default
- **Sepia** - beige with brown text, classic e-reader feel
- **Solari** - bright yellow paper
- **E-ink** - pure black on white, no greys for an e-ink screen to dither

Eight dark:

- **Dark** - dark grey background, white text
- **AMOLED** - pure black, true-black for OLED screens
- **Nord** - cool blue-grey, popular dev color scheme
- **Dracula** - dark purple-tinged
- **Gruvbox** - muted retro
- **Catppuccin** - soft pastel-on-dark
- **Navy** - deep navy blue
- **Grey** - neutral mid-grey

Each theme defines its own background, ink, and accent colors. The reading-stats share card uses its own pinned color scheme that doesn't follow the reader theme - by design, so shared cards look consistent across users.

### E-ink mode

For e-ink phones and tablets (Boox, Hisense, Bigme and similar). Settings → Appearance → **E-ink mode**:

- No screen transitions, no touch ripples
- Scroll-mode taps, volume keys, find and TTS follow jump straight to the spot instead of scrolling there
- Paged-mode page turns are instant

Turning it on also switches the app and the reader to the **E-ink** theme and the reader to paged mode with no page animation. You can change any of these afterwards; turning e-ink mode off leaves them as they are. Auto-scroll still scrolls smoothly, so it will smear on e-ink.

### Reader fonts

Eight fonts in three categories:

**Serif**

- Literata (default) - designed by Google for long-form reading
- Lora - calligraphic feel
- Merriweather - high-contrast, designed for screens
- Crimson Text - book-typography style

**Sans-serif**

- Source Sans 3 - Adobe's open-source sans
- Noto Sans - Google's universal sans

**Accessibility**

- OpenDyslexic - heavier bottoms on letters, designed for readers with dyslexia

**Monospace**

- JetBrains Mono - for the hell of it; some users like reading code-shaped text

**Your own fonts**

Quick Settings → Font → **Add font** imports a TTF or OTF file, which is picked at once and listed above the built-in fonts. Remove one with its delete icon; the reader goes back to the built-in font you had. Imported fonts stay on this device: they aren't in backups, so after a restore the reader uses the built-in font until you add yours again. Only the font's regular style is used, and bold and italics are drawn from it, as with the built-in fonts.

### Sizing

Quick Settings → Typography section:

- **Font size:** 12–32 pt, ±1 step
- **Line spacing:** 1.2× to 2.4×, slider
- **Paragraph spacing:** 0–32 dp, slider
- **Horizontal margins:** 8–40 dp, slider
- **Justify text**, **Hyphenate words**, **Paragraph indent:** toggles

All of these are saved per-app and apply in both scroll and paged mode. New novels open with your last-used settings.

Hyphenation is off by default. Words break by the rules of the book's language: an imported EPUB that names its language uses it, and anything else follows your device language. To change it, turn on hyphenation and tap **Book language** under the switch (library books only). The language is stored with the book, carried by backups and by moving to another source. Some languages have no hyphenation rules on Android. Headings are never hyphenated.

**This book only.** Quick Settings → **Font, size and theme for this book only** gives the book its own look: font, size, theme and colours, line and paragraph spacing, margins, justify, hyphenation and indent. Changes you make while it's on stay with this book; turn it off and the book goes back to the app's look. Reading mode, tap zones, volume keys and auto-scroll speed stay app-wide. The switch only appears for books in your library, and the book's look is included in [backups](#backup-and-restore).


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

Highlights are tied to the paragraph + offset within it. If a source updates a chapter and the text changes, highlights on changed paragraphs are preserved but may shift visually - the underlying offsets don't auto-correct because we can't tell whether the change is meaningful or cosmetic.

Highlights are included in backup/restore. To take them elsewhere, Library → long-press a novel → **Export highlights & notes** shares a Markdown file with your highlights, their notes and your bookmark notes, in reading order under each chapter. They're also cross-referenced by [the codex](#the-codex) - open a character and see every line you've marked about them.

**All highlights.** The quote icon at the top of the Library opens every highlight in your library, newest first, with chips to narrow it to one book. Tap one to open the reader at it. On the home screen, **From Your Highlights** shows one past highlight a day, a different one each day.


---

## Search

Two ranges: your whole library, or the chapter in front of you.

### Full-text library search

**Open it:** Library → the search-in-books icon in the header (distinct from the title filter field below it).

Every downloaded chapter is indexed on-device the moment it's downloaded - imports, deletions, and re-downloads keep the index current automatically, with no background service. Type a name, a phrase, half a memory:

- Results are grouped by book, with the matching text bolded in a snippet of context.
- The last word you type matches as a prefix, so results appear while you're still typing ("cultiv" matches "cultivation").
- Tap a result and the reader opens **at that paragraph**, not just that chapter.

Only downloaded chapters are searchable - the index is built from what's on your device, which is also why it works on a plane. [Archived](#archiving-imported-books) books are left out until they're opened again. Results are capped at 200 hits per query; if a common word maxes that out, add a second word.

### In-book find

**Open it:** the find icon in the reader's top bar. The top bar swaps for a find bar: a text field, a match counter, next/previous arrows, and close.

- Every occurrence on the page gets a subtle mark; the active match is emphasized so it's visible on any theme, light or AMOLED.
- Next/previous wrap around, and the IME's search key also advances.
- Works in both reading modes. In scroll mode it searches everything currently loaded - including next chapters that infinite scroll has stitched in. In paged mode it searches the current chapter and turns pages to each match.
- Minimum two characters; closing the bar clears all marks.

One quirk to know: while TTS is playing, the paragraph currently being read aloud shows the spoken-sentence highlight instead of find marks. Everywhere else on screen, find wins.

For the whole book at once, use library search - in-book find is deliberately close-range.

---

## The codex

The feature for the question every long-series reader asks: _"wait - who is this again?"_

**Open it:** novel detail screen → the codex icon in the top bar.

### Character codex

NovelForge scans your downloaded chapters and builds an index of every recurring name - characters, but also places and factions ("Azure Cloud Sect" is codex-worthy too). The list is ordered by how often each name appears, with a filter field on top.

Each entry shows:

- **First appearance** - "since Ch. 4", so you know how long they've been around
- **Mention counts** - total occurrences and how many distinct chapters they appear in
- **A presence sparkline** - the shape of their presence across the whole book at a glance: introduced early, vanished for 400 chapters, back for the finale arc
- **Every mention** - chapter by chapter, each with a snippet. Tap one and the reader opens at that exact paragraph.
- **Your highlights** - any highlight whose text mentions the name is surfaced on their entry

Detection is heuristic and runs entirely on your phone: capitalization patterns, multi-word name merging ("Elder Chen", "Old Man Zhao"), stop-word and generic-title filtering. No cloud, no model download, no network.

### The spoiler guard

The codex cannot spoil you, and that's structural rather than a filter you have to trust:

- The list hides any entry whose first appearance is past your current reading position - a character you haven't met **doesn't exist yet**.
- Mention lookups are capped at your position in the query itself - a character you have met can't leak future scenes.
- The guard is on by default whenever you have reading progress, and toggleable when you _want_ to peek. The chip shows exactly where the cutoff sits ("up to Ch. 312").

### Relationship graph

**Open it:** the graph icon in the codex top bar.

Who appears with whom, drawn as a live map. Characters are nodes sized by how often they appear; two characters are connected when they share chapters, with the line's weight showing how many. The layout is force-drawn, so factions cluster together and loners drift to the edges - the protagonist's web is usually unmistakable at a glance.

- **Tap a character** to select them: their bonds light up, everything else dims.
- **Tap a second character** for the pair view: every chapter the two share, each one tap from reading the scene (the reader opens at a paragraph mentioning both, when one exists).
- **Pinch to zoom, drag to pan.** Node and label sizes stay constant while zoom spreads the layout, which keeps dense books readable.

The graph is built only from chapters behind your bookmark - the same spoiler guard as the codex - so it literally grows as you read. Finishing an arc redraws the map.

To stay a map rather than a hairball, the graph shows the top 25 characters and the 60 strongest bonds, and ignores pairs that share fewer than 2 chapters.

### Scanning

The first visit to a novel's codex offers a scan. Scans are:

- **On-device and streaming** - chapters are processed one at a time, so even a 2,000-chapter novel never strains memory. A progress bar shows chapter N of M.
- **Incremental** - the app remembers the highest chapter scanned per novel. Hitting refresh after downloading new chapters processes only the new ones.
- **Rebuildable** - a full rebuild is offered from the empty state, useful after re-downloading chapters with fixed text.

Mentions, snippets, and the graph's bonds aren't stored - they're served live by the same full-text index that powers library search, so there's nothing to go stale.

### Honest limitations

- Name detection is English-oriented. Romanized translations (the typical web novel case) are the sweet spot; untranslated CJK text won't tokenize.
- Name variants are separate entries: if a translator writes "Xiulan" in some chapters and "Xiu Lan" in others, the codex sees two people. Merging variants is on the roadmap.
- Detection is heuristic, so the occasional non-name sneaks in (chapter-title words, frequently capitalized phrases). The mention list makes these easy to identify - and they tend to rank low.

---

## Library

The library is your collection of novels you've added. Find it in the bottom navigation bar.

**Adding a novel:** browse a source (Search tab → pick a source → search/browse), tap a novel, tap "Add to Library". Or import an EPUB (see below).

**Sorting:** sort by last read, title, chapters, or recently added. The control is in the library top bar.

**Searching inside books:** the search-in-books icon in the header opens [full-text search](#full-text-library-search) across everything you've downloaded. The filter field below it matches titles only.

**Update badges:** when a source has new chapters since you last opened a novel, the library card shows an "N new" badge. Tap into the novel and the new chapters are at the bottom of the chapter list.

**Continue reading card:** the home screen shows the most-recently-read novel at the top with a single tap to resume. Below it, a 3-cover strip of your library.

**Status:** every book can have a status: Reading, Plan to read, On hold, Completed or Dropped. The filter chips under the search field show one status at a time. Long-press a novel → **Set status** to change it. The app also sets a few for you: opening a book that has no status, or is Plan to read, makes it Reading; reaching the end of the last chapter makes it Completed, but only for an imported book or one its source calls complete, never an ongoing web novel. A status you chose yourself (On hold, Dropped) is never changed. Books you read before statuses existed show under Reading.

**Moving to another source:** when a source dies or falls behind, long-press the novel → **Move to another source**. NovelForge searches your other sources for the title (you can edit the search), you pick the match, and it shows what will happen before anything changes: how many of your chapters exist there, matched by chapter number, and where you'll continue. Your place, bookmarks, highlights, categories, stats, status, the book's own look and its text replacements move with it. Downloaded chapters don't: the new source's text is different, so download them again. The codex is cleared and rebuilds on the next scan. A bookmark in a chapter the new source doesn't have moves to the nearest chapter, the same way your place does, and opens at its top. Bookmarks left behind by a move in 2.3.0 are fixed the same way when you update. A highlight in such a chapter keeps its old chapter, since it marks words in that chapter's text: it still shows in lists, but can't open the reader. Only shown for books from an installed source.


---

## Sources and downloads

NovelForge connects to publicly-accessible web novel sites. The app fetches the page, parses out the chapter content, and renders it in your chosen reader settings.

### Currently supported sources

Every source is a plugin: a small JavaScript file that NovelForge runs in a sandbox. The app ships with these, and keeps them updated from [NovelForge-plugins](https://github.com/AbhinavXT/NovelForge-plugins):

- Royal Road
- ReadNovelFull
- FreeWebNovel
- LibRead
- NovelFull (novelfull.net and novelfull.com)
- PawRead
- Primordial Translation
- WTR-Lab
- FanMTL
- HiraethTranslation
- Light Novel Translations
- Inori (lnori.com)
- MtlNovel
- NoBadNovel
- Novel Light
- Ranobes
- ReadFrom.Net
- ReadHive
- SonicMTL
- Wattpad
- WuxiaBox
- WuxiaClick

**Settings → Source plugins** lists what's installed and lets you remove sources, add other plugin repositories, or install a plugin from a `.js` file. Writing a plugin is documented in [PLUGINS.md](PLUGINS.md).

### Downloading chapters for offline

From a novel's detail screen → "Downloads" tab → "Download all" or pick a range. Chapters are saved locally and read offline.

You can also bulk-download from the Library → long-press novel → Downloads. The download manager runs in the background via WorkManager and survives app restarts. Network type can be restricted to wifi-only in Settings → Downloads.

Downloading also feeds the rest of the app: downloaded chapters are what full-text search indexes and what the codex scans. More downloads, smarter app.

### Update checking

Quietly checks for new chapters in the background when you're on wifi. Off by default. Turn it on in Settings → Updates. Updates show up as badges in the library and as a banner on the novel detail screen.

---

## EPUB import and export

<!-- anchor kept as #epub-import-and-export so existing links still resolve -->

### Import

Use the system file picker (Library → top bar → "Import a book"). Three formats are supported: `.epub`, `.txt`, and `.md`.

**From Anna's Archive.** Library → + → "Search Anna's Archive", or the "Anna's Archive" chip on the Search tab (it carries over what you typed). Results are EPUBs, and can be narrowed by language and type (fiction, non-fiction, comics) or sorted by newest, oldest, largest or smallest. Tapping a result shows its details and any other editions of the same book in the results; "Add to library" downloads it in the background, so you can keep searching or leave the screen. Each result shows its download's progress, a notification follows it, and the finished notification (or the result) opens the book. The first search may show the site's "I'm not a robot" check, which you complete once; it isn't asked again until its cookies expire. Members can enter their secret key (⚙ on that screen) to skip the check and use fast downloads. Without a key, files come from LibGen by the same MD5. Every download is checked against that MD5 before import. The site changes domains often: if search stops working, set a working mirror there too.

**From book catalogs (OPDS).** Library → + → "Browse book catalogs", or the "Catalogs" chip on the Search tab. Project Gutenberg is there from the start; "Add catalog" takes any OPDS address, with one-tap suggestions for Standard Ebooks (Patrons Circle members sign in with their email and a blank password) and self-hosted servers such as Calibre-Web, Kavita and Komga. Browse a catalog's sections, or search it when it supports search, and tap a book to see its details and the EPUB versions on offer (with images first, then smaller ones without). Adding downloads in the background exactly like Anna's Archive: progress on the result and in a notification, and the finished book opens on tap. Catalogs that sign in send the username and password only to the catalog's own address; passwords stay on the device and are left out of backups. Catalogs must use https: Android blocks plain http, and NovelForge doesn't opt out of that.

**EPUB.** The app extracts:

- Cover image
- Chapters, one per spine entry
- Chapter titles, taken from the book's own table of contents, then the chapter's first heading, then its `<title>`
- Author and title metadata
- Description (if present in the EPUB metadata)
- Formatting: italic, bold, underline, strikethrough, superscript and subscript, including styles set through the book's stylesheets (Calibre's class-based italics, for example); headings, centred lines and block quotes
- Pictures inside chapters. The cover isn't repeated, and pages that are only a picture are shown at the start of the next chapter. SVG images aren't shown
- Footnotes: tapping a note marker opens the note in a popup, without turning the page or opening the menu
- The book's table of contents, for the [contents sheet](#contents)

Books that set their whole body in block quotes, as some converted EPUBs do, are shown as normal text rather than indented, and each block is its own paragraph. A book imported before 2.2.0 keeps its old paragraphs until it's removed and imported again, so its bookmarks and highlights stay put. Books that keep each chapter heading in a file of its own, so the contents list points at a page with nothing but "CHAPTER 3", get those chapters' titles and contents lines from the following text. If a book's own contents list still misses most of its chapters, the contents sheet adds them.

Formatting is kept beside the text rather than inside it, so search, highlights, bookmarks, the codex and TTS work on exactly the same text as before. If a chapter's formatting can't be matched to its text, that chapter shows plain text instead. EPUBs imported by earlier versions of the app show plain text until they're [archived and restored](#archiving-imported-books), which keeps your progress. Export and backups don't carry the formatting, pictures or footnotes: a restored book gets them back when its text is restored from the file.

**Plain text and Markdown.** There's no conversion step to EPUB — text files are parsed straight into the same internal shape an EPUB produces, so they behave identically once imported. Chapters are detected by trying five strategies in descending confidence, and the first one that yields at least two chapters wins:

1. **Markdown headings** (`##`) — splits on the shallowest heading level that appears more than once, so a file with one `#` title and many `##` chapters splits on the chapters and uses the `#` as the book title
2. **Setext headings** — a line underlined with `===` or `---`
3. **Chapter lines** — `Chapter 12`, `CHAPTER XIV`, `Part Two`, `Prologue`, `Epilogue`, matched only when they occupy a whole line
4. **Thematic breaks** — `***` or `* * *`, producing numbered parts
5. **Size split** — a file with no structure at all stays one chapter unless it's very long, in which case it breaks at blank lines so no paragraph is ever cut in half

YAML front matter is read for `title`, `author`, and `description`. Markdown formatting (emphasis, links, code spans, list markers) is stripped. Hard-wrapped lines are joined back into paragraphs — without that, a 70-column `.txt` would import as hundreds of one-line paragraphs.

Text before the first heading — a dedication or epigraph — is kept and prepended to chapter one rather than discarded.

**PDF is not supported.** Android has no built-in PDF text extraction, and the standard third-party library has been unmaintained since 2023 with known CVEs. PDF also stores page layout rather than text, so extraction produces markedly worse results than `.txt` or `.md`. Converting to EPUB first (Calibre does this well) gives a much better import.

EPUBs and online novels live side-by-side in the same library. There's no separate "local books" section. The app distinguishes them internally for chapter-update logic (EPUBs don't get update checks).

### Library folder

If you already keep books on the device — for another reader app, or synced from a computer — point NovelForge at that folder instead of importing files one by one: Settings → Library Folder → **Choose folder**, then Library → **+** → **Scan library folder**.

- Subfolders are followed (up to six levels deep), since author and series folders are the norm. A single scan handles up to 5,000 files.
- Each scan imports only files it hasn't seen. Files are recognized by their location, and by their bytes, so the same book sitting in two folders is imported once.
- The folder is read-only to NovelForge. Nothing is moved, renamed, or deleted, so sharing it with another reader app is safe.
- Removing a scanned book from the library remembers it as unwanted, so the next scan doesn't bring it back. Settings → Library Folder → **Re-import N removed files** undoes that.
- Leaving the Library screen stops a scan. Nothing is lost: the next scan starts where this one stopped.

**Import new books archived** (switch, off by default). New books from the folder are stored [archived](#archiving-imported-books): their chapter list, cover, and details go into the library, but their text stays in the file until you open the book. Turn this on for folders of hundreds or thousands of books, where importing everything in full would put a second copy of the library on the device. The switch applies to scans, not to files you import by hand.

### Archiving imported books

An imported book is stored in full: its text, plus a search index over it, comes to roughly one and a half times the size of the file. When you already keep that file on the device, that's a second copy. Archiving drops the stored text and reads it from the file again when you open the book.

**What stays:** everything that's yours. Reading progress, bookmarks, highlights and notes, categories, codex entries, reading stats, the cover, the chapter list.

**What changes:** the first time you open an archived book, it takes a moment longer while the text is read back from the file. Until then, [full-text search](#full-text-library-search) doesn't find words in it. Books from online sources can't be archived; deleting their downloads does the same job.

**Archive one book:** Library → long-press the book → **Archive (free up space)**. Archived books show a small badge on their cover. **Restore from file** in the same menu brings the text back without opening the book, and opening it, exporting it, or listening to it with TTS restores it automatically.

**Archive all imported books:** Settings → Storage → **Archive all imported books**. Works through every imported book still holding its text, one at a time, then [compacts the database](#giving-the-space-back). You can leave the screen while it runs; **Stop** ends it after the current book. Each book is archived completely or not at all, so stopping — or the app being closed — leaves nothing half-done, and running it again carries on with what's left. At the end, a summary says how many books were archived and why any were left alone.

#### Why a book might not be archived

Bookmarks, highlights, and your reading position point at chapter numbers and paragraphs. A restore that came back with different chapters would quietly move every one of them, so archiving checks first that the file still reads back as exactly the text in the library. Books that fail the check keep their text:

- **No file on record** — books imported by hand before NovelForge remembered where files came from. Import the same file again: it's recognized as a duplicate, linked to the existing book, and the book becomes archivable. Your progress isn't touched.
- **File missing or unreadable** — moved, renamed, deleted, or on storage that isn't mounted.
- **File changed** — edited, replaced with another edition, or read differently by a newer version of the app.

Hand-picked files only stay readable for as long as Android lets the app keep its permission on them. Android caps that at 512 files (128 before Android 11); past that, hand-picked imports are stored in full and can't be archived. Books from the [library folder](#library-folder) aren't affected by this limit, so for a large library, use the folder.

#### When the file has gone

If an archived book's file can't be opened, opening the book explains why and offers **Locate file**: pick the file wherever it is now, and the book is restored from it and remembers the new location.

If the file you pick reads differently from the book that was archived — a different edition, or an edited copy — NovelForge says so before doing anything. **Restore anyway** uses it regardless; your bookmarks and position are kept, but may land in slightly different places.

#### Giving the space back

Deleting data from a database doesn't make its file smaller on its own: the space is kept for reuse, and the search index keeps old entries until it's reorganized. Settings → Storage shows the database's size and how much can be reclaimed, and **Compact** rewrites it so the freed space goes back to the device. Archive all compacts automatically when it finishes; after archiving books one at a time, compact by hand.

Compacting a large library takes a minute or two. It needs free storage about the size of what's left in the database afterwards, and briefly that much memory; when the library still stores more text than that comfortably allows (a few hundred MB), it reorganizes the search index and stops there, and says so. Archiving more books, or deleting downloads, makes room for a full compaction.

### Export

Any novel can leave the same way it came in. Library → long-press a novel → **Export as EPUB** → share sheet. What you get:

- A standard **EPUB 3** package with an EPUB 2 table of contents included, so it opens correctly in old and new readers alike (tested against Moon+ Reader, KOReader, and Calibre's expectations).
- The cover image embedded, when the novel has a local one.
- One `<p>` per paragraph, split exactly the way the reader displays them.
- A stable book identifier derived from the novel, so re-exports are recognizably the same book to readers that track identifiers.

Only downloaded chapters are exported (the export works fully offline). Chapters stream into the file one at a time, so exporting a 2,000-chapter novel doesn't strain memory. And it round-trips: an exported EPUB re-imports into NovelForge with the same chapters, text, and cover - useful for archiving before pruning downloads, or for moving a book to a dedicated e-reader.

---

## Translation

Read raw Chinese, Japanese or Korean novels in English (or any of ~60 languages), or clean up machine-translated English, with names that stay the same from chapter to chapter.

### Turning it on

On a library book, tap the translate icon on its page, or Quick Settings → Translation in the reader, and switch on **Translate this book**. From then on each chapter is translated the first time you open it and kept, so it's only translated (and paid for) once. A chapter you were reading online is saved as downloaded once translated. **Translate N downloaded chapters** does the rest of what's downloaded in one go, while the app stays open.

Translated text replaces the chapter's text, so search, TTS, audiobooks, highlights, EPUB export and the codex all work on the translation. Paragraphs stay paragraph for paragraph, so bookmarks and your place stay put. An EPUB's italics and footnotes aren't shown on translated chapters. **Restore original text** puts every translated chapter back and switches translation off.

### Engines

Settings → Translation:

- **AI, with your key.** Claude, OpenAI, Gemini, DeepSeek, OpenRouter, or any other OpenAI-compatible address (https only). Best quality, and it handles both raw text and bad MTL. Chapter text goes to the provider you picked, who bills you. Claude defaults to `claude-opus-5-5`; for the others, type the model name your provider gives you. The key stays on the phone and is left out of backups.
- **On-device.** Google's offline translator, about 30 MB per language pair, downloaded the first time. Free and private but rougher, and it can't improve English that's already English: on an English book it only applies the glossary. The source language comes from the book's language setting, else from its script. Some phones need Google Play services for the model download.

### Glossary

Each book keeps a glossary: a name or term as it appears in the original, how to translate it, and a note such as "female, she/her". The AI fills it in as it meets recurring names, and every later chapter is translated with it, which is what stops "Lin Feng" turning into "Lin Fung" fifty chapters later. Edit or add terms from the Translation sheet; one you set is never overwritten. Changes apply to chapters translated afterwards; restore and translate again to redo earlier ones.

### Limits

- A failed chapter (no key, no connection, a refusal) is shown untranslated with a message saying why.
- Re-downloading a chapter or restoring an archived book brings back the original text; with translation on it's translated again when opened.
- Restore the original text before archiving a translated imported book: archiving checks the stored text against the file.
- After translating a book, rebuild its codex so it finds the translated names.

## Text-to-speech

Three engine options. Pick from the headphones icon in the reader → settings cog → Voice.

### System TTS

Uses your device's built-in text-to-speech engine. Comes with whatever voices Android shipped on your phone - usually 1–2 per language. Quality varies wildly by manufacturer.

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

- **Play / pause / skip paragraph** - standard transport controls
- **Speed** - 0.5× to 2.0×
- **Pitch** - engine-dependent; system TTS often ignores this
- **Volume** - independent of system media volume
- **Sentence pause** - extra silence between sentences (0–500 ms)
- **Paragraph pause** - extra silence between paragraphs (0–2000 ms)
- **Sleep timer** - end of chapter, 15/30/45/60 minutes

The reader auto-scrolls to keep the currently-spoken paragraph in view, and highlights the current sentence in the accent color.

### Foreground service

TTS runs as a foreground service so it keeps playing when the screen is off or you switch apps — including when you leave the reader screen entirely. Playback stops only when you stop it: the reader's stop button, the notification's stop action, or backing out of the reader.

The notification is a full media player: novel cover as artwork, chapter as the title, novel as the byline, and previous / play-pause / next / stop controls. It's bound to a `MediaSession`, so on Android 13 and later the system renders its own player UI from it — the one with the output switcher — and hardware media keys, headset buttons, Bluetooth controls, and Android Auto all work.

Audio focus is requested properly, so phone calls pause TTS and TTS pauses when other media starts. A partial wake lock is held while playing: a foreground service keeps the process alive but not the CPU, and `AudioTrack` has no equivalent of `MediaPlayer.setWakeMode()`, so without one the device could suspend between sentences while the next was being generated.

There's no seek bar. Sentence count isn't time, and presenting it as a clock would be inaccurate; progress is shown as text instead.

---

## Pronunciation dictionary

Neural TTS engines sometimes mangle proper nouns - character names, place names, fictional terms. The pronunciation dictionary lets you fix them.

**Open it:** Quick Settings → More → Pronunciation Dictionary.

**Add an entry:**

- _Word_ - what's in the text
- _Speak as_ - how the engine should say it (try phonetic spellings, e.g. "AY-lin-druh" for "Aelindra")

The dictionary applies before text reaches the TTS engine. Replacements are case-insensitive but preserve word boundaries (so "Lin" doesn't get replaced inside "Linda"). All rules are applied in a single pass, so a replacement can never be re-matched by another rule, and longer entries win over shorter ones — "Li Wei" beats "Li".

**Skipping instead of substituting.** Leave _Speak as_ empty and the word is removed from the spoken text rather than replaced. This is how you silence things the engine reads aloud by name — scraped chapters are full of `*emphasis*` markers, scene-break rules, and mojibake that come out as "asterisk" or worse.

Rules whose entry contains no letters or digits are treated as symbols and matched anywhere, not just at word boundaries — necessary because a symbol usually sits flush against a letter, as in `*emphasis*`.

**Skip common symbols** adds a preset covering `*`, `_`, `~`, `^`, `|`, backtick, `#`, bullets, arrows, and the Unicode replacement character. It deliberately excludes `.` `,` `!` `?` `;` `:` and quotes: engines don't verbalise those, they use them for intonation and pauses. Removing `?` doesn't silence anything — it flattens the rising pitch of a question. You can still add them by hand if you want.

**Per-engine override:** some entries work better for system TTS than for Piper, or vice versa. The dictionary is currently shared across engines - entries you add affect all of them. If this becomes a problem in practice, per-engine dictionaries are on the roadmap.

**Export/Import:** the whole dictionary serializes to a single JSON file. Useful for sharing across devices, or sharing pronunciations for a specific novel with other readers (the JSON is portable).

---

## Audiobook generation

Generate an `.m4b` audiobook from any novel in your library.

**Start:** novel detail screen → menu → "Export as M4B".

You'll be asked:

- **Chapter range** - all, or a contiguous range
- **Voice** - any installed Piper or Kokoro voice (system TTS not supported here; the audio quality wouldn't be worth the file size)
- **Speed and pitch** - same controls as the reader's TTS

The export runs in the background. You can keep using the app. When done, the M4B lands in `Downloads/NovelForge/`.

**M4B specifics:**

- Container is MP4, audio is AAC (LC profile), 128 kbps mono, 22050 Hz sample rate. This is what audiobook players expect.
- Chapter markers (Nero `chpl` atoms) are written, so apps like Smart Audiobook Player and BookFunnel show the correct chapter boundaries.
- File size depends on total runtime. As a rough rule: ~1 MB per minute of audio. A 200-chapter novel can be 8+ hours = 500+ MB. The app warns before generating very large files.

The export uses Android's `MediaCodec` for AAC encoding and `MediaMuxer` for the MP4 container - no FFmpeg dependency, no native binaries beyond what Sherpa-ONNX needs for the voice model itself.

---

## Reading stats

Find the stats screen via Settings → Reading Stats, or tap the streak indicator on the home screen.

### What's tracked

- **Words read** - counted as you scroll past each paragraph (not as you load the chapter, so just-opened-and-closed doesn't count)
- **Chapters finished** - a chapter counts as finished when you scroll to the bottom or advance to the next chapter
- **Time spent** - measured as foreground time with the reader screen visible
- **Daily streak** - consecutive days where you read at least one chapter or paragraph

### Display

The stats screen shows:

- **This week** - words, time, chapters, with week-over-week percentage deltas
- **Today** - words, time, chapters
- **12-week heatmap** - one cell per day, intensity scales with that day's word count
- **Personal records** - longest session, best day, longest streak

**Daily goal.** Under Today, **Set a daily goal** picks a target from 10 to 90 minutes a day. The stats screen and the home screen then show how far you are into it. Time counts when a chapter session ends, so the chapter you're reading now is added when you leave it.


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
- Your [daily goal](#reading-stats), if you set one: "12 / 30 min today" (not at the smallest size)

Tap the widget to jump straight back into the reader at the saved paragraph.

The widget refreshes itself when you save reading position, finish a reading session, or change the goal, so it's always current. It does _not_ poll periodically - battery cost is essentially zero.

---

## Backup and restore

**Backup:** Settings → Backup → Export. Produces a single ZIP containing:

- Reader settings (theme, font, sizes, modes, tap zones, toggles)
- TTS settings (per-engine: speed, pitch, voice, pauses)
- Library (novels, chapter metadata - but not chapter _content_; downloads are not in the backup)
- Reading progress (per-novel paragraph positions)
- Bookmarks and highlights with notes
- Pronunciation dictionary
- Text replacement rules
- Translation glossaries and which books translate (API keys are not included)
- Vocabulary, with each word's review progress
- Reading stats history

The ZIP can be saved to Drive, Dropbox, anywhere.

**Restore:** Settings → Backup → Import. Pick the ZIP. Confirms before overwriting current data. Restore is destructive - it replaces your current library and settings, doesn't merge.

**What's not in the backup:**

- Downloaded chapter content (re-download after restore - the metadata knows what to fetch)
- The text of imported books. A restore without chapter text brings them back [archived](#archiving-imported-books): opening one reads its text from the file, if the file is still on the device. Books that were archived when the backup was made stay archived.
- The full-text search index and the codex (both are derived from downloaded content, so they rebuild after you re-download - the search index automatically, the codex with one tap of the scan button)
- Voice models (re-download from Settings → Voice models)
- Generated audiobooks (regenerate if needed)

These are excluded because they're large and re-derivable. Including voice models would push backup ZIPs to 500 MB+.

**Cross-device:** backup ZIPs are platform-portable across Android versions. The internal schema is versioned, so restoring a v1.6 backup on v1.8 works (forward-compatible). Backwards compatibility (v1.8 backup on v1.6) is not guaranteed; downgrades are not a supported flow.

---

## Privacy

Settings → Privacy.

### App lock

Asks for your fingerprint, face, PIN, pattern or password, whichever your phone's screen lock uses, before showing the app. It asks when the app starts and when you come back after more than a minute away. A quick trip to a file picker or the share sheet doesn't count. Unlocking puts you back exactly where you were. While the lock is on, Android 13 and later show a blank card for the app in Recents instead of your page.

It needs a screen lock set in Android's settings. If you remove the screen lock later, the app opens without asking, so you can never be locked out. NovelForge stores no PIN of its own; the check is entirely Android's.

### Incognito mode

While it's on, nothing you read is recorded: no [history](#reading-stats), no stats, no streak, no minutes toward your daily goal. Your place in each book still saves, so you don't lose it. The home screen shows a reminder while it's on. Turn it off and recording picks up from the next chapter you open.

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

### Library folder

- **Choose folder / Change folder / Forget** - see [Library folder](#library-folder)
- **Import new books archived** - toggle (off by default)
- **Re-import N removed files** - shown when scanned books were removed from the library

### Storage

- **Library database** - current size, and how much compacting would reclaim
- **Archive all imported books** - see [Archiving imported books](#archiving-imported-books)
- **Compact** - see [Giving the space back](#giving-the-space-back)

### Downloads

- **Download on wifi only** - toggle
- **Concurrent downloads** - 1–5
- **Download path** - internal storage (default) or external SD card if available

### Updates

- **Check for new chapters** - toggle (off by default)
- **Update check interval** - daily / every 3 days / weekly
- **Update notifications** - toggle (default on if check is on)

### TTS

- **Default engine** - system / Piper / Kokoro
- **Voice models** - opens the model downloader screen
- **Default voice per engine** - picker for each
- **Default speed, pitch, volume** - sliders
- **Sentence and paragraph pauses** - sliders

### Stats

- **Reset stats** - destructive, asks for confirmation. Wipes the stats database but doesn't touch reading progress or library.

### Backup

- **Export backup** - generate a ZIP, see [Backup and restore](#backup-and-restore)
- **Import backup** - pick a ZIP

### About

- **Version** - current version number, tap for changelog
- **Source code** - opens the GitHub repo
- **Report a bug** - opens GitHub Issues
- **Pronunciation Dictionary** - separate screen for managing entries (also reachable from Quick Settings)
- **License** - GPL-3.0, with a link to the full text and to the source repository

GPL-3.0 §5(d) requires an interactive program that displays legal notices to keep displaying them, so this entry isn't decorative — it's how the app satisfies that. It must show the license and point users to the corresponding source.

---

## Things that aren't features

Some explicit non-features so expectations match reality:

- **No cloud sync.** The app is offline-first and stays that way. Backup/restore is the closest equivalent - manual, on your terms.
- **No cloud AI unless you add it.** The codex, the relationship graph, and search are local heuristics plus a local index. The only thing that ever sends text out is [translation](#translation) with an AI provider, and only for books you switch it on for, with your own key.
- **No account system.** Never. There is no NovelForge server.
- **No iOS version.** App Store rules conflict with the way this app is built.
- **No Play Store distribution.** Sideload only, see the [README](../README.md) for install steps.
- **No analytics, no telemetry, no crash reporting.** If the app crashes, it crashes. You can file a bug with the steps to reproduce; that's the feedback channel.
- **No premium tier, no donations.** Open-source, free, no paid features. If you want to support the project, support a web novelist.

---

For changes between versions, see the in-app Changelog (Settings → About → Changelog) or the [GitHub Releases page](https://github.com/abhinavxt/novelforge/releases).
