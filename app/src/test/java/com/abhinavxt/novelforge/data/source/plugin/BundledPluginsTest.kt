package com.abhinavxt.novelforge.data.source.plugin

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** One entry of app/src/main/assets/plugins/index.json. */
data class BundledEntry(
    val id: String,
    val name: String,
    val version: String,
    val site: String,
    val lang: String?,
    val url: String,
    val sourceId: String?,
) {
    val script: File get() = File(BundledIndex.dir, url)
}

object BundledIndex {
    /** Unit tests run with the module directory as the working directory. */
    val dir: File = listOf(File("src/main/assets/plugins"), File("app/src/main/assets/plugins"))
        .first { it.isDirectory }

    val entries: List<BundledEntry> by lazy {
        val type = object : TypeToken<List<BundledEntry>>() {}.type
        Gson().fromJson<List<BundledEntry>>(File(dir, "index.json").readText(), type).orEmpty()
    }
}

/**
 * PluginManager installs bundled plugins from the index without evaluating
 * them, so the index has to agree with what each script declares.
 */
class BundledPluginsTest {

    private val noNetwork = PluginHttp { throw AssertionError("plugins must not fetch at load time") }

    @Test
    fun `every bundled plugin loads and matches its index entry`() {
        val seenIds = mutableSetOf<String>()
        val seenSources = mutableSetOf<String>()
        for (e in BundledIndex.entries) {
            assertTrue("${e.id}: ${e.script} missing", e.script.isFile)
            assertTrue("duplicate id ${e.id}", seenIds.add(e.id))

            val info = JsPlugin.load(e.script.readText(), e.script.name, noNetwork).info
            assertEquals("${e.id}: id", e.id, info.id)
            assertEquals("${e.id}: name", e.name, info.name)
            assertEquals("${e.id}: version", e.version, info.version)
            assertEquals("${e.id}: site", e.site, info.site)
            assertEquals("${e.id}: lang", e.lang ?: "en", info.lang)

            val sourceId = e.sourceId ?: PluginManager.sourceIdFor(e.id)
            assertTrue("duplicate source id $sourceId", seenSources.add(sourceId))
            if (e.sourceId != null) {
                assertTrue(
                    "${e.id}: sourceId ${e.sourceId} is not in PluginManager.LEGACY_SOURCES",
                    e.sourceId in PluginManager.LEGACY_SOURCES,
                )
            }
        }
    }

    @Test
    fun `a plugin is either bundled or official-only, never both`() {
        val both = BundledIndex.entries.map { it.id }.filter { it in PluginManager.OFFICIAL_ONLY }
        assertEquals(emptyList<String>(), both)
    }

    @Test
    fun `bundled filters are well formed`() {
        for (e in BundledIndex.entries) {
            val plugin = JsPlugin.load(e.script.readText(), e.script.name, noNetwork)
            val filters = plugin.export("filters") as? Map<*, *> ?: continue
            for (key in listOf("categories", "orderBys", "tags")) {
                val list = filters[key] as? List<*> ?: continue
                for (o in list) {
                    val m = o as? Map<*, *>
                    assertTrue("${e.id}: filters.$key entries need a string value", m?.get("value") is String)
                }
            }
            assertTrue("${e.id}: filters without browseNovels()", plugin.hasFunction("browseNovels"))
        }
    }
}
