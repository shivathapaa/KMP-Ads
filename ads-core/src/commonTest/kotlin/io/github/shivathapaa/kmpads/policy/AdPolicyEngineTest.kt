package io.github.shivathapaa.kmpads.policy

import io.github.shivathapaa.kmpads.config.AdFormat
import io.github.shivathapaa.kmpads.config.AdPlacementId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Tests for [AdPolicyEngine]. Time is passed in, so every case is a plain value comparison. */
class AdPolicyEngineTest {
    private val placementId = AdPlacementId("test")
    private val audioIsPlaying = AdSignalKey("app.audio_is_playing")

    private fun input(
        policy: AdPolicy = AdPolicy(),
        counters: PlacementCounters = PlacementCounters(),
        signals: Map<AdSignalKey, Boolean> = emptyMap(),
        nowMillis: Long = BASE_MILLIS,
        dayKey: Int = 100,
        sessionOrdinal: Int = 10,
        sessionStartedAtMillis: Long = BASE_MILLIS - 1.hours.inWholeMilliseconds,
        firstLaunchAtMillis: Long = BASE_MILLIS - 30.days.inWholeMilliseconds,
        firstRunComplete: Boolean = true,
        globalEnabled: Boolean = true,
        remotelyDisabled: Boolean = false,
        unitIdPresent: Boolean = true,
        providerSupportsFormat: Boolean = true,
        stateLoaded: Boolean = true,
    ) = PolicyInput(
        placement = AdPlacement(placementId, AdFormat.Interstitial, policy),
        policy = policy,
        counters = counters,
        session = SessionSnapshot(
            ordinal = sessionOrdinal,
            startedAtMillis = sessionStartedAtMillis,
            firstLaunchAtMillis = firstLaunchAtMillis,
            firstRunComplete = firstRunComplete,
        ),
        signals = signals,
        globalEnabled = globalEnabled,
        remotelyDisabled = remotelyDisabled,
        unitIdPresent = unitIdPresent,
        providerSupportsFormat = providerSupportsFormat,
        stateLoaded = stateLoaded,
        nowMillis = nowMillis,
        dayKey = dayKey,
    )

    @Test
    fun allows_when_nothing_objects() {
        assertEquals(AdDecision.Allow, AdPolicyEngine.evaluate(input()))
    }

    @Test
    fun suppresses_everything_until_persisted_state_has_loaded() {
        // The counters are not restored yet, so nothing may show.
        val decision = AdPolicyEngine.evaluate(input(stateLoaded = false))
        assertEquals(AdDecision.Suppress(SuppressionReason.StateNotLoaded), decision)
    }

    @Test
    fun a_policy_naming_an_unregistered_signal_fails_closed() {
        val policy = AdPolicy(requireAll = setOf(AdSignalKey.ConsentObtained))
        val decision = AdPolicyEngine.evaluate(input(policy = policy, signals = emptyMap()))
        assertEquals(
            AdDecision.Suppress(SuppressionReason.SignalMissing(AdSignalKey.ConsentObtained)),
            decision,
        )
    }

    @Test
    fun a_backwards_clock_keeps_the_cooldown_closed() {
        // A clock earlier than the recorded dismissal must not count as enough elapsed time.
        val counters = PlacementCounters(lastDismissedAtMillis = BASE_MILLIS + 1.hours.inWholeMilliseconds)
        val decision = AdPolicyEngine.evaluate(
            input(policy = AdPolicy(cooldownAfterDismiss = 30.seconds), counters = counters)
        )
        assertIs<AdDecision.Suppress>(decision)
        assertIs<SuppressionReason.CooldownAfterDismiss>(decision.reason)
    }

    @Test
    fun the_global_kill_switch_wins_over_everything_else() {
        val decision = AdPolicyEngine.evaluate(input(globalEnabled = false))
        assertEquals(AdDecision.Suppress(SuppressionReason.GlobalKillSwitch), decision)
    }

    @Test
    fun a_remotely_disabled_placement_is_named_as_such() {
        val decision = AdPolicyEngine.evaluate(input(remotelyDisabled = true))
        assertEquals(AdDecision.Suppress(SuppressionReason.RemotelyDisabled), decision)
    }

