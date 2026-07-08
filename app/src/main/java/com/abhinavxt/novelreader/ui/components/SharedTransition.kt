package com.abhinavxt.novelreader.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

/**
 * Shared-element transition plumbing (Visual Identity phase).
 *
 * Navigation.kt wraps the NavHost in a SharedTransitionLayout and
 * provides its scope here; each participating route provides its
 * AnimatedVisibilityScope (the composable{} lambda receiver). Screens
 * then just call Modifier.novelCoverShared(novelId) on cover elements.
 *
 * Both locals are nullable-by-default so ANY cover rendered outside a
 * providing route (widgets, previews, non-participating screens)
 * silently renders without the transition instead of crashing.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalNavAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Marks a cover as the shared element "cover-{novelId}". The same key
 * on the Library/Home cover and the Detail header cover makes the
 * cover fly-and-resize between the two screens.
 *
 * sharedBounds (not sharedElement) because the cover renders at
 * different sizes on each end and we want the bounds to interpolate.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.novelCoverShared(novelId: String): Modifier {
    val sharedScope = LocalSharedTransitionScope.current ?: return this
    val animatedScope = LocalNavAnimatedVisibilityScope.current ?: return this
    return with(sharedScope) {
        this@novelCoverShared.sharedBounds(
            sharedContentState = rememberSharedContentState(key = "cover-$novelId"),
            animatedVisibilityScope = animatedScope
        )
    }
}
