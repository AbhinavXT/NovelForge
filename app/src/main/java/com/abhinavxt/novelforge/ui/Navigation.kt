package com.abhinavxt.novelforge.ui

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.abhinavxt.novelforge.AppConfig
import com.abhinavxt.novelforge.data.DownloadManager
import com.abhinavxt.novelforge.data.NovelRepository
import com.abhinavxt.novelforge.data.ThemePreferences
import com.abhinavxt.novelforge.ui.screens.*
import java.net.URLDecoder
import java.net.URLEncoder
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LibraryMusic
import com.abhinavxt.novelforge.data.BackupManager
import com.abhinavxt.novelforge.data.ChapterPrefetcher
import com.abhinavxt.novelforge.data.PronunciationManager
import com.abhinavxt.novelforge.data.ReadingStatsTracker
import com.abhinavxt.novelforge.data.TTSManager
import com.abhinavxt.novelforge.data.UpdateChecker
import com.abhinavxt.novelforge.data.source.SourceManager
import com.abhinavxt.novelforge.ui.viewmodel.AudioPlayerViewModel
import com.abhinavxt.novelforge.util.NetworkMonitor


sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Home : Screen("home", "Home", Icons.Default.Home)
    object Search : Screen("search", "Search", Icons.Default.Search)
    object Library : Screen("library", "Library", Icons.Default.MenuBook)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)

    object Detail : Screen(
        route = "detail/{novelId}/{novelUrl}",
        title = "Detail",
        icon = Icons.Default.MenuBook
    ) {
        fun createRoute(novelId: String, novelUrl: String): String {
            val encoded = URLEncoder.encode(novelUrl, "UTF-8")
            return "detail/$novelId/$encoded"
        }
    }

    object Reader : Screen(
        // targetParagraph is an optional deep-jump used by full-text
        // search ("open the reader AT the match"); -1 = no jump,
        // normal progress restore. Optional query args don't affect
        // existing callers.
        route = "reader/{novelId}/{chapterId}/{chapterUrl}/{novelUrl}?targetParagraph={targetParagraph}",
        title = "Reader",
        icon = Icons.Default.MenuBook
    ) {
        fun createRoute(
            novelId: String,
            chapterId: String,
            chapterUrl: String,
            novelUrl: String,
            targetParagraph: Int = -1
        ): String {
            val encodedChapterUrl = URLEncoder.encode(chapterUrl, "UTF-8")
            val encodedNovelUrl = URLEncoder.encode(novelUrl, "UTF-8")
            return "reader/$novelId/$chapterId/$encodedChapterUrl/$encodedNovelUrl" +
                    if (targetParagraph >= 0) "?targetParagraph=$targetParagraph" else ""
        }
    }

    object TextSearch : Screen(
        route = "text_search",
        title = "Search in Books",
        icon = Icons.Default.Search
    )

    object Codex : Screen(
        route = "codex/{novelId}",
        title = "Codex",
        icon = Icons.Default.Search
    ) {
        fun createRoute(novelId: String) = "codex/$novelId"
    }

    object CodexGraph : Screen(
        route = "codex_graph/{novelId}",
        title = "Relationship Graph",
        icon = Icons.Default.Search
    ) {
        fun createRoute(novelId: String) = "codex_graph/$novelId"
    }

    object Downloads : Screen(
        route = "downloads",
        title = "Downloads",
        icon = Icons.Default.Download
    )

    object Updates : Screen(
        route = "updates",
        title = "Updates",
        icon = Icons.Default.MenuBook
    )

    object History : Screen(
        route = "history",
        title = "Reading History",
        icon = Icons.Default.MenuBook
    )

    object AudioLibrary : Screen(
        route = "audio_library",
        title = "Audio",
        icon = Icons.Default.LibraryMusic
    )

    object AudioChapters : Screen(
        route = "audio_chapters/{folderName}",
        title = "Audio Chapters",
        icon = Icons.Default.LibraryMusic
    ) {
        fun createRoute(folderName: String): String {
            val encoded = URLEncoder.encode(folderName, "UTF-8")
            return "audio_chapters/$encoded"
        }
    }

    object AudioPlayer : Screen(
        route = "audio_player/{folderName}/{filePath}",
        title = "Audio Player",
        icon = Icons.Default.LibraryMusic
    ) {
        fun createRoute(folderName: String, filePath: String): String {
            val encodedFolder = URLEncoder.encode(folderName, "UTF-8")
            val encodedPath = URLEncoder.encode(filePath, "UTF-8")
            return "audio_player/$encodedFolder/$encodedPath"
        }
    }

    object Pronunciation : Screen(
        route = "pronunciation",
        title = "Pronunciation",
        icon = Icons.Default.MenuBook
    )

    object ReadingStats : Screen(
        route = "reading_stats",
        title = "Reading Stats",
        icon = Icons.Default.MenuBook
    )

    object Changelog : Screen(
        route = "changelog",
        title = "Changelog",
        icon = Icons.Default.MenuBook
    )

    object ImportFromUrl : Screen(
        route = "import_from_url/{url}",
        title = "Add Novel",
        icon = Icons.Default.MenuBook
    ) {
        fun createRoute(url: String): String {
            // URL must be URL-encoded because the raw URL contains ':/' etc.
            // Route-arg encoding is standard for any user-supplied strings.
            val encoded = URLEncoder.encode(url, "UTF-8")
            return "import_from_url/$encoded"
        }
    }
}

