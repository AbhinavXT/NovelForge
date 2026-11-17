package com.abhinavxt.novelforge.data.sync

/**
 * Pure three-way merge of the local snapshot, the last merged state (shadow) and the files of the
 * other devices. No I/O. See docs/state/SYNC_DESIGN.md sections 3 and 4.
 *
 * Deviations from the section 7 signature: the shadow is a list of [ShadowEntry] (record plus
 * "applied locally" flag and digest) and [deviceId] (the origin of our own edits) is a parameter.
 */
object SyncMerger {
    private const val GUARD_MIN_BOOKS = 5

    private data class Id(val kind: String, val key: String)

    private val SyncRecord.id get() = Id(kind, key)

    private fun SyncRecord.same(o: SyncRecord) = hlc == o.hlc && origin == o.origin

    /** Last writer wins on (hlc, origin). */
    private fun SyncRecord.beats(o: SyncRecord): Boolean =
        hlc > o.hlc || (hlc == o.hlc && origin > o.origin)

    /** True when a row created at [createdAt] (device ms) predates [tomb] and is therefore stale. */
    private fun staleAgainst(clock: HybridClock, createdAt: Long?, tomb: SyncRecord): Boolean =
        createdAt != null && HybridClock.physicalOf(clock.at(createdAt)) < HybridClock.physicalOf(tomb.hlc)

    fun merge(
        shadow: List<ShadowEntry>,
        local: List<LocalRecord>,
        remotes: List<SyncFile>,
        clock: HybridClock,
        deviceId: String,
        preferShadow: Boolean = false,
        /** The user confirmed deleting here the books other devices removed: that guard is off. */
        allowRemoteDeletions: Boolean = false,
        /** The user confirmed removing everywhere the books this device lost: that guard is off. */
        allowLocalDeletions: Boolean = false,
        lastSyncTime: Long = 0L,
        keepLocalBooks: Boolean = false,
    ): MergeResult {
        val first = mergeOnce(
            shadow, local, remotes, clock, deviceId, preferShadow, allowRemoteDeletions, allowLocalDeletions,
            lastSyncTime, keepLocalBooks, holdLocalBooks = false,
        )
        // Only the local side tripped, and the person's answer for the remote side was used (books
        // deleted here, or kept): commit that answer now and hold this device's missing books back, so
        // nothing is tombstoned. The local side stays pending (guardLocalDeletions).
        if (first.massDeletionGuardTripped && first.guardLocalDeletions > 0 && first.guardRemoteDeletions == 0 &&
            first.remoteAnswerUsed
        ) {
            val held = mergeOnce(
                shadow, local, remotes, clock, deviceId, preferShadow, allowRemoteDeletions, allowLocalDeletions,
                lastSyncTime, keepLocalBooks, holdLocalBooks = true,
            )
            return held.copy(guardLocalDeletions = first.guardLocalDeletions)
        }
        return first
    }