    @Test
    fun a_host_declared_signal_suppresses_without_the_library_knowing_what_it_means() {
        val policy = AdPolicy(suppressWhen = setOf(audioIsPlaying))
        val decision = AdPolicyEngine.evaluate(
            input(policy = policy, signals = mapOf(audioIsPlaying to true))
        )
        assertEquals(AdDecision.Suppress(SuppressionReason.SignalSuppressed(audioIsPlaying)), decision)
    }

    @Test
    fun the_same_signal_being_false_allows() {
        val policy = AdPolicy(suppressWhen = setOf(audioIsPlaying))
        val decision = AdPolicyEngine.evaluate(
            input(policy = policy, signals = mapOf(audioIsPlaying to false))
        )
        assertEquals(AdDecision.Allow, decision)
    }

    @Test
    fun a_required_signal_that_is_false_is_reported_distinctly_from_a_missing_one() {
        val policy = AdPolicy(requireAll = setOf(AdSignalKey.ConsentObtained))
        val decision = AdPolicyEngine.evaluate(
            input(policy = policy, signals = mapOf(AdSignalKey.ConsentObtained to false))
        )
        assertEquals(
            AdDecision.Suppress(SuppressionReason.RequiredSignalFalse(AdSignalKey.ConsentObtained)),
            decision,
        )
    }

    @Test
    fun warm_up_counts_sessions_and_reports_how_many_remain() {
        val policy = AdPolicy(warmUp = WarmUp(minSessionOrdinal = 3))
        val decision = AdPolicyEngine.evaluate(input(policy = policy, sessionOrdinal = 1))
        assertEquals(AdDecision.Suppress(SuppressionReason.WarmUpSessions(2)), decision)
    }

    @Test
    fun warm_up_also_gates_on_install_age_and_time_in_session() {
        val policy = AdPolicy(
            warmUp = WarmUp(minAgeSinceFirstLaunch = 1.days, minTimeInSession = 45.seconds)
        )
        val fresh = AdPolicyEngine.evaluate(
            input(policy = policy, firstLaunchAtMillis = BASE_MILLIS - 1.hours.inWholeMilliseconds)
        )
        assertIs<SuppressionReason.WarmUpSinceFirstLaunch>(assertIs<AdDecision.Suppress>(fresh).reason)

        val justOpened = AdPolicyEngine.evaluate(
            input(policy = policy, sessionStartedAtMillis = BASE_MILLIS - 1_000)
        )
        assertIs<SuppressionReason.WarmUpSessionAge>(assertIs<AdDecision.Suppress>(justOpened).reason)
    }

    @Test
    fun warm_up_can_wait_for_first_run_to_complete() {
        val policy = AdPolicy(warmUp = WarmUp(requireFirstRunComplete = true))
        val decision = AdPolicyEngine.evaluate(input(policy = policy, firstRunComplete = false))
        assertEquals(AdDecision.Suppress(SuppressionReason.FirstRunIncomplete), decision)
    }

    @Test
    fun the_minimum_interval_reports_the_time_left_so_a_caller_can_schedule_instead_of_poll() {
        val policy = AdPolicy(frequency = FrequencyCap(minInterval = 3.minutes))
        val counters = PlacementCounters(
            lastShownAtMillis = BASE_MILLIS - 1.minutes.inWholeMilliseconds
        )
        val decision = AdPolicyEngine.evaluate(input(policy = policy, counters = counters))
        assertEquals(
            AdDecision.Suppress(SuppressionReason.MinIntervalNotElapsed(2.minutes)),
            decision,
        )
    }

    @Test
    fun the_minimum_interval_clears_once_it_has_elapsed() {
        val policy = AdPolicy(frequency = FrequencyCap(minInterval = 3.minutes))
        val counters = PlacementCounters(
            lastShownAtMillis = BASE_MILLIS - 4.minutes.inWholeMilliseconds
        )
        assertEquals(AdDecision.Allow, AdPolicyEngine.evaluate(input(policy = policy, counters = counters)))
    }

