package com.abhinavxt.novelforge.data.source

import com.abhinavxt.novelforge.data.source.plugin.JsPluginSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Registry of the installed sources. Every source is a JavaScript plugin
 * (data/source/plugin); PluginManager registers them at start-up and on
 * install/uninstall. Former built-in sources live on as bundled plugins
 * under their old ids, which is why the id and URL tables below remain.
 */
object SourceManager {

    // Insertion-ordered (the picker lists sources in registration order) and
    // locked, because plugin installs register sources from a background
    // thread while the UI reads the list.
    private val sources = LinkedHashMap<String, Source>()

    private val _revision = MutableStateFlow(0)
    /** Bumps whenever a source is added or removed (plugin install/uninstall). */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** Adds or replaces a source. Used by PluginManager. */
    fun registerSource(source: Source) {
        synchronized(sources) { sources[source.id] = source }
        _revision.update { it + 1 }
    }

    fun unregisterSource(id: String) {
        synchronized(sources) { sources.remove(id) }
        _revision.update { it + 1 }
    }

    fun getSource(id: String): Source? = synchronized(sources) { sources[id] }

    fun getAllSources(): List<Source> = synchronized(sources) { sources.values.toList() }

    /** Royal Road when it is installed, otherwise the first source. PluginManager never removes the last one. */
    fun getDefaultSource(): Source =
        getSource("royalroad") ?: getAllSources().firstOrNull() ?: error("no sources registered")

    fun getSourceFromNovelId(novelId: String): Source? = synchronized(sources) {
        val sourceId = novelId.substringBefore("_")
        when (sourceId) {
            "rr" -> sources["royalroad"]
            "rnf" -> sources["rnf"]
            "fwn" -> sources["fwn"]
            "lr" -> sources["lr"]
            "nfn" -> sources["nfn"]
            "pr" -> sources["pr"]
            "pt" -> sources["pt"]
            "wtr" -> sources["wtr"]
            // Everything else uses its Source.id as the prefix directly
            // (e.g. "qanv_..." -> sources["qanv"], "js-foo_..." -> sources["js-foo"]).
            else -> sources[sourceId]
        }
    }

    /**
     * Build the full URL for a novel from its ID.
     * Single source of truth — used by Navigation, HomeViewModel, UpdateCheckerWorker.
     */
    fun constructNovelUrl(novelId: String): String {
        return when {
            novelId.startsWith("rr_") ->
                "https://www.royalroad.com/fiction/${novelId.removePrefix("rr_")}"
            novelId.startsWith("rnf_") ->
                "https://readnovelfull.com/${novelId.removePrefix("rnf_")}.html"
            novelId.startsWith("fwn_") ->
                "https://freewebnovel.com/novel/${novelId.removePrefix("fwn_")}"
            novelId.startsWith("lr_") ->
                "https://libread.com/libread/${novelId.removePrefix("lr_")}"
            novelId.startsWith("nfn_") ->
                "https://novelfull.net/${novelId.removePrefix("nfn_")}.html"
            novelId.startsWith("pr_") -> {
                val slug = novelId.removePrefix("pr_").replace("~", "/")
                // The trailing slash matters: without it PawRead answers 404
                // instead of redirecting to www.pawread.com.
                "https://pawread.com/$slug/"
            }
            novelId.startsWith("pt_") ->
                "https://primodialtranslation.com/series/${novelId.removePrefix("pt_")}/"
            novelId.startsWith("wtr_") -> {
                // "wtr_{rawId}~{slug}" -> /en/novel/{rawId}/{slug}
                val body = novelId.removePrefix("wtr_")
                val rawId = body.substringBefore("~")
                val slug = body.substringAfter("~", "")
                "https://wtr-lab.com/en/novel/$rawId/$slug"
            }
            novelId.startsWith("local_") ->
                "local://$novelId"
            else -> {
                // Every other source encodes the full novel URL into the id
                // ("<sourceId>_<base64url>"), so the plugin source reverses it.
                (getSourceFromNovelId(novelId) as? JsPluginSource)?.novelUrlFromId(novelId) ?: ""
            }
        }
    }
}

