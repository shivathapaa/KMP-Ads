package io.github.shivathapaa.kmpads.runtime

import io.github.shivathapaa.kmpads.config.AdAudienceConfig
import io.github.shivathapaa.kmpads.config.AdPlacementId
import io.github.shivathapaa.kmpads.config.AdPlatform
import io.github.shivathapaa.kmpads.config.AdUnitRegistry
import io.github.shivathapaa.kmpads.error.AdConfigProblem
import io.github.shivathapaa.kmpads.error.AdError
import io.github.shivathapaa.kmpads.error.AdLogger
import io.github.shivathapaa.kmpads.event.AdEvent
import io.github.shivathapaa.kmpads.event.AdEventSink
import io.github.shivathapaa.kmpads.event.AdReward
import io.github.shivathapaa.kmpads.policy.AdDecision
import io.github.shivathapaa.kmpads.policy.AdPlacement
import io.github.shivathapaa.kmpads.policy.AdPolicyRule
import io.github.shivathapaa.kmpads.policy.AdRemoteConfigSource
import io.github.shivathapaa.kmpads.policy.AdSignalKey
import io.github.shivathapaa.kmpads.policy.SuppressionReason
import io.github.shivathapaa.kmpads.provider.AdProvider
import io.github.shivathapaa.kmpads.provider.AdViewFactoryContext
import io.github.shivathapaa.kmpads.provider.BannerAdHandle
import io.github.shivathapaa.kmpads.provider.BannerSizing
import io.github.shivathapaa.kmpads.provider.NativeAdHandle
import io.github.shivathapaa.kmpads.provider.NativeAdStyle
import io.github.shivathapaa.kmpads.storage.AdStorage
import io.github.shivathapaa.kmpads.storage.InMemoryAdStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** The current time in epoch milliseconds. */
public fun interface AdClock {
    public fun nowMillis(): Long

    public companion object {
        public val System: AdClock = AdClock { currentTimeMillisCompat() }
    }
}

internal expect fun currentTimeMillisCompat(): Long

/**
 * Maps an instant to a calendar-day index for the per-day frequency cap. The default counts UTC
 * days; supply your own for local-time days.
 */
public fun interface DayKeyProvider {
    public fun dayKey(nowMillis: Long): Int

    public companion object {
        private const val MILLIS_PER_DAY: Long = 86_400_000L

        public val Utc: DayKeyProvider = DayKeyProvider { (it / MILLIS_PER_DAY).toInt() }
    }
}

/** Background longer than [inactivityThreshold] makes the next foreground a new session. */
public data class SessionConfig(val inactivityThreshold: Duration = 30.minutes)

public sealed interface AdShowOutcome {
    public data class Completed(val reward: AdReward?) : AdShowOutcome
    public data class Suppressed(val reason: SuppressionReason) : AdShowOutcome
    public data object NotReady : AdShowOutcome
    public data class Failed(val error: AdError) : AdShowOutcome
}

public sealed interface AdPreloadOutcome {
    public data object Ready : AdPreloadOutcome
    public data class Suppressed(val reason: SuppressionReason) : AdPreloadOutcome
    public data class Failed(val error: AdError) : AdPreloadOutcome
    /**
     * The placement is a banner or native ad, which loads through its own view, or no provider or unit
     * backs it.
     */
    public data object NotSupported : AdPreloadOutcome
}

/**
 * Everything the runtime needs, passed by constructor.
 *
 * Premium users are handled with a signal: map your entitlement into [AdSignalKey.UserIsPremium].
 */
public data class AdsConfig(
    val placements: List<AdPlacement>,
    val units: AdUnitRegistry,
    val provider: AdProvider,
    val platform: AdPlatform,
    val audience: AdAudienceConfig,
    val scope: CoroutineScope,
    val storage: AdStorage = InMemoryAdStorage(),
    val clock: AdClock = AdClock.System,
    val dayKeyProvider: DayKeyProvider = DayKeyProvider.Utc,
    val signals: Map<AdSignalKey, Flow<Boolean>> = emptyMap(),
    val remoteConfig: AdRemoteConfigSource = AdRemoteConfigSource.Permissive,
    val extraRules: List<AdPolicyRule> = emptyList(),
    val sinks: List<AdEventSink> = emptyList(),
    val logger: AdLogger = AdLogger.NoOp,
    val session: SessionConfig = SessionConfig(),
    /** Whether the host considers this a debug build. */
    val hostDeclaresDebugBuild: Boolean = false,
)

