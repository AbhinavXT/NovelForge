package com.abhinavxt.novelreader

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.abhinavxt.novelreader.AppConfig
import com.abhinavxt.novelreader.data.BackupManager
import com.abhinavxt.novelreader.data.ChapterPrefetcher
import com.abhinavxt.novelreader.data.DownloadManager
import com.abhinavxt.novelreader.data.NovelRepository
import com.abhinavxt.novelreader.data.PronunciationManager
import com.abhinavxt.novelreader.data.ReadingStatsTracker
import com.abhinavxt.novelreader.data.TTSManager
import com.abhinavxt.novelreader.data.ThemePreferences
import com.abhinavxt.novelreader.ui.NavigationHost
import com.abhinavxt.novelreader.ui.Screen
import com.abhinavxt.novelreader.ui.theme.NovelReaderTheme
import com.abhinavxt.novelreader.ui.viewmodel.AudioPlayerViewModel
import com.abhinavxt.novelreader.util.Logger

class MainActivity : ComponentActivity() {

    // ── Volume key navigation ───────────────────────────────────
    // Track whether the reader screen is active so we only intercept
    // volume keys when the user is actually reading. This is set by
    // the Compose UI via the callback below.
    @Volatile
    private var isReaderScreenActive = false

    /** Called by NovelReaderApp composable when reader route changes. */
    fun setReaderActive(active: Boolean) {
        isReaderScreenActive = active
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Only intercept volume keys when the reader screen is active.
        // The ReaderScreen composable decides whether to act on the event
        // based on its own volumeKeyNavigation setting — the Activity
        // just forwards every volume key press unconditionally.
        if (isReaderScreenActive) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    (application as NovelReaderApplication).emitVolumeKey(VolumeKeyEvent.UP)
                    return true  // Consume — prevents system volume UI from showing
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    (application as NovelReaderApplication).emitVolumeKey(VolumeKeyEvent.DOWN)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request notification permission for Android 13+
        requestNotificationPermission()

        val app = application as NovelReaderApplication
        val repository = app.repository
        val downloadManager = app.downloadManager
        val ttsManager = app.ttsManager
        val backupManager = app.backupManager
        val themePreferences = app.themePreferences
        val pronunciationManager = app.pronunciationManager
        val readingStatsTracker = app.readingStatsTracker

        setContent {
            // Observe theme settings as Compose state
            val themeMode by themePreferences.themeMode.collectAsState()
            val colorScheme by themePreferences.colorScheme.collectAsState()

            NovelReaderTheme(
                themeMode = themeMode,
                appColorScheme = colorScheme
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NovelReaderApp(
                        repository = repository,
                        downloadManager = downloadManager,
                        ttsManager = ttsManager,
                        backupManager = backupManager,
                        themePreferences = themePreferences,
                        pronunciationManager = pronunciationManager,
                        readingStatsTracker = readingStatsTracker,
                        chapterPrefetcher = app.chapterPrefetcher
                    )
                }
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissions = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            // Needed to scan Music/NovelReader/ for exported audio files
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_MEDIA_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
            if (permissions.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 100)
            }
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                    100
                )
            }
        }
    }
}

@Composable
fun NovelReaderApp(
    repository: NovelRepository,
    downloadManager: DownloadManager,
    ttsManager: TTSManager,
    backupManager: BackupManager,
    themePreferences: ThemePreferences,
    pronunciationManager: PronunciationManager,
    readingStatsTracker: ReadingStatsTracker,
    chapterPrefetcher: ChapterPrefetcher
) {
    val navController = rememberNavController()

    // Deep-link: if the app was opened from an update notification,
    // navigate to the novel's detail screen automatically
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        val activity = context as? ComponentActivity
        activity?.intent?.getStringExtra(
            com.abhinavxt.novelreader.worker.UpdateCheckerWorker.EXTRA_NAVIGATE_NOVEL
        )?.let { novelId ->
            val url = com.abhinavxt.novelreader.data.source.SourceManager.constructNovelUrl(novelId)
            navController.navigate(Screen.Detail.createRoute(novelId, url))
            // Clear the extra so it doesn't re-navigate on config change
            activity.intent.removeExtra(
                com.abhinavxt.novelreader.worker.UpdateCheckerWorker.EXTRA_NAVIGATE_NOVEL
            )
        }
    }

    // ── Track reader screen active state for volume key interception ──
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val currentRoute = currentDestination?.route

    // Tell the Activity whether the reader is active so it knows
    // to intercept volume keys in onKeyDown
    LaunchedEffect(currentRoute) {
        val activity = context as? MainActivity
        val isOnReader = currentRoute?.startsWith("reader/") == true
        activity?.setReaderActive(isOnReader)
    }

    val bottomNavScreens = if (AppConfig.ONLINE_SOURCES_ENABLED) {
        listOf(
            Screen.Home,
            Screen.Search,
            Screen.Library,
            Screen.AudioLibrary,
            Screen.Settings
        )
    } else {
        listOf(
            Screen.Library,
            Screen.AudioLibrary,
            Screen.Settings
        )
    }

    val showBottomNav = bottomNavScreens.any { screen ->
        currentRoute == screen.route
    }

//    val context = LocalContext.current
    val audioPlayerViewModel: AudioPlayerViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = AudioPlayerViewModel.provideFactory(context)
    )

    Scaffold(
        bottomBar = {
            if (showBottomNav) {
                NavigationBar {
                    bottomNavScreens.forEach { screen ->
                        val isSelected = currentDestination?.hierarchy?.any {
                            it.route == screen.route
                        } == true

                        NavigationBarItem(
                            icon = {
                                Icon(
                                    imageVector = screen.icon,
                                    contentDescription = screen.title
                                )
                            },
                            label = { Text(screen.title) },
                            selected = isSelected,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id)
                                    launchSingleTop = true
                                }
                            }
                        )
                    }
                }
            }
        },
        content = { innerPadding ->
            NavigationHost(
                navController = navController,
                repository = repository,
                downloadManager = downloadManager,
                ttsManager = ttsManager,
                backupManager = backupManager,
                themePreferences = themePreferences,
                pronunciationManager = pronunciationManager,
                readingStatsTracker = readingStatsTracker,
                chapterPrefetcher = chapterPrefetcher,
                audioPlayerViewModel = audioPlayerViewModel,
                modifier = Modifier.padding(innerPadding)
            )
        }
    )
}