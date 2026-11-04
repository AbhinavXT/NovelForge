package com.abhinavxt.novelforge.data.source

import org.junit.Assert.assertEquals
import org.junit.Test

/** PawRead answers 404 for novel URLs without a trailing slash. */
class PawReadUrlTest {

    @Test
    fun `novel urls are rebuilt with a trailing slash`() {
        assertEquals("https://pawread.com/novel/serpent-ancestor/", SourceManager.constructNovelUrl("pr_novel~serpent-ancestor"))
    }

    @Test
    fun `shared links with and without www resolve to the same novel`() {
        for (link in listOf(
            "https://pawread.com/novel/serpent-ancestor/",
            "https://www.pawread.com/novel/serpent-ancestor/",
            "https://www.pawread.com/novel/serpent-ancestor",
        )) {
            val resolved = NovelUrlResolver.resolve(link)!!
            assertEquals(link, "pr", resolved.sourceId)
            assertEquals(link, "pr_novel~serpent-ancestor", resolved.novelId)
            assertEquals(link, "https://pawread.com/novel/serpent-ancestor/", resolved.canonicalUrl)
        }
    }
}