    private fun mergeOnce(
        shadow: List<ShadowEntry>,
        local: List<LocalRecord>,
        remotes: List<SyncFile>,
        clock: HybridClock,
        deviceId: String,
        /**
         * Set for the first merge after an app backup was restored: for kinds WITHOUT a known edit time
         * (status, look, category link, bookmark note) a local difference against a live shadow record
         * is the restored old value, not an edit; the shadow (or newer remote) record is applied again.
         * Kinds with an edit time follow the normal rules. A row absent locally is a real deletion
         * (a restore only inserts rows).
         */
        preferShadow: Boolean = false,
        allowRemoteDeletions: Boolean,
        allowLocalDeletions: Boolean,
        /**
         * Device time (ms) of this device's last successful committed sync, 0 when unknown. A local row
         * is a revert (an old value put back, e.g. by a restore) only if its edit time is not newer than
         * the shadow record AND is at or before this time; an edit after it is always a real edit, even
         * when clock skew puts the shadow record's hlc ahead. With 0 nothing is a revert.
         */
        lastSyncTime: Long,
        /**
         * "Keep them": every local book that a remote tombstone would delete in this merge is claimed
         * again with a fresh hlc (and its children with it), so nothing is deleted here and the other
         * devices see a re-add. Applies to this merge only.
         */
        keepLocalBooks: Boolean,
        /** Books this device lost are left as they are (no tombstone) instead of being removed everywhere. */
        holdLocalBooks: Boolean,
    ): MergeResult {
        val skipped = remotes.filter { !it.isSupported }.map { it.deviceName.ifBlank { it.deviceId } }
        val shadowMap = LinkedHashMap<Id, ShadowEntry>()
        for (e in shadow) shadowMap[e.record.id] = e

        // Highest tombstone ever seen per book, kept even after a re-add replaced it.
        val tombFloor = HashMap<String, Long>()
        fun noteTomb(r: SyncRecord) {
            if (r.kind == SyncKind.BOOK && r.deleted) tombFloor.merge(r.key, r.hlc) { a, b -> maxOf(a, b) }
        }

        // 1. Fold shadow and every usable remote file into one winner per record.
        val out = LinkedHashMap<Id, SyncRecord>()
        for (e in shadow) {
            out[e.record.id] = e.record
            clock.observe(e.record.hlc)
            noteTomb(e.record)
        }
        for (file in remotes) {
            if (!file.isSupported) continue
            for (r in file.records) {
                clock.observe(r.hlc)
                noteTomb(r)
                if (r.kind == SyncKind.STAT && r.deleted) continue // stats are never deleted
                val cur = out[r.id]
                if (cur == null || r.beats(cur)) out[r.id] = r
            }
        }

        // 2. Reconcile the local snapshot with the winners.
        val loc = HashMap<Id, LocalRecord>()
        for (l in local) if (l.kind in SyncKind.KNOWN) loc[Id(l.kind, l.key)] = l

        val changes = LinkedHashMap<Id, LocalChange>()
        val entries = HashMap<Id, ShadowEntry>()
        var edits = 0
        var localDeletions = 0
        var remoteUpserts = 0
        var remoteDeletions = 0
        var newBookTombstones = 0
        var remoteBookDeletions = 0

        fun adopt(id: Id, winner: SyncRecord, hasRow: Boolean) {
            // The winner is not (or not yet) reflected locally: report it and mark the entry as touched.
            entries[id] = ShadowEntry(winner, applied = false, digest = null)
            if (winner.deleted) {
                if (hasRow) {
                    changes[id] = LocalChange.Delete(winner); remoteDeletions++
                    if (id.kind == SyncKind.BOOK) remoteBookDeletions++
                }
            } else {
                changes[id] = LocalChange.Upsert(winner); remoteUpserts++
            }
        }

        fun claim(l: LocalRecord, prevHlc: Long): SyncRecord {
            val h = l.editedAt?.let { maxOf(clock.at(it), prevHlc + 1) } ?: clock.tick()
            return SyncRecord(l.kind, l.key, h, deviceId, false, l.payload)
        }

        // Books this merge deletes locally because of a remote tombstone, and books kept despite one.
        val deletedBooks = HashSet<String>()
        val keptBooks = HashSet<String>()
        val heldBooks = HashSet<String>()

        fun isRevert(l: LocalRecord, sr: SyncRecord): Boolean {
            val at = l.editedAt ?: return preferShadow
            return lastSyncTime > 0L && at <= lastSyncTime && clock.at(at) <= sr.hlc
        }

        fun bookIsApplied(bookKey: String): Boolean =
            shadowMap[Id(SyncKind.BOOK, bookKey)]?.let { it.applied && !it.record.deleted } == true

        /** This device is joining sync for this record: its parent book (categories: any record) is not applied yet. */
        fun isJoin(id: Id): Boolean = when {
            id.kind == SyncKind.CATEGORY -> shadow.none { it.applied }
            id.kind in SyncKind.CHILDREN -> !bookIsApplied(SyncKeys.parentBookKey(id.key))
            else -> true
        }

        fun claimed(id: Id, l: LocalRecord, mine: SyncRecord) {
            out[id] = mine
            edits++
            entries[id] = ShadowEntry(mine, applied = true, digest = l.digest)
        }

        /** A remote tombstone beat a local book: delete it, or with [keepLocalBooks] claim it again. */
        fun tombstoneBeatsBook(id: Id, tomb: SyncRecord, l: LocalRecord) {
            if (keepLocalBooks) {
                claimed(id, l, SyncRecord(SyncKind.BOOK, l.key, clock.tick(), deviceId, false, l.payload))
                keptBooks += l.key
            } else {
                adopt(id, tomb, hasRow = true)
                deletedBooks += l.key
            }
        }

        val ids = LinkedHashSet<Id>()
        ids.addAll(out.keys.filter { it.kind in SyncKind.KNOWN })
        ids.addAll(loc.keys)
        val ordered = ids.sortedWith(compareBy({ it.kind != SyncKind.BOOK }, { it.kind }, { it.key }))

        for (id in ordered) {
            val s = shadowMap[id]
            val c = out[id]
            val l = loc[id]
            val sApplied = s != null && s.applied && !s.record.deleted

            if (l != null) {
                if (id.kind in SyncKind.CHILDREN) {
                    val parent = SyncKeys.parentBookKey(id.key)
                    // Children of a book this merge deletes go with it; none is claimed.
                    if (parent in deletedBooks) continue
                    if (parent in keptBooks) {
                        // Newer than the book's tombstone so the other devices restore it, but not newer than
                        // a genuinely later edit made elsewhere (progress from a third device is kept).
                        val floor = (tombFloor[parent] ?: 0L) + 1
                        if (c != null && !c.deleted && c.hlc >= floor) {
                            adopt(id, c, hasRow = true)
                        } else {
                            claimed(id, l, SyncRecord(l.kind, l.key, maxOf(c?.hlc ?: 0L, floor), deviceId, false, l.payload))
                        }
                        continue
                    }
                }
                if (sApplied) {
                    val sr = s!!.record
                    val edited = s.digest != null && s.digest != l.digest
                    if (!edited) {
                        if (c != null && !c.same(sr)) {
                            if (c.deleted && l.kind == SyncKind.BOOK) tombstoneBeatsBook(id, c, l) else adopt(id, c, hasRow = true)
                        } else entries[id] = ShadowEntry(sr, applied = true, digest = l.digest)
                    } else if (isRevert(l, sr)) {
                        // A revert, not an edit: the row holds an older value than the shadow (a restored
                        // backup). Put the winner back instead of claiming the old value as new.
                        val w = c ?: sr
                        if (w.deleted && l.kind == SyncKind.BOOK) tombstoneBeatsBook(id, w, l) else adopt(id, w, hasRow = true)
                    } else {
                        val mine = claim(l, sr.hlc)
                        if (c == null || mine.beats(c)) {
                            claimed(id, l, mine)
                        } else {
                            adopt(id, c, hasRow = true)
                        }
                    }
                } else if (c == null) {
                    claimed(id, l, claim(l, 0L))
                } else if (!c.deleted) {
                    if (l.kind == SyncKind.BOOK || l.kind == SyncKind.STAT) {
                        adopt(id, c, hasRow = true) // link the local book / the same reading event
                    } else if (l.editedAt == null && isJoin(id)) {
                        // This device is joining (its book is not synced yet) and the row has no known
                        // edit time (status, look, category link, bookmark note): what the other devices
                        // hold is not overwritten. On a device that already syncs this is an edit.
                        adopt(id, c, hasRow = true)
                    } else {
                        val mine = claim(l, 0L)
                        if (mine.beats(c)) claimed(id, l, mine) else adopt(id, c, hasRow = true)
                    }
                } else if (l.kind == SyncKind.BOOK) {
                    // A local book against a tombstone: stale (created before it), or a real re-add.
                    if (staleAgainst(clock, l.createdAt, c)) {
                        tombstoneBeatsBook(id, c, l)
                    } else {
                        claimed(id, l, SyncRecord(SyncKind.BOOK, l.key, clock.tick(), deviceId, false, l.payload))
                    }
                } else if ((l.kind == SyncKind.BOOKMARK || l.kind == SyncKind.HIGHLIGHT) && staleAgainst(clock, l.createdAt, c)) {
                    // Created before its tombstone: it was deleted since (an old backup brought it back).
                    adopt(id, c, hasRow = true)
                } else {
                    val mine = claim(l, 0L)
                    if (mine.beats(c)) claimed(id, l, mine) else adopt(id, c, hasRow = true)
                }
            } else {
                if (sApplied) {
                    // The row is gone locally: a deletion, unless it is a stat or already a tombstone.
                    val parentKey = if (id.kind in SyncKind.CHILDREN) SyncKeys.parentBookKey(id.key) else null
                    when {
                        holdLocalBooks && c?.deleted != true && (id.kind == SyncKind.BOOK || parentKey in heldBooks) -> {
                            // Held back: no tombstone for a book this device lost (nor for its rows).
                            if (id.kind == SyncKind.BOOK) heldBooks += id.key
                            out[id] = s!!.record
                            entries[id] = s
                        }
                        id.kind == SyncKind.STAT -> entries[id] = ShadowEntry(c ?: s!!.record, false, null)
                        c != null && c.deleted -> entries[id] = ShadowEntry(c, false, null)
                        id.kind in SyncKind.CHILDREN && out[Id(SyncKind.BOOK, SyncKeys.parentBookKey(id.key))]?.deleted == true -> Unit
                        else -> {
                            val tomb = SyncRecord(id.kind, id.key, clock.tick(), deviceId, true, null)
                            out[id] = tomb
                            noteTomb(tomb)
                            localDeletions++
                            if (id.kind == SyncKind.BOOK) newBookTombstones++
                            entries[id] = ShadowEntry(tomb, false, null)
                        }
                    }
                } else if (c != null && !c.deleted) {
                    // Never applied here (no local row): never a tombstone, keep and republish.
                    // Offer it again in case a local row can be created or matched now.
                    changes[id] = LocalChange.Upsert(c)
                    entries[id] = ShadowEntry(c, applied = false, digest = null)
                }
            }
        }

        // 3. Mass-deletion guard: change nothing. Two sides: this device lost many books (they would be
        // tombstoned for everyone), or other devices removed many (they would be deleted here).
        val appliedBooks = shadow.count { it.record.kind == SyncKind.BOOK && !it.record.deleted && it.applied }
        val localBooks = local.count { it.kind == SyncKind.BOOK }
        val localTrip = newBookTombstones > GUARD_MIN_BOOKS && newBookTombstones * 2 > appliedBooks
        val remoteTrip = remoteBookDeletions > GUARD_MIN_BOOKS && remoteBookDeletions * 2 > localBooks
        val tripLocal = localTrip && !allowLocalDeletions
        val tripRemote = remoteTrip && !allowRemoteDeletions
        if (tripLocal || tripRemote) {
            return MergeResult(
                shadow = shadow,
                records = shadow.map { it.record },
                changes = emptyList(),
                counts = MergeCounts(),
                massDeletionGuardTripped = true,
                guardLocalDeletions = if (tripLocal) newBookTombstones else 0,
                guardRemoteDeletions = if (tripRemote) remoteBookDeletions else 0,
                remoteAnswerUsed = (allowRemoteDeletions && remoteBookDeletions > 0) || (keepLocalBooks && keptBooks.isNotEmpty()),
                skippedHigherFormat = skipped,
            )
        }

        // 4. Children older than their book's tombstone are dropped.
        var dropped = 0
        for (r in out.values.toList()) {
            if (r.kind !in SyncKind.CHILDREN) continue
            val floor = tombFloor[SyncKeys.parentBookKey(r.key)] ?: continue
            if (r.hlc < floor) {
                out.remove(r.id)
                entries.remove(r.id)
                changes.remove(r.id)
                dropped++
            }
        }

        val finalEntries = out.values
            .sortedWith(compareBy({ it.kind }, { it.key }))
            .map { r ->
                entries[r.id] ?: shadowMap[r.id]?.takeIf { it.record.same(r) }
                    ?: ShadowEntry(r, applied = false, digest = null)
            }
        return MergeResult(
            shadow = finalEntries,
            records = finalEntries.map { it.record },
            changes = changes.values.toList(),
            counts = MergeCounts(edits, localDeletions, remoteUpserts, remoteDeletions, dropped),
            massDeletionGuardTripped = false,
            skippedHigherFormat = skipped,
        )
    }
}
