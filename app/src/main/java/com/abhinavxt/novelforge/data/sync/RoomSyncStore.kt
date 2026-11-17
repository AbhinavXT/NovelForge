package com.abhinavxt.novelforge.data.sync

import androidx.room.withTransaction
import com.abhinavxt.novelforge.data.LibraryRemover
import com.abhinavxt.novelforge.data.NovelRepository
import com.abhinavxt.novelforge.data.database.AppDatabase
import com.abhinavxt.novelforge.data.database.BookmarkEntity
import com.abhinavxt.novelforge.data.database.CategoryEntity
import com.abhinavxt.novelforge.data.database.HighlightEntity
import com.abhinavxt.novelforge.data.database.NovelCategoryCrossRef
import com.abhinavxt.novelforge.data.database.ReadingProgressEntity
import com.abhinavxt.novelforge.data.database.ReadingStatEvent
import com.abhinavxt.novelforge.data.database.NovelEntity
import com.abhinavxt.novelforge.data.database.SyncRecordEntity
import com.abhinavxt.novelforge.data.epub.LocalBookArchive
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.MessageDigest

/**
 * The Room-backed [SyncLocalStore]. This part reads: [snapshot], [shadow], [saveShadow].
 * Keys and payload shapes are listed next to the code that builds them.
 * Chapter positions are always chapter NUMBERS (chapter ids embed the device-local novel id).
 */
