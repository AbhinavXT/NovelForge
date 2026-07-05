package com.abhinavxt.novelreader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.consumeWindowInsets
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.abhinavxt.novelreader.data.BackupManager
import com.abhinavxt.novelreader.data.ChapterPrefetcher
import com.abhinavxt.novelreader.data.DownloadManager
import com.abhinavxt.novelreader.data.NovelRepository
import com.abhinavxt.novelreader.data.PronunciationManager
import com.abhinavxt.novelreader.data.ReadingStatsTracker
import com.abhinavxt.novelreader.data.TTSManager
import com.abhinavxt.novelreader.data.ThemeMode
import com.abhinavxt.novelreader.data.ThemePreferences
import com.abhinavxt.novelreader.data.UpdateChecker
import com.abhinavxt.novelreader.data.source.NovelUrlResolver
import com.abhinavxt.novelreader.data.source.SourceManager
import com.abhinavxt.novelreader.worker.UpdateCheckerWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    // ── Deep-link intents delivered while the Activity is alive ─
    //
    // onNewIntent() fires when the app is already running (share
    // target, widget tap, update notification). Compose can't
    // observe setIntent(), so we also push the Intent into this
    // StateFlow; NovelReaderApp collects it and routes it through
    // handleDeepLink(). Without this, warm-launch deep links were
    // silently dropped — the old LaunchedEffect(Unit) handlers only
    // ever ran once, against the cold-start Intent.
    private val _newIntents = MutableStateFlow<Intent?>(null)
    val newIntents: StateFlow<Intent?> = _newIntents.asStateFlow()

    /** Called by the UI after a pending deep-link intent has been routed. */
    fun consumeNewIntent() {
        _newIntents.value = null
    }

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
        // Opt in to edge-to-edge explicitly. Android 15 (target 35)
        // enforces it anyway — opting in now means we control the
        // timing and have verified the insets, instead of layouts
        // jumping under the system bars on a future targetSdk bump.
        enableEdgeToEdge()
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

            // Resolve dark mode the same way NovelReaderTheme does, so
            // system-bar icon contrast follows the IN-APP theme setting.
            // Plain enableEdgeToEdge() detects darkness from the system
            // uiMode only — wrong when the user forces light/dark inside
            // the app while the system is set to the opposite.
            val darkTheme = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT,
                    ) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(
                        lightScrim,
                        darkScrim,
                    ) { darkTheme },
                )
                onDispose {}
            }

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
     * Called by Android when a new Intent is delivered to an already-running
     * Activity — share target, widget tap, or update notification while the
     * app is in the foreground/background.
     *
     * setIntent() keeps Activity.intent consistent for anyone reading it;
     * the StateFlow emission is what actually makes Compose react.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        _newIntents.value = intent
    }
}

// ── Edge-to-edge scrims ──────────────────────────────────────────
// Used for the navigation bar on older APIs / 3-button navigation,
// where a fully transparent bar isn't supported. Values match the
// androidx edge-to-edge sample.
private val lightScrim = android.graphics.Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val darkScrim = android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b)

/**
 * Routes a deep-link Intent to the right screen. Returns true if the
 * Intent carried a deep link and navigation happened.
 *
 * Handled cases (mutually exclusive in practice):
 *  1. Update-checker notification tap → novel detail
 *  2. Continue-reading widget tap     → detail + reader (builds back stack)
 *  3. ACTION_SEND shared URL          → import-from-URL screen
 *
 * One function for BOTH delivery paths — the cold-start Intent and
 * warm-launch Intents from onNewIntent(). Extras are removed / the
 * action neutralized after handling so a configuration change doesn't
 * replay the navigation.
 */
private fun handleDeepLink(intent: Intent, navController: NavHostController): Boolean {
    // ── 1. Update-checker notification ──────────────────────────
    intent.getStringExtra(UpdateCheckerWorker.EXTRA_NAVIGATE_NOVEL)?.let { novelId ->
        val url = SourceManager.constructNovelUrl(novelId)
        navController.navigate(Screen.Detail.createRoute(novelId, url))
        intent.removeExtra(UpdateCheckerWorker.EXTRA_NAVIGATE_NOVEL)
        return true
    }

    // ── 2. Continue-reading widget tap ──────────────────────────
    val widgetNovelId = intent.getStringExtra(ContinueReadingWidget.EXTRA_NOVEL_ID)
    val widgetNovelUrl = intent.getStringExtra(ContinueReadingWidget.EXTRA_NOVEL_URL)
    val widgetChapterId = intent.getStringExtra(ContinueReadingWidget.EXTRA_CHAPTER_ID)
    val widgetChapterUrl = intent.getStringExtra(ContinueReadingWidget.EXTRA_CHAPTER_URL)

    if (widgetNovelId != null && widgetChapterId != null &&
        widgetNovelUrl != null && widgetChapterUrl != null
    ) {
        // Detail first so Back from the reader lands on the novel page.
        navController.navigate(Screen.Detail.createRoute(widgetNovelId, widgetNovelUrl))
        navController.navigate(
            Screen.Reader.createRoute(
                widgetNovelId, widgetChapterId, widgetChapterUrl, widgetNovelUrl
            )
        )
        intent.removeExtra(ContinueReadingWidget.EXTRA_NOVEL_ID)
        intent.removeExtra(ContinueReadingWidget.EXTRA_NOVEL_URL)
        intent.removeExtra(ContinueReadingWidget.EXTRA_CHAPTER_ID)
        intent.removeExtra(ContinueReadingWidget.EXTRA_CHAPTER_URL)
        return true
    }

    // ── 3. Share-to-app URL (ACTION_SEND text/plain) ────────────
    if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
        val sharedUrl = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?.let { NovelUrlResolver.extractFirstUrl(it) }
        if (sharedUrl != null) {
            navController.navigate(Screen.ImportFromUrl.createRoute(sharedUrl))
            // Neutralize so rotation doesn't re-open the import screen.
            intent.replaceExtras(Bundle())
            intent.action = Intent.ACTION_MAIN
            return true
        }
    }

    return false
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

    // ── Deep links: one handler, two delivery paths ──────────────
    //
    //   Cold start:  the launch Intent, handled once on first
    //                composition.
    //   Warm launch: onNewIntent() pushes into newIntents; we collect
    //                and route each one, then mark it consumed.
    //
    // The old code had three separate LaunchedEffect(Unit) blocks —
    // which never re-run — so any deep link arriving while the app
    // was already alive (shared URL, widget tap, notification) was
    // silently dropped.
    val mainActivity = context as? MainActivity
    if (mainActivity != null) {
        LaunchedEffect(Unit) {
            handleDeepLink(mainActivity.intent, navController)
        }

        val pendingIntent by mainActivity.newIntents.collectAsState()
        LaunchedEffect(pendingIntent) {
            pendingIntent?.let { intent ->
                handleDeepLink(intent, navController)
                mainActivity.consumeNewIntent()
            }
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
                                    // saveState/restoreState preserve each
                                    // tab's back stack — search query, scroll
                                    // position, results. Without them every
                                    // tab switch recreated the destination
                                    // from scratch.
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
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
                // consumeWindowInsets is REQUIRED alongside edge-to-edge:
                // 11 screens carry their own nested Scaffold, and without
                // marking these insets as consumed each inner Scaffold
                // would apply status/navigation-bar padding a second time.
                modifier = Modifier
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
            )
        }
    )
}