package com.abhinavxt.novelforge.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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
    fun finishingWelcomeHandsTheActionOnAndSticks() {
        Onboarding.init(context)
        Onboarding.finishWelcome(Onboarding.Action.IMPORT_EPUB)
        assertEquals(Onboarding.Action.IMPORT_EPUB, Onboarding.pendingAction.value)
        Onboarding.pendingAction.value = null
        Onboarding.init(context)
        assertTrue(Onboarding.welcomeDone.value)
    }
}
