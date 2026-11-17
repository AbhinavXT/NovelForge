package com.abhinavxt.novelforge.data.sync

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergerTest {
    // ---- helpers -----------------------------------------------------------------------------

    private fun p(vararg kv: Pair<String, Any>): JsonObject {
        val o = JsonObject()
        for ((k, v) in kv) when (v) {
            is Number -> o.addProperty(k, v)
            is Boolean -> o.addProperty(k, v)
            else -> o.addProperty(k, v.toString())
        }
        return o
    }

    private fun h(ms: Long, c: Int = 0) = (ms shl 16) or c.toLong()

    private fun rec(kind: String, key: String, ms: Long, origin: String = "r", deleted: Boolean = false, payload: JsonObject? = if (deleted) null else p("v" to ms)) =
        SyncRecord(kind, key, h(ms), origin, deleted, payload)

    private fun file(id: String, vararg recs: SyncRecord) = SyncFile(1, id, id, "t", 0, recs.toList())

    private fun lr(kind: String, key: String, digest: String = "d1", payload: JsonObject? = p("v" to 1), editedAt: Long? = null, createdAt: Long? = null) =
        LocalRecord(kind, key, payload, digest, editedAt, createdAt)

    private fun applied(r: SyncRecord, digest: String = "d1") = ShadowEntry(r, applied = true, digest = digest)

    private fun clk(now: Long = 1_000_000L, off: Long = 0L) = HybridClock({ now }, { off })

    private fun run(shadow: List<ShadowEntry> = emptyList(), local: List<LocalRecord> = emptyList(), remotes: List<SyncFile> = emptyList(), now: Long = 1_000_000L) =
        SyncMerger.merge(shadow, local, remotes, clk(now), "me")

    private fun MergeResult.get(kind: String, key: String) = records.firstOrNull { it.kind == kind && it.key == key }

    // ---- local edits -------------------------------------------------------------------------

    @Test
    fun localEdit_detectedByDigest_stampedWithRealEditTime() {
        val s = rec("progress", "o:1", 100, "me")
        val res = run(listOf(applied(s)), listOf(lr("progress", "o:1", digest = "d2", payload = p("pos" to 9), editedAt = 5_000)))
        val r = res.get("progress", "o:1")!!
        assertEquals(h(5_000), r.hlc)
        assertEquals("me", r.origin)
        assertEquals(9, r.payload!!["pos"].asInt)
        assertEquals(1, res.counts.localEdits)
        assertTrue(res.changes.isEmpty())
    }

    @Test
    fun localEdit_withoutEditTime_usesClockTick() {
        val s = rec("progress", "o:1", 100)
        val res = run(listOf(applied(s)), listOf(lr("progress", "o:1", digest = "d2")), now = 777_000)
        assertEquals(777_000L, HybridClock.physicalOf(res.get("progress", "o:1")!!.hlc))
    }

    @Test
    fun unchangedDigest_isNotAnEdit() {
        val s = rec("progress", "o:1", 100, "other")
        val res = run(listOf(applied(s)), listOf(lr("progress", "o:1", digest = "d1")))
        assertEquals(s, res.get("progress", "o:1"))
        assertEquals(0, res.counts.localEdits)
        assertTrue(res.changes.isEmpty())
    }

    // ---- last writer wins --------------------------------------------------------------------

    @Test
    fun lastWriterWins_higherHlcBeatsLower_andReportsUpsert() {
        val s = rec("status", "o:1", 100, "a")
        val newer = rec("status", "o:1", 200, "b")
        val res = run(listOf(applied(s)), listOf(lr("status", "o:1")), listOf(file("b", newer)))
        assertEquals(newer, res.get("status", "o:1"))
        assertEquals(listOf<LocalChange>(LocalChange.Upsert(newer)), res.changes)
        // the store must fill applied/digest again for a touched entry
        assertFalse(res.shadow.single { it.record.key == "o:1" }.applied)
    }

    @Test
    fun lastWriterWins_tieOnHlcBrokenByOrigin_inEitherOrder() {
        val a = rec("status", "o:1", 100, "a")
        val b = rec("status", "o:1", 100, "b")
        val r1 = run(remotes = listOf(file("a", a), file("b", b)))
        val r2 = run(remotes = listOf(file("b", b), file("a", a)))
        assertEquals("b", r1.get("status", "o:1")!!.origin)
        assertEquals(r1.records, r2.records)
    }

    @Test
    fun localEdit_losesToNewerRemoteEdit() {
        val s = rec("progress", "o:1", 100)
        val remote = rec("progress", "o:1", 9_000, "b")
        val res = run(listOf(applied(s)), listOf(lr("progress", "o:1", digest = "d2", editedAt = 500)), listOf(file("b", remote)))
        assertEquals(remote, res.get("progress", "o:1"))
        assertEquals(0, res.counts.localEdits)
        assertEquals(1, res.changes.size)
    }

    @Test
    fun localEdit_beatsOlderRemoteEdit() {
        val s = rec("progress", "o:1", 100)
        val remote = rec("progress", "o:1", 300, "b")
        val res = run(listOf(applied(s)), listOf(lr("progress", "o:1", digest = "d2", editedAt = 5_000)), listOf(file("b", remote)))
        assertEquals("me", res.get("progress", "o:1")!!.origin)
        assertTrue(res.changes.isEmpty())
    }

    // ---- deletions ---------------------------------------------------------------------------

    @Test
    fun appliedShadowRecordMissingLocally_becomesTombstone() {
        val s = rec("book", "o:1", 100)
        val res = run(listOf(applied(s)), emptyList())
        val t = res.get("book", "o:1")!!
        assertTrue(t.deleted)
        assertNull(t.payload)
        assertTrue(t.hlc > s.hlc)
        assertEquals("me", t.origin)
        assertEquals(1, res.counts.localDeletions)
    }

    @Test
    fun neverAppliedShadowRecords_areNeverTombstoned_andAreRepublished() {
        val book = rec("book", "t:abc", 100, "b")
        val prog = rec("progress", "t:abc", 110, "b")
        val shadow = listOf(ShadowEntry(book, false), ShadowEntry(prog, false))
        val res = run(shadow, emptyList())
        assertEquals(book, res.get("book", "t:abc"))
        assertEquals(prog, res.get("progress", "t:abc"))
        assertTrue(res.records.none { it.deleted })
        assertEquals(0, res.counts.localDeletions)
        assertEquals(2, res.records.size)
    }

    @Test
    fun absenceFromRemoteFile_neverDeletes() {
        val book = rec("book", "o:1", 100)
        val res = run(listOf(applied(book)), listOf(lr("book", "o:1")), listOf(file("b")))
        assertEquals(book, res.get("book", "o:1"))
        assertTrue(res.changes.isEmpty())
    }

    @Test
    fun remoteTombstone_deletesUnchangedLocalRow() {
        val book = rec("book", "o:1", 100)
        val tomb = rec("book", "o:1", 200, "b", deleted = true)
        val res = run(listOf(applied(book)), listOf(lr("book", "o:1")), listOf(file("b", tomb)))
        assertEquals(listOf<LocalChange>(LocalChange.Delete(tomb)), res.changes)
        assertEquals(1, res.counts.remoteDeletions)
    }

    @Test
    fun childrenOlderThanTheBooksTombstone_areDropped() {
        val tomb = rec("book", "o:1", 1_000, "b", deleted = true)
        val old = rec("progress", "o:1", 500, "b")
        val oldMark = rec("bookmark", "o:1|3|4", 600, "c")
        val newer = rec("bookmark", "o:1|5|6", 1_500, "c")
        val res = run(remotes = listOf(file("b", tomb, old), file("c", oldMark, newer)))
        assertNull(res.get("progress", "o:1"))
        assertNull(res.get("bookmark", "o:1|3|4"))
        assertNotNull(res.get("bookmark", "o:1|5|6"))
        assertEquals(2, res.counts.droppedChildren)
        assertTrue(res.get("book", "o:1")!!.deleted)
    }

    @Test
    fun staleLocalRowCreatedBeforeTombstone_isReportedForDeletion() {
        val tomb = rec("book", "t:x", 1_000, "b", deleted = true)
        val shadow = listOf(ShadowEntry(tomb, false))
        val res = run(shadow, listOf(lr("book", "t:x", createdAt = 500)))
        assertEquals(listOf<LocalChange>(LocalChange.Delete(tomb)), res.changes)
        assertTrue(res.get("book", "t:x")!!.deleted)
    }

    @Test
    fun handPickedReimportAfterTombstone_winsAsReadd() {
        val tomb = rec("book", "t:x", 1_000, "b", deleted = true)
        val res = run(listOf(ShadowEntry(tomb, false)), listOf(lr("book", "t:x", createdAt = 5_000)))
        val r = res.get("book", "t:x")!!
        assertFalse(r.deleted)
        assertTrue(r.hlc > tomb.hlc)
        assertTrue(res.changes.isEmpty())
    }

    @Test
    fun folderScanBook_createdBeforeTombstone_isStale() {
        val tomb = rec("book", "t:x", 5_000, "b", deleted = true)
        val res = run(listOf(ShadowEntry(tomb, false)), listOf(lr("book", "t:x", createdAt = 1_000)))
        assertTrue(res.get("book", "t:x")!!.deleted)
        assertEquals(listOf<LocalChange>(LocalChange.Delete(tomb)), res.changes)
    }

    @Test
    fun folderScanBook_createdAfterTombstone_isAReAdd() {
        // Ruling 5: only the creation time decides; having come from a folder scan is no longer enough.
        val tomb = rec("book", "t:x", 1_000, "b", deleted = true)
        val res = run(listOf(ShadowEntry(tomb, false)), listOf(lr("book", "t:x", createdAt = 5_000)))
        assertFalse(res.get("book", "t:x")!!.deleted)
        assertTrue(res.changes.isEmpty())
    }

    @Test
    fun unlinkedLocalBook_matchedToRemoteRecord_isLinkedNotDuplicated() {
        val remote = rec("book", "t:x", 100, "b")
        val prog = rec("progress", "t:x", 110, "b")
        val res = run(emptyList(), listOf(lr("book", "t:x", createdAt = 50)), listOf(file("b", remote, prog)))
        assertEquals(remote, res.get("book", "t:x"))
        assertEquals(2, res.records.size)
        assertTrue(res.changes.any { it is LocalChange.Upsert && it.record.kind == "book" })
        assertTrue(res.changes.any { it is LocalChange.Upsert && it.record.kind == "progress" })
    }

    // ---- stats, unknown kinds ----------------------------------------------------------------

    @Test
    fun stats_areAUnion_andNeverDeleted() {
        val s1 = rec("stat", "2026-01-01", 100, "a")
        val s2 = rec("stat", "2026-01-02", 100, "b")
        val ghost = rec("stat", "2026-01-01", 900, "b", deleted = true)
        val res = run(listOf(applied(s1)), emptyList(), listOf(file("b", s2, ghost)))
        assertEquals(s1, res.get("stat", "2026-01-01"))
        assertEquals(s2, res.get("stat", "2026-01-02"))
        assertEquals(0, res.counts.localDeletions)
    }

    @Test
    fun unknownKinds_surviveMergeAndCodecRoundTrip() {
        val odd = SyncRecord("hologram", "k1", h(100), "b", false, p("a" to 1, "b" to "x"))
        val oddShadow = SyncRecord("sparkle", "k2", h(90), "c", true, null)
        val res = run(listOf(ShadowEntry(oddShadow, false)), emptyList(), listOf(file("b", odd)))
        assertEquals(odd, res.get("hologram", "k1"))
        assertEquals(oddShadow, res.get("sparkle", "k2"))
        assertTrue(res.changes.isEmpty())
        val back = SyncFileCodec.decode(SyncFileCodec.encode(SyncFile(1, "me", "n", "v", 1, res.records)))
        assertEquals(res.records, back.records)
        val again = run(res.shadow, emptyList(), listOf(back))
        assertEquals(res.records, again.records)
    }

    // ---- failures and guard ------------------------------------------------------------------

    @Test
    fun corruptRemote_isSkippedWithoutDeletingAnything() {
        val book = rec("book", "o:1", 100)
        val prog = rec("progress", "o:1", 110)
        val bytes = SyncFileCodec.encode(file("b", book)).let { it.copyOf(it.size / 2) }
        val remotes = ArrayList<SyncFile>()
        try {
            remotes.add(SyncFileCodec.decode(bytes))
        } catch (_: SyncFileCorruptException) {
            // the caller skips the file for this round
        }
        val res = run(listOf(applied(book), applied(prog)), listOf(lr("book", "o:1"), lr("progress", "o:1")), remotes)
        assertEquals(listOf(book, prog), res.records)
        assertTrue(res.changes.isEmpty())
        assertEquals(0, res.counts.localDeletions + res.counts.remoteDeletions)
    }

    @Test
    fun higherFormatFile_isSkippedAndReported() {
        val future = SyncFile(2, "d", "Boox", "9.0", 0, listOf(rec("book", "o:9", 100, "d")))
        val res = run(remotes = listOf(future))
        assertNull(res.get("book", "o:9"))
        assertEquals(listOf("Boox"), res.skippedHigherFormat)
    }

    private fun books(n: Int) = (1..n).map { rec("book", "o:$it", 100L + it) }

    @Test
    fun massDeletionGuard_tripsOverHalfAndOverFive() {
        val bs = books(8)
        val shadow = bs.map { applied(it) }
        val keep = bs.take(2).map { lr("book", it.key) }
        val res = run(shadow, keep)
        assertTrue(res.massDeletionGuardTripped)
        assertTrue(res.changes.isEmpty())
        assertEquals(shadow, res.shadow)
        assertTrue(res.records.none { it.deleted })
    }

    @Test
    fun massDeletionGuard_doesNotTripAtHalfOrAtFive() {
        val twelve = books(12)
        val half = run(twelve.map { applied(it) }, twelve.drop(6).map { lr("book", it.key) })
        assertFalse(half.massDeletionGuardTripped)
        assertEquals(6, half.counts.localDeletions)
        val six = books(6)
        val five = run(six.map { applied(it) }, six.drop(5).map { lr("book", it.key) })
        assertFalse(five.massDeletionGuardTripped)
        assertEquals(5, five.counts.localDeletions)
    }

    // ---- keys --------------------------------------------------------------------------------

    @Test
    fun keys_escapeSeparatorSoBookKeysNeverContainIt() {
        val book = "o:" + SyncKeys.escape("https://x.org/a|b%c")
        assertFalse(book.contains('|'))
        val child = SyncKeys.child(book, 3, 4)
        assertEquals(book, SyncKeys.parentBookKey(child))
        assertEquals("https://x.org/a|b%c", SyncKeys.unescape(book.removePrefix("o:")))
        assertEquals(book, SyncKeys.parentBookKey(book))
    }

    // ---- multi-device scenarios (a tiny in-memory device stands in for the Room store) ---------

    private class Row(var payload: JsonObject, var editedAt: Long? = null, var createdAt: Long? = null)

    private inner class Dev(val id: String, var now: Long, val offset: Long = 0, val files: MutableSet<String> = mutableSetOf()) {
        val rows = LinkedHashMap<Pair<String, String>, Row>()
        var shadow: List<ShadowEntry> = emptyList()
        var published: SyncFile? = null

        fun put(kind: String, key: String, payload: JsonObject, editedAt: Long? = null, createdAt: Long? = null) {
            rows[kind to key] = Row(payload, editedAt, createdAt)
        }

        fun canApply(r: SyncRecord) = when (r.kind) {
            SyncKind.BOOK -> r.key.startsWith("o:") || r.key in files
            in SyncKind.CHILDREN -> rows.containsKey(SyncKind.BOOK to SyncKeys.parentBookKey(r.key))
            SyncKind.CATEGORY, SyncKind.STAT -> true
            else -> false
        }

        fun sync(all: List<Dev>): MergeResult {
            val remotes = all.filter { it !== this }.mapNotNull { it.published }
            val local = rows.map { (k, r) -> LocalRecord(k.first, k.second, r.payload, r.payload.toString(), r.editedAt, r.createdAt) }
            val res = SyncMerger.merge(shadow, local, remotes, HybridClock({ now }, { offset }), id)
            for (ch in res.changes) {
                val k = ch.record.kind to ch.record.key
                when (ch) {
                    is LocalChange.Upsert -> if (canApply(ch.record)) rows[k] = Row(ch.record.payload!!)
                    is LocalChange.Delete -> {
                        rows.remove(k)
                        if (k.first == SyncKind.BOOK) rows.keys.removeAll { it.first in SyncKind.CHILDREN && SyncKeys.parentBookKey(it.second) == k.second }
                    }
                }
            }
            val touched = res.changes.map { it.record.kind to it.record.key }.toSet()
            shadow = res.shadow.map { e ->
                val k = e.record.kind to e.record.key
                if (k in touched) {
                    val row = rows[k]
                    ShadowEntry(e.record, row != null && !e.record.deleted, row?.payload?.toString())
                } else e
            }
            published = SyncFile(1, id, id, "t", now, res.records)
            return res
        }

        fun view() = published!!.records.associate { (it.kind to it.key) to "${it.hlc}/${it.origin}/${it.deleted}/${it.payload}" }
        fun pos(key: String) = rows[SyncKind.PROGRESS to key]?.payload?.get("pos")?.asInt
    }

    private fun round(devs: List<Dev>, order: List<Int>) = order.forEach { devs[it].sync(devs) }

    private fun converge(devs: List<Dev>, order: List<Int> = devs.indices.toList()) = repeat(3) { round(devs, order) }

    private fun assertConverged(devs: List<Dev>) {
        val v = devs[0].view()
        for (d in devs) assertEquals("device ${d.id}", v, d.view())
    }

    private fun seeded(n: Int = 2, now: Long = 10_000): List<Dev> {
        val devs = (0 until n).map { Dev("dev$it", now) }
        devs[0].put("book", "o:1", p("title" to "One"))
        devs[0].put("progress", "o:1", p("pos" to 1))
        devs[0].put("book", "o:2", p("title" to "Two"))
        converge(devs)
        return devs
    }

    @Test
    fun twoDevices_convergeToIdenticalRecordSets_andRepeatedMergesChangeNothing() {
        val devs = seeded(2)
        assertConverged(devs)
        assertEquals(1, devs[1].pos("o:1"))
        devs.forEach { it.now = 20_000 }
        devs[0].put("progress", "o:1", p("pos" to 7), editedAt = 20_000)
        devs[1].put("status", "o:1", p("s" to "reading"), editedAt = 20_100)
        converge(devs)
        assertConverged(devs)
        assertEquals(7, devs[1].pos("o:1"))
        val before = devs[0].view()
        val again = devs[0].sync(devs)
        devs[1].sync(devs)
        assertEquals(before, devs[0].view())
        assertEquals(0, again.counts.localEdits + again.counts.localDeletions)
        assertTrue(again.changes.isEmpty())
    }

    @Test
    fun threeDevices_convergeWhateverTheSyncOrder() {
        val orders = listOf(listOf(0, 1, 2), listOf(2, 1, 0), listOf(1, 0, 2), listOf(1, 2, 0), listOf(0, 2, 1), listOf(2, 0, 1))
        for (order in orders) {
            val devs = seeded(3)
            devs.forEach { it.now = 30_000 }
            devs[0].put("progress", "o:1", p("pos" to 8), editedAt = 30_000)
            devs[1].rows.remove("book" to "o:2")
            devs[2].put("highlight", "o:1|1|2|0|5", p("color" to "y"), editedAt = 30_050)
            converge(devs, order)
            assertConverged(devs)
            assertEquals("order $order", true, devs[0].view()["book" to "o:2"]!!.contains("/true/"))
            assertNotNull(devs[1].rows["highlight" to "o:1|1|2|0|5"])
            assertEquals(8, devs[2].pos("o:1"))
        }
    }

    @Test
    fun mergingRemotesInAnyOrder_givesTheSameRecords() {
        val f1 = file("a", rec("book", "o:1", 100, "a"), rec("status", "o:1", 300, "a"))
        val f2 = file("b", rec("status", "o:1", 300, "b"), rec("book", "o:1", 200, "b", deleted = true))
        val f3 = file("c", rec("progress", "o:1", 250, "c"), rec("hologram", "q", 5, "c"))
        val ref = run(remotes = listOf(f1, f2, f3)).records
        for (perm in listOf(listOf(f1, f3, f2), listOf(f2, f1, f3), listOf(f3, f2, f1))) {
            assertEquals(ref, run(remotes = perm).records)
        }
    }

    @Test
    fun clockSkew_deviceAnHourBehind_stillWinsAfterObservingTheOthersChange() {
        val hour = 3_600_000L
        val a = Dev("a", 1_000_000_000L)
        val b = Dev("b", 1_000_000_000L - hour)
        val devs = listOf(a, b)
        a.put("book", "o:1", p("t" to "x"))
        a.put("progress", "o:1", p("pos" to 5))
        converge(devs)
        assertEquals(5, b.pos("o:1"))
        // b reads on from there; its wall clock is an hour behind a's, no edit time recorded.
        b.put("progress", "o:1", p("pos" to 9))
        converge(devs)
        assertEquals(9, a.pos("o:1"))
        assertEquals(9, b.pos("o:1"))
        assertConverged(devs)
    }

    @Test
    fun clockSkew_serverOffsetCorrectsRealEditTimes() {
        val a = Dev("a", 1_000_000L)
        val b = Dev("b", 1_000_000L - 3_600_000L, offset = 3_600_000L)
        val devs = listOf(a, b)
        a.put("book", "o:1", p("t" to "x"))
        a.put("progress", "o:1", p("pos" to 5))
        converge(devs)
        // b's own clock reads an hour earlier, but with the server offset the edit is later than a's.
        b.put("progress", "o:1", p("pos" to 2), editedAt = 1_000_000L - 3_600_000L + 5_000)
        converge(devs)
        assertEquals(2, a.pos("o:1"))
    }

    @Test
    fun concurrentTombstoneVersusOlderChildEdit_sameResultInBothOrders() {
        for (order in listOf(listOf(0, 1), listOf(1, 0))) {
            val devs = seeded(2, now = 10_000)
            devs.forEach { it.now = 50_000 }
            devs[0].rows.remove("book" to "o:1")
            devs[0].rows.remove("progress" to "o:1")
            devs[1].put("progress", "o:1", p("pos" to 4), editedAt = 40_000) // edited before the delete
            converge(devs, order)
            assertConverged(devs)
            assertTrue(devs[1].view()["book" to "o:1"]!!.contains("/true/"))
            assertNull(devs[1].view()["progress" to "o:1"])
            assertNull(devs[1].rows["book" to "o:1"])
        }
    }

    @Test
    fun concurrentTombstoneVersusNewerChildEdit_neverBringsTheBookBack() {
        for (order in listOf(listOf(0, 1), listOf(1, 0))) {
            val devs = seeded(2, now = 10_000)
            devs[0].now = 50_000
            devs[1].now = 90_000
            devs[0].rows.remove("book" to "o:1")
            devs[0].rows.remove("progress" to "o:1")
            devs[1].put("progress", "o:1", p("pos" to 4), editedAt = 80_000) // edited after the delete
            converge(devs, order)
            assertConverged(devs)
            assertTrue(devs[0].view()["book" to "o:1"]!!.contains("/true/"))
            assertNull(devs[0].rows["book" to "o:1"])
            assertNull(devs[1].rows["book" to "o:1"])
        }
    }

    @Test
    fun rereadToAnEarlierPosition_winsWhenItIsTheLaterWrite() {
        val devs = seeded(2, now = 10_000)
        devs[0].put("progress", "o:1", p("pos" to 10), editedAt = 11_000)
        converge(devs)
        assertEquals(10, devs[1].pos("o:1"))
        devs[1].now = 12_000
        devs[1].put("progress", "o:1", p("pos" to 3), editedAt = 12_000)
        converge(devs)
        assertEquals(3, devs[0].pos("o:1"))
        assertEquals(3, devs[1].pos("o:1"))
    }

    @Test
    fun furtherPosition_losesWhenItIsTheEarlierWrite() {
        val devs = seeded(2, now = 10_000)
        devs.forEach { it.now = 20_000 }
        devs[0].put("progress", "o:1", p("pos" to 50), editedAt = 15_000)
        devs[1].put("progress", "o:1", p("pos" to 3), editedAt = 18_000)
        converge(devs)
        assertEquals(3, devs[0].pos("o:1"))
        assertEquals(3, devs[1].pos("o:1"))
    }

    @Test
    fun sameHighlightOnTwoDevices_yieldsOneRecord() {
        val devs = seeded(2, now = 10_000)
        devs.forEach { it.now = 20_000 }
        val key = SyncKeys.child("o:1", 2, 3, 10, 25)
        devs[0].put("highlight", key, p("color" to "yellow"), editedAt = 19_000)
        devs[1].put("highlight", key, p("color" to "yellow"), editedAt = 19_500)
        converge(devs)
        assertConverged(devs)
        assertEquals(1, devs[0].published!!.records.count { it.kind == "highlight" })
        assertEquals(1, devs[1].published!!.records.count { it.kind == "highlight" })
    }

    @Test
    fun bookOnlyOnOneDevice_isKeptAndRepublishedByTheOthers_untilTheFileIsImported() {
        val a = Dev("a", 10_000)
        val b = Dev("b", 10_000)
        val c = Dev("c", 10_000)
        val devs = listOf(a, b, c)
        a.files.add("t:abc")
        a.put("book", "t:abc", p("title" to "Epub"))
        a.put("progress", "t:abc", p("pos" to 6), editedAt = 9_000)
        a.put("bookmark", "t:abc|1|2", p("note" to "n"), editedAt = 9_000)
        converge(devs)
        // b has no such book: it must hold the records unapplied and publish them, never tombstone them.
        assertNull(b.rows["book" to "t:abc"])
        assertTrue(b.published!!.records.any { it.key == "t:abc" && !it.deleted })
        assertTrue(b.published!!.records.none { it.deleted })
        // a goes away for a while; c still gets everything from b's file
        b.files.add("t:abc")
        b.put("book", "t:abc", p("title" to "Epub"), createdAt = 12_000)
        converge(devs)
        assertEquals(6, b.pos("t:abc"))
        assertNotNull(b.rows["bookmark" to "t:abc|1|2"])
        assertConverged(devs)
    }

    @Test
    fun restoredOldBackupOnASyncedDevice_isDeletedNotResurrected() {
        val devs = seeded(2, now = 10_000)
        devs.forEach { it.now = 100_000 }
        devs[0].rows.remove("book" to "o:2")
        converge(devs)
        // device 1 restores an old backup: the row comes back with an old createdAt
        devs[1].rows["book" to "o:2"] = Row(p("title" to "Two"), createdAt = 5_000)
        converge(devs)
        assertNull(devs[1].rows["book" to "o:2"])
        assertTrue(devs[0].view()["book" to "o:2"]!!.contains("/true/"))
    }

    // ---- review fixes: reverts, restore, joining, guard ---------------------------------------

    private fun merge(
        shadow: List<ShadowEntry> = emptyList(), local: List<LocalRecord> = emptyList(),
        remotes: List<SyncFile> = emptyList(), preferShadow: Boolean = false,
        allowRemoteDeletions: Boolean = false, allowLocalDeletions: Boolean = false,
        lastSyncTime: Long = 0L, keepLocalBooks: Boolean = false,
    ) = SyncMerger.merge(
        shadow, local, remotes, clk(), "me", preferShadow, allowRemoteDeletions, allowLocalDeletions,
        lastSyncTime, keepLocalBooks,
    )

    @Test
    fun localRowOlderThanShadow_isARevert_notAClaim_andLeavesNoPingPong() {
        val s = rec("progress", "o:1", 5_000)
        val res = merge(listOf(applied(s, "d1")), listOf(lr("progress", "o:1", digest = "old", editedAt = 1_000)), lastSyncTime = 2_000)
        assertEquals(s, res.get("progress", "o:1"))
        assertEquals(listOf<LocalChange>(LocalChange.Upsert(s)), res.changes)
        assertEquals(0, res.counts.localEdits)
        // The store applies it and reports the digest of the row as stored: the shadow entry is settled.
        val settled = ApplyReport(mapOf(("progress" to "o:1") to AppliedRow("1", "d1"))).applyTo(res.shadow)
        val e = settled.single { it.record.kind == "progress" }
        assertTrue(e.applied)
        assertEquals("d1", e.digest)
        // Next round: the row now matches its shadow entry, nothing to do.
        val again = merge(settled, listOf(lr("progress", "o:1", digest = "d1", editedAt = 1_000)), lastSyncTime = 2_000)
        assertTrue(again.changes.isEmpty())
        assertEquals(0, again.counts.localEdits)
    }

    @Test
    fun localRowNewerThanShadow_isStillAnEdit() {
        val s = rec("progress", "o:1", 5_000)
        val res = merge(listOf(applied(s, "d1")), listOf(lr("progress", "o:1", digest = "new", editedAt = 9_000)))
        assertTrue(res.get("progress", "o:1")!!.hlc > s.hlc)
        assertEquals(1, res.counts.localEdits)
        assertTrue(res.changes.isEmpty())
    }

    @Test
    fun bookmarkAndHighlightCreatedBeforeTheirTombstone_areDeletedAgain() {
        for (kind in listOf("bookmark", "highlight")) {
            val tomb = rec(kind, "o:1|2|7", 5_000, "b", deleted = true)
            val stale = merge(listOf(ShadowEntry(tomb, false)), listOf(lr(kind, "o:1|2|7", createdAt = 1_000)))
            assertTrue(stale.get(kind, "o:1|2|7")!!.deleted)
            assertEquals(listOf<LocalChange>(LocalChange.Delete(tomb)), stale.changes)
            val fresh = merge(listOf(ShadowEntry(tomb, false)), listOf(lr(kind, "o:1|2|7", createdAt = 9_000)))
            assertFalse(fresh.get(kind, "o:1|2|7")!!.deleted)
            assertTrue(fresh.changes.isEmpty())
        }
    }

    @Test
    fun preferShadow_reappliesTheShadowInsteadOfClaimingRestoredValues() {
        val s = rec("status", "o:1", 5_000)
        val res = merge(listOf(applied(s, "d1")), listOf(lr("status", "o:1", digest = "old")), preferShadow = true)
        assertEquals(s, res.get("status", "o:1"))
        assertEquals(listOf<LocalChange>(LocalChange.Upsert(s)), res.changes)
        assertEquals(0, res.counts.localEdits)
        // Without the flag the same row (no known edit time) is an edit, as before.
        val normal = merge(listOf(applied(s, "d1")), listOf(lr("status", "o:1", digest = "old")))
        assertEquals(1, normal.counts.localEdits)
    }

    @Test
    fun preferShadow_appliesANewerRemoteInsteadOfTheShadow() {
        val s = rec("status", "o:1", 5_000)
        val r = rec("status", "o:1", 7_000, "b")
        val res = merge(listOf(applied(s, "d1")), listOf(lr("status", "o:1", digest = "old")), listOf(file("b", r)), preferShadow = true)
        assertEquals(listOf<LocalChange>(LocalChange.Upsert(r)), res.changes)
    }

    @Test
    fun preferShadow_stillTombstonesARowThatIsAbsentLocally() {
        // A restore only inserts rows, so an absent row is a real deletion.
        val s = rec("bookmark", "o:1|2|7", 5_000)
        val res = merge(listOf(applied(s, "d1")), emptyList(), preferShadow = true)
        assertTrue(res.get("bookmark", "o:1|2|7")!!.deleted)
        assertEquals(1, res.counts.localDeletions)
    }

    @Test
    fun preferShadow_stillClaimsRowsWithNoShadowEntry() {
        val res = merge(emptyList(), listOf(lr("status", "o:1", editedAt = null)), preferShadow = true)
        assertEquals("me", res.get("status", "o:1")!!.origin)
        assertEquals(1, res.counts.localEdits)
    }

    @Test
    fun joiningDevice_adoptsTheRemoteRecord_whenTheLocalRowHasNoKnownEditTime() {
        val remote = rec("status", "o:1", 1_000, "b")
        val res = merge(emptyList(), listOf(lr("status", "o:1", digest = "mine")), listOf(file("b", remote)))
        assertEquals(remote, res.get("status", "o:1"))
        assertEquals(listOf<LocalChange>(LocalChange.Upsert(remote)), res.changes)
        assertEquals(0, res.counts.localEdits)
    }

    @Test
    fun joiningDevice_comparesAKnownEditTimeWithTheRemoteAsUsual() {
        val remote = rec("progress", "o:1", 5_000, "b")
        val newer = merge(emptyList(), listOf(lr("progress", "o:1", editedAt = 9_000)), listOf(file("b", remote)))
        assertEquals("me", newer.get("progress", "o:1")!!.origin)
        assertTrue(newer.changes.isEmpty())
        val older = merge(emptyList(), listOf(lr("progress", "o:1", editedAt = 1_000)), listOf(file("b", remote)))
        assertEquals(remote, older.get("progress", "o:1"))
        assertEquals(listOf<LocalChange>(LocalChange.Upsert(remote)), older.changes)
    }

    private fun booksWithRemoteTombstones(total: Int, removed: Int): Triple<List<ShadowEntry>, List<LocalRecord>, SyncFile> {
        val keys = (1..total).map { "o:$it" }
        val shadow = keys.map { applied(rec("book", it, 100)) }
        val local = keys.map { lr("book", it) }
        val tombs = keys.take(removed).map { rec("book", it, 200, "b", deleted = true) }
        return Triple(shadow, local, file("b", *tombs.toTypedArray()))
    }

    @Test
    fun manyRemoteBookDeletions_tripTheGuard_andNothingIsChanged() {
        val (shadow, local, remote) = booksWithRemoteTombstones(8, 6)
        val res = merge(shadow, local, listOf(remote))
        assertTrue(res.massDeletionGuardTripped)
        assertEquals(6, res.guardRemoteDeletions)
        assertEquals(0, res.guardLocalDeletions)
        assertTrue(res.changes.isEmpty())
        assertEquals(shadow, res.shadow)
    }

    @Test
    fun allowRemoteDeletions_appliesManyRemoteDeletions() {
        val (shadow, local, remote) = booksWithRemoteTombstones(8, 6)
        val res = merge(shadow, local, listOf(remote), allowRemoteDeletions = true)
        assertFalse(res.massDeletionGuardTripped)
        assertEquals(6, res.changes.count { it is LocalChange.Delete })
    }

    @Test
    fun fewOrNotMajorityRemoteDeletions_doNotTripTheGuard() {
        val (s1, l1, r1) = booksWithRemoteTombstones(8, 5)
        assertFalse(merge(s1, l1, listOf(r1)).massDeletionGuardTripped)
        val (s2, l2, r2) = booksWithRemoteTombstones(20, 6)
        assertFalse(merge(s2, l2, listOf(r2)).massDeletionGuardTripped)
    }

    @Test
    fun allowLocalDeletions_turnsTheLocalGuardOff_butNotTheRemoteOne() {
        val shadow = (1..8).map { applied(rec("book", "o:$it", 100)) }
        assertTrue(merge(shadow, emptyList()).massDeletionGuardTripped)
        assertTrue("the other answer does not count", merge(shadow, emptyList(), allowRemoteDeletions = true).massDeletionGuardTripped)
        val res = merge(shadow, emptyList(), allowLocalDeletions = true)
        assertFalse(res.massDeletionGuardTripped)
        assertEquals(8, res.counts.localDeletions)
        val (s2, l2, r2) = booksWithRemoteTombstones(8, 6)
        assertTrue(merge(s2, l2, listOf(r2), allowLocalDeletions = true).massDeletionGuardTripped)
    }

    // ---- second pass --------------------------------------------------------------------------

    @Test
    fun s2_underPreferShadow_kindsWithAnEditTimeFollowTheNormalRules() {
        val s = rec("progress", "o:1", 5_000)
        // An edit after the last sync is claimed even though the restore token is set.
        val edit = merge(listOf(applied(s, "d1")), listOf(lr("progress", "o:1", digest = "new", editedAt = 9_000)), preferShadow = true, lastSyncTime = 8_000)
        assertEquals(1, edit.counts.localEdits)
        assertTrue(edit.get("progress", "o:1")!!.hlc > s.hlc)
        // A restored older value (edit time before the last sync) is a revert.
        val old = merge(listOf(applied(s, "d1")), listOf(lr("progress", "o:1", digest = "old", editedAt = 1_000)), preferShadow = true, lastSyncTime = 8_000)
        assertEquals(0, old.counts.localEdits)
        assertEquals(listOf<LocalChange>(LocalChange.Upsert(s)), old.changes)
    }

    @Test
    fun s3_aChildWithNoShadowRecordIsAnEdit_whenItsBookIsAlreadySynced() {
        val book = rec("book", "o:1", 100)
        val remote = rec("status", "o:1", 1_000, "b")
        val res = merge(
            listOf(applied(book)), listOf(lr("book", "o:1"), lr("status", "o:1", digest = "completed")),
            listOf(file("b", remote)),
        )
        val w = res.get("status", "o:1")!!
        assertEquals("me", w.origin)
        assertTrue(w.hlc > remote.hlc)
        assertTrue(res.changes.isEmpty())
    }

    @Test
    fun s3_itIsAJoinOnlyWhenTheBookHasNoAppliedShadowEntry() {
        val book = rec("book", "o:1", 100)
        val remote = rec("status", "o:1", 1_000, "b")
        val res = merge(
            listOf(ShadowEntry(book, applied = false)), listOf(lr("book", "o:1"), lr("status", "o:1", digest = "mine")),
            listOf(file("b", remote)),
        )
        assertEquals(remote, res.get("status", "o:1"))
        assertEquals(0, res.counts.localEdits)
        // A category is a join only while the shadow has no applied entries at all.
        val cat = rec("category", "fav", 1_000, "b")
        assertEquals(cat, merge(emptyList(), listOf(lr("category", "fav")), listOf(file("b", cat))).get("category", "fav"))
        val synced = merge(listOf(applied(book)), listOf(lr("book", "o:1"), lr("category", "fav")), listOf(file("b", cat)))
        assertEquals("me", synced.get("category", "fav")!!.origin)
    }

    @Test
    fun s4_anEditAfterTheLastSyncIsClaimed_evenWhenTheShadowHlcIsAheadBecauseOfSkew() {
        val ahead = rec("progress", "o:1", 900_000_000) // a clock far ahead
        val res = merge(
            listOf(applied(ahead, "d1")), listOf(lr("progress", "o:1", digest = "new", editedAt = 50_000)),
            lastSyncTime = 40_000,
        )
        assertEquals(1, res.counts.localEdits)
        assertTrue(res.get("progress", "o:1")!!.hlc > ahead.hlc)
        assertEquals("me", res.get("progress", "o:1")!!.origin)
    }

    @Test
    fun s4_withNoKnownLastSync_nothingIsARevert() {
        val s = rec("progress", "o:1", 5_000)
        val res = merge(listOf(applied(s, "d1")), listOf(lr("progress", "o:1", digest = "old", editedAt = 1_000)), lastSyncTime = 0)
        assertEquals(1, res.counts.localEdits)
    }

    @Test
    fun s6_keepLocalBooks_claimsEveryBookARemoteTombstoneWouldDelete_andItsChildren() {
        val (shadow, local, remote) = booksWithRemoteTombstones(8, 6)
        val child = rec("progress", "o:1", 100)
        val res = merge(
            shadow + applied(child, "d1"), local + lr("progress", "o:1", digest = "d1", editedAt = 90),
            listOf(remote), keepLocalBooks = true,
        )
        assertFalse(res.massDeletionGuardTripped)
        assertTrue(res.changes.none { it is LocalChange.Delete })
        for (i in 1..8) {
            val b = res.get("book", "o:$i")!!
            assertFalse("o:$i", b.deleted)
            if (i <= 6) {
                assertEquals("me", b.origin)
                assertTrue(b.hlc > h(200))
            }
        }
        val kept = res.get("progress", "o:1")!!
        assertEquals("the child is kept with a fresh hlc, not dropped", "me", kept.origin)
        assertTrue(kept.hlc > h(200))
        // Without the option the same merge deletes them (guard off).
        assertEquals(6, merge(shadow, local, listOf(remote), allowRemoteDeletions = true).changes.count { it is LocalChange.Delete })
    }

    @Test
    fun s6_bothGuardsReportTheirCounts() {
        val shadow = (1..14).map { applied(rec("book", "o:$it", 100)) }
        val local = (1..6).map { lr("book", "o:$it") }
        val remote = file("b", *(1..6).map { rec("book", "o:$it", 200, "b", deleted = true) }.toTypedArray())
        val res = merge(shadow, local, listOf(remote))
        assertTrue(res.massDeletionGuardTripped)
        assertEquals(8, res.guardLocalDeletions)
        assertEquals(6, res.guardRemoteDeletions)
    }

    @Test
    fun childrenOfAStaleRestoredBookAreDroppedWithIt_notClaimed() {
        val tomb = rec("book", "t:x", 5_000, "b", deleted = true)
        val res = merge(
            listOf(ShadowEntry(tomb, false)),
            listOf(
                lr("book", "t:x", createdAt = 1_000),
                lr("status", "t:x", digest = "s"),
                lr("look", "t:x", digest = "l"),
                lr("progress", "t:x", editedAt = 2_000),
                lr("catlink", "t:x|fav"),
            ),
        )
        assertEquals(listOf<LocalChange>(LocalChange.Delete(tomb)), res.changes)
        for (k in listOf("status" to "t:x", "look" to "t:x", "progress" to "t:x", "catlink" to "t:x|fav")) {
            assertNull("claimed ${k.first}", res.get(k.first, k.second))
        }
        assertEquals(0, res.counts.localEdits)
    }

    @Test
    fun keptBookChildren_areNewerThanTheTombstone_butNeverBeatAThirdDevicesLaterEdit() {
        val (shadow, local, remote) = booksWithRemoteTombstones(8, 6)
        val child = rec("progress", "o:1", 100)
        val third = file("c", rec("progress", "o:1", 500, "c"))
        val withThird = merge(
            shadow + applied(child, "d1"), local + lr("progress", "o:1", digest = "d1", editedAt = 90),
            listOf(remote, third), keepLocalBooks = true,
        ).get("progress", "o:1")!!
        assertEquals("the later progress of the third device stands", "c", withThird.origin)
        assertEquals(h(500), withThird.hlc)
        val alone = merge(
            shadow + applied(child, "d1"), local + lr("progress", "o:1", digest = "d1", editedAt = 90),
            listOf(remote), keepLocalBooks = true,
        ).get("progress", "o:1")!!
        assertEquals("just past the tombstone, not a fresh tick", h(200) + 1, alone.hlc)
    }

    @Test
    fun remoteAnswerUsed_commitsItAndHoldsTheLocalSideBack() {
        // 10 books here: 6 were removed elsewhere, and 14 more are in the shadow but not here.
        val keys = (1..24).map { "o:$it" }
        val shadow = keys.map { applied(rec("book", it, 100)) }
        val local = keys.take(10).map { lr("book", it) }
        val remote = file("b", *keys.take(6).map { rec("book", it, 200, "b", deleted = true) }.toTypedArray())
        val res = merge(shadow, local, listOf(remote), allowRemoteDeletions = true)
        assertFalse(res.massDeletionGuardTripped)
        assertEquals(6, res.changes.count { it is LocalChange.Delete })
        assertEquals("the local side stays pending", 14, res.guardLocalDeletions)
        for (k in keys.drop(10)) assertFalse("no tombstone for $k", res.get("book", k)!!.deleted)
    }
}
