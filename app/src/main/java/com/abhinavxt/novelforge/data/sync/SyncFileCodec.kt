package com.abhinavxt.novelforge.data.sync

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** The file is truncated, not gzip, not JSON, or lacks required fields. Skip it for this round. */
class SyncFileCorruptException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * gzip JSON with short field names: {format, deviceId, deviceName, appVersion, writtenAt,
 * records:[{k, key, h, o, d, p}]}. A file with a higher format decodes to a [SyncFile] with
 * that format and no records; check [SyncFile.isSupported].
 */
object SyncFileCodec {
    fun encode(file: SyncFile): ByteArray {
        val root = JsonObject()
        root.addProperty("format", file.format)
        root.addProperty("deviceId", file.deviceId)
        root.addProperty("deviceName", file.deviceName)
        root.addProperty("appVersion", file.appVersion)
        root.addProperty("writtenAt", file.writtenAt)
        val arr = JsonArray()
        for (r in file.records) {
            val o = JsonObject()
            o.addProperty("k", r.kind)
            o.addProperty("key", r.key)
            o.addProperty("h", r.hlc)
            o.addProperty("o", r.origin)
            o.addProperty("d", r.deleted)
            if (r.payload != null) o.add("p", r.payload)
            arr.add(o)
        }
        root.add("records", arr)
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(root.toString().toByteArray(Charsets.UTF_8)) }
        return bos.toByteArray()
    }

    /** A device file larger than this (compressed) is not read. */
    const val MAX_COMPRESSED_BYTES = 20L * 1024 * 1024

    /** Inflating stops, and the file is skipped, past this size. */
    const val MAX_INFLATED_BYTES = 32L * 1024 * 1024

    /**
     * A hostile or broken file (too big, a gzip bomb, JSON nested until the stack overflows) is reported
     * as [SyncFileCorruptException], including when it ends in an OutOfMemoryError or StackOverflowError.
     */
    fun decode(
        bytes: ByteArray,
        maxCompressed: Long = MAX_COMPRESSED_BYTES,
        maxInflated: Long = MAX_INFLATED_BYTES,
    ): SyncFile {
        try {
            if (bytes.size > maxCompressed) throw SyncFileCorruptException("file too large")
            val root = GZIPInputStream(ByteArrayInputStream(bytes)).use { gz ->
                // Parse straight from the capped stream: the inflated text is never held in memory whole.
                JsonParser.parseReader(InputStreamReader(CappedInputStream(gz, maxInflated), Charsets.UTF_8))
            }
            if (!root.isJsonObject) throw SyncFileCorruptException("root is not an object")
            val obj = root.asJsonObject
            val format = obj.req("format").asInt
            val deviceId = obj.req("deviceId").asString
            val deviceName = obj.opt("deviceName")?.asString ?: ""
            val appVersion = obj.opt("appVersion")?.asString ?: ""
            val writtenAt = obj.opt("writtenAt")?.asLong ?: 0L
            if (format > SyncFormat.CURRENT) {
                return SyncFile(format, deviceId, deviceName, appVersion, writtenAt, emptyList())
            }
            val records = obj.req("records").asJsonArray.map { e ->
                val o = e.asJsonObject
                val p = o.opt("p")
                SyncRecord(
                    kind = o.req("k").asString,
                    key = o.req("key").asString,
                    hlc = o.req("h").asLong,
                    origin = o.req("o").asString,
                    deleted = o.opt("d")?.asBoolean ?: false,
                    payload = if (p != null && p.isJsonObject) p.asJsonObject else null,
                )
            }
            return SyncFile(format, deviceId, deviceName, appVersion, writtenAt, records)
        } catch (e: SyncFileCorruptException) {
            throw e
        } catch (e: Throwable) {
            // Errors (OutOfMemoryError, StackOverflowError) from hostile input are skipped like any bad file.
            throw SyncFileCorruptException("cannot read sync file (${e.javaClass.simpleName})", e)
        }
    }

    private class CappedInputStream(private val inner: InputStream, private val max: Long) : InputStream() {
        private var count = 0L
        private fun add(n: Int) {
            if (n > 0) {
                count += n
                if (count > max) throw SyncFileCorruptException("file inflates past the limit")
            }
        }
        override fun read(): Int = inner.read().also { if (it >= 0) add(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { add(it) }
    }

    private fun JsonObject.opt(name: String): JsonElement? =
        get(name)?.takeIf { it !is JsonNull }

    private fun JsonObject.req(name: String): JsonElement =
        opt(name) ?: throw SyncFileCorruptException("missing field $name")
}
