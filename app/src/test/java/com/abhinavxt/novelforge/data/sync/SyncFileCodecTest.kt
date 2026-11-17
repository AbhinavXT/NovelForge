package com.abhinavxt.novelforge.data.sync

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class SyncFileCodecTest {
    private fun payload(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    private fun sample() = SyncFile(
        format = 1, deviceId = "dev-a", deviceName = "Pixel", appVersion = "2.9.3", writtenAt = 1_700_000_000_000L,
        records = listOf(
            SyncRecord("book", "o:1", (1_700_000_000_000L shl 16) or 7, "dev-a", false, payload("""{"title":"T","extra":{"x":[1,2.50,"z"]}}""")),
            SyncRecord("progress", "o:1", 5L, "dev-b", false, payload("""{"ch":3,"p":12}""")),
            SyncRecord("bookmark", "o:1|3|4", 9L, "dev-a", true, null),
            SyncRecord("futurekind", "zz", 11L, "dev-c", false, payload("""{"newField":true,"n":1.0}""")),
        ),
    )

    private fun gunzip(b: ByteArray) = GZIPInputStream(ByteArrayInputStream(b)).use { String(it.readBytes()) }

    private fun gzip(s: String): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(s.toByteArray()) }
        return bos.toByteArray()
    }

    @Test
    fun roundTrip_keepsEverythingIncludingUnknownKindsAndFields() {
        val f = sample()
        val back = SyncFileCodec.decode(SyncFileCodec.encode(f))
        assertEquals(f, back)
        val fk = back.records.first { it.kind == "futurekind" }
        assertTrue(fk.payload!!["newField"].asBoolean)
        // number text preserved
        assertEquals("1.0", fk.payload!!["n"].toString())
    }

    @Test
    fun encode_usesShortFieldNames() {
        val root = JsonParser.parseString(gunzip(SyncFileCodec.encode(sample()))).asJsonObject
        assertEquals(1, root["format"].asInt)
        val rec = root["records"].asJsonArray[0].asJsonObject
        assertEquals(setOf("k", "key", "h", "o", "d", "p"), rec.keySet())
        val tomb = root["records"].asJsonArray[2].asJsonObject
        assertFalse(tomb.has("p"))
        assertTrue(tomb["d"].asBoolean)
    }

    @Test
    fun tombstone_decodesWithNullPayload() {
        val back = SyncFileCodec.decode(SyncFileCodec.encode(sample()))
        val t = back.records.first { it.kind == "bookmark" }
        assertTrue(t.deleted)
        assertNull(t.payload)
    }

    @Test
    fun truncatedFile_throwsCorruptException() {
        val bytes = SyncFileCodec.encode(sample())
        for (cut in listOf(0, 5, bytes.size / 2, bytes.size - 3)) {
            try {
                SyncFileCodec.decode(bytes.copyOf(cut))
                fail("expected corrupt for cut=$cut")
            } catch (_: SyncFileCorruptException) {
            }
        }
    }

    @Test
    fun garbageAndWrongShapes_throwCorruptException() {
        val bad = listOf(
            "not gzip at all".toByteArray(),
            gzip("not json {"),
            gzip("[1,2,3]"),
            gzip("""{"format":1}"""),
            gzip("""{"format":1,"deviceId":"d","records":[{"k":"book"}]}"""),
            gzip("""{"format":1,"deviceId":"d","records":"x"}"""),
        )
        for (b in bad) {
            try {
                SyncFileCodec.decode(b)
                fail("expected corrupt")
            } catch (_: SyncFileCorruptException) {
            }
        }
    }

    @Test
    fun higherFormat_isReportedNotThrown() {
        val bytes = gzip("""{"format":2,"deviceId":"d","deviceName":"Tab","appVersion":"9","writtenAt":5,"records":{"new":"shape"}}""")
        val f = SyncFileCodec.decode(bytes)
        assertEquals(2, f.format)
        assertFalse(f.isSupported)
        assertEquals("Tab", f.deviceName)
        assertTrue(f.records.isEmpty())
    }

    @Test
    fun missingOptionalFields_useDefaults() {
        val f = SyncFileCodec.decode(gzip("""{"format":1,"deviceId":"d","records":[{"k":"x","key":"y","h":3,"o":"d"}]}"""))
        assertEquals("", f.deviceName)
        assertFalse(f.records[0].deleted)
        assertNull(f.records[0].payload)
    }

    private fun gzipStream(write: (java.io.OutputStream) -> Unit): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { write(it) }
        return bos.toByteArray()
    }

    @Test
    fun oversizedCompressedFile_isRejected() {
        try {
            SyncFileCodec.decode(ByteArray(2_000), maxCompressed = 1_000)
            fail("expected SyncFileCorruptException")
        } catch (_: SyncFileCorruptException) {
        }
    }

    @Test
    fun gzipBomb_isStoppedAtTheInflatedLimit_withoutBufferingIt() {
        val bomb = gzipStream { g ->
            g.write("""{"format":1,"deviceId":"d","records":[""".toByteArray())
            val chunk = ByteArray(1 shl 16) { ' '.code.toByte() }
            repeat(40) { g.write(chunk) } // 2.5 MB of valid whitespace
            g.write("]}".toByteArray())
        }
        assertTrue(bomb.size < 100_000)
        try {
            SyncFileCodec.decode(bomb, maxInflated = 1_000_000)
            fail("expected SyncFileCorruptException")
        } catch (_: SyncFileCorruptException) {
        }
        // The same file is fine under a roomier limit: the cap, not the content, rejected it.
        assertEquals(0, SyncFileCodec.decode(bomb, maxInflated = 10_000_000).records.size)
    }

    @Test
    fun theLimitsAre20MbCompressedAnd32MbInflated() {
        assertEquals(20L * 1024 * 1024, SyncFileCodec.MAX_COMPRESSED_BYTES)
        assertEquals(32L * 1024 * 1024, SyncFileCodec.MAX_INFLATED_BYTES)
    }

    @Test
    fun deeplyNestedJson_isCorrupt_notACrash() {
        val depth = 300_000
        val deep = gzipStream { g ->
            g.write("""{"format":1,"deviceId":"d","records":""".toByteArray())
            g.write("[".repeat(depth).toByteArray())
            g.write("]".repeat(depth).toByteArray())
            g.write("}".toByteArray())
        }
        try {
            SyncFileCodec.decode(deep)
            fail("expected SyncFileCorruptException")
        } catch (_: SyncFileCorruptException) {
        }
    }
}
