package io.github.shivathapaa.kmpads.ios

import io.github.shivathapaa.kmpads.bridge.KMPAdsConsentCallback
import io.github.shivathapaa.kmpads.bridge.KMPAdsHost
import io.github.shivathapaa.kmpads.bridge.KMPAdsLoadCallback
import io.github.shivathapaa.kmpads.bridge.KMPAdsProtocol
import io.github.shivathapaa.kmpads.bridge.KMPAdsShowCallback
import io.github.shivathapaa.kmpads.bridge.KMPAdsStartCallback
import io.github.shivathapaa.kmpads.bridge.KMPAdsSurfaceCallback
import io.github.shivathapaa.kmpads.consent.AdConsentState
import kotlinx.coroutines.test.runTest
import platform.UIKit.UIView
import kotlin.test.Test
import kotlin.test.assertEquals

/** Tests for when the iOS consent controller asks the Swift host to gather consent again. */
class IosConsentControllerTest {
    @Test
    fun a_failed_gather_is_retried_on_the_next_call() = runTest {
        // Offline on a fresh install: the info update fails and UMP cannot allow requests yet.
        val host = ConsentHost(
            Outcome(canRequestAds = false, errorMessage = "The Internet connection appears to be offline."),
            Outcome(canRequestAds = true, errorMessage = null),
        )
        val controller = iosConsentController(host)

        val first = controller.gather()
        assertEquals(AdConsentState.Status.Failed, first.status)

        // Back online, for example on the next foreground: the host is asked again.
        val second = controller.gather()

        assertEquals(2, host.gatherCalls)
        assertEquals(true, second.canRequestAds)
        assertEquals(AdConsentState.Status.Obtained, second.status)
        assertEquals(second, controller.state.value)
    }

    @Test
    fun a_completed_gather_is_not_repeated() = runTest {
        val host = ConsentHost(Outcome(canRequestAds = true, errorMessage = null))
        val controller = iosConsentController(host)

        val first = controller.gather()
        val second = controller.gather()

        assertEquals(1, host.gatherCalls)
        assertEquals(first, second)
    }

    @Test
    fun a_gather_that_ends_without_consent_is_not_repeated() = runTest {
        // A completed gather in which consent is still required: asking again would show the form again.
        val host = ConsentHost(Outcome(canRequestAds = false, errorMessage = null))
        val controller = iosConsentController(host)

        controller.gather()
        val second = controller.gather()

        assertEquals(1, host.gatherCalls)
        assertEquals(AdConsentState.Status.Required, second.status)
    }

    private class Outcome(val canRequestAds: Boolean, val errorMessage: String?)

    /** A Swift host stand-in that answers each `gatherConsent` with the next outcome, at once. */
    private class ConsentHost(vararg outcomes: Outcome) : KMPAdsHost {
        private val pending = ArrayDeque(outcomes.toList())
        private var canRequest = false
        var gatherCalls = 0
            private set

        override fun gatherConsent(
            debugGeography: String,
            testDeviceIdentifiers: List<String>,
            callback: KMPAdsConsentCallback,
        ) {
            gatherCalls++
            val outcome = pending.removeFirstOrNull() ?: error("gatherConsent called too often")
            canRequest = outcome.canRequestAds
            callback.onConsentResolved(
                canRequestAds = outcome.canRequestAds,
                privacyOptionsRequired = false,
                errorMessage = outcome.errorMessage,
            )
        }

        override fun canRequestAds(): Boolean = canRequest
        override fun isPrivacyOptionsRequired(): Boolean = false
        override fun protocolVersion(): Int = KMPAdsProtocol.VERSION

        override fun start(
            testDeviceIdentifiers: List<String>,
            ageRestriction: String,
            maxAdContentRating: String,
            callback: KMPAdsStartCallback,
        ): Unit = Unit

        override fun setAppMuted(muted: Boolean): Unit = Unit
        override fun presentPrivacyOptionsForm(callback: KMPAdsConsentCallback): Unit = Unit
        override fun resetConsent(): Unit = Unit
        override fun requestTrackingAuthorization(callback: KMPAdsConsentCallback): Unit = Unit
        override fun adaptiveBannerHeightPoints(widthPoints: Double): Double = 0.0

        override fun makeBanner(
            unitId: String,
            widthPoints: Double,
            sizing: String,
            heightPoints: Double,
            callback: KMPAdsSurfaceCallback,
        ): UIView = UIView()

        override fun makeNative(
            unitId: String,
            templateId: String,
            callback: KMPAdsSurfaceCallback,
        ): UIView = UIView()

        override fun disposeSurface(view: UIView): Unit = Unit

        override fun loadFullScreen(
            format: String,
            unitId: String,
            callback: KMPAdsLoadCallback,
        ): Unit = Unit

        override fun isFullScreenReady(format: String, unitId: String): Boolean = false

        override fun showFullScreen(
            format: String,
            unitId: String,
            callback: KMPAdsShowCallback,
        ): Unit = Unit

        override fun discardFullScreen(format: String, unitId: String): Unit = Unit
    }
}
