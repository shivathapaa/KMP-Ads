package io.github.shivathapaa.kmpads.policy

import io.github.shivathapaa.kmpads.config.AdFormat
import io.github.shivathapaa.kmpads.config.AdPlacementId
import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A named boolean the host provides as a `Flow<Boolean>`. Policies refer to signals by key through
 * [AdPolicy.suppressWhen] and [AdPolicy.requireAll]. The library defines the well-known keys below;
 * apps can declare their own.
 */
@JvmInline
public value class AdSignalKey(public val value: String) {
    public companion object {
        public val UserIsPremium: AdSignalKey = AdSignalKey("ads.user_is_premium")
        public val ConsentObtained: AdSignalKey = AdSignalKey("ads.consent_obtained")
        public val NetworkAvailable: AdSignalKey = AdSignalKey("ads.network_available")
        public val AppInForeground: AdSignalKey = AdSignalKey("ads.app_in_foreground")

        public val WellKnown: Set<AdSignalKey> =
            setOf(UserIsPremium, ConsentObtained, NetworkAvailable, AppInForeground)
    }
}

/** How often a placement may fire. `null` means "no cap of this kind". */
public data class FrequencyCap(
    val minInterval: Duration = Duration.ZERO,
    val perSession: Int? = null,
    val perDay: Int? = null,
) {
    public companion object {
        public val None: FrequencyCap = FrequencyCap()
    }
}

/**
 * How long a fresh install is left without ads. Every condition must hold: `minSessionOrdinal = 3`
 * means nothing shows before the user's third session.
 */
public data class WarmUp(
    val minSessionOrdinal: Int = 1,
    val minAgeSinceFirstLaunch: Duration = Duration.ZERO,
    val minTimeInSession: Duration = Duration.ZERO,
    val requireFirstRunComplete: Boolean = false,
) {
    public companion object {
        public val None: WarmUp = WarmUp()
    }
}

/**
 * The rules that decide whether a placement may show.
 *
 * Any signal in [suppressWhen] that is true suppresses the placement, and every signal in
 * [requireAll] must be true. For other conditions, add an [AdPolicyRule].
 */
public data class AdPolicy(
    val enabled: Boolean = true,
    val frequency: FrequencyCap = FrequencyCap.None,
    val cooldownAfterDismiss: Duration = Duration.ZERO,
    val warmUp: WarmUp = WarmUp.None,
    val suppressWhen: Set<AdSignalKey> = emptySet(),
    val requireAll: Set<AdSignalKey> = emptySet(),
) {
    public companion object {
        public val Default: AdPolicy = AdPolicy()

        /** A conservative preset for full-screen formats. */
        public val FullScreenDefault: AdPolicy = AdPolicy(
            frequency = FrequencyCap(minInterval = 3.minutes, perSession = 3, perDay = 12),
            cooldownAfterDismiss = 30.seconds,
            warmUp = WarmUp(
                minSessionOrdinal = 3,
                minAgeSinceFirstLaunch = 1.days,
                minTimeInSession = 45.seconds,
            ),
            suppressWhen = setOf(AdSignalKey.UserIsPremium),
            requireAll = setOf(AdSignalKey.ConsentObtained, AdSignalKey.NetworkAvailable),
        )

        /** An inline surface: no interval or session caps, but the same gates and premium rule. */
        public val InlineDefault: AdPolicy = AdPolicy(
            suppressWhen = setOf(AdSignalKey.UserIsPremium),
            requireAll = setOf(AdSignalKey.ConsentObtained, AdSignalKey.NetworkAvailable),
        )

        /**
         * A full-screen ad the user asks for, such as a "watch an ad to unlock" button. Has no frequency cap
         * or warm-up, and is still hidden for premium users and gated on consent and network.
         */
        public val OnDemand: AdPolicy = AdPolicy(
            frequency = FrequencyCap.None,
            warmUp = WarmUp.None,
            suppressWhen = setOf(AdSignalKey.UserIsPremium),
            requireAll = setOf(AdSignalKey.ConsentObtained, AdSignalKey.NetworkAvailable),
        )
    }
}

/** One declared ad opportunity: an id, the format it serves, and the rules it obeys. */
public data class AdPlacement(
    val id: AdPlacementId,
    val format: AdFormat,
    val policy: AdPolicy = AdPolicy.Default,
)
