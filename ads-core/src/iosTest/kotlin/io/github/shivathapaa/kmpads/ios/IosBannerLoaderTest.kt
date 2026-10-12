package io.github.shivathapaa.kmpads.ios

import io.github.shivathapaa.kmpads.bridge.KMPAdsConsentCallback
import io.github.shivathapaa.kmpads.bridge.KMPAdsHost
import io.github.shivathapaa.kmpads.bridge.KMPAdsLoadCallback
import io.github.shivathapaa.kmpads.bridge.KMPAdsProtocol
import io.github.shivathapaa.kmpads.bridge.KMPAdsShowCallback
import io.github.shivathapaa.kmpads.bridge.KMPAdsStartCallback
import io.github.shivathapaa.kmpads.bridge.KMPAdsSurfaceCallback
import io.github.shivathapaa.kmpads.config.AdFormat
import io.github.shivathapaa.kmpads.config.AdPlacementId
import io.github.shivathapaa.kmpads.config.AdPlatform
import io.github.shivathapaa.kmpads.config.GoogleTestUnits
import io.github.shivathapaa.kmpads.error.AdError
import io.github.shivathapaa.kmpads.event.AdRevenue
import io.github.shivathapaa.kmpads.provider.AdViewFactoryContext
import io.github.shivathapaa.kmpads.provider.BannerAdHandle
import io.github.shivathapaa.kmpads.provider.BannerAdListener
import io.github.shivathapaa.kmpads.provider.BannerAdLoader
import io.github.shivathapaa.kmpads.provider.BannerAdRequest
import io.github.shivathapaa.kmpads.provider.BannerSizing
import platform.UIKit.UIView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the iOS banner loader. The Swift host builds the banner at whatever size it is told, so
 * the size it is told must be the height the slot reserved.
 */
class IosBannerLoaderTest {
    private val host = FakeHost()
    private val context = AdViewFactoryContext.stub()

    private fun loader(): BannerAdLoader = assertNotNull(iosAdProvider(host).banner)

    private fun create(sizing: BannerSizing): BannerAdHandle = loader().createBanner(
        context = context,
        request = BannerAdRequest(
            placement = AdPlacementId("banner"),
            unitId = GoogleTestUnits.forFormat(AdPlatform.Ios, AdFormat.Banner),
            widthDp = WIDTH,
            sizing = sizing,
        ),
        listener = NoOpBannerListener,
    )

    @Test
    fun anchoredAdaptiveRequestsTheReservedAdaptiveHeight() {
        val handle = create(BannerSizing.AnchoredAdaptive)

        assertEquals(KMPAdsProtocol.BANNER_ANCHORED_ADAPTIVE, host.lastSizing)
        assertEquals(ADAPTIVE_HEIGHT, handle.reservedHeightDp)
        assertEquals(handle.reservedHeightDp.toDouble(), host.lastHeightPoints)
        assertEquals(WIDTH.toDouble(), host.lastWidthPoints)
    }

    @Test
    fun standardRequestsTheStandardBanner() {
        val handle = create(BannerSizing.Standard320x50)

        assertEquals(KMPAdsProtocol.BANNER_STANDARD, host.lastSizing)
        assertEquals(50, handle.reservedHeightDp)
        assertEquals(handle.reservedHeightDp.toDouble(), host.lastHeightPoints)
    }

    @Test
    fun fixedHeightRequestsTheFixedHeight() {
        val handle = create(BannerSizing.FixedHeight(72))

        assertEquals(KMPAdsProtocol.BANNER_FIXED_HEIGHT, host.lastSizing)
        assertEquals(72, handle.reservedHeightDp)
        assertEquals(72.0, host.lastHeightPoints)
    }

    @Test
    fun isLoadedFollowsTheSurfaceCallback() {
        val handle = create(BannerSizing.AnchoredAdaptive)
        assertFalse(handle.isLoaded.value)

        host.callback().onSurfaceLoaded(WIDTH.toDouble(), ADAPTIVE_HEIGHT.toDouble())
        assertTrue(handle.isLoaded.value)

        // A failed refresh keeps the previous creative on screen.
        host.callback().onSurfaceFailed(code = 1, message = "no fill")
        assertTrue(handle.isLoaded.value)
    }

    @Test
    fun aFirstFailureLeavesTheBannerUnloaded() {
        val handle = create(BannerSizing.AnchoredAdaptive)

        host.callback().onSurfaceFailed(code = 1, message = "no fill")

        assertFalse(handle.isLoaded.value)
    }

    @Test
    fun aHostOnAnotherProtocolVersionGetsNoBannerLoader() {
        host.version = KMPAdsProtocol.VERSION - 1

        assertNull(iosAdProvider(host).banner)
    }

    private class FakeHost : KMPAdsHost {
        var version: Int = KMPAdsProtocol.VERSION
        var lastWidthPoints: Double? = null
        var lastSizing: String? = null
        var lastHeightPoints: Double? = null
        private var lastCallback: KMPAdsSurfaceCallback? = null

        fun callback(): KMPAdsSurfaceCallback = assertNotNull(lastCallback)

        override fun protocolVersion(): Int = version

        override fun adaptiveBannerHeightPoints(widthPoints: Double): Double =
            ADAPTIVE_HEIGHT.toDouble()

        override fun makeBanner(
            unitId: String,
            widthPoints: Double,
            sizing: String,
            heightPoints: Double,
            callback: KMPAdsSurfaceCallback,
        ): UIView {
            lastWidthPoints = widthPoints
            lastSizing = sizing
            lastHeightPoints = heightPoints
            lastCallback = callback
            return UIView()
        }

        override fun start(
            testDeviceIdentifiers: List<String>,
            ageRestriction: String,
            maxAdContentRating: String,
            callback: KMPAdsStartCallback,
        ): Unit = Unit

        override fun setAppMuted(muted: Boolean): Unit = Unit
        override fun canRequestAds(): Boolean = true
        override fun isPrivacyOptionsRequired(): Boolean = false

        override fun gatherConsent(
            debugGeography: String,
            testDeviceIdentifiers: List<String>,
            callback: KMPAdsConsentCallback,
        ): Unit = Unit

        override fun presentPrivacyOptionsForm(callback: KMPAdsConsentCallback): Unit = Unit
        override fun resetConsent(): Unit = Unit
        override fun requestTrackingAuthorization(callback: KMPAdsConsentCallback): Unit = Unit

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

    private object NoOpBannerListener : BannerAdListener {
        override fun onLoaded(): Unit = Unit
        override fun onLoadFailed(error: AdError): Unit = Unit
        override fun onImpression(): Unit = Unit
        override fun onClicked(): Unit = Unit
        override fun onRevenue(revenue: AdRevenue): Unit = Unit
    }

    private companion object {
        const val WIDTH = 390
        const val ADAPTIVE_HEIGHT = 62
    }
}
