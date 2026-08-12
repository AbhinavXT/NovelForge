package com.abhinavxt.novelforge.data.source.providers

import com.abhinavxt.novelforge.data.source.*

import com.abhinavxt.novelforge.data.model.Chapter
import com.abhinavxt.novelforge.data.model.Novel
import com.abhinavxt.novelforge.data.model.NovelPreview
import com.abhinavxt.novelforge.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Source for wtr-lab.com — machine-translated CN/KR web novels.
 *
 * WTR-Lab is a Next.js app, so instead of scraping rendered HTML we read
 * the embedded __NEXT_DATA__ JSON and the site's own JSON APIs:
 *
 *   Search/browse  : GET  /en/novel-finder?text=...&orderBy=...&page=N
 *                    (SSR page; series list lives in __NEXT_DATA__
 *                     props.pageProps.series. Fallback: the
 *                     /_next/data/{buildId}/... JSON route.)
 *   Novel details  : GET  /en/novel/{rawId}/{slug}
 *                    (__NEXT_DATA__ props.pageProps.serie.serie_data)
 *   Chapter list   : GET  /api/chapters/{rawId}?start=A&end=B  (250/batch)
 *   Chapter content: POST /api/reader/get
 *                    {translate:"ai"|"web", language:"en",
 *                     raw_id, chapter_no, retry:false, force_retry:false}
 *
 * Content normally arrives as a JSON array of translated paragraphs.
 * For chapters with no cached translation the API returns an encrypted
 * ("arr:"/"str:" prefixed) AES-256-GCM payload of the RAW text; the key
 * is embedded in one of the site's JS bundles and the site translates
 * client-side via Google's public translate endpoint. We mirror that
 * fallback chain (same approach as LNReader's WTRLAB plugin).
 *
 * Novel ID scheme: "wtr_{rawId}~{slug}"  (slug needed to rebuild URLs).
 * Chapter URL    : {baseUrl}/en/novel/{rawId}/{slug}/chapter-{order}
 */
class WtrLabSource : BrowseSource {

    override val id = "wtr"
    override val name = "WTR-Lab"
    override val baseUrl = "https://wtr-lab.com"

    private val client = SourceManager.sharedClient

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    // Sentry tracing headers some API routes expect; harvested from the
    // last fetched HTML page's <meta> tags (like the site itself sends).
    @Volatile private var baggage: String = ""
    @Volatile private var sentryTrace: String = ""

    // Cached AES key for the encrypted-chapter fallback. Extracted once
    // from the site's JS bundles; survives for the process lifetime.
    @Volatile private var cachedEncKey: String? = null

    // ── HTTP helpers ────────────────────────────────────────────────────

    private suspend fun fetchHtml(url: String): String? = withContext(Dispatchers.IO) {
        try {
            Logger.d("WtrLab", "Fetching: $url")
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.5")
                .header("Referer", "$baseUrl/en")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Logger.e("WtrLab", "HTML request failed: ${response.code} for $url")
                response.close()
                return@withContext null
            }
            val body = response.body?.string()
            // Refresh tracing tokens whenever a page hands them to us
            body?.let {
                Regex("<meta name=\"baggage\" content=\"([^\"]*)\"").find(it)
                    ?.groupValues?.get(1)?.let { b -> baggage = b }
                Regex("<meta name=\"sentry-trace\" content=\"([^\"]*)\"").find(it)
                    ?.groupValues?.get(1)?.let { t -> sentryTrace = t }
            }
            body
        } catch (e: Exception) {
            Logger.e("WtrLab", "fetchHtml error for $url", e)
            null
        }
    }

    private suspend fun fetchJson(url: String): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .header("Referer", "$baseUrl/en")
            if (baggage.isNotEmpty()) builder.header("baggage", baggage)
            if (sentryTrace.isNotEmpty()) builder.header("sentry-trace", sentryTrace)

            val response = client.newCall(builder.build()).execute()
            if (!response.isSuccessful) {
                Logger.e("WtrLab", "JSON request failed: ${response.code} for $url")
                response.close()
                return@withContext null
            }
            response.body?.string()?.let { JSONObject(it) }
        } catch (e: Exception) {
            Logger.e("WtrLab", "fetchJson error for $url", e)
            null
        }
    }

    private suspend fun postJson(url: String, payload: JSONObject, referer: String): JSONObject? =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/json")
                    .header("Referer", referer)
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val response = client.newCall(request).execute()
                val text = response.body?.string()
                if (text.isNullOrBlank()) {
                    Logger.e("WtrLab", "POST empty body: ${response.code} for $url")
                    return@withContext null
                }
                // Note: reader/get can return a JSON error body with a
                // non-2xx status; we still want to read it to decide on
                // the ai→web fallback, so parse regardless of status.
                JSONObject(text)
            } catch (e: Exception) {
                Logger.e("WtrLab", "postJson error for $url", e)
                null
            }
        }

    // ── __NEXT_DATA__ helpers ───────────────────────────────────────────

    private fun extractNextData(html: String): JSONObject? {
        return try {
            val script = Jsoup.parse(html).selectFirst("script#__NEXT_DATA__")
                ?: return null
            JSONObject(script.data())
        } catch (e: Exception) {
            Logger.e("WtrLab", "extractNextData parse error", e)
            null
        }
    }

    private fun parseSeriesArray(series: JSONArray): List<NovelPreview> {
        val novels = mutableListOf<NovelPreview>()
        val seenIds = mutableSetOf<Long>()
        for (i in 0 until series.length()) {
            val serie = series.optJSONObject(i) ?: continue
            val rawId = serie.optLong("raw_id", -1)
            val slug = serie.optString("slug", "")
            if (rawId <= 0 || slug.isBlank() || !seenIds.add(rawId)) continue
            val data = serie.optJSONObject("data") ?: continue
            val title = data.optString("title").trim()
            if (title.isBlank()) continue
            novels.add(
                NovelPreview(
                    id = "wtr_${rawId}~$slug",
                    title = title,
                    author = data.optString("author", "").trim(),
                    coverUrl = data.optString("image", "").takeIf { it.isNotBlank() },
                    description = data.optString("description", "").trim(),
                    source = name
                )
            )
        }
        return novels
    }

    /**
     * Fetch a novel-finder result list. Primary path: parse the SSR'd
     * __NEXT_DATA__ from the HTML page. Fallback: the /_next/data JSON
     * route using the buildId from the same page (buildId changes on
     * every site deploy, so it must never be hardcoded).
     */
    private suspend fun fetchFinder(params: String): List<NovelPreview> {
        val html = fetchHtml("$baseUrl/en/novel-finder?$params") ?: return emptyList()
        val nextData = extractNextData(html) ?: return emptyList()

        // Primary: series embedded in the page props
        nextData.optJSONObject("props")
            ?.optJSONObject("pageProps")
            ?.optJSONArray("series")
            ?.let { return parseSeriesArray(it) }

        // Fallback: JSON data route with the live buildId
        val buildId = nextData.optString("buildId", "")
        if (buildId.isBlank()) return emptyList()
        val json = fetchJson("$baseUrl/_next/data/$buildId/en/novel-finder.json?$params")
            ?: return emptyList()
        val series = json.optJSONObject("pageProps")?.optJSONArray("series")
            ?: return emptyList()
        return parseSeriesArray(series)
    }

    // ── Search ──────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<NovelPreview> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val results = fetchFinder("text=$encoded&orderBy=view&order=desc&page=1")
            Logger.d("WtrLab", "Search '$query' returned ${results.size} results")
            results
        } catch (e: Exception) {
            Logger.e("WtrLab", "Search error", e)
            emptyList()
        }
    }

    // ── Popular / Browse ────────────────────────────────────────────────

    override suspend fun getPopular(page: Int): List<NovelPreview> =
        browse(page, category = null, orderBy = null, tag = null)

    override val canBrowse = true

    // Finder API params (QuickNovel scheme): status / orderBy / genre ids.
    override val filters = BrowseFilters(
        categories = listOf(
            FilterOption("Ongoing", "ongoing"),
            FilterOption("Completed", "completed"),
        ),
        orderBys = listOf(
            FilterOption("Weekly Rank", "weekly_rank"),
            FilterOption("Views", "view"),
            FilterOption("Readers", "reader"),
            FilterOption("Date", "date"),
            FilterOption("Chapters", "chapter"),
            FilterOption("Name", "name"),
        ),
        tags = listOf(
            "Action" to "1", "Adult" to "2", "Adventure" to "3", "Anime" to "4",
            "Arts" to "5", "Comedy" to "6", "Drama" to "7", "Eastern" to "8",
            "Fan-fiction" to "9", "Fantasy" to "10", "Game" to "11",
            "Gender-bender" to "12", "Harem" to "13", "Historical" to "14",
            "Horror" to "15", "Isekai" to "16", "Josei" to "17", "LGBT" to "18",
            "Magic" to "19", "Magical Realism" to "20", "Manhua" to "21",
            "Martial Arts" to "22", "Mature" to "23", "Mecha" to "24",
            "Military" to "25", "Modern Life" to "26", "Movies" to "27",
            "Mystery" to "28", "Other" to "29", "Psychological" to "30",
            "Realistic Fiction" to "31", "Reincarnation" to "32",
            "Romance" to "33", "School Life" to "34", "Sci-fi" to "35",
            "Seinen" to "36", "Shoujo" to "37", "Shoujo-ai" to "38",
            "Shounen" to "39", "Shounen-ai" to "40", "Slice of Life" to "41",
            "Smut" to "42", "Sports" to "43", "Supernatural" to "44",
            "System" to "45", "Tragedy" to "46",
        ).map { FilterOption(it.first, it.second) },
    )

    override suspend fun browse(
        page: Int,
        category: String?,
        orderBy: String?,
        tag: String?,
    ): List<NovelPreview> {
        return try {
            val params = buildString {
                append("orderBy=${orderBy ?: "weekly_rank"}&order=desc&page=$page")
                if (!category.isNullOrBlank()) append("&status=$category")
                if (!tag.isNullOrBlank()) append("&genre=$tag")
            }
            val results = fetchFinder(params)
            Logger.d("WtrLab", "Browse page $page returned ${results.size} results")
            results
        } catch (e: Exception) {
            Logger.e("WtrLab", "browse error (p$page)", e)
            emptyList()
        }
    }

    // ── Novel details + chapter list ────────────────────────────────────

    override suspend fun getNovelDetails(novelUrl: String): Novel? {
        try {
            val html = fetchHtml(novelUrl) ?: return null
            val serie = extractNextData(html)
                ?.optJSONObject("props")
                ?.optJSONObject("pageProps")
                ?.optJSONObject("serie")
                ?.optJSONObject("serie_data")
            if (serie == null) {
                Logger.e("WtrLab", "No serie_data in __NEXT_DATA__ for $novelUrl")
                return null
            }

            val rawId = serie.optLong("raw_id", -1)
            val slug = serie.optString("slug", "")
            if (rawId <= 0 || slug.isBlank()) {
                Logger.e("WtrLab", "Missing raw_id/slug for $novelUrl")
                return null
            }

            val data = serie.optJSONObject("data") ?: JSONObject()
            val chapterCount = serie.optInt("chapter_count", 0)
            val status = when (serie.optInt("status", -1)) {
                0 -> "Ongoing"
                1 -> "Completed"
                else -> "Unknown"
            }

            val chapters = fetchAllChapters(rawId, slug, chapterCount)
            Logger.d("WtrLab", "Novel $rawId: ${chapters.size}/$chapterCount chapters")

            return Novel(
                id = "wtr_${rawId}~$slug",
                title = data.optString("title", "").trim(),
                author = data.optString("author", "").trim(),
                coverUrl = data.optString("image", "").takeIf { it.isNotBlank() },
                description = data.optString("description", "").trim(),
                source = name,
                status = status,
                chapters = chapters
            )
        } catch (e: Exception) {
            Logger.e("WtrLab", "getNovelDetails error", e)
            return null
        }
    }

    private suspend fun fetchAllChapters(
        rawId: Long,
        slug: String,
        totalChapters: Int
    ): List<Chapter> {
        val all = mutableListOf<Chapter>()
        val batchSize = 250
        // chapter_count can lag reality; probe at least one batch
        val target = maxOf(totalChapters, 1)

        var start = 1
        while (start <= target) {
            val end = minOf(start + batchSize - 1, target)
            val json = fetchJson("$baseUrl/api/chapters/$rawId?start=$start&end=$end")
            val chaptersArr = json?.optJSONArray("chapters")
                ?: json?.optJSONObject("data")?.optJSONArray("chapters")
            if (chaptersArr == null) {
                Logger.e("WtrLab", "Chapter batch $start-$end failed for $rawId")
                break
            }
            for (i in 0 until chaptersArr.length()) {
                val ch = chaptersArr.optJSONObject(i) ?: continue
                val order = ch.optInt("order", -1)
                if (order <= 0) continue
                // "title" is the translated title; "name" is the raw one
                val title = ch.optString("title", "").trim()
                    .ifBlank { ch.optString("name", "").trim() }
                    .ifBlank { "Chapter $order" }
                all.add(
                    Chapter(
                        id = "wtr_${rawId}_$order",
                        number = order,
                        title = title,
                        url = "$baseUrl/en/novel/$rawId/$slug/chapter-$order"
                    )
                )
            }
            if (chaptersArr.length() < batchSize) break
            start += batchSize
        }
        return all.distinctBy { it.number }.sortedBy { it.number }
    }

    // ── Chapter content ─────────────────────────────────────────────────

    override suspend fun getChapterContent(chapterUrl: String): String? {
        try {
            // Both current (/en/novel/{id}/{slug}) and legacy
            // (/en/serie-{id}/{slug}) URL shapes are accepted.
            val match = Regex("""(?:novel/|serie-)(\d+)/[^/]+/chapter-(\d+)""")
                .find(chapterUrl)
            if (match == null) {
                Logger.e("WtrLab", "Cannot parse chapter URL: $chapterUrl")
                return null
            }
            val rawId = match.groupValues[1].toLong()
            val chapterNo = match.groupValues[2].toInt()

            // Try premium AI translation first (works if cached/unlocked),
            // then the free web MTL. Same order as the site's own reader.
            var readerData: JSONObject? = null
            for (mode in listOf("ai", "web")) {
                val payload = JSONObject().apply {
                    put("translate", mode)
                    put("language", "en")
                    put("raw_id", rawId)
                    put("chapter_no", chapterNo)
                    put("retry", false)
                    put("force_retry", false)
                }
                val json = postJson("$baseUrl/api/reader/get", payload, chapterUrl)
                if (json != null && json.optBoolean("success", true) && !json.has("error")) {
                    readerData = json
                    break
                }
                Logger.d("WtrLab", "reader/get '$mode' unavailable for ch$chapterNo, trying next")
            }
            if (readerData == null) {
                Logger.e("WtrLab", "reader/get failed for $chapterUrl")
                return null
            }

            val chapterData = readerData.optJSONObject("data")
                ?.optJSONObject("data") ?: return null

            // Glossary: paragraphs contain ※N⛬ placeholders that map to
            // per-novel term translations
            val glossary = mutableListOf<String>()
            chapterData.optJSONObject("glossary_data")
                ?.optJSONArray("terms")?.let { terms ->
                    for (i in 0 until terms.length()) {
                        glossary.add(terms.optJSONArray(i)?.optString(0) ?: "")
                    }
                }

            // Body: JSON array of paragraphs, or encrypted raw payload
            val paragraphs: List<String> = when (val body = chapterData.get("body")) {
                is JSONArray -> (0 until body.length()).map { body.optString(it, "") }
                is String ->
                    if (body.startsWith("arr:") || body.startsWith("str:")) {
                        decryptAndTranslate(body, chapterUrl) ?: return null
                    } else {
                        listOf(body)
                    }
                else -> return null
            }

            val glossaryRegex = Regex("""(?:wtr-lab\s+)?※([0-9]+)[⛬〓]""")
            val text = paragraphs
                .map { p ->
                    val resolved = if (glossary.isNotEmpty()) {
                        glossaryRegex.replace(p) { m ->
                            glossary.getOrNull(m.groupValues[1].toInt()) ?: m.value
                        }
                    } else p
                    // Strip any residual markup (translate fallback wraps
                    // lines in <a i=N> tags)
                    Jsoup.parse(resolved).text().trim()
                }
                .filter { it.isNotBlank() }

            return if (text.isEmpty()) null else text.joinToString("\n\n")
        } catch (e: Exception) {
            Logger.e("WtrLab", "getChapterContent error", e)
            return null
        }
    }

    // ── Encrypted-chapter fallback ──────────────────────────────────────
    // When no server-side translation is cached, the API returns the raw
    // (Chinese) chapter encrypted with AES-256-GCM. The key ships inside
    // one of the site's JS bundles; the site then translates client-side
    // through Google's public endpoint. We do the same.

    private suspend fun decryptAndTranslate(
        encrypted: String,
        chapterUrl: String
    ): List<String>? {
        val key = cachedEncKey ?: findEncryptionKey(chapterUrl)?.also { cachedEncKey = it }
        if (key == null) {
            Logger.e("WtrLab", "Encryption key not found; cannot decode raw chapter")
            return null
        }

        val isArray = encrypted.startsWith("arr:")
        val payload = encrypted.substringAfter(":")
        val decrypted = try {
            decryptAesGcm(payload, key)
        } catch (e: Exception) {
            // Key may have rotated with a site deploy — refresh once
            Logger.e("WtrLab", "Decrypt failed, refreshing key", e)
            cachedEncKey = null
            val fresh = findEncryptionKey(chapterUrl) ?: return null
            cachedEncKey = fresh
            try {
                decryptAesGcm(payload, fresh)
            } catch (e2: Exception) {
                Logger.e("WtrLab", "Decrypt failed after key refresh", e2)
                return null
            }
        }

        val rawLines: List<String> = if (isArray) {
            val arr = JSONArray(decrypted)
            (0 until arr.length()).map { arr.optString(it, "") }
        } else {
            listOf(decrypted)
        }

        return translateLines(rawLines)
    }

    private fun decryptAesGcm(payload: String, encKey: String): String {
        val parts = payload.split(":")
        require(parts.size == 3) { "Invalid encrypted data format" }
        val decoder = Base64.getDecoder()
        val iv = decoder.decode(parts[0])
        val tag = decoder.decode(parts[1])
        val ciphertext = decoder.decode(parts[2])

        // javax.crypto GCM expects ciphertext || tag
        val combined = ByteArray(ciphertext.size + tag.size)
        System.arraycopy(ciphertext, 0, combined, 0, ciphertext.size)
        System.arraycopy(tag, 0, combined, ciphertext.size, tag.size)

        val keyBytes = encKey.take(32).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            GCMParameterSpec(tag.size * 8, iv)
        )
        return String(cipher.doFinal(combined), Charsets.UTF_8)
    }

    /**
     * The AES key sits in a head script bundle as a string literal:
     *   new TextEncoder().encode("<32-char-key>...")
     * Find it by scanning the chapter page's <head> script sources.
     */
    private suspend fun findEncryptionKey(chapterUrl: String): String? {
        val html = fetchHtml(chapterUrl) ?: return null
        val doc = Jsoup.parse(html, chapterUrl)
        val searchKey = "TextEncoder().encode(\""

        val srcs = doc.select("head script[src]")
            .mapNotNull { it.attr("abs:src").takeIf { s -> s.isNotBlank() } }
            .distinct()

        for (src in srcs) {
            val script = withContext(Dispatchers.IO) {
                try {
                    val req = Request.Builder().url(src)
                        .header("User-Agent", userAgent).build()
                    val resp = client.newCall(req).execute()
                    if (resp.isSuccessful) resp.body?.string() else {
                        resp.close(); null
                    }
                } catch (e: Exception) {
                    null
                }
            } ?: continue
            val index = script.indexOf(searchKey)
            if (index >= 0) {
                val keyStart = index + searchKey.length
                if (keyStart + 32 <= script.length) {
                    Logger.d("WtrLab", "Encryption key found in $src")
                    return script.substring(keyStart, keyStart + 32)
                }
            }
        }
        Logger.e("WtrLab", "Encryption key not found in ${srcs.size} scripts")
        return null
    }

    /**
     * Translate raw zh-CN lines via Google's public translate endpoint —
     * the same call the site itself makes for client-side translation.
     * Lines are wrapped in <a i=N> markers so ordering survives.
     */
    private suspend fun translateLines(lines: List<String>): List<String>? =
        withContext(Dispatchers.IO) {
            try {
                val out = mutableListOf<String>()
                // Batch to keep request bodies reasonable on long chapters
                for (batch in lines.chunked(100)) {
                    val contained = JSONArray()
                    batch.forEachIndexed { i, line ->
                        contained.put("<a i=$i>$line</a>")
                    }
                    val body = "[[${contained},\"zh-CN\",\"en\"],\"te_lib\"]"
                    val request = Request.Builder()
                        .url("https://translate-pa.googleapis.com/v1/translateHtml")
                        .header("Content-Type", "application/json+protobuf")
                        // Public web API key used by translate.google.com's
                        // embedded widget (te_lib) — not a secret.
                        .header("X-Goog-API-Key", "AIzaSyATBXajvzQLTDHEQbcpq0Ihe0vWDHmO520")
                        .header("Referer", "$baseUrl/")
                        .post(body.toRequestBody("application/json+protobuf".toMediaType()))
                        .build()
                    val response = client.newCall(request).execute()
                    val text = response.body?.string()
                    if (!response.isSuccessful || text.isNullOrBlank()) {
                        Logger.e("WtrLab", "Translate failed: ${response.code}")
                        return@withContext null
                    }
                    val translated = JSONArray(text).optJSONArray(0) ?: return@withContext null
                    for (i in 0 until translated.length()) {
                        out.add(translated.optString(i, ""))
                    }
                }
                out
            } catch (e: Exception) {
                Logger.e("WtrLab", "translateLines error", e)
                null
            }
        }
}
