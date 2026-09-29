package io.github.shivathapaa.kmpads.ios

import io.github.shivathapaa.kmpads.bridge.KMPAdsHost
import io.github.shivathapaa.kmpads.bridge.KMPAdsLoadCallback
import io.github.shivathapaa.kmpads.bridge.KMPAdsProtocol
import io.github.shivathapaa.kmpads.bridge.KMPAdsShowCallback
import io.github.shivathapaa.kmpads.bridge.KMPAdsStartCallback
import io.github.shivathapaa.kmpads.bridge.KMPAdsSurfaceCallback
import io.github.shivathapaa.kmpads.config.AdAudienceConfig
import io.github.shivathapaa.kmpads.config.AdFormat
import io.github.shivathapaa.kmpads.config.AdPlacementId
import io.github.shivathapaa.kmpads.error.AdError
import io.github.shivathapaa.kmpads.error.AdLogLevel
import io.github.shivathapaa.kmpads.error.AdLogger
import io.github.shivathapaa.kmpads.provider.AdInitOutcome
import io.github.shivathapaa.kmpads.provider.AdLoadOutcome
import io.github.shivathapaa.kmpads.provider.AdLoadRequest
import io.github.shivathapaa.kmpads.provider.AdNetworkId
import io.github.shivathapaa.kmpads.provider.AdPlatformView
import io.github.shivathapaa.kmpads.provider.AdProvider
import io.github.shivathapaa.kmpads.provider.AdViewFactoryContext
import io.github.shivathapaa.kmpads.provider.BannerAdHandle
import io.github.shivathapaa.kmpads.provider.BannerAdListener
import io.github.shivathapaa.kmpads.provider.BannerAdLoader
import io.github.shivathapaa.kmpads.provider.BannerAdRequest
import io.github.shivathapaa.kmpads.provider.BannerSizing
import io.github.shivathapaa.kmpads.provider.FullScreenAdListener
import io.github.shivathapaa.kmpads.provider.FullScreenAdLoader
import io.github.shivathapaa.kmpads.provider.NativeAdAssets
import io.github.shivathapaa.kmpads.provider.NativeAdHandle
import io.github.shivathapaa.kmpads.provider.NativeAdListener
import io.github.shivathapaa.kmpads.provider.NativeAdLoadOutcome
import io.github.shivathapaa.kmpads.provider.NativeAdLoader
import io.github.shivathapaa.kmpads.provider.NativeAdRequest
import io.github.shivathapaa.kmpads.provider.asAdPlatformView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.TimeSource

private const val TAG = "kmp-ads/ios"

/** Standard banner height in points, for the non-adaptive sizing. */
private const val STANDARD_BANNER_HEIGHT_DP = 50

/**
 * Creates the iOS provider backed by a Swift [host]. A `null` host is supported: every request fails
 * with [KMPAdsProtocol.ERROR_CODE_NO_HOST] and no view is created.
 */
public fun iosAdProvider(
    host: KMPAdsHost?,
    audience: AdAudienceConfig = AdAudienceConfig.GeneralAudience,
    testDeviceIdentifiers: List<String> = emptyList(),
    logger: AdLogger = AdLogger.NoOp,
): AdProvider = IosAdProvider(host, audience, testDeviceIdentifiers, logger)

