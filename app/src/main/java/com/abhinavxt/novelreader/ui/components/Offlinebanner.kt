package com.abhinavxt.novelreader.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.abhinavxt.novelreader.util.NetworkMonitor

/**
 * Slim banner shown at the top of online-dependent screens when the
 * device has no network.
 *
 * ── Design notes ──
 *  • Animated slide-down-and-fade entrance; slide-up-and-fade exit.
 *    Draws attention without being jarring.
 *  • Uses MaterialTheme's errorContainer / onErrorContainer — matches
 *    the theme's error palette automatically for light + dark modes.
 *  • Icon + single line of text. Deliberately NOT dismissible; this is
 *    a state indicator, not a notification. When the network comes
 *    back the banner hides itself.
 *  • Height is ~36dp so it doesn't push the rest of the UI around much.
 *
 * ── Placement ──
 * Drop this as the FIRST child of the Column in screens that need it:
 *   Column {
 *       OfflineBanner(monitor)
 *       ...rest of screen...
 *   }
 * Or inside Scaffold above the content.
 *
 * ── When to show it ──
 * Only on screens where network matters. Skip for:
 *   - Reader screen (content is downloaded locally)
 *   - Settings / Stats / Pronunciation (local data)
 *   - Audio library / player (local MP3s)
 * Show for:
 *   - Home (continue-reading uses cover URLs)
 *   - Search (every action needs network)
 *   - Library (cover refresh + update check)
 *   - Detail (initial fetch needs network)
 *   - Downloads (chapter downloads need network)
 */
@Composable
fun OfflineBanner(
    monitor: NetworkMonitor,
    modifier: Modifier = Modifier
) {
    val isOnline by monitor.isOnline.collectAsState()

    // AnimatedVisibility takes care of the slide-down/up when state
    // flips. No need to manually manage a remembered "visible" flag.
    AnimatedVisibility(
        visible = !isOnline,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        OfflineBannerContent(modifier = modifier)
    }
}

/**
 * Variant for callers who already have an isOnline boolean in hand
 * (e.g. passed down from a parent that observes once + shares across
 * many screens).
 */
@Composable
fun OfflineBanner(
    isOnline: Boolean,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = !isOnline,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        OfflineBannerContent(modifier = modifier)
    }
}

@Composable
private fun OfflineBannerContent(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Icon(
            imageVector = Icons.Default.CloudOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.size(10.dp))
        Text(
            text = "Offline — browsing cached content only",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}