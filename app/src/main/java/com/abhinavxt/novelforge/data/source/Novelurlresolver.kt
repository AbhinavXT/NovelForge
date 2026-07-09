package com.abhinavxt.novelforge.data.source

/**
 * Parses a shared URL and resolves it to a (sourceId, novelId) pair.
 *
 * This is the inverse of SourceManager.constructNovelUrl — given a URL
 * from a browser's share sheet, figure out which source owns it and
 * extract the slug that identifies the novel.
 *
 * Why this lives outside SourceManager: the patterns are tightly coupled
 * to the URL shapes of each source, but the logic is "share-target
 * specific" (tolerates query strings, trailing slashes, user noise) in
 * a way that SourceManager's strict constructNovelUrl is not. Keeping
 * them separate prevents one from softening the other.
 *
 * Usage:
 *   val resolved = NovelUrlResolver.resolve("https://www.royalroad.com/fiction/12345/some-slug")
 *   // -> ResolvedNovel(sourceId = "royalroad", novelId = "rr_12345", canonicalUrl = "...")
 */
object NovelUrlResolver {

    /** Successfully mapped a URL to a known source + novel. */
    data class ResolvedNovel(
        val sourceId: String,       // e.g. "royalroad"
        val novelId: String,        // internal ID e.g. "rr_12345"
        val canonicalUrl: String    // normalised URL we'll store/fetch with
    )

    /**
     * Try to parse a URL and resolve it. Returns null if no registered
     * source matches.
     */
    fun resolve(url: String): ResolvedNovel? {
        val normalized = normalizeUrl(url) ?: return null

        // Run through each pattern. Order matters only in that we want
        // more specific patterns first — but our sources have disjoint
        // domains so order is actually irrelevant here.
        return tryRoyalRoad(normalized)
            ?: tryReadNovelFull(normalized)
            ?: tryFreeWebNovel(normalized)
            ?: tryLibRead(normalized)
            ?: tryNovelFullNet(normalized)
            ?: tryPawRead(normalized)
            ?: tryPrimordialTranslation(normalized)
    }

    /**
     * Extract the first http(s) URL from a blob of text. Browsers often
     * share bare URLs via EXTRA_TEXT, but the user might also share a
     * message like "Check this out: https://... it's great!" — we want
     * to handle both.
     */
    fun extractFirstUrl(text: String): String? {
        val match = URL_REGEX.find(text) ?: return null
        return match.value
    }

    // ─── Normalisation ─────────────────────────────────────────────

    /**
     * Canonicalise a URL before matching. Handles:
     *  - missing scheme ("royalroad.com/..." → "https://royalroad.com/...")
     *  - trailing whitespace and zero-width chars from messy sharing
     *  - ensures scheme is lowercase (schemes are case-insensitive but
     *    our regexes aren't)
     *
     * Returns null if the input isn't a plausible URL at all.
     */
    private fun normalizeUrl(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null

        // Strip zero-width characters sometimes inserted when copying
        // from Discord/Slack rich text.
        s = s.replace("\u200B", "").replace("\u200C", "").replace("\u200D", "")

        // Prepend https:// if scheme is missing. Some share sources hand
        // out bare domains.
        if (!s.contains("://")) {
            s = "https://$s"
        }

        // Lowercase the scheme and host. Path is case-sensitive on most
        // of these sites, so don't lowercase that.
        return try {
            val schemeEnd = s.indexOf("://")
            val pathStart = s.indexOf('/', schemeEnd + 3).let {
                if (it == -1) s.length else it
            }
            val schemePlusHost = s.substring(0, pathStart).lowercase()
            val path = s.substring(pathStart)
            schemePlusHost + path
        } catch (e: Exception) {
            null
        }
    }

    // Broad URL matcher — http(s) only, accepts most reasonable URL chars.
    private val URL_REGEX = Regex("""https?://[^\s<>"']+""")

    // ─── Per-source pattern matchers ───────────────────────────────
    //
    // Each returns a ResolvedNovel if the URL matches its shape, or null.
    // We keep each one explicit rather than table-driven so that future
    // source-specific quirks (e.g. PawRead's ~ encoding) stay readable.

