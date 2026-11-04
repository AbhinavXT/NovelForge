package com.abhinavxt.novelforge.data.source.plugin

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.abhinavxt.novelforge.data.source.SourceManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File

/**
 * PluginManager against the real bundled assets, on a fresh install.
 *
 * PluginManager and SourceManager are process-wide singletons; each test
 * resets them (resetForTests) because Robolectric gives every test a fresh
 * app with its own files directory.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PluginManagerTest {

    private fun script(id: String) = """
        module.exports = {
          id: "$id", name: "Test $id", version: "1.0.0", site: "https://example.test",
          popularNovels: function () { return []; },
          searchNovels: function () { return []; },
          parseNovel: function () { return null; },
          parseChapter: function () { return ""; }
        };
    """.trimIndent()

    @After
    fun reset() = PluginManager.resetForTests()

    @Test
    fun `upgrading from the built-in sources keeps the retired ones`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        // Installed earlier, updated now, no installed.json yet: an upgrade from 1.8.x.
        Shadows.shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName).apply {
            firstInstallTime = 1_000L
            lastUpdateTime = 2_000L
        }
        PluginManager.init(context)
        for (id in listOf("qanv", "qgray", "qlnw")) {
            assertTrue("$id should still have a source", SourceManager.getSource(id) is JsPluginSource)
        }
        assertNotNull(SourceManager.getSource("royalroad"))
        // Still not offered to anyone else.
        assertTrue(PluginManager.available.value.none { it.retired })
    }

    @Test
    fun `bundled plugins install safely and can't be taken over`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        PluginManager.init(context)
        val dir = File(context.filesDir, "plugins")
        val royalRoadFile = File(dir, "royalroad.js")

        // Fresh install: bundled plugins under their legacy source ids, files named after them.
        assertTrue("royalroad should be a plugin source", SourceManager.getSource("royalroad") is JsPluginSource)
        assertTrue(royalRoadFile.isFile)
        assertNotNull(SourceManager.getSource("qwclick"))
        // Retired plugins are for upgrades from the Kotlin sources only.
        assertNull(SourceManager.getSource("qanv"))
        assertNull(SourceManager.getSource("qlnw"))
        assertTrue(PluginManager.available.value.none { it.retired })
        val original = royalRoadFile.readText()

        // A script from a file can't use a built-in id.
        val takeover = PluginManager.installFromScript(script("royalroad"))
        assertTrue(takeover.isFailure)
        assertTrue(takeover.exceptionOrNull()!!.message!!, takeover.exceptionOrNull()!!.message!!.contains("built-in"))
        assertEquals(original, royalRoadFile.readText())
        assertEquals("royalroad", (SourceManager.getSource("royalroad") as JsPluginSource).plugin.id)

        // A look-alike id gets its own source and its own file.
        val lookAlike = PluginManager.installFromScript(script("RoyalRoad")).getOrThrow()
        assertEquals("js-royalroad", lookAlike.sourceId)
        assertNull(lookAlike.legacySourceId)
        assertTrue(File(dir, "js-royalroad.js").isFile)
        assertEquals("built-in script untouched", original, royalRoadFile.readText())
        assertEquals("royalroad", (SourceManager.getSource("royalroad") as JsPluginSource).plugin.id)

        // Removing the look-alike leaves the built-in alone.
        PluginManager.uninstall("RoyalRoad").getOrThrow()
        assertNull(SourceManager.getSource("js-royalroad"))
        assertEquals(original, royalRoadFile.readText())
        assertNotNull(SourceManager.getSource("royalroad"))
    }
}
