package com.example.novelreader.data.source

// Manages all available novel sources.
// This makes it easy to add new sources later.
object SourceManager {

    // Map of source ID to source instance
    private val sources = mutableMapOf<String, Source>()

    init {
        // Register all available sources
        registerSource(RoyalRoadSource())
        registerSource(ReadNovelFullSource())   //add for new
    }

    private fun registerSource(source: Source) {
        sources[source.id] = source
    }

    // Get a source by its ID
    fun getSource(id: String): Source? {
        return sources[id]
    }

    // Get all available sources
    fun getAllSources(): List<Source> {
        return sources.values.toList()
    }

    // Get the default source (Royal Road for now)
    fun getDefaultSource(): Source {
        return sources["royalroad"]!!
    }

    // Extract source ID from a novel ID
    // Novel IDs are formatted as "sourceId_novelId"
    fun getSourceFromNovelId(novelId: String): Source? {
        val sourceId = novelId.substringBefore("_")
        return when (sourceId) {
            "rr" -> sources["royalroad"]
            "rnf" -> sources["rnf"] //add for new
            else -> null
        }
    }
}