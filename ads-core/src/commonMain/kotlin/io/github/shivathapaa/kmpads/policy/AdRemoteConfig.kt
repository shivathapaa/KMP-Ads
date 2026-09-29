package io.github.shivathapaa.kmpads.policy

import io.github.shivathapaa.kmpads.config.AdPlacementId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Duration.Companion.seconds

/**
 * Remote overrides applied on top of a compiled-in policy. A null field leaves the compiled-in value
 * unchanged.
 */
public data class AdPolicyPatch(
    val enabled: Boolean? = null,
    val minIntervalSeconds: Long? = null,
    val perSession: Int? = null,
    val perDay: Int? = null,
    val cooldownAfterDismissSeconds: Long? = null,
    val minSessionOrdinal: Int? = null,
) {
    public fun applyTo(policy: AdPolicy): AdPolicy = policy.copy(
        enabled = enabled ?: policy.enabled,
        frequency = policy.frequency.copy(
            minInterval = minIntervalSeconds?.seconds ?: policy.frequency.minInterval,
            perSession = perSession ?: policy.frequency.perSession,
            perDay = perDay ?: policy.frequency.perDay,
        ),
        cooldownAfterDismiss =
            cooldownAfterDismissSeconds?.seconds ?: policy.cooldownAfterDismiss,
        warmUp = policy.warmUp.copy(
            minSessionOrdinal = minSessionOrdinal ?: policy.warmUp.minSessionOrdinal,
        ),
    )
}

/** The remote kill switch, one level above per-placement patches. */
public data class AdRemoteConfig(
    val globalEnabled: Boolean = true,
    val disabledPlacements: Set<AdPlacementId> = emptySet(),
    val overrides: Map<AdPlacementId, AdPolicyPatch> = emptyMap(),
) {
    public companion object {
        public val Permissive: AdRemoteConfig = AdRemoteConfig()

        /** Disables every placement. Use it for any failure to load remote configuration. */
        public val AllDisabled: AdRemoteConfig = AdRemoteConfig(globalEnabled = false)
    }
}

/**
 * Supplies [AdRemoteConfig] from the host's own backend. Emit [AdRemoteConfig.AllDisabled] on a
 * transport or parse failure, not [AdRemoteConfig.Permissive].
 */
public fun interface AdRemoteConfigSource {
    public fun observe(): Flow<AdRemoteConfig>

    public companion object {
        public val Permissive: AdRemoteConfigSource =
            AdRemoteConfigSource { flowOf(AdRemoteConfig.Permissive) }
    }
}
