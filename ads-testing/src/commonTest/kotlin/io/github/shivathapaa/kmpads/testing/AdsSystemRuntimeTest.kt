package io.github.shivathapaa.kmpads.testing

import io.github.shivathapaa.kmpads.config.AdAudienceConfig
import io.github.shivathapaa.kmpads.config.AdFormat
import io.github.shivathapaa.kmpads.config.AdPlacementId
import io.github.shivathapaa.kmpads.config.AdPlatform
import io.github.shivathapaa.kmpads.config.AdUnitRegistry
import io.github.shivathapaa.kmpads.event.AdEvent
import io.github.shivathapaa.kmpads.event.AdReward
import io.github.shivathapaa.kmpads.policy.AdPlacement
import io.github.shivathapaa.kmpads.policy.AdPolicy
import io.github.shivathapaa.kmpads.policy.AdSignalKey
import io.github.shivathapaa.kmpads.policy.FrequencyCap
import io.github.shivathapaa.kmpads.policy.SuppressionReason
import io.github.shivathapaa.kmpads.policy.WarmUp
import io.github.shivathapaa.kmpads.runtime.AdShowOutcome
import io.github.shivathapaa.kmpads.runtime.AdsBootstrap
import io.github.shivathapaa.kmpads.runtime.AdsConfig
import io.github.shivathapaa.kmpads.runtime.AdsSystem
import io.github.shivathapaa.kmpads.storage.InMemoryAdStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Runtime tests with persistence, signals and a fake provider wired together. */
@OptIn(ExperimentalCoroutinesApi::class)
class AdsSystemRuntimeTest {
    private val interstitial = AdPlacementId("interstitial")

    // Full-screen work runs on Dispatchers.Main; runTest shares this dispatcher's scheduler.
    @BeforeTest
    fun setMainDispatcher() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun TestScope.build(
        provider: FakeAdProvider = FakeAdProvider(),
        clock: MutableAdClock = MutableAdClock(),
        sink: RecordingEventSink = RecordingEventSink(),
        policy: AdPolicy = AdPolicy(warmUp = WarmUp.None),
        isPremium: Boolean = false,
    ): Fixture {
        val bootstrap = AdsSystem.create(
            AdsConfig(
                placements = listOf(AdPlacement(interstitial, AdFormat.Interstitial, policy)),
                units = AdUnitRegistry.testOnly(),
                provider = provider,
                platform = AdPlatform.Android,
                audience = AdAudienceConfig.GeneralAudience,
                // The TestScope, not backgroundScope: advanceUntilIdle() does not run backgroundScope work.
                scope = this,
                storage = InMemoryAdStorage(),
                clock = clock,
                signals = mapOf(
                    AdSignalKey.UserIsPremium to flowOf(isPremium),
                    AdSignalKey.ConsentObtained to flowOf(true),
                    AdSignalKey.NetworkAvailable to flowOf(true),
                ),
                sinks = listOf(sink),
                hostDeclaresDebugBuild = true,
            )
        )
        // Assert Ready so a misconfiguration fails here instead of falling back to the no-op system.
        val ready = assertIs<AdsBootstrap.Ready>(bootstrap, "ads system should be configured")
        return Fixture(ready.system, provider, clock, sink)
    }

    @Test
    fun a_show_completes_only_when_the_provider_reports_a_dismissal() = runTest {
        val fixture = build()
        advanceUntilIdle()

        var outcome: AdShowOutcome? = null
        launch { outcome = fixture.ads.show(interstitial) }
        advanceUntilIdle()

        // Shown, but still on screen: the caller has not resumed yet.
        assertTrue(fixture.provider.shownPlacements.contains(interstitial))
        assertEquals(null, outcome)

        fixture.provider.completeShow(interstitial)
        advanceUntilIdle()

        assertEquals(AdShowOutcome.Completed(reward = null), outcome)
    }

    @Test
    fun a_reward_arrives_before_the_dismissal_and_is_folded_into_one_outcome() = runTest {
        val fixture = build()
        advanceUntilIdle()

        var outcome: AdShowOutcome? = null
        launch { outcome = fixture.ads.show(interstitial) }
        advanceUntilIdle()

        fixture.provider.completeShow(interstitial, AdReward(type = "coins", amount = 10))
        advanceUntilIdle()

        assertEquals(AdShowOutcome.Completed(AdReward("coins", 10)), outcome)
        assertEquals(1, fixture.sink.ofType<AdEvent.RewardEarned>().size)
    }

    @Test
    fun the_minimum_interval_is_enforced_across_real_shows_and_clears_on_time() = runTest {
        val clock = MutableAdClock()
        val fixture = build(
            clock = clock,
            policy = AdPolicy(
                warmUp = WarmUp.None,
                frequency = FrequencyCap(minInterval = 3.minutes),
            ),
        )
        advanceUntilIdle()

        launch { fixture.ads.show(interstitial) }
        advanceUntilIdle()
        fixture.provider.completeShow(interstitial)
        advanceUntilIdle()

        clock.advance(1.minutes)
        val tooSoon = fixture.ads.show(interstitial)
        assertIs<AdShowOutcome.Suppressed>(tooSoon)
        assertIs<SuppressionReason.MinIntervalNotElapsed>(tooSoon.reason)

        clock.advance(2.minutes + 1.minutes)
        launch { fixture.ads.show(interstitial) }
        advanceUntilIdle()
        fixture.provider.completeShow(interstitial)
        advanceUntilIdle()

        assertEquals(2, fixture.sink.ofType<AdEvent.Impression>().size)
    }

    @Test
    fun a_premium_user_produces_no_provider_traffic_at_all() = runTest {
        val fixture = build(
            isPremium = true,
            policy = AdPolicy(
                warmUp = WarmUp.None,
                suppressWhen = setOf(AdSignalKey.UserIsPremium),
            ),
        )
        advanceUntilIdle()

        val outcome = fixture.ads.show(interstitial)

        assertIs<AdShowOutcome.Suppressed>(outcome)
        assertEquals(
            SuppressionReason.SignalSuppressed(AdSignalKey.UserIsPremium),
            outcome.reason,
        )
        // Suppressed before the network: nothing loads or shows, and the SDK is never initialised.
        assertTrue(fixture.provider.loadedPlacements.isEmpty())
        assertTrue(fixture.provider.shownPlacements.isEmpty())
        assertEquals(0, fixture.provider.initializeCount)
    }

    @Test
    fun the_sdk_is_initialised_once_and_only_on_the_first_unsuppressed_request() = runTest {
        val fixture = build()
        advanceUntilIdle()
        assertEquals(0, fixture.provider.initializeCount)

        fixture.ads.preload(interstitial)
        advanceUntilIdle()
        assertEquals(1, fixture.provider.initializeCount)

        fixture.ads.preload(interstitial)
        advanceUntilIdle()
        assertEquals(1, fixture.provider.initializeCount)
    }

    @Test
    fun every_suppression_is_named_in_telemetry() = runTest {
        val fixture = build(
            policy = AdPolicy(warmUp = WarmUp(minSessionOrdinal = 5)),
        )
        advanceUntilIdle()

        fixture.ads.show(interstitial)

        val reasons = fixture.sink.suppressionReasons()
        assertEquals(1, reasons.size)
        assertIs<SuppressionReason.WarmUpSessions>(reasons.single())
    }

    private class Fixture(
        val ads: AdsSystem,
        val provider: FakeAdProvider,
        val clock: MutableAdClock,
        val sink: RecordingEventSink,
    )
}
