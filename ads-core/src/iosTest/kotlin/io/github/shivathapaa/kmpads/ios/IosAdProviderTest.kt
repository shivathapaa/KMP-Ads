package io.github.shivathapaa.kmpads.ios

import io.github.shivathapaa.kmpads.bridge.KMPAdsConsentCallback
import io.github.shivathapaa.kmpads.bridge.KMPAdsHost
import io.github.shivathapaa.kmpads.bridge.KMPAdsLoadCallback
import io.github.shivathapaa.kmpads.bridge.KMPAdsProtocol
import io.github.shivathapaa.kmpads.bridge.KMPAdsShowCallback
import io.github.shivathapaa.kmpads.bridge.KMPAdsStartCallback
import io.github.shivathapaa.kmpads.bridge.KMPAdsSurfaceCallback
import io.github.shivathapaa.kmpads.config.AdAudienceConfig
import io.github.shivathapaa.kmpads.config.AdFormat
import io.github.shivathapaa.kmpads.config.AdPlacementId
import io.github.shivathapaa.kmpads.config.AdPlatform
import io.github.shivathapaa.kmpads.config.AdUnitRegistry
import io.github.shivathapaa.kmpads.config.GoogleTestUnits
import io.github.shivathapaa.kmpads.error.AdConfigProblem
import io.github.shivathapaa.kmpads.error.AdError
import io.github.shivathapaa.kmpads.error.AdLogger
import io.github.shivathapaa.kmpads.event.AdRevenue
import io.github.shivathapaa.kmpads.policy.AdPlacement
import io.github.shivathapaa.kmpads.policy.AdPolicy
import io.github.shivathapaa.kmpads.policy.AdSignalKey
import io.github.shivathapaa.kmpads.provider.AdInitOutcome
import io.github.shivathapaa.kmpads.provider.AdProvider
import io.github.shivathapaa.kmpads.provider.AdViewFactoryContext
import io.github.shivathapaa.kmpads.provider.BannerAdListener
import io.github.shivathapaa.kmpads.provider.BannerAdRequest
import io.github.shivathapaa.kmpads.provider.BannerSizing
import io.github.shivathapaa.kmpads.runtime.AdsBootstrap
import io.github.shivathapaa.kmpads.runtime.AdsConfig
import io.github.shivathapaa.kmpads.runtime.AdsSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import platform.UIKit.UIView
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** Tests for how the iOS provider configures the SDK through the Swift host. */
@OptIn(ExperimentalCoroutinesApi::class)
class IosAdProviderTest {
    private val interstitial = AdPlacementId("interstitial")

    // Full-screen loads run on Dispatchers.Main, which the test thread would otherwise block.
    @BeforeTest
    fun setMainDispatcher() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    private val childDirected = AdAudienceConfig(
        ageRestriction = AdAudienceConfig.AgeRestriction.ChildDirected,
        maxAdContentRating = AdAudienceConfig.MaxAdContentRating.G,
    )

    /** A child-directed app's configuration, with one on-demand interstitial. */
    private fun childDirectedConfig(provider: AdProvider, scope: CoroutineScope) = AdsConfig(
        placements = listOf(AdPlacement(interstitial, AdFormat.Interstitial, AdPolicy.OnDemand)),
        units = AdUnitRegistry.testOnly(),
        provider = provider,
        platform = AdPlatform.Ios,
        audience = childDirected,
        scope = scope,
        signals = mapOf(
            AdSignalKey.ConsentObtained to flowOf(true),
            AdSignalKey.NetworkAvailable to flowOf(true),
            AdSignalKey.UserIsPremium to flowOf(false),
        ),
    )

    @Test
    fun a_child_directed_app_never_starts_the_sdk_as_a_general_audience_app() = runTest {
        val host = RecordingHost()
        // The audience is declared in AdsConfig only, and left at its default for the provider.
        val ads = AdsSystem.create(childDirectedConfig(iosAdProvider(host), this)).orNoOp()
        advanceUntilIdle()

        ads.preload(interstitial)
        advanceUntilIdle()

        // A general-audience start would let the SDK serve personalised ads to children. Quoted,
        // because the unspecified age restriction is an empty string.
        assertEquals(
            emptyList(),
            host.startedWith.filter { it != KMPAdsProtocol.AGE_CHILD }.map { "\"$it\"" },
        )
    }