class RoomSyncStore(
    private val db: AppDatabase,
    /** Sync-driven book deletes go through the same code as the Library screen. Give it a Context in the app. */
    private val remover: LibraryRemover = LibraryRemover(NovelRepository(db)),
) : SyncLocalStore {

    /** Books linked by [apply] whose link is not in the saved shadow yet; [saveShadow] consumes them. */
    private val pendingLinks = HashMap<String, String>()

    /** novelId -> book key as computed by the latest [snapshot] of this transaction; [apply] consumes it. */
    private var snapshotKeys: Map<String, String> = emptyMap()

    override suspend fun <T> transaction(block: suspend () -> T): T {
        snapshotKeys = emptyMap()
        pendingLinks.clear() // links of a rolled-back transaction must not leak into the next one
        return db.withTransaction { block() }
    }

    override suspend fun apply(changes: List<LocalChange>): ApplyReport {
        val keys = snapshotKeys
        snapshotKeys = emptyMap()
        return Applier(keys).run(changes)
    }

    /**
     * Writes remote changes; the exact inverse of [snapshot]. Order: book deletes, book upserts,
     * categories, other upserts, other deletes, category deletes. A child whose book (or, for
     * positions, whose chapter row) is not on this device is NOT applied and nothing is created.
     * Runs inside the transaction the engine opened.
     */
    private inner class Applier(private val snapshotKeys: Map<String, String>) {
        private val novelDao = db.novelDao()
        private val applied = HashMap<Pair<String, String>, AppliedRow>()
        private val notApplied = HashSet<Pair<String, String>>()
        private val novels = HashMap<String, NovelEntity>()
        private val linked = HashMap<String, String>()   // bookKey -> novel id, from the saved shadow
        private val keyToId = HashMap<String, String>()
        /** Keys of book records the shadow holds as applied; a local book with such a key is already synced. */
        private val shadowKeys = HashSet<String>()
        private var categories: HashMap<String, Long>? = null // lower-cased name -> id
        private var statKeys: HashSet<Triple<String, Long, Int>>? = null
        private val effects = ArrayList<suspend () -> Unit>()

        suspend fun run(changes: List<LocalChange>): ApplyReport {
            for (n in novelDao.getAllNovelsOnce()) novels[n.id] = n
            val shadowBooks = db.syncDao().byKind(SyncKind.BOOK)
            for ((k, id) in linkedBooks(shadowBooks)) if (id in novels) linked[k] = id
            for (row in shadowBooks) if (!row.deleted && row.localId != null) shadowKeys += row.key
            for (n in novels.values) BookIdentity.bookKey(n)?.let { keyToId.putIfAbsent(it, n.id) }
            keyToId.putAll(linked)
            for ((k, id) in pendingLinks) if (id in novels) keyToId[k] = id

            for (c in changes.sortedBy { rank(it) }) {
                val r = c.record
                val row = when (c) {
                    is LocalChange.Upsert -> upsert(r)
                    is LocalChange.Delete -> { delete(r); AppliedRow(null, null) }
                }
                if (row != null) applied[r.kind to r.key] = row else notApplied += r.kind to r.key
            }
            return ApplyReport(applied, notApplied, effects.toList())
        }

        private fun rank(c: LocalChange): Int {
            val k = c.record.kind
            return if (c is LocalChange.Delete) when (k) {
                SyncKind.BOOK -> 0
                SyncKind.CATEGORY -> 5
                else -> 4
            } else when (k) {
                SyncKind.BOOK -> 1
                SyncKind.CATEGORY -> 2
                else -> 3
            }
        }

        private fun JsonObject?.str(name: String): String? =
            this?.get(name)?.takeIf { it.isJsonPrimitive }?.asString
        private fun JsonObject?.int(name: String): Int? = str(name)?.toIntOrNull()
        private fun JsonObject?.long(name: String): Long? = str(name)?.toLongOrNull()

        private fun parts(key: String): List<String> = key.split(SyncKeys.SEP).map { SyncKeys.unescape(it) }

        /** The local novel a child record belongs to, or null if the book is not on this device. */
        private fun bookOf(key: String): String? =
            keyToId[SyncKeys.parentBookKey(key)]?.takeIf { it in novels }

        private suspend fun upsert(r: SyncRecord): AppliedRow? {
            val p = r.payload
            return when (r.kind) {
                SyncKind.BOOK -> upsertBook(r)
                SyncKind.CATEGORY -> {
                    val id = ensureCategory(r.key, p.str("name") ?: r.key, p.long("createdAt"))
                    val local = novelDao.getAllCategoriesOnce().first { it.id == id }
                    AppliedRow(r.key, digest("category", local.name))
                }
                SyncKind.STATUS -> {
                    val id = bookOf(r.key) ?: return null
                    val status = p.str("status") ?: return null
                    novelDao.setReadingStatus(id, status)
                    AppliedRow(id, digest("status", status))
                }
                SyncKind.LOOK -> {
                    val id = bookOf(r.key) ?: return null
                    val json = p.str("json") ?: return null
                    novelDao.setReaderSettingsJson(id, json)
                    AppliedRow(id, digest("look", json))
                }
                SyncKind.PROGRESS -> {
                    val id = bookOf(r.key) ?: return null
                    val n = p.int("chapterNumber") ?: return null
                    val para = p.int("paragraph") ?: 0
                    // The progress row needs a chapter id; an online book's ids come from its source,
                    // so without the fetched chapter row there is nothing to point at yet.
                    val ch = db.chapterDao().getChapterRefByNumber(id, n) ?: return null
                    db.readingProgressDao().saveProgress(
                        ReadingProgressEntity(
                            id, ch.id, n, para, p.int("chaptersRead") ?: 0,
                            p.long("lastReadAt") ?: System.currentTimeMillis(),
                        )
                    )
                    AppliedRow(id, digest("progress", n, para))
                }
                SyncKind.BOOKMARK -> {
                    val id = bookOf(r.key) ?: return null
                    val n = p.int("chapterNumber") ?: return null
                    val para = p.int("paragraph") ?: return null
                    val ch = db.chapterDao().getChapterRefByNumber(id, n) ?: return null
                    val note = p.str("note")
                    val dao = db.bookmarkDao()
                    // Lowest id first: the order the snapshot uses to pick the row of a duplicate.
                    val existing = dao.getBookmarksForNovelOnce(id).sortedBy { it.id }
                        .firstOrNull { it.chapterNumber == n && it.paragraphIndex == para }
                    if (existing != null) dao.updateNote(existing.id, note)
                    else dao.insertBookmark(
                        BookmarkEntity(
                            novelId = id, chapterId = ch.id, chapterUrl = ch.url, chapterNumber = n,
                            chapterTitle = ch.title, paragraphIndex = para,
                            textSnippet = p.str("snippet") ?: "", note = note,
                            createdAt = p.long("createdAt") ?: System.currentTimeMillis(),
                        )
                    )
                    AppliedRow(id, digest("bookmark", note))
                }
                SyncKind.HIGHLIGHT -> {
                    val id = bookOf(r.key) ?: return null
                    val n = p.int("chapterNumber") ?: return null
                    val para = p.int("paragraph") ?: return null
                    val start = p.int("start") ?: return null
                    val end = p.int("end") ?: return null
                    val ch = db.chapterDao().getChapterRefByNumber(id, n) ?: return null
                    val note = p.str("note")
                    val color = p.str("color") ?: "YELLOW"
                    val now = System.currentTimeMillis()
                    val updatedAt = p.long("updatedAt") ?: now
                    val dao = db.highlightDao()
                    val existing = dao.getHighlightsForNovelOnce(id).sortedBy { it.id }.firstOrNull {
                        it.chapterNumber == n && it.paragraphIndex == para && it.startOffset == start && it.endOffset == end
                    }
                    if (existing != null) {
                        dao.updateNote(existing.id, note, updatedAt)
                        dao.updateColor(existing.id, color, updatedAt)
                    } else dao.insertHighlight(
                        HighlightEntity(
                            novelId = id, chapterId = ch.id, chapterNumber = n, chapterTitle = ch.title,
                            paragraphIndex = para, startOffset = start, endOffset = end,
                            selectedText = p.str("text") ?: "", note = note, color = color,
                            createdAt = p.long("createdAt") ?: now, updatedAt = updatedAt,
                        )
                    )
                    AppliedRow(id, digest("highlight", color, note))
                }
                SyncKind.CATLINK -> {
                    val id = bookOf(r.key) ?: return null
                    val ck = parts(r.key).getOrNull(1) ?: return null
                    val cat = ensureCategory(ck, p.str("category") ?: ck, null)
                    db.categoryDao().insertCrossRefs(listOf(NovelCategoryCrossRef(id, cat)))
                    AppliedRow(id, digest("catlink"))
                }
                SyncKind.STAT -> {
                    val id = bookOf(r.key) ?: return null
                    val at = p.long("completedAt") ?: return null
                    val n = p.int("chapterNumber") ?: -1
                    val chapterId = if (n < 0) "" else (db.chapterDao().getChapterRefByNumber(id, n) ?: return null).id
                    val keys = statKeys ?: HashSet<Triple<String, Long, Int>>().also { set ->
                        for (s in novelDao.getStatSyncRows()) set += Triple(s.novelId, s.completedAt, s.chapterNumber ?: -1)
                        statKeys = set
                    }
                    if (keys.add(Triple(id, at, n))) {
                        db.readingStatDao().insertEvent(
                            ReadingStatEvent(
                                novelId = id, chapterId = chapterId, wordsRead = p.int("words") ?: 0,
                                readingTimeMs = p.long("ms") ?: 0L, completedAt = at,
                            )
                        )
                    }
                    AppliedRow(id, digest("stat"))
                }
                else -> null
            }
        }

        private suspend fun upsertBook(r: SyncRecord): AppliedRow? {
            val p = r.payload
            val id: String
            if (r.key.startsWith("o:")) {
                id = SyncKeys.unescape(r.key.removePrefix("o:"))
                val local = novels[id]
                if (local == null) {
                    val total = p.int("totalChapters") ?: 0
                    val n = NovelEntity(
                        id = id, title = p.str("title") ?: id, author = p.str("author") ?: "",
                        coverUrl = p.str("coverUrl"), description = p.str("description") ?: "",
                        source = p.str("source") ?: "", status = p.str("status") ?: "",
                        totalChapters = total, addedToLibraryAt = p.long("addedToLibraryAt") ?: System.currentTimeMillis(),
                        previousTotalChapters = total, language = p.str("language"),
                    )
                    novelDao.insertNovel(n)
                    novels[id] = n
                } else if (BookIdentity.isImported(local)) {
                    return null
                } else {
                    // Only fill gaps: a local cover, description or downloaded text is never overwritten.
                    val merged = local.copy(
                        title = local.title.ifBlank { p.str("title") ?: local.title },
                        author = local.author.ifBlank { p.str("author") ?: "" },
                        description = local.description.ifBlank { p.str("description") ?: "" },
                        coverUrl = local.coverUrl?.takeIf { it.isNotBlank() } ?: p.str("coverUrl"),
                        language = local.language ?: p.str("language"),
                        totalChapters = maxOf(local.totalChapters, p.int("totalChapters") ?: 0),
                    )
                    if (merged != local) { novelDao.updateNovel(merged); novels[id] = merged }
                }
            } else {
                val claimed = linked.filter { it.key != r.key }.values.toSet()
                // A local book that already carries a different key (linked in the shadow, saved in the
                // shadow under its own key, or keyed by this merge's snapshot) is never linked to this
                // record: two records on one book would later tombstone one of them and delete the book
                // elsewhere. Such a remote book stays not applied.
                fun carriesOtherKey(n: NovelEntity): Boolean {
                    val own = snapshotKeys[n.id] ?: BookIdentity.bookKey(n)?.takeIf { it in shadowKeys }
                    return own != null && own != r.key
                }
                // The book this key already points at is checked like any candidate: a book whose
                // fingerprint changed still carries its first key, and a second key must not be linked to it.
                val bound = keyToId[r.key]
                    ?.let { novels[it] }
                    ?.takeIf { BookIdentity.isImported(it) && !carriesOtherKey(it) }
                    ?.id
                id = bound
                    ?: BookIdentity.match(r, novels.values.filter { it.id !in claimed && !carriesOtherKey(it) })
                    ?: return null
            }
            keyToId[r.key] = id
            linked[r.key] = id
            pendingLinks[r.key] = id
            return AppliedRow(id, digest("book"))
        }

        private suspend fun categoryIds(): HashMap<String, Long> = categories ?: HashMap<String, Long>().also { m ->
            for (c in novelDao.getAllCategoriesOnce().sortedBy { it.id }) {
                val k = c.name.trim().lowercase()
                if (k.isNotEmpty()) m.putIfAbsent(k, c.id)
            }
            categories = m
        }

        private suspend fun ensureCategory(key: String, name: String, createdAt: Long?): Long {
            val ids = categoryIds()
            val k = key.trim().lowercase()
            return ids[k] ?: db.categoryDao().insertCategory(
                CategoryEntity(name = name.trim().ifEmpty { k }, createdAt = createdAt ?: System.currentTimeMillis())
            ).also { ids[k] = it }
        }

        private suspend fun delete(r: SyncRecord) {
            when (r.kind) {
                SyncKind.BOOK -> {
                    val id = keyToId[r.key]?.takeIf { it in novels } ?: return
                    effects += remover.removeRows(id).let { fx -> suspend { fx.afterCommit() } }
                    novels.remove(id)
                    keyToId.entries.removeAll { it.value == id }
                    linked.entries.removeAll { it.value == id }
                    pendingLinks.entries.removeAll { it.value == id }
                }
                SyncKind.CATEGORY -> {
                    val k = r.key.trim().lowercase()
                    val id = categoryIds()[k] ?: return
                    db.categoryDao().clearCategoryAssignments(id)
                    db.categoryDao().deleteCategory(id)
                    categoryIds().remove(k)
                }
                SyncKind.CATLINK -> {
                    val id = bookOf(r.key) ?: return
                    val cat = categoryIds()[parts(r.key).getOrNull(1)?.trim()?.lowercase()] ?: return
                    db.categoryDao().deleteCrossRef(id, cat)
                }
                SyncKind.BOOKMARK -> {
                    val id = bookOf(r.key) ?: return
                    val q = parts(r.key).drop(1).map { it.toIntOrNull() }
                    if (q.size < 2 || q.any { it == null }) return
                    db.bookmarkDao().getBookmarksForNovelOnce(id)
                        .filter { it.chapterNumber == q[0] && it.paragraphIndex == q[1] }
                        .forEach { db.bookmarkDao().deleteBookmark(it.id) }
                }
                SyncKind.HIGHLIGHT -> {
                    val id = bookOf(r.key) ?: return
                    val q = parts(r.key).drop(1).map { it.toIntOrNull() }
                    if (q.size < 4 || q.any { it == null }) return
                    db.highlightDao().getHighlightsForNovelOnce(id).filter {
                        it.chapterNumber == q[0] && it.paragraphIndex == q[1] && it.startOffset == q[2] && it.endOffset == q[3]
                    }.forEach { db.highlightDao().deleteHighlight(it.id) }
                }
                SyncKind.STATUS -> bookOf(r.key)?.let { novelDao.setReadingStatus(it, null) }
                SyncKind.LOOK -> bookOf(r.key)?.let { novelDao.setReaderSettingsJson(it, null) }
                SyncKind.PROGRESS -> bookOf(r.key)?.let { db.readingProgressDao().deleteProgress(it) }
                else -> Unit // stats are never deleted; unknown kinds are carried only
            }
        }
    }

    // ── Shadow ──────────────────────────────────────────────────────────

    override suspend fun shadow(): List<ShadowEntry> = db.syncDao().all().map { e ->
        ShadowEntry(
            record = SyncRecord(
                e.kind, e.key, e.hlc, e.origin, e.deleted,
                e.payload?.let { JsonParser.parseString(it).asJsonObject },
            ),
            applied = e.localId != null,
            digest = e.digest,
        )
    }

    /**
     * Replaces the table. localId of an applied entry: the local novel id for `book` and the kinds
     * that belong to a book; for `category` and `stat` (no single owner) the record key, used only
     * as a non-null "a local row exists" marker. Applied entries whose book cannot be resolved get
     * the key as marker too, so they stay applied.
     */
    override suspend fun saveShadow(entries: List<ShadowEntry>) {
        val dao = db.syncDao()
        val previousLinks = linkedBooks(dao.byKind(SyncKind.BOOK)) + pendingLinks
        val byKey = HashMap<String, String>()
        for (n in db.novelDao().getAllNovelsOnce()) BookIdentity.bookKey(n)?.let { byKey[it] = n.id }
        val rows = entries.map { en ->
            val r = en.record
            val localId = if (!en.applied || r.deleted) null else when (r.kind) {
                SyncKind.CATEGORY, SyncKind.STAT -> r.key
                else -> {
                    val bk = if (r.kind == SyncKind.BOOK) r.key else SyncKeys.parentBookKey(r.key)
                    previousLinks[bk] ?: byKey[bk] ?: r.key
                }
            }
            SyncRecordEntity(
                r.kind, r.key, r.hlc, r.origin, r.deleted, r.payload?.toString(),
                localId, if (localId == null) null else en.digest,
            )
        }
        dao.deleteAll()
        dao.upsertAll(rows)
        pendingLinks.clear()
        snapshotKeys = emptyMap()
    }

    /** bookKey -> local novel id, for book records linked to a real novel id (not the key marker). */
    private fun linkedBooks(rows: List<SyncRecordEntity>): Map<String, String> =
        rows.filter { !it.deleted && it.localId != null && it.localId != it.key }
            .associate { it.key to it.localId!! }

    /**
     * novelId -> the one key it is reported under, picked deterministically: the linked record with the
     * lowest hlc, ties by key. A novel linked under several keys (legacy damage) always keeps the same one.
     */
    private fun primaryKeys(rows: List<SyncRecordEntity>): Map<String, String> {
        val out = HashMap<String, String>()
        rows.filter { !it.deleted && it.localId != null && it.localId != it.key }
            .sortedWith(compareBy({ it.hlc }, { it.key }))
            .forEach { out.putIfAbsent(it.localId!!, it.key) }
        return out
    }

    // ── Snapshot ────────────────────────────────────────────────────────

    override suspend fun snapshot(): List<LocalRecord> {
        backfillFingerprints()
        val novels = db.novelDao().getAllNovelsOnce()
        // A book keeps the key it was first linked under, even if its fingerprint changed since.
        val shadowBooks = db.syncDao().byKind(SyncKind.BOOK)
        val linkedKeyByNovel = primaryKeys(shadowBooks)
        val keyOf = HashMap<String, String>() // novelId -> bookKey
        val out = ArrayList<LocalRecord>()
        for (n in novels) {
            val key = linkedKeyByNovel[n.id] ?: BookIdentity.bookKey(n) ?: continue
            keyOf[n.id] = key
            out += bookRecords(n, key)
        }
        // A book whose novel still exists is never reported missing, whatever key it is linked under:
        // other keys linked to an existing novel get a presence record too (no children), so they are
        // never tombstoned and the book is never deleted on another device.
        val novelById = novels.associateBy { it.id }
        for (row in shadowBooks) {
            val id = row.localId ?: continue
            if (row.deleted || id == row.key || row.key == keyOf[id]) continue
            val n = novelById[id] ?: continue
            out += bookRecords(n, row.key).first()
        }
        snapshotKeys = HashMap(keyOf)

        // progress: key = book key, payload {chapterNumber, paragraph, chaptersRead, lastReadAt};
        // digest = chapterNumber+paragraph; editedAt = lastReadAt
        for (p in db.novelDao().getAllProgressOnce()) {
            val bk = keyOf[p.novelId] ?: continue
            val payload = JsonObject().apply {
                addProperty("chapterNumber", p.currentChapterNumber)
                addProperty("paragraph", p.paragraphIndex)
                addProperty("chaptersRead", p.chaptersRead)
                addProperty("lastReadAt", p.lastReadAt)
            }
            out += LocalRecord(
                SyncKind.PROGRESS, bk, payload,
                digest("progress", p.currentChapterNumber, p.paragraphIndex),
                editedAt = p.lastReadAt,
            )
        }

        // bookmark: key bk|chapterNumber|paragraph,
        // payload {chapterNumber, chapterTitle, chapterUrl, paragraph, snippet, note, createdAt}; digest = note
        val seen = HashSet<String>()
        for (b in db.bookmarkDao().getAllBookmarksOnce().sortedBy { it.id }) {
            val bk = keyOf[b.novelId] ?: continue
            val key = SyncKeys.child(bk, b.chapterNumber, b.paragraphIndex)
            if (!seen.add(key)) continue
            val payload = JsonObject().apply {
                addProperty("chapterNumber", b.chapterNumber)
                addProperty("chapterTitle", b.chapterTitle)
                addProperty("chapterUrl", b.chapterUrl)
                addProperty("paragraph", b.paragraphIndex)
                addProperty("snippet", b.textSnippet)
                addProperty("note", b.note)
                addProperty("createdAt", b.createdAt)
            }
            out += LocalRecord(SyncKind.BOOKMARK, key, payload, digest("bookmark", b.note), createdAt = b.createdAt)
        }

        // highlight: key bk|chapterNumber|paragraph|start|end, payload {chapterNumber, chapterTitle,
        // paragraph, start, end, text, note, color, createdAt, updatedAt}; digest = color+note; editedAt = updatedAt
        seen.clear()
        for (h in db.highlightDao().getAllHighlightsOnce().sortedBy { it.id }) {
            val bk = keyOf[h.novelId] ?: continue
            val key = SyncKeys.child(bk, h.chapterNumber, h.paragraphIndex, h.startOffset, h.endOffset)
            if (!seen.add(key)) continue
            val payload = JsonObject().apply {
                addProperty("chapterNumber", h.chapterNumber)
                addProperty("chapterTitle", h.chapterTitle)
                addProperty("paragraph", h.paragraphIndex)
                addProperty("start", h.startOffset)
                addProperty("end", h.endOffset)
                addProperty("text", h.selectedText)
                addProperty("note", h.note)
                addProperty("color", h.color)
                addProperty("createdAt", h.createdAt)
                addProperty("updatedAt", h.updatedAt)
            }
            out += LocalRecord(
                SyncKind.HIGHLIGHT, key, payload, digest("highlight", h.color, h.note),
                editedAt = h.updatedAt, createdAt = h.createdAt,
            )
        }

        // category: key = lower-cased trimmed name, payload {name, createdAt}; digest = name
        val catKeyById = HashMap<Long, String>()
        val catKeys = HashSet<String>()
        for (c in db.novelDao().getAllCategoriesOnce().sortedBy { it.id }) {
            val key = c.name.trim().lowercase()
            if (key.isEmpty()) continue
            catKeyById[c.id] = key
            if (!catKeys.add(key)) continue
            val payload = JsonObject().apply { addProperty("name", c.name); addProperty("createdAt", c.createdAt) }
            out += LocalRecord(SyncKind.CATEGORY, key, payload, digest("category", c.name), createdAt = c.createdAt)
        }

        // catlink: key bk|<category key>, payload {category: <name as stored>}; digest = presence
        val catNameById = db.novelDao().getAllCategoriesOnce().associate { it.id to it.name }
        seen.clear()
        for (x in db.novelDao().getAllCrossRefsOnce().sortedWith(compareBy({ it.novelId }, { it.categoryId }))) {
            val bk = keyOf[x.novelId] ?: continue
            val ck = catKeyById[x.categoryId] ?: continue
            val key = SyncKeys.child(bk, ck)
            if (!seen.add(key)) continue
            val payload = JsonObject().apply { addProperty("category", catNameById[x.categoryId] ?: ck) }
            out += LocalRecord(SyncKind.CATLINK, key, payload, digest("catlink"))
        }

        // stat: one record per reading event; key bk|completedAt|chapterNumber (-1 if the chapter row
        // is gone), payload {chapterNumber, words, ms, completedAt}; digest = presence
        seen.clear()
        for (s in db.novelDao().getStatSyncRows()) {
            val bk = keyOf[s.novelId] ?: continue
            // No chapter row: the key would be unstable (-1) and the stat counted twice elsewhere.
            val chapterNumber = s.chapterNumber ?: continue
            val key = SyncKeys.child(bk, s.completedAt, chapterNumber)
            if (!seen.add(key)) continue
            val payload = JsonObject().apply {
                addProperty("chapterNumber", chapterNumber)
                addProperty("words", s.wordsRead)
                addProperty("ms", s.readingTimeMs)
                addProperty("completedAt", s.completedAt)
            }
            out += LocalRecord(SyncKind.STAT, key, payload, digest("stat"), createdAt = s.completedAt)
        }
        return out
    }

    /**
     * book (key o:/t:/h:), payload {id, imported, title, author, source, status, description, totalChapters,
     * coverUrl (online only; an imported cover is a device path), language, textFingerprint, contentHash,
     * addedToLibraryAt, archivedAt}; digest = presence. Plus status (key = book key, payload {status})
     * and look (key = book key, payload {json}); their digest is the value.
     */
    private fun bookRecords(n: NovelEntity, key: String): List<LocalRecord> {
        val imported = BookIdentity.isImported(n)
        val payload = JsonObject().apply {
            addProperty("id", n.id)
            addProperty("imported", imported)
            addProperty("title", n.title)
            addProperty("author", n.author)
            addProperty("source", n.source)
            addProperty("status", n.status)
            addProperty("description", n.description)
            addProperty("totalChapters", n.totalChapters)
            if (!imported) addProperty("coverUrl", n.coverUrl)
            addProperty("language", n.language)
            addProperty(BookIdentity.PAYLOAD_FINGERPRINT, n.textFingerprint)
            addProperty(BookIdentity.PAYLOAD_HASH, n.contentHash)
            addProperty("addedToLibraryAt", n.addedToLibraryAt)
            addProperty("archivedAt", n.archivedAt)
        }
        val list = arrayListOf(
            LocalRecord(
                SyncKind.BOOK, key, payload, digest("book"),
                createdAt = n.addedToLibraryAt,
            )
        )
        n.readingStatus?.let {
            list += LocalRecord(
                SyncKind.STATUS, key, JsonObject().apply { addProperty("status", it) }, digest("status", it),
            )
        }
        n.readerSettingsJson?.let {
            list += LocalRecord(
                SyncKind.LOOK, key, JsonObject().apply { addProperty("json", it) }, digest("look", it),
            )
        }
        return list
    }

    /** Imported books without a fingerprint get one from their stored text, the way the importer computes it. */
    private suspend fun backfillFingerprints() {
        val novels = db.novelDao()
        val chapters = db.chapterDao()
        for (id in novels.getLocalNovelIdsWithoutFingerprint()) {
            val texts = chapters.getStoredTexts(id, Int.MIN_VALUE, Int.MAX_VALUE)
            // Partial text would give a fingerprint that no other device computes.
            if (texts.isEmpty() || texts.size != chapters.getChapterCount(id)) continue
            novels.setFingerprint(id, LocalBookArchive.fingerprint(texts.sortedBy { it.number }.map { it.content }))
        }
    }

    private fun digest(vararg parts: Any?): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (p in parts) md.update(((p?.toString() ?: "\u0001null") + "\u0000").toByteArray(Charsets.UTF_8))
        return md.digest().take(8).joinToString("") { "%02x".format(it) }
    }
}