internal class IosAdProvider(
    private val host: KMPAdsHost?,
    private val audience: AdAudienceConfig,
    private val testDeviceIdentifiers: List<String>,
    private val logger: AdLogger,
) : AdProvider {
    override val id: AdNetworkId = AdNetworkId("admob-ios")

    private val usableHost: KMPAdsHost? = host?.takeIf { candidate ->
        val version = candidate.protocolVersion()
        val matches = version == KMPAdsProtocol.VERSION
        if (!matches) {
            // The Swift adapter was built for a different protocol version, so ads are disabled.
            logger.log(
                AdLogLevel.Error,
                TAG,
                "KMPAdsHost protocol $version != expected ${KMPAdsProtocol.VERSION}; ads disabled",
                null,
            )
        }
        matches
    }

    override val fullScreen: FullScreenAdLoader? =
        usableHost?.let { IosFullScreenLoader(it, logger) }

    override val banner: BannerAdLoader? = usableHost?.let { IosBannerLoader(it, logger) }

    // Rendered by the Swift adapter in the SDK's native ad view, through KMPAdsHost.makeNative.
    override val native: NativeAdLoader? = usableHost?.let { IosNativeLoader(it, logger) }

    override suspend fun initialize(): AdInitOutcome {
        val adsHost = usableHost ?: return AdInitOutcome.Failed(AdError.NoProvider())
        val result = CompletableDeferred<AdInitOutcome>()
        adsHost.start(
            testDeviceIdentifiers = testDeviceIdentifiers,
            ageRestriction = audience.ageRestriction.toBridgeAgeRestriction(),
            maxAdContentRating = audience.maxAdContentRating.toBridgeRating(),
            callback = object : KMPAdsStartCallback {
                override fun onStarted(canRequestAds: Boolean, adapterSummary: String?) {
                    if (adapterSummary != null) {
                        logger.log(AdLogLevel.Info, TAG, "adapters: $adapterSummary", null)
                    }
                    result.complete(AdInitOutcome.Ready)
                }

                override fun onStartFailed(code: Int, message: String) {
                    logger.log(AdLogLevel.Error, TAG, "start failed ($code): $message", null)
                    result.complete(AdInitOutcome.Failed(iosErrorFor(code)))
                }
            },
        )
        return result.await()
    }

    override fun onAppForegrounded(): Unit = Unit

    override fun onAppBackgrounded(): Unit = Unit

    override fun dispose(): Unit = Unit
}

private class IosFullScreenLoader(
    private val host: KMPAdsHost,
    private val logger: AdLogger,
) : FullScreenAdLoader {
    /** The unit last loaded for each placement, used by `show` and `isReady`. */
    private val loaded = mutableMapOf<AdPlacementId, LoadedUnit>()

    override suspend fun load(request: AdLoadRequest): AdLoadOutcome {
        val format = request.format.toBridgeFormat()
            ?: return AdLoadOutcome.Failed(AdError.InvalidRequest())
        val unitId = request.unitId.value
        val startedAt = TimeSource.Monotonic.markNow()
        val result = CompletableDeferred<AdLoadOutcome>()

        host.loadFullScreen(
            format = format,
            unitId = unitId,
            callback = object : KMPAdsLoadCallback {
                override fun onLoaded(format: String, unitId: String) {
                    loaded[request.placement] = LoadedUnit(format, unitId)
                    result.complete(AdLoadOutcome.Loaded(startedAt.elapsedNow()))
                }

                override fun onFailedToLoad(
                    format: String,
                    unitId: String,
                    code: Int,
                    message: String,
                ) {
                    logger.log(AdLogLevel.Warn, TAG, "load $format failed ($code): $message", null)
                    result.complete(AdLoadOutcome.Failed(iosErrorFor(code)))
                }
            },
        )
        return result.await()
    }

    override fun isReady(placement: AdPlacementId): Boolean {
        val unit = loaded[placement] ?: return false
        return host.isFullScreenReady(unit.format, unit.unitId)
    }

    override fun show(placement: AdPlacementId, listener: FullScreenAdListener) {
        val unit = loaded[placement]
        if (unit == null) {
            listener.onShowFailed(AdError.InvalidRequest())
            return
        }
        host.showFullScreen(
            format = unit.format,
            unitId = unit.unitId,
            callback = object : KMPAdsShowCallback {
                override fun onShown(format: String, unitId: String) = listener.onShown()

                override fun onImpression(format: String, unitId: String) = listener.onImpression()

                override fun onClick(format: String, unitId: String) = listener.onClicked()

                override fun onUserEarnedReward(
                    format: String,
                    unitId: String,
                    rewardType: String,
                    amount: Int,
                ) {
                    listener.onRewardEarned(
                        io.github.shivathapaa.kmpads.event.AdReward(rewardType, amount)
                    )
                }

                override fun onDismissed(format: String, unitId: String) {
                    loaded.remove(placement)
                    listener.onDismissed()
                }

                override fun onFailedToShow(
                    format: String,
                    unitId: String,
                    code: Int,
                    message: String,
                ) {
                    logger.log(AdLogLevel.Warn, TAG, "show $format failed ($code): $message", null)
                    loaded.remove(placement)
                    listener.onShowFailed(iosErrorFor(code))
                }
            },
        )
    }

    override fun discard(placement: AdPlacementId) {
        val unit = loaded.remove(placement) ?: return
        host.discardFullScreen(unit.format, unit.unitId)
    }

    private data class LoadedUnit(val format: String, val unitId: String)
}

