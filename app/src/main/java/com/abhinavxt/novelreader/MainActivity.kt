package com.abhinavxt.novelreader

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import com.abhinavxt.novelreader.data.DownloadManager
import com.abhinavxt.novelreader.data.NovelRepository
import com.abhinavxt.novelreader.data.TTSManager
import com.abhinavxt.novelreader.ui.NavigationHost
import com.abhinavxt.novelreader.ui.Screen
import com.abhinavxt.novelreader.ui.theme.NovelReaderTheme
import com.abhinavxt.novelreader.ui.viewmodel.AudioPlayerViewModel
import com.abhinavxt.novelreader.util.Logger

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request notification permission for Android 13+
        requestNotificationPermission()

        val app = application as NovelReaderApplication
        val repository = app.repository
        val downloadManager = app.downloadManager
        val ttsManager = app.ttsManager
        val backupManager = app.backupManager

        setContent {
            NovelReaderTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NovelReaderApp(
                        repository = repository,
                        downloadManager = downloadManager,
                        ttsManager = ttsManager,
                        backupManager = backupManager
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
    backupManager: BackupManager
) {
    val navController = rememberNavController()

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

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val currentRoute = currentDestination?.route

    val showBottomNav = bottomNavScreens.any { screen ->
        currentRoute == screen.route
    }

    val context = LocalContext.current
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
                audioPlayerViewModel = audioPlayerViewModel,
                modifier = Modifier.padding(innerPadding)
            )
        }
    )
}