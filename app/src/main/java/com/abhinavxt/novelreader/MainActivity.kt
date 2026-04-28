package com.abhinavxt.novelreader

import android.Manifest
import android.content.Intent
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.abhinavxt.novelreader.data.BackupManager
import com.abhinavxt.novelreader.data.ChapterPrefetcher
import com.abhinavxt.novelreader.data.DownloadManager
import com.abhinavxt.novelreader.data.NovelRepository
import com.abhinavxt.novelreader.data.PronunciationManager
import com.abhinavxt.novelreader.data.ReadingStatsTracker
import com.abhinavxt.novelreader.data.TTSManager
import com.abhinavxt.novelreader.data.ThemePreferences
import com.abhinavxt.novelreader.data.UpdateChecker
import com.abhinavxt.novelreader.data.source.NovelUrlResolver
import com.abhinavxt.novelreader.ui.NavigationHost
import com.abhinavxt.novelreader.ui.Screen
import com.abhinavxt.novelreader.ui.components.UpdateAvailableDialog
import com.abhinavxt.novelreader.ui.components.openUpdateUrlInBrowser
import com.abhinavxt.novelreader.ui.theme.NovelReaderTheme
import com.abhinavxt.novelreader.ui.viewmodel.AudioPlayerViewModel
import com.abhinavxt.novelreader.util.Logger
import com.abhinavxt.novelreader.util.NetworkMonitor
import com.abhinavxt.novelreader.widget.ContinueReadingWidget

class MainActivity : ComponentActivity() {

    // ── Volume key navigation state ─────────────────────────────
    //
    // Two separate flags because they gate different things:
    //
    //   isReaderScreenActive: are we on the reader route right now?
    //     Set/cleared by the Compose route-change effect.
    //
    //   volumeKeyNavEnabled: has the user turned ON volume-key
    //     scrolling in reader settings? Set by the ReaderScreen
    //     composable whenever settings change.
    //
    // We intercept volume keys ONLY when BOTH are true. If the user
    // has disabled volume-key nav, we fall through to super.onKeyDown
    // so the system volume UI works as normal.
    //
    // @Volatile because these are read from the onKeyDown UI thread
    // and written from Compose's main-thread LaunchedEffect — both
    // are the main thread but @Volatile is the conservative, correct
    // annotation for "written by A, read by B, no synchronization."
    @Volatile
    private var isReaderScreenActive = false

    @Volatile
    private var volumeKeyNavEnabled = false

    /** Called by NovelReaderApp composable when reader route changes. */
    fun setReaderActive(active: Boolean) {
        isReaderScreenActive = active
    }

