package io.github.shivathapaa.kmpads.admob

import android.app.Activity
import io.github.shivathapaa.kmpads.consent.AdDebugGeography
import io.github.shivathapaa.kmpads.error.AdLogger
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Tests for when the UMP consent controller asks UMP to gather consent again. */
class UmpConsentControllerTest {
    private val activities = ForegroundActivityTracker().apply { onActivityResumed(Activity()) }

    @Test
    fun a_failed_info_update_is_retried_on_the_next_call() = runTest {
        // Offline on a fresh install: the update fails and UMP cannot allow requests yet.
        val ump = FakeUmp(infoUpdateFails = mutableListOf(true, false))
        val controller = UmpConsentController(ump, activities, AdLogger.NoOp)

        assertEquals(false, controller.gather().canRequestAds)

        // Back online, for example on the next onResume: UMP is asked again.
        val second = controller.gather()

        assertEquals(2, ump.infoUpdates)
        assertEquals(true, second.canRequestAds)
    }

    @Test
    fun a_failed_consent_form_is_retried_on_the_next_call() = runTest {
        val ump = FakeUmp(infoUpdateFails = mutableListOf(false, false), formFails = mutableListOf(true, false))
        val controller = UmpConsentController(ump, activities, AdLogger.NoOp)

        assertEquals(false, controller.gather().canRequestAds)
        val second = controller.gather()

        assertEquals(2, ump.formsShown)
        assertEquals(true, second.canRequestAds)
    }

    @Test
    fun a_completed_gather_is_not_repeated() = runTest {
        val ump = FakeUmp(infoUpdateFails = mutableListOf(false))
        val controller = UmpConsentController(ump, activities, AdLogger.NoOp)

        val first = controller.gather()
        val second = controller.gather()

        assertEquals(1, ump.infoUpdates)
        assertEquals(1, ump.formsShown)
        assertEquals(first, second)
    }

    @Test
    fun a_gather_with_no_activity_is_retried_once_there_is_one() = runTest {
        val tracker = ForegroundActivityTracker()
        val ump = FakeUmp(infoUpdateFails = mutableListOf(false))
        val controller = UmpConsentController(ump, tracker, AdLogger.NoOp)

        controller.gather()
        assertEquals(0, ump.infoUpdates)

        tracker.onActivityResumed(Activity())
        controller.gather()

        assertEquals(1, ump.infoUpdates)
    }

    /**
     * Answers each call at once with the next scripted outcome. A successful update followed by the
     * form, or no form, is what lets UMP allow requests.
     */
    private class FakeUmp(
        private val infoUpdateFails: MutableList<Boolean>,
        private val formFails: MutableList<Boolean> = mutableListOf(),
    ) : UmpClient {
        override var canRequestAds: Boolean = false
            private set
        override val privacyOptionsRequired: Boolean = false

        var infoUpdates = 0
            private set
        var formsShown = 0
            private set
        private var infoUpToDate = false

        override fun requestConsentInfoUpdate(
            activity: Activity,
            debugGeography: AdDebugGeography,
            testDeviceIdentifiers: List<String>,
            onSuccess: () -> Unit,
            onFailure: (message: String?) -> Unit,
        ) {
            infoUpdates++
            infoUpToDate = !infoUpdateFails.removeAt(0)
            if (infoUpToDate) onSuccess() else onFailure("network error")
        }

        override fun loadAndShowConsentFormIfRequired(
            activity: Activity,
            onComplete: (errorMessage: String?) -> Unit,
        ) {
            formsShown++
            val failed = !infoUpToDate || formFails.removeFirstOrNull() == true
            if (failed) {
                onComplete("form unavailable")
            } else {
                canRequestAds = true
                onComplete(null)
            }
        }

        override fun showPrivacyOptionsForm(
            activity: Activity,
            onComplete: (errorMessage: String?) -> Unit,
        ) = onComplete(null)

        override fun reset() {
            canRequestAds = false
        }
    }
}
