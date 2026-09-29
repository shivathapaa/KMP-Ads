package io.github.shivathapaa.kmpads.policy

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Per-placement counters used by the frequency and cooldown rules. [shownThisSession] is not
 * persisted.
 */
public data class PlacementCounters(
    val lastShownAtMillis: Long? = null,
    val lastDismissedAtMillis: Long? = null,
    val dayKey: Int = Int.MIN_VALUE,
    val shownToday: Int = 0,
    val shownThisSession: Int = 0,
    val shownEver: Int = 0,
)

/** The current session, as the warm-up rule sees it. [ordinal] is 1-based: a first run is 1. */
public data class SessionSnapshot(
    val ordinal: Int,
    val startedAtMillis: Long,
    val firstLaunchAtMillis: Long,
    val firstRunComplete: Boolean,
)

/**
 * Everything the engine evaluates. The caller supplies [nowMillis] and [dayKey], so evaluation does
 * no I/O and reads no clock.
 */
public data class PolicyInput(
    val placement: AdPlacement,
    /** The placement's policy after any remote patch has been applied. */
    val policy: AdPolicy,
    val counters: PlacementCounters,
    val session: SessionSnapshot,
    val signals: Map<AdSignalKey, Boolean>,
    val globalEnabled: Boolean,
    val remotelyDisabled: Boolean,
    val unitIdPresent: Boolean,
    val providerSupportsFormat: Boolean,
    val stateLoaded: Boolean,
    val nowMillis: Long,
    /** A calendar-day index in whatever timezone the host chose. Only equality matters here. */
    val dayKey: Int,
)

/**
 * Why a placement was not shown. Some reasons carry data, such as
 * [MinIntervalNotElapsed.remaining].
 */
public sealed interface SuppressionReason {
    public data object StateNotLoaded : SuppressionReason
    public data object GlobalKillSwitch : SuppressionReason
    public data object PlacementDisabled : SuppressionReason
    public data object RemotelyDisabled : SuppressionReason
    public data object UnitIdMissing : SuppressionReason
    public data object ProviderLacksFormat : SuppressionReason
    public data object FirstRunIncomplete : SuppressionReason

    public data class RequiredSignalFalse(val key: AdSignalKey) : SuppressionReason
    public data class SignalSuppressed(val key: AdSignalKey) : SuppressionReason

    /** A policy named a signal nobody registered. Fails closed rather than defaulting to true. */
    public data class SignalMissing(val key: AdSignalKey) : SuppressionReason

    public data class WarmUpSessions(val remaining: Int) : SuppressionReason
    public data class WarmUpSinceFirstLaunch(val remaining: Duration) : SuppressionReason
    public data class WarmUpSessionAge(val remaining: Duration) : SuppressionReason
    public data class CooldownAfterDismiss(val remaining: Duration) : SuppressionReason
    public data class MinIntervalNotElapsed(val remaining: Duration) : SuppressionReason
    public data class SessionCapReached(val cap: Int) : SuppressionReason
    public data class DailyCapReached(val cap: Int) : SuppressionReason
}

public sealed interface AdDecision {
    public data object Allow : AdDecision

    public data class Suppress(val reason: SuppressionReason) : AdDecision

    public val isAllowed: Boolean get() = this is Allow
}

/**
 * A rule that can veto a placement: returning non-null suppresses it, returning null defers to the
 * next rule. Hosts can append rules; the default rules always run first.
 */
public fun interface AdPolicyRule {
    public fun check(input: PolicyInput): SuppressionReason?
}

/** Evaluates placement policies. Pure and independent of the rest of the library. */
public object AdPolicyEngine {
    /** The default rules, in evaluation order. */
    public val DefaultRules: List<AdPolicyRule> = listOf(
        StateLoadedRule,
        KillSwitchRule,
        WiringRule,
        SignalRule,
        WarmUpRule,
        CooldownRule,
        FrequencyRule,
    )

    public fun evaluate(
        input: PolicyInput,
        rules: List<AdPolicyRule> = DefaultRules,
    ): AdDecision {
        rules.forEach { rule ->
            val reason = rule.check(input)
            if (reason != null) return AdDecision.Suppress(reason)
        }
        return AdDecision.Allow
    }
}