    /** Called by the Reader composable whenever the volume-nav setting changes. */
    fun setVolumeKeyNavEnabled(enabled: Boolean) {
        volumeKeyNavEnabled = enabled
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Intercept volume keys ONLY when (a) we're on the reader screen
        // AND (b) the user has enabled volume-key scrolling. Otherwise,
        // fall through to system default (volume bar appears, volume
        // changes). This fixes the bug where disabling the setting
        // silently swallowed volume presses.
        if (isReaderScreenActive && volumeKeyNavEnabled) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    (application as NovelReaderApplication).emitVolumeKey(VolumeKeyEvent.UP)
                    return true  // Consume — prevents system volume UI
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    (application as NovelReaderApplication).emitVolumeKey(VolumeKeyEvent.DOWN)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        // Must also intercept KEYUP when we intercepted KEYDOWN, otherwise
        // the system receives an unmatched KEYUP and on some OEMs plays
        // the volume click sound or flashes the volume bar briefly.
        if (isReaderScreenActive && volumeKeyNavEnabled) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_VOLUME_DOWN -> return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNotificationPermission()

        val app = application as NovelReaderApplication
        val repository = app.repository
        val downloadManager = app.downloadManager
        val ttsManager = app.ttsManager
        val backupManager = app.backupManager
        val themePreferences = app.themePreferences
        val pronunciationManager = app.pronunciationManager
        val readingStatsTracker = app.readingStatsTracker
        val updateChecker = app.updateChecker
        val networkMonitor = app.networkMonitor

        setContent {
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
                        chapterPrefetcher = app.chapterPrefetcher,
                        updateChecker = updateChecker,
                        networkMonitor = networkMonitor
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

    /**
     * If the Activity was launched via ACTION_SEND with a URL in the
     * extras, return that URL. Null for any other launch path.
     */
    private fun extractSharedUrl(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        if (intent.type != "text/plain") return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        return NovelUrlResolver.extractFirstUrl(text)
    }

    /**
     * Public version callable from Compose. Reads the Activity's current
     * intent (set at launch or updated by onNewIntent).
     */
    fun extractSharedUrlForNav(): String? = extractSharedUrl(intent)

    /**
     * Called by Android when a new Intent is delivered to an already-running
     * Activity. Happens when the user shares a URL into NovelForge while
     * the app is already in the foreground/background — and also when the
     * continue-reading widget is tapped while the app is already open.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
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
    chapterPrefetcher: ChapterPrefetcher,
    updateChecker: UpdateChecker,
    networkMonitor: NetworkMonitor
) {
    val navController = rememberNavController()

    val context = LocalContext.current

    // ── Observe reader settings to gate volume-key interception ───
    //
    // Without this, MainActivity's onKeyDown swallows volume keys
    // unconditionally when on the reader screen, which breaks the
    // system volume bar when the user has volume-key nav disabled.
    //
    // We pull just the boolean we need from the settings Flow and
    // push it to the Activity. The Flow updates live, so toggling
    // the setting in Quick Settings takes effect immediately.
    val readerSettings by repository.getReaderSettingsFlow()
        .collectAsState(initial = com.abhinavxt.novelreader.data.model.ReaderSettings())

    LaunchedEffect(readerSettings.volumeKeyNavigation) {
        val activity = context as? MainActivity
        activity?.setVolumeKeyNavEnabled(readerSettings.volumeKeyNavigation)
    }

    // ── Deep-link 1: update-checker notification ────────────────
    LaunchedEffect(Unit) {
        val activity = context as? ComponentActivity
        activity?.intent?.getStringExtra(
            com.abhinavxt.novelreader.worker.UpdateCheckerWorker.EXTRA_NAVIGATE_NOVEL
        )?.let { novelId ->
            val url = com.abhinavxt.novelreader.data.source.SourceManager.constructNovelUrl(novelId)
            navController.navigate(Screen.Detail.createRoute(novelId, url))
            activity.intent.removeExtra(
                com.abhinavxt.novelreader.worker.UpdateCheckerWorker.EXTRA_NAVIGATE_NOVEL
            )
        }
    }

    // ── Deep-link 2: share-to-app URL target ────────────────────
    LaunchedEffect(Unit) {
        val activity = context as? MainActivity ?: return@LaunchedEffect
        val sharedUrl = activity.extractSharedUrlForNav() ?: return@LaunchedEffect
        navController.navigate(Screen.ImportFromUrl.createRoute(sharedUrl))
        activity.intent?.replaceExtras(android.os.Bundle())
        activity.intent?.action = Intent.ACTION_MAIN
    }

    // ── Deep-link 3: continue-reading widget tap ────────────────
    LaunchedEffect(Unit) {
        val activity = context as? ComponentActivity ?: return@LaunchedEffect
        val intent = activity.intent ?: return@LaunchedEffect

        val novelId = intent.getStringExtra(ContinueReadingWidget.EXTRA_NOVEL_ID)
        val novelUrl = intent.getStringExtra(ContinueReadingWidget.EXTRA_NOVEL_URL)
        val chapterId = intent.getStringExtra(ContinueReadingWidget.EXTRA_CHAPTER_ID)
        val chapterUrl = intent.getStringExtra(ContinueReadingWidget.EXTRA_CHAPTER_URL)

        if (novelId != null && chapterId != null &&
            novelUrl != null && chapterUrl != null
        ) {
            navController.navigate(Screen.Detail.createRoute(novelId, novelUrl))
            navController.navigate(
                Screen.Reader.createRoute(novelId, chapterId, chapterUrl, novelUrl)
            )
            intent.removeExtra(ContinueReadingWidget.EXTRA_NOVEL_ID)
            intent.removeExtra(ContinueReadingWidget.EXTRA_NOVEL_URL)
            intent.removeExtra(ContinueReadingWidget.EXTRA_CHAPTER_ID)
            intent.removeExtra(ContinueReadingWidget.EXTRA_CHAPTER_URL)
        }
    }

    // ── Track reader screen active state for volume key interception ──
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val currentRoute = currentDestination?.route

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

    val audioPlayerViewModel: AudioPlayerViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = AudioPlayerViewModel.provideFactory(context)
    )

    val updateStatus by updateChecker.status.collectAsState()
    var showUpdateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(updateStatus) {
        val s = updateStatus
        if (s is UpdateChecker.Status.Available &&
            !updateChecker.isDismissed(s.info.latestVersion)
        ) {
            showUpdateDialog = true
        }
    }

    if (showUpdateDialog) {
        val s = updateStatus
        if (s is UpdateChecker.Status.Available) {
            UpdateAvailableDialog(
                info = s.info,
                onDismiss = {
                    updateChecker.dismiss(s.info.latestVersion)
                    showUpdateDialog = false
                },
                onUpdate = {
                    openUpdateUrlInBrowser(context, s.info.downloadUrl)
                    showUpdateDialog = false
                }
            )
        }
    }

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
                updateChecker = updateChecker,
                networkMonitor = networkMonitor,
                modifier = Modifier.padding(innerPadding)
            )
        }
    )
}