private class IosBannerLoader(
    private val host: KMPAdsHost,
    private val logger: AdLogger,
) : BannerAdLoader {
    override fun reservedHeightDp(
        context: AdViewFactoryContext,
        widthDp: Int,
        sizing: BannerSizing,
    ): Int = when (sizing) {
        // One point equals one dp on iOS, so no conversion is needed.
        BannerSizing.AnchoredAdaptive ->
            host.adaptiveBannerHeightPoints(widthDp.toDouble()).toInt()

        BannerSizing.Standard320x50 -> STANDARD_BANNER_HEIGHT_DP
        is BannerSizing.FixedHeight -> sizing.heightDp
    }

    override fun createBanner(
        context: AdViewFactoryContext,
        request: BannerAdRequest,
        listener: BannerAdListener,
    ): BannerAdHandle {
        val reserved = reservedHeightDp(context, request.widthDp, request.sizing)
        val view = host.makeBanner(
            unitId = request.unitId.value,
            widthPoints = request.widthDp.toDouble(),
            callback = object : KMPAdsSurfaceCallback {
                override fun onSurfaceLoaded(widthPoints: Double, heightPoints: Double) =
                    listener.onLoaded()

                override fun onSurfaceFailed(code: Int, message: String) {
                    logger.log(AdLogLevel.Warn, TAG, "banner failed ($code): $message", null)
                    listener.onLoadFailed(iosErrorFor(code))
                }

                override fun onSurfaceImpression() = listener.onImpression()

                override fun onSurfaceClick() = listener.onClicked()
            },
        )
        return IosBannerHandle(host, view.asAdPlatformView(), view, reserved)
    }
}

private class IosBannerHandle(
    private val host: KMPAdsHost,
    override val view: AdPlatformView,
    private val nativeView: platform.UIKit.UIView,
    override val reservedHeightDp: Int,
) : BannerAdHandle {
    // The adapter exposes no fill callback for banners, so the banner is reported as loaded.
    override val isLoaded: StateFlow<Boolean> = MutableStateFlow(true)

    /** The adapter returns a view that is already loading, so there is nothing to start here. */
    override fun load(): Unit = Unit

    override fun pause(): Unit = Unit

    override fun resume(): Unit = Unit

    override fun dispose() {
        host.disposeSurface(nativeView)
    }
}

/** The native template the Swift adapter renders. */
private const val NATIVE_TEMPLATE_ID = "medium"

