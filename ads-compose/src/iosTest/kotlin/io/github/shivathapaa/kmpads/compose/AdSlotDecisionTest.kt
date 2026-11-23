package io.github.shivathapaa.kmpads.compose

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.shivathapaa.kmpads.config.AdPlacementId
import io.github.shivathapaa.kmpads.event.AdEvent
import io.github.shivathapaa.kmpads.policy.AdDecision
import io.github.shivathapaa.kmpads.policy.AdSignalKey
import io.github.shivathapaa.kmpads.policy.SuppressionReason
import io.github.shivathapaa.kmpads.provider.AdPlatformView
import io.github.shivathapaa.kmpads.provider.AdViewFactoryContext
import io.github.shivathapaa.kmpads.provider.BannerAdHandle
import io.github.shivathapaa.kmpads.provider.BannerSizing
import io.github.shivathapaa.kmpads.provider.NativeAdAssets
import io.github.shivathapaa.kmpads.provider.NativeAdHandle
import io.github.shivathapaa.kmpads.provider.NativeAdStyle
import io.github.shivathapaa.kmpads.runtime.AdPreloadOutcome
import io.github.shivathapaa.kmpads.runtime.AdShowOutcome
import io.github.shivathapaa.kmpads.runtime.AdsSystem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A slot kept on screen while suppressed (`collapseWhenSuppressed = false`) must follow the
 * decision: request once the placement is allowed, and destroy its ad once it is suppressed.
 */
@OptIn(ExperimentalTestApi::class)
class AdSlotDecisionTest {
    private val placement = AdPlacementId("slot")
    private val suppressed: AdDecision =
        AdDecision.Suppress(SuppressionReason.RequiredSignalFalse(AdSignalKey.ConsentObtained))

    @Test
    fun a_kept_banner_slot_requests_its_banner_once_allowed() = runComposeUiTest {
        val ads = DecisionAds(initial = suppressed)
        setContent {
            AdBannerSlot(placement = placement, ads = ads, collapseWhenSuppressed = false)
        }
        waitForIdle()
        assertEquals(0, ads.bannersCreated)

        ads.decision.value = AdDecision.Allow
        waitForIdle()
        assertEquals(1, ads.bannersCreated)
    }

    @Test
    fun a_kept_banner_slot_destroys_its_banner_once_suppressed() = runComposeUiTest {
        val ads = DecisionAds(initial = AdDecision.Allow)
        setContent {
            AdBannerSlot(placement = placement, ads = ads, collapseWhenSuppressed = false)
        }
        waitForIdle()
        assertEquals(1, ads.bannersCreated, "created while allowed")

        // Consent withdrawn or premium bought: the live ad has to go, not just stop refreshing.
        ads.decision.value = suppressed
        waitForIdle()
        assertEquals(1, ads.bannersDisposed, "destroyed once suppressed")
    }

    @Test
    fun a_kept_native_slot_requests_its_ad_once_allowed() = runComposeUiTest {
        val ads = DecisionAds(initial = suppressed)
        setContent {
            AdNativeSlot(
                placement = placement,
                style = style,
                ads = ads,
                collapseWhenSuppressed = false,
            )
        }
        waitForIdle()
        assertEquals(0, ads.nativesLoaded)

        ads.decision.value = AdDecision.Allow
        waitForIdle()
        assertEquals(1, ads.nativesLoaded)
    }

    @Test
    fun a_kept_native_slot_destroys_its_ad_once_suppressed() = runComposeUiTest {
        val ads = DecisionAds(initial = AdDecision.Allow)
        setContent {
            AdNativeSlot(
                placement = placement,
                style = style,
                ads = ads,
                collapseWhenSuppressed = false,
            )
        }
        waitForIdle()
        assertEquals(1, ads.nativesLoaded, "loaded while allowed")

        ads.decision.value = suppressed
        waitForIdle()
        assertEquals(1, ads.nativesDisposed, "destroyed once suppressed")
    }

    private val style = NativeAdStyle(
        backgroundArgb = 0xFFFFFFFF,
        primaryTextArgb = 0xFF000000,
        secondaryTextArgb = 0xFF666666,
        ctaBackgroundArgb = 0xFF0A84FF,
        ctaTextArgb = 0xFFFFFFFF,
        cornerRadiusDp = 8,
        headlineTextSizeSp = 16f,
        bodyTextSizeSp = 13f,
    )
}

/**
 * An [AdsSystem] whose decision the test sets. Like the runtime, it hands out an ad only while the
 * placement is allowed.
 */
private class DecisionAds(initial: AdDecision) : AdsSystem {
    val decision = MutableStateFlow(initial)

    var bannersCreated = 0
        private set
    var bannersDisposed = 0
        private set
    var nativesLoaded = 0
        private set
    var nativesDisposed = 0
        private set

    override val events: SharedFlow<AdEvent> = MutableSharedFlow()

    override fun decision(placement: AdPlacementId): Flow<AdDecision> = decision

    override suspend fun preload(placement: AdPlacementId): AdPreloadOutcome =
        AdPreloadOutcome.NotSupported

    override suspend fun show(placement: AdPlacementId): AdShowOutcome = AdShowOutcome.NotReady

    override fun reservedBannerHeightDp(
        context: AdViewFactoryContext,
        widthDp: Int,
        sizing: BannerSizing,
    ): Int = 50

    override fun createBanner(
        context: AdViewFactoryContext,
        placement: AdPlacementId,
        widthDp: Int,
        sizing: BannerSizing,
    ): BannerAdHandle? {
        if (decision.value !is AdDecision.Allow) return null
        bannersCreated++
        return StubBanner(onDispose = { bannersDisposed++ })
    }

    override suspend fun loadNative(
        context: AdViewFactoryContext,
        placement: AdPlacementId,
        style: NativeAdStyle,
    ): NativeAdHandle? {
        if (decision.value !is AdDecision.Allow) return null
        nativesLoaded++
        return StubNative(onDispose = { nativesDisposed++ })
    }

    override fun onAppForegrounded(): Unit = Unit
    override fun onAppBackgrounded(): Unit = Unit
    override fun notifyFirstRunComplete(): Unit = Unit
    override suspend fun shutdown(): Unit = Unit
}

private class StubBanner(private val onDispose: () -> Unit) : BannerAdHandle {
    override val view: AdPlatformView = AdPlatformView.stub()
    override val reservedHeightDp: Int = 50
    override val isLoaded: StateFlow<Boolean> = MutableStateFlow(false)
    override fun load(): Unit = Unit
    override fun pause(): Unit = Unit
    override fun resume(): Unit = Unit
    override fun dispose(): Unit = onDispose()
}

private class StubNative(private val onDispose: () -> Unit) : NativeAdHandle {
    override val assets: NativeAdAssets = NativeAdAssets(
        headline = null,
        body = null,
        callToAction = null,
        advertiser = null,
        starRating = null,
        hasIcon = false,
        hasMedia = false,
        mediaAspectRatio = null,
    )
    override val view: AdPlatformView = AdPlatformView.stub()
    override fun dispose(): Unit = onDispose()
}
