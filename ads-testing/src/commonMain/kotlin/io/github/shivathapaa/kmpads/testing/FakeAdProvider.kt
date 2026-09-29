package io.github.shivathapaa.kmpads.testing

import io.github.shivathapaa.kmpads.config.AdPlacementId
import io.github.shivathapaa.kmpads.error.AdError
import io.github.shivathapaa.kmpads.event.AdEvent
import io.github.shivathapaa.kmpads.event.AdEventSink
import io.github.shivathapaa.kmpads.event.AdReward
import io.github.shivathapaa.kmpads.policy.AdDecision
import io.github.shivathapaa.kmpads.policy.AdSignalKey
import io.github.shivathapaa.kmpads.policy.SuppressionReason
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
import io.github.shivathapaa.kmpads.provider.NativeAdLoader
import io.github.shivathapaa.kmpads.runtime.AdClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration

/** An [AdClock] that a test advances by hand. */
public class MutableAdClock(private var nowMillis: Long = DEFAULT_START_MILLIS) : AdClock {
    override fun nowMillis(): Long = nowMillis

    public fun advance(by: Duration) {
        nowMillis += by.inWholeMilliseconds
    }

    public fun setTo(millis: Long) {
        nowMillis = millis
    }

    public companion object {
        /** The default start instant. */
        public const val DEFAULT_START_MILLIS: Long = 1_700_000_000_000L
    }
}

/** Collects every event, for assertions about what the runtime decided and why. */
public class RecordingEventSink : AdEventSink {
    private val recorded = mutableListOf<AdEvent>()

    public val events: List<AdEvent> get() = recorded.toList()

    override fun onEvent(event: AdEvent) {
        recorded += event
    }

    public inline fun <reified T : AdEvent> ofType(): List<T> = events.filterIsInstance<T>()

    public fun suppressionReasons(): List<SuppressionReason> =
        ofType<AdEvent.Suppressed>().map { it.reason }

    public fun clear() {
        recorded.clear()
    }
}

/**
 * An [AdProvider] for tests that never touches a network. Full-screen presentations are finished by
 * hand with [completeShow] and [failShow].
 */