/** Suppresses every placement until persisted state has loaded. */
public object StateLoadedRule : AdPolicyRule {
    override fun check(input: PolicyInput): SuppressionReason? =
        if (input.stateLoaded) null else SuppressionReason.StateNotLoaded
}

public object KillSwitchRule : AdPolicyRule {
    override fun check(input: PolicyInput): SuppressionReason? = when {
        !input.globalEnabled -> SuppressionReason.GlobalKillSwitch
        input.remotelyDisabled -> SuppressionReason.RemotelyDisabled
        !input.policy.enabled -> SuppressionReason.PlacementDisabled
        else -> null
    }
}

public object WiringRule : AdPolicyRule {
    override fun check(input: PolicyInput): SuppressionReason? = when {
        !input.unitIdPresent -> SuppressionReason.UnitIdMissing
        !input.providerSupportsFormat -> SuppressionReason.ProviderLacksFormat
        else -> null
    }
}

public object SignalRule : AdPolicyRule {
    override fun check(input: PolicyInput): SuppressionReason? {
        input.policy.requireAll.forEach { key ->
            val value = input.signals[key] ?: return SuppressionReason.SignalMissing(key)
            if (!value) return SuppressionReason.RequiredSignalFalse(key)
        }
        input.policy.suppressWhen.forEach { key ->
            val value = input.signals[key] ?: return SuppressionReason.SignalMissing(key)
            if (value) return SuppressionReason.SignalSuppressed(key)
        }
        return null
    }
}

public object WarmUpRule : AdPolicyRule {
    override fun check(input: PolicyInput): SuppressionReason? {
        val warmUp = input.policy.warmUp
        val session = input.session

        if (warmUp.requireFirstRunComplete && !session.firstRunComplete) {
            return SuppressionReason.FirstRunIncomplete
        }
        if (session.ordinal < warmUp.minSessionOrdinal) {
            return SuppressionReason.WarmUpSessions(warmUp.minSessionOrdinal - session.ordinal)
        }
        if (warmUp.minAgeSinceFirstLaunch > Duration.ZERO) {
            val age = (input.nowMillis - session.firstLaunchAtMillis).milliseconds
            if (age < warmUp.minAgeSinceFirstLaunch) {
                return SuppressionReason.WarmUpSinceFirstLaunch(warmUp.minAgeSinceFirstLaunch - age)
            }
        }
        if (warmUp.minTimeInSession > Duration.ZERO) {
            val inSession = (input.nowMillis - session.startedAtMillis).milliseconds
            if (inSession < warmUp.minTimeInSession) {
                return SuppressionReason.WarmUpSessionAge(warmUp.minTimeInSession - inSession)
            }
        }
        return null
    }
}

public object CooldownRule : AdPolicyRule {
    override fun check(input: PolicyInput): SuppressionReason? {
        val cooldown = input.policy.cooldownAfterDismiss
        if (cooldown <= Duration.ZERO) return null
        val dismissedAt = input.counters.lastDismissedAtMillis ?: return null
        // A clock that moved backwards gives a negative elapsed time, which suppresses.
        val elapsed = (input.nowMillis - dismissedAt).milliseconds
        return if (elapsed < cooldown) {
            SuppressionReason.CooldownAfterDismiss(cooldown - elapsed)
        } else {
            null
        }
    }
}

public object FrequencyRule : AdPolicyRule {
    override fun check(input: PolicyInput): SuppressionReason? {
        val cap = input.policy.frequency
        val counters = input.counters

        cap.perSession?.let { limit ->
            if (counters.shownThisSession >= limit) {
                return SuppressionReason.SessionCapReached(limit)
            }
        }

        cap.perDay?.let { limit ->
            // Counters from a previous day count as zero.
            val today = if (counters.dayKey == input.dayKey) counters.shownToday else 0
            if (today >= limit) return SuppressionReason.DailyCapReached(limit)
        }

        if (cap.minInterval > Duration.ZERO) {
            val lastShownAt = counters.lastShownAtMillis ?: return null
            val elapsed = (input.nowMillis - lastShownAt).milliseconds
            if (elapsed < cap.minInterval) {
                return SuppressionReason.MinIntervalNotElapsed(cap.minInterval - elapsed)
            }
        }
        return null
    }
}