    /**
     * RoyalRoad: https://www.royalroad.com/fiction/12345/...
     * The numeric ID is the anchor — the slug after it is optional and
     * often truncated when users paste.
     *
     * Both www. and non-www variants are accepted.
     */
    private fun tryRoyalRoad(url: String): ResolvedNovel? {
        val re = Regex("""https?://(?:www\.)?royalroad\.com/fiction/(\d+)""")
        val match = re.find(url) ?: return null
        val id = match.groupValues[1]
        return ResolvedNovel(
            sourceId = "royalroad",
            novelId = "rr_$id",
            canonicalUrl = "https://www.royalroad.com/fiction/$id"
        )
    }

    /**
     * ReadNovelFull: https://readnovelfull.com/some-slug.html
     * The slug ends at .html. Everything after # or ? is discarded.
     */
    private fun tryReadNovelFull(url: String): ResolvedNovel? {
        val re = Regex("""https?://readnovelfull\.com/([^/?#]+?)\.html""")
        val match = re.find(url) ?: return null
        val slug = match.groupValues[1]
        return ResolvedNovel(
            sourceId = "rnf",
            novelId = "rnf_$slug",
            canonicalUrl = "https://readnovelfull.com/$slug.html"
        )
    }

    /**
     * FreeWebNovel: https://freewebnovel.com/novel/some-slug
     */
    private fun tryFreeWebNovel(url: String): ResolvedNovel? {
        val re = Regex("""https?://freewebnovel\.com/novel/([^/?#]+)""")
        val match = re.find(url) ?: return null
        val slug = match.groupValues[1]
        return ResolvedNovel(
            sourceId = "fwn",
            novelId = "fwn_$slug",
            canonicalUrl = "https://freewebnovel.com/novel/$slug"
        )
    }

    /**
     * LibRead: https://libread.com/libread/some-slug
     */
    private fun tryLibRead(url: String): ResolvedNovel? {
        val re = Regex("""https?://libread\.com/libread/([^/?#]+)""")
        val match = re.find(url) ?: return null
        val slug = match.groupValues[1]
        return ResolvedNovel(
            sourceId = "lr",
            novelId = "lr_$slug",
            canonicalUrl = "https://libread.com/libread/$slug"
        )
    }

    /**
     * NovelFullNet: https://novelfull.net/some-slug.html
     */
    private fun tryNovelFullNet(url: String): ResolvedNovel? {
        val re = Regex("""https?://novelfull\.net/([^/?#]+?)\.html""")
        val match = re.find(url) ?: return null
        val slug = match.groupValues[1]
        return ResolvedNovel(
            sourceId = "nfn",
            novelId = "nfn_$slug",
            canonicalUrl = "https://novelfull.net/$slug.html"
        )
    }

    /**
     * PawRead: https://pawread.com/some/slug/with/path
     * The novelId convention uses ~ to encode slashes in the path.
     * We replicate SourceManager.constructNovelUrl's inverse encoding.
     */
    private fun tryPawRead(url: String): ResolvedNovel? {
        val re = Regex("""https?://pawread\.com/(.+?)(?:[?#]|$)""")
        val match = re.find(url) ?: return null
        val path = match.groupValues[1].trimEnd('/')
        if (path.isEmpty()) return null
        return ResolvedNovel(
            sourceId = "pr",
            novelId = "pr_${path.replace("/", "~")}",
            canonicalUrl = "https://pawread.com/$path"
        )
    }

    /**
     * PrimordialTranslation: https://primodialtranslation.com/series/some-slug/
     * Note the domain spelling matches the original source (PrimOdial,
     * not PrimOrdial — that's literally how the site is spelled).
     */
    private fun tryPrimordialTranslation(url: String): ResolvedNovel? {
        val re = Regex("""https?://primodialtranslation\.com/series/([^/?#]+)""")
        val match = re.find(url) ?: return null
        val slug = match.groupValues[1]
        return ResolvedNovel(
            sourceId = "pt",
            novelId = "pt_$slug",
            canonicalUrl = "https://primodialtranslation.com/series/$slug/"
        )
    }
}