public class FakeAdProvider(
    override val id: AdNetworkId = AdNetworkId("fake"),
    supportsFullScreen: Boolean = true,
    supportsBanner: Boolean = true,
) : AdProvider {
    public var initOutcome: AdInitOutcome = AdInitOutcome.Ready
    public var initializeCount: Int = 0
        private set

    public var nextLoadOutcome: AdLoadOutcome = AdLoadOutcome.Loaded(Duration.ZERO)

    /** Height the fake banner reserves. */
    public var bannerHeightDp: Int = FAKE_BANNER_HEIGHT_DP

    private val fullScreenLoader = if (supportsFullScreen) FakeFullScreenLoader() else null
    private val bannerLoader = if (supportsBanner) FakeBannerLoader(this) else null

    override val fullScreen: FullScreenAdLoader? get() = fullScreenLoader
    override val banner: BannerAdLoader? get() = bannerLoader
    override val native: NativeAdLoader? get() = null

    public val loadedPlacements: List<AdPlacementId> get() = fullScreenLoader?.loaded().orEmpty()
    public val shownPlacements: List<AdPlacementId> get() = fullScreenLoader?.shown().orEmpty()
    public val liveBanners: Int get() = bannerLoader?.live ?: 0

    override suspend fun initialize(): AdInitOutcome {
        initializeCount++
        return initOutcome
    }

    override fun onAppForegrounded(): Unit = Unit

    override fun onAppBackgrounded(): Unit = Unit

    override fun dispose(): Unit = Unit

    /** Drives a presentation to its end. `reward = null` is a plain dismissal. */
    public fun completeShow(placement: AdPlacementId, reward: AdReward? = null) {
        fullScreenLoader?.complete(placement, reward)
    }

    public fun failShow(placement: AdPlacementId, error: AdError = AdError.Internal()) {
        fullScreenLoader?.fail(placement, error)
    }

    public fun deliverBannerLoad(placement: AdPlacementId) {
        bannerLoader?.deliverLoad(placement)
    }

    public fun deliverBannerImpression(placement: AdPlacementId) {
        bannerLoader?.deliverImpression(placement)
    }

    public fun failBannerLoad(placement: AdPlacementId, error: AdError = AdError.NoFill()) {
        bannerLoader?.deliverFailure(placement, error)
    }

    private inner class FakeFullScreenLoader : FullScreenAdLoader {
        private val ready = mutableSetOf<AdPlacementId>()
        private val showOrder = mutableListOf<AdPlacementId>()
        private val loadOrder = mutableListOf<AdPlacementId>()
        private val listeners = mutableMapOf<AdPlacementId, FullScreenAdListener>()

        fun loaded(): List<AdPlacementId> = loadOrder.toList()

        fun shown(): List<AdPlacementId> = showOrder.toList()

        override suspend fun load(request: AdLoadRequest): AdLoadOutcome {
            loadOrder += request.placement
            return nextLoadOutcome.also {
                if (it is AdLoadOutcome.Loaded) ready += request.placement
            }
        }

        override fun isReady(placement: AdPlacementId): Boolean = placement in ready

        override fun show(placement: AdPlacementId, listener: FullScreenAdListener) {
            showOrder += placement
            listeners[placement] = listener
            listener.onShown()
            listener.onImpression()
        }

        override fun discard(placement: AdPlacementId) {
            ready -= placement
            listeners -= placement
        }

        fun complete(placement: AdPlacementId, reward: AdReward?) {
            val listener = listeners.remove(placement) ?: return
            ready -= placement
            if (reward != null) listener.onRewardEarned(reward)
            listener.onDismissed()
        }

        fun fail(placement: AdPlacementId, error: AdError) {
            val listener = listeners.remove(placement) ?: return
            ready -= placement
            listener.onShowFailed(error)
        }
    }

    private class FakeBannerLoader(private val provider: FakeAdProvider) : BannerAdLoader {
        private val listeners = mutableMapOf<AdPlacementId, BannerAdListener>()
        private val loadStates = mutableMapOf<AdPlacementId, MutableStateFlow<Boolean>>()
        var live: Int = 0
            private set

        override fun reservedHeightDp(
            context: AdViewFactoryContext,
            widthDp: Int,
            sizing: BannerSizing,
        ): Int = when (sizing) {
            is BannerSizing.FixedHeight -> sizing.heightDp
            else -> provider.bannerHeightDp
        }

        override fun createBanner(
            context: AdViewFactoryContext,
            request: BannerAdRequest,
            listener: BannerAdListener,
        ): BannerAdHandle {
            listeners[request.placement] = listener
            val loaded = MutableStateFlow(false)
            loadStates[request.placement] = loaded
            live++
            return FakeBannerHandle(
                reservedHeightDp = reservedHeightDp(context, request.widthDp, request.sizing),
                isLoaded = loaded.asStateFlow(),
                onDispose = {
                    listeners -= request.placement
                    loadStates -= request.placement
                    live--
                },
            )
        }

        fun deliverLoad(placement: AdPlacementId) {
            loadStates[placement]?.value = true
            listeners[placement]?.onLoaded()
        }

        fun deliverImpression(placement: AdPlacementId) {
            listeners[placement]?.onImpression()
        }

        fun deliverFailure(placement: AdPlacementId, error: AdError) {
            // An initial no-fill keeps the slot collapsed; a later failure keeps a loaded banner in place.
            if (loadStates[placement]?.value != true) loadStates[placement]?.value = false
            listeners[placement]?.onLoadFailed(error)
        }
    }

    private class FakeBannerHandle(
        override val reservedHeightDp: Int,
        override val isLoaded: StateFlow<Boolean>,
        private val onDispose: () -> Unit,
    ) : BannerAdHandle {
        override val view: AdPlatformView = AdPlatformView.stub()

        override fun load(): Unit = Unit

        override fun pause(): Unit = Unit

        override fun resume(): Unit = Unit

        override fun dispose(): Unit = onDispose()
    }

    public companion object {
        public const val FAKE_BANNER_HEIGHT_DP: Int = 50
    }
}

public fun AdDecision.assertAllowed() {
    check(this is AdDecision.Allow) { "expected Allow but was $this" }
}

public fun AdDecision.assertSuppressed(): SuppressionReason {
    check(this is AdDecision.Suppress) { "expected Suppress but was Allow" }
    return reason
}

/** Asserts that the decision was suppressed by the signal [key]. */
public fun AdDecision.assertSuppressedBySignal(key: AdSignalKey) {
    val reason = assertSuppressed()
    check(reason is SuppressionReason.SignalSuppressed && reason.key == key) {
        "expected suppression by signal ${key.value} but was $reason"
    }
}
