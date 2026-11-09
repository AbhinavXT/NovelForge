package com.abhinavxt.novelforge.ui.components

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.abhinavxt.novelforge.data.ThemePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App lock: the device's own screen lock (fingerprint, face, PIN...) guards
 * the app on launch and after [RELOCK_AFTER_MS] away. Create in onCreate,
 * before the activity starts, so the credential launcher can register.
 *
 * The app stays composed under the lock screen, so unlocking returns to the
 * same place; a short trip to a file picker or share sheet doesn't relock.
 */
class AppLock(
    private val activity: ComponentActivity,
    private val prefs: ThemePreferences,
    saved: Bundle?
) : DefaultLifecycleObserver {

    private val _locked = MutableStateFlow(saved?.getBoolean(KEY_LOCKED) ?: prefs.appLock.value)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    // elapsedRealtime survives process death, so a restored activity still knows how long it was away.
    private var leftAt = saved?.getLong(KEY_LEFT_AT) ?: 0L
    private var prompting = false

    private val credentialLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            prompting = false
            if (it.resultCode == Activity.RESULT_OK) _locked.value = false
        }

    init {
        activity.lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        val away = leftAt != 0L && SystemClock.elapsedRealtime() - leftAt > RELOCK_AFTER_MS
        if (prefs.appLock.value && !prompting && away) _locked.value = true
        leftAt = 0L
        applyRecents()
    }

    override fun onStop(owner: LifecycleOwner) {
        // The credential screen stops us too; that isn't leaving.
        if (!prompting) leftAt = SystemClock.elapsedRealtime()
    }

    fun save(out: Bundle) {
        out.putBoolean(KEY_LOCKED, _locked.value)
        out.putLong(KEY_LEFT_AT, leftAt)
    }

    /** Keeps the book off the Recents thumbnail while the lock is on (Android 13+). */
    fun applyRecents() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.setRecentsScreenshotEnabled(!prefs.appLock.value)
        }
    }

    fun unlock() {
        if (prompting) return
        // The screen lock was removed since: there's nothing to check against, and no way back in otherwise.
        if (!hasScreenLock(activity)) {
            _locked.value = false
            return
        }
        prompting = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricPrompt.Builder(activity)
                .setTitle("Unlock NovelForge")
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build()
                .authenticate(CancellationSignal(), activity.mainExecutor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            prompting = false
                            _locked.value = false
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            prompting = false
                        }
                    })
        } else {
            @Suppress("DEPRECATION") // The only screen-lock check below Android 11 without a library.
            val intent = activity.getSystemService(KeyguardManager::class.java)
                .createConfirmDeviceCredentialIntent("Unlock NovelForge", null)
            if (intent == null) {
                prompting = false
                _locked.value = false
            } else {
                credentialLauncher.launch(intent)
            }
        }
    }

    companion object {
        const val RELOCK_AFTER_MS = 60_000L
        private const val KEY_LOCKED = "app_lock_locked"
        private const val KEY_LEFT_AT = "app_lock_left_at"

        fun hasScreenLock(context: Context): Boolean =
            context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
    }
}

/** Covers the app while locked; its Surface takes every touch meant for what's underneath. */
@Composable
fun LockScreen(onUnlock: () -> Unit, onLeave: () -> Unit) {
    BackHandler(onBack = onLeave)
    LaunchedEffect(Unit) { onUnlock() }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(16.dp))
            Text("NovelForge is locked", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onUnlock) { Text("Unlock") }
        }
    }
}