    @Test
    fun the_session_cap_holds_until_the_session_rolls() {
        val policy = AdPolicy(frequency = FrequencyCap(perSession = 2))
        val capped = PlacementCounters(shownThisSession = 2)
        assertEquals(
            AdDecision.Suppress(SuppressionReason.SessionCapReached(2)),
            AdPolicyEngine.evaluate(input(policy = policy, counters = capped)),
        )
    }

    @Test
    fun the_daily_cap_resets_on_the_next_calendar_day_with_no_scheduled_work() {
        // Counters from a previous day count as zero.
        val policy = AdPolicy(frequency = FrequencyCap(perDay = 2))
        val counters = PlacementCounters(dayKey = 100, shownToday = 2)

        assertEquals(
            AdDecision.Suppress(SuppressionReason.DailyCapReached(2)),
            AdPolicyEngine.evaluate(input(policy = policy, counters = counters, dayKey = 100)),
        )
        assertEquals(
            AdDecision.Allow,
            AdPolicyEngine.evaluate(input(policy = policy, counters = counters, dayKey = 101)),
        )
    }

    @Test
    fun wiring_problems_outrank_frequency_so_the_reported_reason_is_the_actionable_one() {
        val policy = AdPolicy(frequency = FrequencyCap(perSession = 1))
        val decision = AdPolicyEngine.evaluate(
            input(
                policy = policy,
                counters = PlacementCounters(shownThisSession = 5),
                unitIdPresent = false,
            )
        )
        assertEquals(AdDecision.Suppress(SuppressionReason.UnitIdMissing), decision)
    }

    @Test
    fun a_host_rule_can_veto_what_the_defaults_allowed() {
        val alwaysVeto = AdPolicyRule { SuppressionReason.PlacementDisabled }
        val decision = AdPolicyEngine.evaluate(
            input = input(),
            rules = AdPolicyEngine.DefaultRules + alwaysVeto,
        )
        assertEquals(AdDecision.Suppress(SuppressionReason.PlacementDisabled), decision)
    }

    @Test
    fun a_host_rule_cannot_re_allow_what_a_default_rule_vetoed() {
        val alwaysAllow = AdPolicyRule { null }
        val decision = AdPolicyEngine.evaluate(
            input = input(globalEnabled = false),
            rules = AdPolicyEngine.DefaultRules + alwaysAllow,
        )
        assertEquals(AdDecision.Suppress(SuppressionReason.GlobalKillSwitch), decision)
    }

    @Test
    fun the_full_screen_default_blocks_a_brand_new_install() {
        val decision = AdPolicyEngine.evaluate(
            input(
                policy = AdPolicy.FullScreenDefault,
                sessionOrdinal = 1,
                signals = mapOf(
                    AdSignalKey.UserIsPremium to false,
                    AdSignalKey.ConsentObtained to true,
                    AdSignalKey.NetworkAvailable to true,
                ),
            )
        )
        assertTrue(decision is AdDecision.Suppress)
    }

    @Test
    fun the_full_screen_default_blocks_a_premium_user_outright() {
        val decision = AdPolicyEngine.evaluate(
            input(
                policy = AdPolicy.FullScreenDefault.copy(warmUp = WarmUp.None),
                signals = mapOf(
                    AdSignalKey.UserIsPremium to true,
                    AdSignalKey.ConsentObtained to true,
                    AdSignalKey.NetworkAvailable to true,
                ),
            )
        )
        assertEquals(
            AdDecision.Suppress(SuppressionReason.SignalSuppressed(AdSignalKey.UserIsPremium)),
            decision,
        )
    }

    @Test
    fun a_patched_policy_is_what_gets_evaluated() {
        val patched = AdPolicyPatch(perDay = 1).applyTo(
            AdPolicy(frequency = FrequencyCap(perDay = 10))
        )
        assertEquals(1, patched.frequency.perDay)
        // An absent field leaves the compiled-in value unchanged.
        assertEquals(Duration.ZERO, patched.frequency.minInterval)

        val decision = AdPolicyEngine.evaluate(
            input(
                policy = patched,
                counters = PlacementCounters(dayKey = 100, shownToday = 1),
                dayKey = 100,
            )
        )
        assertEquals(AdDecision.Suppress(SuppressionReason.DailyCapReached(1)), decision)
    }

    private companion object {
        const val BASE_MILLIS = 1_700_000_000_000L
    }
}
