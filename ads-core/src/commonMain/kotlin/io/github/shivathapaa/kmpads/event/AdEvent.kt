package io.github.shivathapaa.kmpads.event

import io.github.shivathapaa.kmpads.config.AdFormat
import io.github.shivathapaa.kmpads.config.AdPlacementId
import io.github.shivathapaa.kmpads.error.AdConfigProblem
import io.github.shivathapaa.kmpads.error.AdError
import io.github.shivathapaa.kmpads.policy.SuppressionReason
import kotlin.time.Duration

/** What a rewarded ad granted. [type] and [amount] are configured in the ad network's dashboard. */
public data class AdReward(val type: String, val amount: Int)

public enum class RevenuePrecision { Unknown, Estimated, PublisherProvided, Precise }

/**
 * A paid event. [valueMicros] is an integer amount in micros. [mediationNetwork] names the adapter
 * that served the ad, for diagnostics.
 */
public data class AdRevenue(
    val valueMicros: Long,
    val currencyCode: String,
    val precision: RevenuePrecision,
    val mediationNetwork: String?,
)

/**
 * Everything that happens to an ad, as data. [atMillis] is epoch milliseconds from the system's
 * [io.github.shivathapaa.kmpads.runtime.AdClock].
 */
public sealed interface AdEvent {
    public val atMillis: Long
    public val placement: AdPlacementId
    public val format: AdFormat

    public data class RequestStarted(
        override val atMillis: Long,
        override val placement: AdPlacementId,
        override val format: AdFormat,
    ) : AdEvent

    public data class Loaded(
        override val atMillis: Long,
        override val placement: AdPlacementId,
        override val format: AdFormat,
        val latency: Duration,
    ) : AdEvent

    public data class LoadFailed(
        override val atMillis: Long,
        override val placement: AdPlacementId,
        override val format: AdFormat,
        val error: AdError,
    ) : AdEvent

    public data class Impression(
        override val atMillis: Long,
        override val placement: AdPlacementId,
        override val format: AdFormat,
    ) : AdEvent

    public data class Clicked(
        override val atMillis: Long,
        override val placement: AdPlacementId,
        override val format: AdFormat,
    ) : AdEvent

    public data class Opened(
        override val atMillis: Long,
        override val placement: AdPlacementId,
        override val format: AdFormat,
    ) : AdEvent

    public data class Dismissed(
        override val atMillis: Long,
        override val placement: AdPlacementId,
        override val format: AdFormat,
    ) : AdEvent

    public data class RewardEarned(
        override val atMillis: Long,
        override val placement: AdPlacementId,
        override val format: AdFormat,
        val reward: AdReward,
    ) : AdEvent

    public data class Revenue(
        override val atMillis: Long,
        override val placement: AdPlacementId,
        override val format: AdFormat,
        val revenue: AdRevenue,
    ) : AdEvent

    /** A placement was not shown, with the reason it was suppressed. */
    public data class Suppressed(
        override val atMillis: Long,
        override val placement: AdPlacementId,
        override val format: AdFormat,
        val reason: SuppressionReason,
    ) : AdEvent

    public data class ShowFailed(
        override val atMillis: Long,
        override val placement: AdPlacementId,
        override val format: AdFormat,
        val error: AdError,
    ) : AdEvent

    /** Library health events, as opposed to ad lifecycle events. */
    public sealed interface Diagnostic : AdEvent {
        public data class LiveUnitsInDebugBuild(
            override val atMillis: Long,
            override val placement: AdPlacementId,
            override val format: AdFormat,
        ) : Diagnostic

        public data class Misconfigured(
            override val atMillis: Long,
            override val placement: AdPlacementId,
            override val format: AdFormat,
            val problems: List<AdConfigProblem>,
        ) : Diagnostic

        public data class StorageFailed(
            override val atMillis: Long,
            override val placement: AdPlacementId,
            override val format: AdFormat,
            val cause: Throwable?,
        ) : Diagnostic
    }
}

/**
 * Receives [AdEvent]s, for example to forward them to an analytics service. Implementations must not
 * block or throw: they are called from ad SDK callbacks.
 */
public fun interface AdEventSink {
    public fun onEvent(event: AdEvent)

    public companion object {
        public val NoOp: AdEventSink = AdEventSink { }
    }
}
