package io.github.shivathapaa.kmpads.error

import io.github.shivathapaa.kmpads.config.AdFormat
import io.github.shivathapaa.kmpads.config.AdPlacementId
import io.github.shivathapaa.kmpads.config.AdPlatform
import io.github.shivathapaa.kmpads.config.AdUnitMode

/**
 * Why an ad failed. Holds no user-facing text: [networkCode] is the ad network's error code, and
 * [cause] is a throwable for logging only.
 */
public sealed interface AdError {
    public val networkCode: Int?
    public val cause: Throwable?

    /** The request succeeded but there was no ad to serve. */
    public data class NoFill(
        override val networkCode: Int? = null,
        override val cause: Throwable? = null,
    ) : AdError

    public data class NetworkUnavailable(
        override val networkCode: Int? = null,
        override val cause: Throwable? = null,
    ) : AdError

    public data class Timeout(
        override val networkCode: Int? = null,
        override val cause: Throwable? = null,
    ) : AdError

    /** A malformed request: usually a wrong unit id or a format the unit is not configured for. */
    public data class InvalidRequest(
        override val networkCode: Int? = null,
        override val cause: Throwable? = null,
    ) : AdError

    public data class SdkNotInitialized(
        override val networkCode: Int? = null,
        override val cause: Throwable? = null,
    ) : AdError

    public data class RateLimited(
        override val networkCode: Int? = null,
        override val cause: Throwable? = null,
    ) : AdError

    /** Another full-screen ad is on screen. Never a reason to retry immediately. */
    public data class AlreadyShowing(
        override val networkCode: Int? = null,
        override val cause: Throwable? = null,
    ) : AdError

    /** No Activity or view controller was available to present into. */
    public data class NoPresentationHost(
        override val networkCode: Int? = null,
        override val cause: Throwable? = null,
    ) : AdError

    /** No provider was supplied, so the app runs without ads. */
    public data class NoProvider(
        override val networkCode: Int? = null,
        override val cause: Throwable? = null,
    ) : AdError

    public data class Internal(
        override val networkCode: Int? = null,
        override val cause: Throwable? = null,
    ) : AdError
}

/** A configuration mistake, reported once when the ads system is created. */
public sealed interface AdConfigProblem {
    public data class MalformedUnitId(
        val placement: AdPlacementId,
        val platform: AdPlatform,
    ) : AdConfigProblem

    public data class MissingUnitId(
        val placement: AdPlacementId,
        val platform: AdPlatform,
    ) : AdConfigProblem

    public data class DuplicatePlacement(val placement: AdPlacementId) : AdConfigProblem

    /** A policy names a signal for which no source was registered. Fails closed at evaluation. */
    public data class UnregisteredSignal(
        val placement: AdPlacementId,
        val key: String,
    ) : AdConfigProblem

    public data class FormatUnsupportedByProvider(
        val placement: AdPlacementId,
        val format: AdFormat,
    ) : AdConfigProblem

    /** The host declared a debug build but requested live ad units. */
    public data class LiveUnitsInDebugBuild(val mode: AdUnitMode) : AdConfigProblem
}

public enum class AdLogLevel { Debug, Info, Warn, Error }

/**
 * Log output from the library, including ad SDK error messages. Hosts forward it to their own
 * logger.
 */
public fun interface AdLogger {
    public fun log(level: AdLogLevel, tag: String, message: String, cause: Throwable?)

    public companion object {
        public val NoOp: AdLogger = AdLogger { _, _, _, _ -> }
    }
}
