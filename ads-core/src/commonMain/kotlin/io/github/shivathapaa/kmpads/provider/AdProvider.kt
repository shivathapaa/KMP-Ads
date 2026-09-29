package io.github.shivathapaa.kmpads.provider

import io.github.shivathapaa.kmpads.config.AdFormat
import io.github.shivathapaa.kmpads.config.AdPlacementId
import io.github.shivathapaa.kmpads.config.AdUnitId
import io.github.shivathapaa.kmpads.error.AdError
import io.github.shivathapaa.kmpads.event.AdReward
import io.github.shivathapaa.kmpads.event.AdRevenue
import kotlinx.coroutines.flow.StateFlow
import kotlin.jvm.JvmInline
import kotlin.time.Duration

/**
 * A platform ad view: `android.view.View` on Android, `UIView` on iOS. Created only by providers and
 * unwrapped with the platform accessors in this package.
 */
public class AdPlatformView internal constructor(internal val platform: Any) {
    public companion object {
        /**
         * A view that unwraps to nothing on every platform. For fakes, previews and screenshot tests.
         */
        public fun stub(): AdPlatformView = AdPlatformView(StubPlatformToken)
    }
}

/**
 * What a provider needs to build a view. On Android it wraps the Activity context; on iOS it is
 * empty, because the Swift adapter resolves its own presenting view controller.
 */
public class AdViewFactoryContext internal constructor(internal val platform: Any?) {
    public companion object {
        /** A context that unwraps to nothing. For fakes, previews and screenshot tests. */
        public fun stub(): AdViewFactoryContext = AdViewFactoryContext(null)
    }
}

/** Marks a view holder that came from [AdPlatformView.stub] rather than from a real SDK. */
internal object StubPlatformToken

@JvmInline
public value class AdNetworkId(public val value: String)

public sealed interface AdInitOutcome {
    public data object Ready : AdInitOutcome
    public data class Failed(val error: AdError) : AdInitOutcome
}

/**
 * An ad network adapter. A loader property is null when the network does not support that format;
 * placements of that format are then suppressed.
 */
public interface AdProvider {
    public val id: AdNetworkId

    /** Idempotent. Called once by the runtime, and safe to call again after process death. */
    public suspend fun initialize(): AdInitOutcome

    public val fullScreen: FullScreenAdLoader?
    public val banner: BannerAdLoader?
    public val native: NativeAdLoader?

    public fun onAppForegrounded()
    public fun onAppBackgrounded()
    public fun dispose()
}

public data class AdLoadRequest(
    val placement: AdPlacementId,
    val format: AdFormat,
    val unitId: AdUnitId,
)

public sealed interface AdLoadOutcome {
    public data class Loaded(val latency: Duration) : AdLoadOutcome
    public data class Failed(val error: AdError) : AdLoadOutcome
}

public interface FullScreenAdLoader {
    /** Suspends until the SDK resolves. Cancellation must discard the in-flight request. */
    public suspend fun load(request: AdLoadRequest): AdLoadOutcome

    public fun isReady(placement: AdPlacementId): Boolean

    /**
     * Presents a loaded ad and reports the reward and the dismissal through [listener]. The runtime
     * wraps this in a suspending call.
     */
    public fun show(placement: AdPlacementId, listener: FullScreenAdListener)

    public fun discard(placement: AdPlacementId)
}

public interface FullScreenAdListener {
    public fun onShown()
    public fun onImpression()
    public fun onClicked()
    public fun onRewardEarned(reward: AdReward)
    public fun onRevenue(revenue: AdRevenue)
    public fun onDismissed()
    public fun onShowFailed(error: AdError)
}

public sealed interface BannerSizing {
    /** Anchored adaptive: the height is a pure function of the width, known before any request. */
    public data object AnchoredAdaptive : BannerSizing
    public data object Standard320x50 : BannerSizing
    public data class FixedHeight(val heightDp: Int) : BannerSizing
}

public data class BannerAdRequest(
    val placement: AdPlacementId,
    val unitId: AdUnitId,
    val widthDp: Int,
    val sizing: BannerSizing,
)

public interface BannerAdLoader {
    /**
     * The banner's height for a width. Synchronous and network-free, so a slot can reserve the height
     * before requesting an ad.
     */
    public fun reservedHeightDp(
        context: AdViewFactoryContext,
        widthDp: Int,
        sizing: BannerSizing,
    ): Int

    public fun createBanner(
        context: AdViewFactoryContext,
        request: BannerAdRequest,
        listener: BannerAdListener,
    ): BannerAdHandle
}

public interface BannerAdHandle {
    public val view: AdPlatformView
    public val reservedHeightDp: Int

    /**
     * Whether a creative is loaded: `false` until the first fill and after an initial no-fill or a
     * failed request. Once `true`, a failed refresh does not reset it, because the previous creative
     * stays on screen.
     */
    public val isLoaded: StateFlow<Boolean>
    public fun load()
    public fun pause()
    public fun resume()
    public fun dispose()
}

public interface BannerAdListener {
    public fun onLoaded()
    public fun onLoadFailed(error: AdError)
    public fun onImpression()
    public fun onClicked()
    public fun onRevenue(revenue: AdRevenue)
}

/** Platform-neutral styling. Colours are `0xAARRGGBB`; sizes are dp and sp values. */
public data class NativeAdStyle(
    val backgroundArgb: Long,
    val primaryTextArgb: Long,
    val secondaryTextArgb: Long,
    val ctaBackgroundArgb: Long,
    val ctaTextArgb: Long,
    val cornerRadiusDp: Int,
    val headlineTextSizeSp: Float,
    val bodyTextSizeSp: Float,
)

public data class NativeAdRequest(
    val placement: AdPlacementId,
    val unitId: AdUnitId,
    val style: NativeAdStyle,
)

/**
 * Read-only asset metadata, for deciding whether to show an ad, sizing a placeholder and logging.
 * Render the ad through [NativeAdHandle.view], never from these strings.
 */
public data class NativeAdAssets(
    val headline: String?,
    val body: String?,
    val callToAction: String?,
    val advertiser: String?,
    val starRating: Double?,
    val hasIcon: Boolean,
    val hasMedia: Boolean,
    val mediaAspectRatio: Float?,
)

public interface NativeAdHandle {
    public val assets: NativeAdAssets
    public val view: AdPlatformView
    public fun dispose()
}

public sealed interface NativeAdLoadOutcome {
    public data class Loaded(val handle: NativeAdHandle) : NativeAdLoadOutcome
    public data class Failed(val error: AdError) : NativeAdLoadOutcome
}

public interface NativeAdLoader {
    public suspend fun load(
        context: AdViewFactoryContext,
        request: NativeAdRequest,
        listener: NativeAdListener,
    ): NativeAdLoadOutcome
}

public interface NativeAdListener {
    public fun onImpression()
    public fun onClicked()
    public fun onRevenue(revenue: AdRevenue)
}
