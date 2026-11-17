package com.abhinavxt.novelforge.data.sync

import com.abhinavxt.novelforge.data.database.NovelEntity
import com.abhinavxt.novelforge.data.epub.LocalBookArchive

/**
 * How a book is named across devices. Online books: `o:<novelId>`. Imported books have no shared
 * id (each device invents its own `local_...`), so they are named by their text: `t:<fingerprint>`,
 * or `h:<contentHash>` while no fingerprint is known. An imported book with neither has no key and
 * is not synced yet. See docs/state/SYNC_DESIGN.md section 4.
 */
object BookIdentity {
    const val PAYLOAD_FINGERPRINT = "textFingerprint"
    const val PAYLOAD_HASH = "contentHash"

    /** Imported books are the ones the importer tags with the local source (their id starts with "local_" too). */
    fun isImported(n: NovelEntity): Boolean = n.source == LocalBookArchive.LOCAL_SOURCE

    fun bookKey(n: NovelEntity): String? {
        if (!isImported(n)) return "o:" + SyncKeys.escape(n.id)
        n.textFingerprint?.takeIf { it.isNotBlank() }?.let { return "t:" + SyncKeys.escape(it) }
        n.contentHash?.takeIf { it.isNotBlank() }?.let { return "h:" + SyncKeys.escape(it) }
        return null
    }

    /**
     * The local novel id that the remote `book` record [remote] refers to, among [candidates]
     * (books not yet linked to a record), or null. Imported books: fingerprint first, then content
     * hash. Online books: the same novel id.
     */
    fun match(remote: SyncRecord, candidates: List<NovelEntity>): String? {
        if (remote.kind != SyncKind.BOOK || remote.deleted) return null
        val key = remote.key
        if (key.startsWith("o:")) {
            val id = SyncKeys.unescape(key.removePrefix("o:"))
            return candidates.firstOrNull { !isImported(it) && it.id == id }?.id
        }
        val payload = remote.payload
        fun field(name: String): String? =
            payload?.get(name)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
        val fp = field(PAYLOAD_FINGERPRINT)
            ?: key.takeIf { it.startsWith("t:") }?.let { SyncKeys.unescape(it.removePrefix("t:")) }
        val hash = field(PAYLOAD_HASH)
            ?: key.takeIf { it.startsWith("h:") }?.let { SyncKeys.unescape(it.removePrefix("h:")) }
        val imported = candidates.filter { isImported(it) }
        if (!fp.isNullOrBlank()) imported.firstOrNull { it.textFingerprint == fp }?.let { return it.id }
        if (!hash.isNullOrBlank()) imported.firstOrNull { it.contentHash == hash }?.let { return it.id }
        return null
    }
}
