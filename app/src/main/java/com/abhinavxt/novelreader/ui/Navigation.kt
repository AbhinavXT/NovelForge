package com.abhinavxt.novelreader.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.abhinavxt.novelreader.data.DownloadManager
import com.abhinavxt.novelreader.data.NovelRepository
import com.abhinavxt.novelreader.ui.screens.*
import java.net.URLDecoder
import java.net.URLEncoder
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LibraryMusic
import com.abhinavxt.novelreader.data.BackupManager
import com.abhinavxt.novelreader.data.TTSManager
import com.abhinavxt.novelreader.ui.viewmodel.AudioPlayerViewModel

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
        route = "reader/{novelId}/{chapterId}/{chapterUrl}/{novelUrl}",
        title = "Reader",
        icon = Icons.Default.MenuBook
    ) {
        fun createRoute(novelId: String, chapterId: String, chapterUrl: String, novelUrl: String): String {
            val encodedChapterUrl = URLEncoder.encode(chapterUrl, "UTF-8")
            val encodedNovelUrl = URLEncoder.encode(novelUrl, "UTF-8")
            return "reader/$novelId/$chapterId/$encodedChapterUrl/$encodedNovelUrl"
        }
    }

    object Downloads : Screen(
        route = "downloads",
        title = "Downloads",
        icon = Icons.Default.Download
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
}

private fun constructNovelUrl(novelId: String): String {
    return when {
        novelId.startsWith("rr_") -> {
            "https://www.royalroad.com/fiction/${novelId.removePrefix("rr_")}"
        }
        novelId.startsWith("rnf_") -> {
            "https://readnovelfull.com/${novelId.removePrefix("rnf_")}.html"
        }
        novelId.startsWith("local_") -> {
            // Local novels don't need a URL, use placeholder
            "local://$novelId"
        }
        else -> ""
    }
}

@Composable
fun NavigationHost(
    navController: NavHostController,
    repository: NovelRepository,
    downloadManager: DownloadManager,
    ttsManager: TTSManager,
    backupManager: BackupManager,
    audioPlayerViewModel: AudioPlayerViewModel,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route,
        modifier = modifier
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                repository = repository,
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
                }
            )
        }

        composable(Screen.Search.route) {
            SearchScreen(
                repository = repository,
                onNovelClick = { novelPreview ->
                    val url = constructNovelUrl(novelPreview.id)
                    navController.navigate(Screen.Detail.createRoute(novelPreview.id, url))
                }
            )
        }

        composable(Screen.Library.route) {
            LibraryScreen(
                repository = repository,
                onNovelClick = { novelId ->
                    val url = constructNovelUrl(novelId)
                    navController.navigate(Screen.Detail.createRoute(novelId, url))
                }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                backupManager = backupManager
            )
        }

        composable(
            route = Screen.Detail.route,
            arguments = listOf(
                navArgument("novelId") { type = NavType.StringType },
                navArgument("novelUrl") { type = NavType.StringType }
            )
        ) { backStackEntry ->
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
                }
            )
        }

        composable(
            route = Screen.Reader.route,
            arguments = listOf(
                navArgument("novelId") { type = NavType.StringType },
                navArgument("chapterId") { type = NavType.StringType },
                navArgument("chapterUrl") { type = NavType.StringType },
                navArgument("novelUrl") { type = NavType.StringType }
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

            ReaderScreen(
                novelId = novelId,
                chapterId = chapterId,
                chapterUrl = chapterUrl,
                novelUrl = novelUrl,
                repository = repository,
                ttsManager = ttsManager,
                onBackClick = { navController.popBackStack() }
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
                }
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
    }
}