private class IosNativeLoader(
    private val host: KMPAdsHost,
    private val logger: AdLogger,
) : NativeAdLoader {
    override suspend fun load(
        context: AdViewFactoryContext,
        request: NativeAdRequest,
        listener: NativeAdListener,
    ): NativeAdLoadOutcome {
        // Waits for the load result from the surface callback; null means the ad loaded.
        val loadResult = CompletableDeferred<AdError?>()

        val view = host.makeNative(
            unitId = request.unitId.value,
            templateId = NATIVE_TEMPLATE_ID,
            callback = object : KMPAdsSurfaceCallback {
                override fun onSurfaceLoaded(widthPoints: Double, heightPoints: Double) {
                    loadResult.complete(null)
                }

                override fun onSurfaceFailed(code: Int, message: String) {
                    logger.log(AdLogLevel.Warn, TAG, "native failed ($code): $message", null)
                    loadResult.complete(iosErrorFor(code))
                }

                override fun onSurfaceImpression() = listener.onImpression()

                override fun onSurfaceClick() = listener.onClicked()
            },
        )

        // Release the view if the load is cancelled while waiting for the result.
        val error = try {
            loadResult.await()
        } catch (cancellation: Throwable) {
            host.disposeSurface(view)
            throw cancellation
        }

        return when (error) {
            null -> NativeAdLoadOutcome.Loaded(IosNativeHandle(host, view.asAdPlatformView(), view))
            else -> {
                host.disposeSurface(view)
                NativeAdLoadOutcome.Failed(error)
            }
        }
    }
}

private class IosNativeHandle(
    private val host: KMPAdsHost,
    override val view: AdPlatformView,
    private val nativeView: platform.UIKit.UIView,
) : NativeAdHandle {
    /** Empty on iOS: native ads are rendered only through [view]. */
    override val assets: NativeAdAssets = EMPTY_NATIVE_ASSETS

    override fun dispose() {
        host.disposeSurface(nativeView)
    }
}

private val EMPTY_NATIVE_ASSETS: NativeAdAssets = NativeAdAssets(
    headline = null,
    body = null,
    callToAction = null,
    advertiser = null,
    starRating = null,
    hasIcon = false,
    hasMedia = false,
    mediaAspectRatio = null,
)

private fun AdFormat.toBridgeFormat(): String? = when (this) {
    AdFormat.Interstitial -> KMPAdsProtocol.FORMAT_INTERSTITIAL
    AdFormat.Rewarded -> KMPAdsProtocol.FORMAT_REWARDED
    AdFormat.RewardedInterstitial -> KMPAdsProtocol.FORMAT_REWARDED_INTERSTITIAL
    AdFormat.AppOpen -> KMPAdsProtocol.FORMAT_APP_OPEN
    AdFormat.Banner, AdFormat.Native -> null
}

private fun AdAudienceConfig.AgeRestriction.toBridgeAgeRestriction(): String = when (this) {
    AdAudienceConfig.AgeRestriction.Unspecified -> KMPAdsProtocol.AGE_UNSPECIFIED
    AdAudienceConfig.AgeRestriction.ChildDirected -> KMPAdsProtocol.AGE_CHILD
    AdAudienceConfig.AgeRestriction.Teen -> KMPAdsProtocol.AGE_TEEN
}

private fun AdAudienceConfig.MaxAdContentRating.toBridgeRating(): String = when (this) {
    AdAudienceConfig.MaxAdContentRating.Unspecified -> KMPAdsProtocol.RATING_UNSPECIFIED
    AdAudienceConfig.MaxAdContentRating.G -> KMPAdsProtocol.RATING_G
    AdAudienceConfig.MaxAdContentRating.PG -> KMPAdsProtocol.RATING_PG
    AdAudienceConfig.MaxAdContentRating.T -> KMPAdsProtocol.RATING_T
    AdAudienceConfig.MaxAdContentRating.MA -> KMPAdsProtocol.RATING_MA
}

/** Maps a Google Mobile Ads iOS error code to an [AdError]. */
private fun iosErrorFor(code: Int): AdError = when (code) {
    KMPAdsProtocol.ERROR_CODE_NO_HOST -> AdError.NoProvider(code)
    KMPAdsProtocol.ERROR_CODE_PROTOCOL_MISMATCH -> AdError.NoProvider(code)
    0, 8 -> AdError.InvalidRequest(code)
    1 -> AdError.NoFill(code)
    2 -> AdError.NetworkUnavailable(code)
    3 -> AdError.Internal(code)
    else -> AdError.Internal(code)
}
