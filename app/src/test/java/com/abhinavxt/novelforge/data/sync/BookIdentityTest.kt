package com.abhinavxt.novelforge.data.sync

import com.abhinavxt.novelforge.data.database.NovelEntity
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookIdentityTest {

    private fun novel(
        id: String,
        source: String = "local",
        fp: String? = null,
        hash: String? = null,
    ) = NovelEntity(
        id = id, title = "T", author = "A", coverUrl = null, description = "", source = source,
        status = "Completed", totalChapters = 1, textFingerprint = fp, contentHash = hash,
    )

    private fun remote(key: String, fp: String? = null, hash: String? = null, deleted: Boolean = false) =
        SyncRecord(
            SyncKind.BOOK, key, 1L, "dev", deleted,
            if (deleted) null else JsonObject().apply {
                fp?.let { addProperty("textFingerprint", it) }
                hash?.let { addProperty("contentHash", it) }
            },
        )

    @Test
    fun keyForms() {
        assertEquals("o:royalroad%7C123", BookIdentity.bookKey(novel("royalroad|123", source = "Royal Road")))
        assertEquals("o:rr_1", BookIdentity.bookKey(novel("rr_1", source = "Royal Road")))
        assertEquals("t:abc", BookIdentity.bookKey(novel("local_1", fp = "abc", hash = "h")))
        assertEquals("h:hh", BookIdentity.bookKey(novel("local_2", hash = "hh")))
        assertNull(BookIdentity.bookKey(novel("local_3")))
    }

    @Test
    fun matchPrefersFingerprintThenHash() {
        val byHash = novel("local_a", hash = "H")
        val byFp = novel("local_b", fp = "F", hash = "other")
        val candidates = listOf(byHash, byFp)
        assertEquals("local_b", BookIdentity.match(remote("t:F", fp = "F", hash = "H"), candidates))
        assertEquals("local_a", BookIdentity.match(remote("h:H", hash = "H"), candidates))
        assertEquals("local_a", BookIdentity.match(remote("t:zzz", fp = "zzz", hash = "H"), candidates))
        assertNull(BookIdentity.match(remote("t:zzz", fp = "zzz", hash = "nope"), candidates))
    }

    @Test
    fun matchIgnoresTombstonesAndOnlineBooksForImportedKeys() {
        assertNull(BookIdentity.match(remote("t:F", deleted = true), listOf(novel("local_b", fp = "F"))))
        assertNull(BookIdentity.match(remote("t:F", fp = "F"), listOf(novel("o1", source = "Royal Road", fp = "F"))))
        assertEquals("o1", BookIdentity.match(remote("o:o1"), listOf(novel("o1", source = "Royal Road"))))
        assertNull(BookIdentity.match(remote("o:o2"), listOf(novel("o1", source = "Royal Road"))))
    }
}