/** The facade a host uses. [show] evaluates the policy itself. */
public interface AdsSystem {
    public val events: SharedFlow<AdEvent>

    /** Live decision for a placement, for UI that needs to hide a whole section, not just an ad. */
    public fun decision(placement: AdPlacementId): Flow<AdDecision>

    /**
     * Preloads a full-screen placement so a later [show] presents without waiting for the network.
     * Returns [AdPreloadOutcome.NotSupported] for banner and native placements.
     */
    public suspend fun preload(placement: AdPlacementId): AdPreloadOutcome

    public suspend fun show(placement: AdPlacementId): AdShowOutcome

    /** Synchronous and network-free. See [io.github.shivathapaa.kmpads.provider.BannerAdLoader]. */
    public fun reservedBannerHeightDp(
        context: AdViewFactoryContext,
        widthDp: Int,
        sizing: BannerSizing,
    ): Int

    /** Returns null when the placement is suppressed, unsupported, or has no unit id. */
    public fun createBanner(
        context: AdViewFactoryContext,
        placement: AdPlacementId,
        widthDp: Int,
        sizing: BannerSizing,
    ): BannerAdHandle?

    /** Returns null for the same reasons as [createBanner]. */
    public suspend fun loadNative(
        context: AdViewFactoryContext,
        placement: AdPlacementId,
        style: NativeAdStyle,
    ): NativeAdHandle?

    public fun onAppForegrounded()

    public fun onAppBackgrounded()

    /** Lets [io.github.shivathapaa.kmpads.policy.WarmUp.requireFirstRunComplete] become true. */
    public fun notifyFirstRunComplete()

    public suspend fun shutdown()

    public companion object {
        /**
         * Builds the runtime, or returns every configuration problem at once. See [AdsBootstrap.orNoOp].
         */
        public fun create(config: AdsConfig): AdsBootstrap {
            val problems = validate(config)
            return if (problems.isEmpty()) {
                AdsBootstrap.Ready(DefaultAdsSystem(config))
            } else {
                AdsBootstrap.Misconfigured(problems)
            }
        }

        private fun validate(config: AdsConfig): List<AdConfigProblem> = buildList {
            val seen = mutableSetOf<AdPlacementId>()
            config.placements.forEach { placement ->
                if (!seen.add(placement.id)) add(AdConfigProblem.DuplicatePlacement(placement.id))

                if (config.units.resolve(placement.id, placement.format, config.platform) == null) {
                    add(AdConfigProblem.MissingUnitId(placement.id, config.platform))
                }

                val declared = placement.policy.requireAll + placement.policy.suppressWhen
                declared.forEach { key ->
                    if (key !in config.signals && key !in AdSignalKey.WellKnown) {
                        add(AdConfigProblem.UnregisteredSignal(placement.id, key.value))
                    }
                }
            }

            if (config.hostDeclaresDebugBuild && config.units.mode !is
                io.github.shivathapaa.kmpads.config.AdUnitMode.ForceTestUnits
            ) {
                add(AdConfigProblem.LiveUnitsInDebugBuild(config.units.mode))
            }
        }
    }
}

public sealed interface AdsBootstrap {
    public data class Ready(val system: AdsSystem) : AdsBootstrap

    public data class Misconfigured(val problems: List<AdConfigProblem>) : AdsBootstrap

    /**
     * Returns a runtime that shows no ads when configuration failed, after passing the problems to
     * [onProblems].
     */
    public fun orNoOp(onProblems: (List<AdConfigProblem>) -> Unit = {}): AdsSystem = when (this) {
        is Ready -> system
        is Misconfigured -> {
            onProblems(problems)
            NoOpAdsSystem
        }
    }
}
