package com.abhinavxt.novelforge.data.source

import com.abhinavxt.novelforge.data.source.providers.*

object SourceManager {

    private val sources = mutableMapOf<String, Source>()

    init {
        registerSource(RoyalRoadSource())
        registerSource(ReadNovelFullSource())
        registerSource(FreeWebNovelSource())
        registerSource(LibReadSource())
        registerSource(NovelFullNetSource())
        registerSource(PawReadSource())
        registerSource(PrimordialTranslationSource())
        registerSource(WtrLabSource())

        // Sources ported from QuickNovel via the nf compatibility layer.
        // Their Source.id doubles as the novel-id prefix, so the generic
        // fallbacks in getSourceFromNovelId()/constructNovelUrl() resolve them
        // without any per-source hardcoding here.
        com.abhinavxt.novelforge.data.source.nf.NfSources.all().forEach { registerSource(it) }
    }

    private fun registerSource(source: Source) {
        sources[source.id] = source
    }

    fun getSource(id: String): Source? = sources[id]

    fun getAllSources(): List<Source> = sources.values.toList()

    fun getDefaultSource(): Source =
        sources["royalroad"] ?: error("RoyalRoad source not registered")

    fun getSourceFromNovelId(novelId: String): Source? {
        val sourceId = novelId.substringBefore("_")
        return when (sourceId) {
            "rr" -> sources["royalroad"]
            "rnf" -> sources["rnf"]
            "fwn" -> sources["fwn"]
            "lr" -> sources["lr"]
            "nfn" -> sources["nfn"]
            "pr" -> sources["pr"]
            "pt" -> sources["pt"]
            "wtr" -> sources["wtr"]
            // Adapter-backed sources use their Source.id as the prefix directly
            // (e.g. "qanv_..." -> sources["qanv"]), so no mapping table is needed.
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
                "https://pawread.com/$slug"
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
                // Adapter-backed sources encode the full novel URL into the id
                // (base64url), so the adapter itself can reverse it. This keeps
                // constructNovelUrl() free of per-source cases for all ~40
                // ported sources.
                (getSourceFromNovelId(novelId) as? com.abhinavxt.novelforge.data.source.nf.NfSourceAdapter)
                    ?.novelUrlFromId(novelId)
                    ?: ""
            }
        }
    }
}

