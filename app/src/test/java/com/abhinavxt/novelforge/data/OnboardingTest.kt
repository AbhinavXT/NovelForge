package com.abhinavxt.novelforge.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OnboardingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun tipsShowOneAtATimeAndOnlyOnce() {
        Onboarding.init(context)
        Onboarding.resetTips()

        assertTrue(Onboarding.claim(Tip.CODEX))
        assertFalse("another tip is up", Onboarding.claim(Tip.DOWNLOAD))
        Onboarding.release(Tip.CODEX)
        assertNull(Onboarding.activeTip.value)

        assertFalse("seen tips stay hidden", Onboarding.claim(Tip.CODEX))
        assertTrue(Onboarding.claim(Tip.DOWNLOAD))
        Onboarding.release(Tip.DOWNLOAD)

        Onboarding.init(context) // survives a restart
        assertEquals(setOf("CODEX", "DOWNLOAD"), Onboarding.seenTips.value)

        Onboarding.release(Tip.PLUGINS, seen = false) // not its turn: no effect
        assertTrue(Onboarding.claim(Tip.PLUGINS))
        Onboarding.release(Tip.PLUGINS, seen = false)
        assertTrue("unread tips come back", Onboarding.claim(Tip.PLUGINS))
        Onboarding.release(Tip.PLUGINS)

        Onboarding.resetTips()
        assertTrue(Onboarding.claim(Tip.CODEX))
        Onboarding.release(Tip.CODEX)
    }

    @Test
    fun finishingWelcomeHandsTheActionOnAndSticks() {
        Onboarding.init(context)
        Onboarding.finishWelcome(Onboarding.Action.IMPORT_EPUB)
        assertEquals(Onboarding.Action.IMPORT_EPUB, Onboarding.pendingAction.value)
        Onboarding.pendingAction.value = null
        Onboarding.init(context)
        assertTrue(Onboarding.welcomeDone.value)
    }
}
