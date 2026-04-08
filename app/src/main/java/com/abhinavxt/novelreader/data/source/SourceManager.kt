package com.abhinavxt.novelreader.data.source

object SourceManager {

    private val sources = mutableMapOf<String, Source>()

    init {
        registerSource(RoyalRoadSource())
        registerSource(ReadNovelFullSource())
        registerSource(FreeWebNovelSource())
        registerSource(LibReadSource())
        registerSource(NovelFullNetSource())
        registerSource(PrimordialTranslationSource())
    }

    private fun registerSource(source: Source) {
        sources[source.id] = source
    }

    fun getSource(id: String): Source? = sources[id]

    fun getAllSources(): List<Source> = sources.values.toList()

    fun getDefaultSource(): Source = sources["royalroad"]!!

    fun getSourceFromNovelId(novelId: String): Source? {
        val sourceId = novelId.substringBefore("_")
        return when (sourceId) {
            "rr" -> sources["royalroad"]
            "rnf" -> sources["rnf"]
            "fwn" -> sources["fwn"]
            "lr" -> sources["lr"]
            "nfn" -> sources["nfn"]
            "pt" -> sources["pt"]
            else -> null
        }
    }
}