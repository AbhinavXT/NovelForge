package com.example.novelreader.data

import com.example.novelreader.data.model.Chapter
import com.example.novelreader.data.model.Novel
import com.example.novelreader.data.model.NovelPreview

object PlaceholderData {

    val sampleNovels = listOf(
        NovelPreview(
            id = "1",
            title = "The Beginning After The End",
            author = "TurtleMe",
            coverUrl = null,
            description = "King Grey has unrivaled strength, wealth, and prestige in a world governed by martial ability. However, solitude lingers closely behind those with great power.",
            source = "Royal Road"
        ),
        NovelPreview(
            id = "2",
            title = "Mother of Learning",
            author = "nobody103",
            coverUrl = null,
            description = "Zorian is a teenage mage of humble birth and modest skill, attending his third year of education at Cyoria's magical academy.",
            source = "Royal Road"
        ),
        NovelPreview(
            id = "3",
            title = "Omniscient Reader's Viewpoint",
            author = "Sing Shong",
            coverUrl = null,
            description = "Only I know the end of this world. One day our MC finds himself stuck in the world of his favorite webnovel.",
            source = "WebNovel"
        ),
        NovelPreview(
            id = "4",
            title = "Solo Leveling",
            author = "Chugong",
            coverUrl = null,
            description = "In a world where hunters with various magical abilities battle deadly monsters to protect humanity, Sung Jinwoo is the weakest of them all.",
            source = "WebNovel"
        ),
        NovelPreview(
            id = "5",
            title = "The Wandering Inn",
            author = "pirateaba",
            coverUrl = null,
            description = "An inn is a place to rest, a roof to hang over your head, and maybe a hot meal. This is the story of the girl who runs it.",
            source = "Royal Road"
        ),
        NovelPreview(
            id = "6",
            title = "Reverend Insanity",
            author = "Gu Zhen Ren",
            coverUrl = null,
            description = "Humans are the spirit of all living beings. Gu are the essence of heaven and earth. A story of a villain who reincarnates 500 years into the past.",
            source = "WebNovel"
        ),
        NovelPreview(
            id = "7",
            title = "Lord of the Mysteries",
            author = "Cuttlefish That Loves Diving",
            coverUrl = null,
            description = "With the rising tide of steam power and machinery, who can come close to being a Savior?",
            source = "WebNovel"
        ),
        NovelPreview(
            id = "8",
            title = "Super Minion",
            author = "Forth",
            coverUrl = null,
            description = "Fortress City has Super Villains, who have evil lairs, and in them live minions. Tofu is one such minion.",
            source = "Royal Road"
        )
    )

    fun searchNovels(query: String): List<NovelPreview> {
        if (query.isBlank()) {
            return sampleNovels
        }
        return sampleNovels.filter { novel ->
            novel.title.contains(query, ignoreCase = true) ||
                    novel.author.contains(query, ignoreCase = true)
        }
    }

    fun getNovelById(id: String): Novel? {
        val preview = sampleNovels.find { it.id == id } ?: return null

        val chapterCount = when (id) {
            "1" -> 150
            "2" -> 108
            "3" -> 551
            "4" -> 270
            "5" -> 300
            "6" -> 2334
            "7" -> 1432
            "8" -> 95
            else -> 50
        }

        val chapters = List(chapterCount) { index ->
            Chapter(
                id = "${id}_${index + 1}",
                number = index + 1,
                title = "Chapter ${index + 1}",
                url = "https://example.com/novel/$id/chapter/${index + 1}"
            )
        }

        val status = when (id) {
            "2", "4", "6", "8" -> "Completed"
            else -> "Ongoing"
        }

        return Novel(
            id = preview.id,
            title = preview.title,
            author = preview.author,
            coverUrl = preview.coverUrl,
            description = preview.description,
            source = preview.source,
            status = status,
            chapters = chapters
        )
    }

    // New function to get chapter content.
    // In a real app, this would fetch HTML from the web and parse it.
    // For now, we generate placeholder text that looks like a novel chapter.
    fun getChapterContent(novelId: String, chapterId: String): ChapterContent? {
        val novel = getNovelById(novelId) ?: return null
        val chapter = novel.chapters.find { it.id == chapterId } ?: return null

        // Find the index of current chapter for prev/next navigation
        val currentIndex = novel.chapters.indexOf(chapter)
        val prevChapterId = if (currentIndex > 0) novel.chapters[currentIndex - 1].id else null
        val nextChapterId = if (currentIndex < novel.chapters.size - 1) novel.chapters[currentIndex + 1].id else null

        // Generate fake chapter content.
        // This creates several paragraphs of Lorem Ipsum-style text.
        val paragraphs = listOf(
            "The morning sun cast long shadows across the ancient courtyard as our protagonist stood at the threshold of a new beginning. Years of training had led to this moment, and yet nothing could have truly prepared them for what lay ahead.",
            "\"Are you certain about this?\" the old master asked, his weathered face betraying neither approval nor concern. His voice carried the weight of decades of wisdom, each word chosen with deliberate care.",
            "A gentle breeze stirred the leaves of the great oak tree that had stood sentinel over this place for generations. Its branches seemed to whisper secrets of those who had come before, their stories woven into the very fabric of its bark.",
            "The path forward was clear, even if the destination remained shrouded in mystery. Sometimes, the journey itself held more value than any endpoint could offer. This was a lesson that would take many more chapters to fully understand.",
            "As the first step was taken, the world seemed to hold its breath. Birds fell silent in the trees, and even the wind paused its eternal dance. It was as if nature itself recognized the significance of this moment.",
            "The weight of expectation pressed down like a physical force, but there was also something else—a spark of excitement, of possibility, of the unknown adventures that awaited just beyond the horizon.",
            "\"Remember,\" the master called out as the distance between them grew, \"strength alone will never be enough. It is wisdom, compassion, and perseverance that will see you through the darkest times.\"",
            "Those words would echo through the days and weeks to come, a guiding light when all other lights went out. For now, though, they were simply words—their true meaning waiting to be discovered through experience.",
            "The road stretched endlessly before them, winding through forests and over mountains, through villages and across rivers. Each step brought new challenges, new allies, and new enemies.",
            "But that is a story for another chapter. For now, it was enough to simply begin."
        )

        // Create the full content by joining paragraphs with double newlines
        val content = paragraphs.joinToString("\n\n")

        return ChapterContent(
            novelId = novelId,
            novelTitle = novel.title,
            chapterId = chapterId,
            chapterTitle = chapter.title,
            chapterNumber = chapter.number,
            content = content,
            totalChapters = novel.chapters.size,
            prevChapterId = prevChapterId,
            nextChapterId = nextChapterId
        )
    }
}

// Data class to hold all information needed for the reader screen.
// This bundles the chapter content with navigation info.
data class ChapterContent(
    val novelId: String,
    val novelTitle: String,
    val chapterId: String,
    val chapterTitle: String,
    val chapterNumber: Int,
    val content: String,
    val totalChapters: Int,
    val prevChapterId: String?,    // Null if this is the first chapter
    val nextChapterId: String?     // Null if this is the last chapter
)