private fun constructNovelUrl(novelId: String): String {
    return SourceManager.constructNovelUrl(novelId)
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun NavigationHost(
    navController: NavHostController,
    repository: NovelRepository,
    downloadManager: DownloadManager,
    ttsManager: TTSManager,
    backupManager: BackupManager,
    themePreferences: ThemePreferences,
    pronunciationManager: PronunciationManager,
    readingStatsTracker: ReadingStatsTracker,
    chapterPrefetcher: ChapterPrefetcher,
    audioPlayerViewModel: AudioPlayerViewModel,
    updateChecker: UpdateChecker,
    networkMonitor: NetworkMonitor,
    modifier: Modifier = Modifier
) {
    val startRoute = if (AppConfig.ONLINE_SOURCES_ENABLED) Screen.Home.route else Screen.Library.route

    androidx.compose.animation.SharedTransitionLayout(modifier = modifier) {
        androidx.compose.runtime.CompositionLocalProvider(
            com.abhinavxt.novelforge.ui.components.LocalSharedTransitionScope provides this@SharedTransitionLayout
        ) {
            NavHost(
                navController = navController,
                startDestination = startRoute,
                // ── Default screen transitions ──────────────────────────
                // Slide in from right + fade in when navigating forward.
                // Slide out to right + fade out when navigating back.
                // 250ms feels snappy without being jarring.
                enterTransition = {
                    slideInHorizontally(
                        initialOffsetX = { fullWidth -> fullWidth / 4 },
                        animationSpec = tween(250)
                    ) + fadeIn(animationSpec = tween(250))
                },
                exitTransition = {
                    slideOutHorizontally(
                        targetOffsetX = { fullWidth -> -fullWidth / 4 },
                        animationSpec = tween(250)
                    ) + fadeOut(animationSpec = tween(150))
                },
                popEnterTransition = {
                    slideInHorizontally(
                        initialOffsetX = { fullWidth -> -fullWidth / 4 },
                        animationSpec = tween(250)
                    ) + fadeIn(animationSpec = tween(250))
                },
                popExitTransition = {
                    slideOutHorizontally(
                        targetOffsetX = { fullWidth -> fullWidth / 4 },
                        animationSpec = tween(250)
                    ) + fadeOut(animationSpec = tween(150))
                }
            ) {
                // ── Online source screens (Browse/Search/Home) ────────────
                // Only registered when online sources are enabled

                if (AppConfig.ONLINE_SOURCES_ENABLED) {
                    composable(Screen.Home.route) {
                        androidx.compose.runtime.CompositionLocalProvider(
                            com.abhinavxt.novelforge.ui.components.LocalNavAnimatedVisibilityScope provides this@composable
                        ) {

                            HomeScreen(
                                repository = repository,
                                readingStatsTracker = readingStatsTracker,
                                onBrowseClick = {
                                    navController.navigate(Screen.Search.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                onNovelClick = { novelId ->
                                    val url = constructNovelUrl(novelId)
                                    navController.navigate(Screen.Detail.createRoute(novelId, url))
                                },
                                onContinueReading = { novelId, chapterId, chapterUrl, novelUrl ->
                                    navController.navigate(
                                        Screen.Reader.createRoute(
                                            novelId = novelId,
                                            chapterId = chapterId,
                                            chapterUrl = chapterUrl,
                                            novelUrl = novelUrl
                                        )
                                    )
                                },
                                onSeeLibrary = {
                                    navController.navigate(Screen.Library.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                onSeeUpdates = {
                                    navController.navigate(Screen.Updates.route)
                                },
                                networkMonitor = networkMonitor
                            )

                        }
                    }

                    composable(Screen.Search.route) {
                        SearchScreen(
                            repository = repository,
                            onNovelClick = { novelPreview ->
                                val url = constructNovelUrl(novelPreview.id)
                                navController.navigate(Screen.Detail.createRoute(novelPreview.id, url))
                            },
                            networkMonitor = networkMonitor
                        )
                    }

                    composable(Screen.History.route) {
                        HistoryScreen(
                            repository = repository,
                            onBackClick = { navController.popBackStack() },
                            onNovelClick = { novelId ->
                                val url = constructNovelUrl(novelId)
                                navController.navigate(Screen.Detail.createRoute(novelId, url))
                            }
                        )
                    }

                    composable(Screen.Updates.route) {
                        UpdatesScreen(
                            repository = repository,
                            onBackClick = { navController.popBackStack() },
                            onNovelClick = { novelId ->
                                val url = constructNovelUrl(novelId)
                                navController.navigate(Screen.Detail.createRoute(novelId, url))
                            }
                        )
                    }

                    composable(Screen.Downloads.route) {
                        DownloadsScreen(
                            repository = repository,
                            downloadManager = downloadManager,
                            onBackClick = { navController.popBackStack() },
                            onNovelClick = { novelId ->
                                val url = constructNovelUrl(novelId)
                                navController.navigate(Screen.Detail.createRoute(novelId, url))
                            },
                            networkMonitor = networkMonitor
                        )
                    }
                }

                composable(Screen.Library.route) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        com.abhinavxt.novelforge.ui.components.LocalNavAnimatedVisibilityScope provides this@composable
                    ) {

                        LibraryScreen(
                            repository = repository,
                            themePreferences = themePreferences,
                            onNovelClick = { novelId ->
                                val url = constructNovelUrl(novelId)
                                navController.navigate(Screen.Detail.createRoute(novelId, url))
                            },
                            onTextSearchClick = {
                                navController.navigate(Screen.TextSearch.route)
                            },
                            networkMonitor = networkMonitor
                        )

                    }
                }

                // ── Full-text search over downloaded chapters ────────
                composable(Screen.TextSearch.route) {
                    TextSearchScreen(
                        repository = repository,
                        onBackClick = { navController.popBackStack() },
                        onHitClick = { novelId, chapterId, chapterUrl, paragraphIndex ->
                            navController.navigate(
                                Screen.Reader.createRoute(
                                    novelId = novelId,
                                    chapterId = chapterId,
                                    chapterUrl = chapterUrl,
                                    novelUrl = constructNovelUrl(novelId),
                                    targetParagraph = paragraphIndex
                                )
                            )
                        }
                    )
                }

                composable(Screen.Settings.route) {
                    SettingsScreen(
                        backupManager = backupManager,
                        themePreferences = themePreferences,
                        onNavigateToPronunciation = {
                            navController.navigate(Screen.Pronunciation.route)
                        },
                        onNavigateToReadingStats = {
                            navController.navigate(Screen.ReadingStats.route)
                        },
                        onNavigateToChangelog = {
                            navController.navigate(Screen.Changelog.route)
                        },
                        updateChecker = updateChecker,
                    )
                }

                composable(Screen.Pronunciation.route) {
                    PronunciationScreen(
                        pronunciationManager = pronunciationManager,
                        onBackClick = { navController.popBackStack() }
                    )
                }

                composable(Screen.ReadingStats.route) {
                    ReadingStatsScreen(
                        readingStatsTracker = readingStatsTracker,
                        repository = repository,
                        onBackClick = { navController.popBackStack() },
                        onHistoryClick = { navController.navigate(Screen.History.route) }
                    )
                }

                composable(Screen.Changelog.route) {
                    ChangelogScreen(
                        onBackClick = { navController.popBackStack() }
                    )
                }

                composable(
                    route = Screen.Detail.route,
                    arguments = listOf(
                        navArgument("novelId") { type = NavType.StringType },
                        navArgument("novelUrl") { type = NavType.StringType }
                    )
                ) { backStackEntry ->
                    androidx.compose.runtime.CompositionLocalProvider(
                        com.abhinavxt.novelforge.ui.components.LocalNavAnimatedVisibilityScope provides this@composable
                    ) {
                        val novelId = backStackEntry.arguments?.getString("novelId")!!
                        val novelUrl = URLDecoder.decode(
                            backStackEntry.arguments?.getString("novelUrl")!!,
                            "UTF-8"
                        )

                        NovelDetailScreen(
                            novelId = novelId,
                            novelUrl = novelUrl,
                            repository = repository,
                            downloadManager = downloadManager,
                            ttsManager = ttsManager,
                            readingStatsTracker = readingStatsTracker,
                            networkMonitor = networkMonitor,
                            onBackClick = { navController.popBackStack() },
                            onChapterClick = { chapterId, chapterUrl ->
                                navController.navigate(
                                    Screen.Reader.createRoute(
                                        novelId = novelId,
                                        chapterId = chapterId,
                                        chapterUrl = chapterUrl,
                                        novelUrl = novelUrl
                                    )
                                )
                            },
                            onCodexClick = {
                                navController.navigate(Screen.Codex.createRoute(novelId))
                            }
                        )

                    }
                }

                // ── Character Codex ──────────────────────────────────
                composable(
                    route = Screen.Codex.route,
                    arguments = listOf(
                        navArgument("novelId") { type = NavType.StringType }
                    )
                ) { backStackEntry ->
                    val novelId = backStackEntry.arguments?.getString("novelId")!!
                    CodexScreen(
                        repository = repository,
                        novelId = novelId,
                        onBackClick = { navController.popBackStack() },
                        onMentionClick = { chapterId, chapterUrl, paragraphIndex ->
                            navController.navigate(
                                Screen.Reader.createRoute(
                                    novelId = novelId,
                                    chapterId = chapterId,
                                    chapterUrl = chapterUrl,
                                    novelUrl = constructNovelUrl(novelId),
                                    targetParagraph = paragraphIndex
                                )
                            )
                        },
                        onGraphClick = {
                            navController.navigate(Screen.CodexGraph.createRoute(novelId))
                        }
                    )
                }

                // ── Character relationship graph ─────────────────────
                composable(
                    route = Screen.CodexGraph.route,
                    arguments = listOf(
                        navArgument("novelId") { type = NavType.StringType }
                    )
                ) { backStackEntry ->
                    val novelId = backStackEntry.arguments?.getString("novelId")!!
                    CodexGraphScreen(
                        repository = repository,
                        novelId = novelId,
                        onBackClick = { navController.popBackStack() },
                        onChapterClick = { chapterId, chapterUrl, paragraphIndex ->
                            navController.navigate(
                                Screen.Reader.createRoute(
                                    novelId = novelId,
                                    chapterId = chapterId,
                                    chapterUrl = chapterUrl,
                                    novelUrl = constructNovelUrl(novelId),
                                    targetParagraph = paragraphIndex
                                )
                            )
                        }
                    )
                }

                composable(
                    route = Screen.Reader.route,
                    arguments = listOf(
                        navArgument("novelId") { type = NavType.StringType },
                        navArgument("chapterId") { type = NavType.StringType },
                        navArgument("chapterUrl") { type = NavType.StringType },
                        navArgument("novelUrl") { type = NavType.StringType },
                        navArgument("targetParagraph") {
                            type = NavType.IntType
                            defaultValue = -1
                        }
                    )
                ) { backStackEntry ->
                    val novelId = backStackEntry.arguments?.getString("novelId")!!
                    val chapterId = backStackEntry.arguments?.getString("chapterId")!!
                    val chapterUrl = URLDecoder.decode(
                        backStackEntry.arguments?.getString("chapterUrl")!!,
                        "UTF-8"
                    )
                    val novelUrl = URLDecoder.decode(
                        backStackEntry.arguments?.getString("novelUrl")!!,
                        "UTF-8"
                    )
                    val targetParagraph = backStackEntry.arguments?.getInt("targetParagraph") ?: -1

                    ReaderScreen(
                        novelId = novelId,
                        chapterId = chapterId,
                        chapterUrl = chapterUrl,
                        novelUrl = novelUrl,
                        targetParagraph = targetParagraph,
                        repository = repository,
                        ttsManager = ttsManager,
                        themePreferences = themePreferences,
                        statsTracker = readingStatsTracker,
                        chapterPrefetcher = chapterPrefetcher,
                        onBackClick = { navController.popBackStack() }
                    )
                }

                // ── Audio screens ────────────────────────────────────────

                composable(Screen.AudioLibrary.route) {
                    AudioLibraryScreen(
                        viewModel = audioPlayerViewModel,
                        onNovelClick = { folderName ->
                            navController.navigate(Screen.AudioChapters.createRoute(folderName))
                        },
                        onBackClick = { navController.popBackStack() }
                    )
                }

                composable(
                    route = Screen.AudioChapters.route,
                    arguments = listOf(
                        navArgument("folderName") { type = NavType.StringType }
                    )
                ) { backStackEntry ->
                    val folderName = URLDecoder.decode(
                        backStackEntry.arguments?.getString("folderName")!!,
                        "UTF-8"
                    )

                    AudioChapterListScreen(
                        viewModel = audioPlayerViewModel,
                        novelFolderName = folderName,
                        onChapterClick = { folder, filePath ->
                            navController.navigate(Screen.AudioPlayer.createRoute(folder, filePath))
                        },
                        onBackClick = { navController.popBackStack() }
                    )
                }

                composable(
                    route = Screen.AudioPlayer.route,
                    arguments = listOf(
                        navArgument("folderName") { type = NavType.StringType },
                        navArgument("filePath") { type = NavType.StringType }
                    )
                ) { backStackEntry ->
                    val folderName = URLDecoder.decode(
                        backStackEntry.arguments?.getString("folderName")!!,
                        "UTF-8"
                    )
                    val filePath = URLDecoder.decode(
                        backStackEntry.arguments?.getString("filePath")!!,
                        "UTF-8"
                    )

                    AudioPlayerScreen(
                        viewModel = audioPlayerViewModel,
                        novelFolderName = folderName,
                        chapterFilePath = filePath,
                        onBackClick = { navController.popBackStack() }
                    )
                }

                composable(
                    route = Screen.ImportFromUrl.route,
                    arguments = listOf(
                        navArgument("url") { type = NavType.StringType }
                    )
                ) { backStackEntry ->
                    val url = URLDecoder.decode(
                        backStackEntry.arguments?.getString("url") ?: "",
                        "UTF-8"
                    )
                    ImportFromUrlScreen(
                        sharedUrl = url,
                        repository = repository,
                        networkMonitor = networkMonitor,
                        onBackClick = { navController.popBackStack() },
                        onOpenNovel = { novelId, novelUrl ->
                            // Replace the import screen with the detail screen so
                            // the user doesn't see ImportFromUrl again if they hit
                            // back from the reader.
                            navController.navigate(Screen.Detail.createRoute(novelId, novelUrl)) {
                                popUpTo(Screen.ImportFromUrl.route) { inclusive = true }
                            }
                        }
                    )
                }
            }
        }
    }
}