    @Test
    fun a_provider_left_at_another_audience_is_refused_when_the_system_is_created() = runTest {
        val bootstrap = AdsSystem.create(childDirectedConfig(iosAdProvider(RecordingHost()), this))

        assertEquals(
            listOf(
                AdConfigProblem.AudienceMismatch(
                    declared = childDirected,
                    provider = AdAudienceConfig.GeneralAudience,
                )
            ),
            assertIs<AdsBootstrap.Misconfigured>(bootstrap).problems,
        )
    }

    @Test
    fun the_declared_audience_is_the_one_the_sdk_starts_with() = runTest {
        val host = RecordingHost()
        val provider = iosAdProvider(host, audience = childDirected)
        val ads = AdsSystem.create(childDirectedConfig(provider, this)).orNoOp()
        advanceUntilIdle()

        ads.preload(interstitial)
        advanceUntilIdle()

        assertEquals(listOf(KMPAdsProtocol.AGE_CHILD), host.startedWith)
    }

    @Test
    fun ads_are_muted_once_the_sdk_has_started() = runTest {
        val host = RecordingHost()

        assertEquals(AdInitOutcome.Ready, iosAdProvider(host).initialize())

        assertEquals(listOf(true), host.mutedWith)
    }

    @Test
    fun ad_audio_is_left_alone_when_muting_is_off() = runTest {
        val host = RecordingHost()

        assertEquals(AdInitOutcome.Ready, iosAdProvider(host, muteAds = false).initialize())

        assertEquals(emptyList(), host.mutedWith)
    }

    @Test
    fun a_throwing_logger_cannot_escape_a_swift_callback() {
        val host = RecordingHost()
        val loader = assertNotNull(
            iosAdProvider(host, logger = AdLogger { _, _, _, _ -> error("logger failed") }).banner
        )
        loader.createBanner(
            context = AdViewFactoryContext.stub(),
            request = BannerAdRequest(
                placement = AdPlacementId("banner"),
                unitId = GoogleTestUnits.forFormat(AdPlatform.Ios, AdFormat.Banner),
                widthDp = 390,
                sizing = BannerSizing.AnchoredAdaptive,
            ),
            listener = NoOpBannerListener,
        )

        // In an app this call comes from Swift, where an escaping Kotlin exception ends the process.
        host.surfaceCallback().onSurfaceFailed(code = 1, message = "no fill")
    }

    /** A Swift host stand-in that records what the library asks of it and answers at once. */
    private class RecordingHost : KMPAdsHost {
        val startedWith = mutableListOf<String>()
        val mutedWith = mutableListOf<Boolean>()
        private var lastSurfaceCallback: KMPAdsSurfaceCallback? = null

        fun surfaceCallback(): KMPAdsSurfaceCallback = assertNotNull(lastSurfaceCallback)

        override fun protocolVersion(): Int = KMPAdsProtocol.VERSION

        override fun start(
            testDeviceIdentifiers: List<String>,
            ageRestriction: String,
            maxAdContentRating: String,
            callback: KMPAdsStartCallback,
        ) {
            startedWith += ageRestriction
            callback.onStarted(canRequestAds = true, adapterSummary = null)
        }

        override fun setAppMuted(muted: Boolean) {
            mutedWith += muted
        }

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

        override fun adaptiveBannerHeightPoints(widthPoints: Double): Double = 62.0

        override fun makeBanner(
            unitId: String,
            widthPoints: Double,
            sizing: String,
            heightPoints: Double,
            callback: KMPAdsSurfaceCallback,
        ): UIView {
            lastSurfaceCallback = callback
            return UIView()
        }

        override fun makeNative(
            unitId: String,
            templateId: String,
            callback: KMPAdsSurfaceCallback,
        ): UIView = UIView()

        override fun disposeSurface(view: UIView): Unit = Unit

        override fun loadFullScreen(format: String, unitId: String, callback: KMPAdsLoadCallback) {
            callback.onFailedToLoad(format, unitId, code = 1, message = "no fill")
        }

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
}
