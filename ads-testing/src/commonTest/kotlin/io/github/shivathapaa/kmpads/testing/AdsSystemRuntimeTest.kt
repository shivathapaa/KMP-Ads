package io.github.shivathapaa.kmpads.testing

import io.github.shivathapaa.kmpads.config.AdAudienceConfig
import io.github.shivathapaa.kmpads.config.AdFormat
import io.github.shivathapaa.kmpads.config.AdPlacementId
import io.github.shivathapaa.kmpads.config.AdPlatform
import io.github.shivathapaa.kmpads.config.AdUnitRegistry
import io.github.shivathapaa.kmpads.error.AdConfigProblem
import io.github.shivathapaa.kmpads.error.AdLogger
import io.github.shivathapaa.kmpads.event.AdEvent
import io.github.shivathapaa.kmpads.event.AdEventSink
import io.github.shivathapaa.kmpads.event.AdReward
import io.github.shivathapaa.kmpads.policy.AdDecision
import io.github.shivathapaa.kmpads.policy.AdPlacement
import io.github.shivathapaa.kmpads.policy.AdPolicy
import io.github.shivathapaa.kmpads.policy.AdRemoteConfig
import io.github.shivathapaa.kmpads.policy.AdRemoteConfigSource
import io.github.shivathapaa.kmpads.policy.AdSignalKey
import io.github.shivathapaa.kmpads.policy.FrequencyCap
import io.github.shivathapaa.kmpads.policy.SuppressionReason
import io.github.shivathapaa.kmpads.policy.WarmUp
import io.github.shivathapaa.kmpads.provider.AdProvider
import io.github.shivathapaa.kmpads.provider.AdViewFactoryContext
import io.github.shivathapaa.kmpads.provider.BannerSizing
import io.github.shivathapaa.kmpads.runtime.AdShowOutcome
import io.github.shivathapaa.kmpads.runtime.AdsBootstrap
import io.github.shivathapaa.kmpads.runtime.AdsConfig
import io.github.shivathapaa.kmpads.runtime.AdsSystem
import io.github.shivathapaa.kmpads.runtime.DayKeyProvider
import io.github.shivathapaa.kmpads.storage.InMemoryAdStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** Runtime tests with persistence, signals and a fake provider wired together. */
@OptIn(ExperimentalCoroutinesApi::class)
class AdsSystemRuntimeTest {
    private val interstitial = AdPlacementId("interstitial")
    private val banner = AdPlacementId("banner")

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
        dayKeyProvider: DayKeyProvider = DayKeyProvider.Utc,
        sink: RecordingEventSink = RecordingEventSink(),
        policy: AdPolicy = AdPolicy(warmUp = WarmUp.None),
        isPremium: Boolean = false,
        hostSinks: List<AdEventSink> = emptyList(),
        logger: AdLogger = AdLogger.NoOp,
        remoteConfig: AdRemoteConfigSource = AdRemoteConfigSource.Permissive,
        // The TestScope, not backgroundScope: advanceUntilIdle() does not run backgroundScope work.
        scope: CoroutineScope = this,
    ): Fixture {
        val bootstrap = AdsSystem.create(
            AdsConfig(
                placements = listOf(
                    AdPlacement(interstitial, AdFormat.Interstitial, policy),
                    AdPlacement(banner, AdFormat.Banner, AdPolicy.InlineDefault),
                ),
                units = AdUnitRegistry.testOnly(),
                provider = provider,
                platform = AdPlatform.Android,
                audience = AdAudienceConfig.GeneralAudience,
                scope = scope,
                storage = InMemoryAdStorage(),
                clock = clock,
                dayKeyProvider = dayKeyProvider,
                signals = mapOf(
                    AdSignalKey.UserIsPremium to flowOf(isPremium),
                    AdSignalKey.ConsentObtained to flowOf(true),
                    AdSignalKey.NetworkAvailable to flowOf(true),
                ),
                remoteConfig = remoteConfig,
                sinks = listOf(sink) + hostSinks,
                logger = logger,
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
    fun flying_west_across_midnight_does_not_reset_the_daily_cap() = runTest {
        // A local-time day key goes from 101 back to 100 when the user crosses midnight westwards.
        var localDay = 101
        val fixture = build(
            policy = AdPolicy(frequency = FrequencyCap(perDay = 2)),
            dayKeyProvider = DayKeyProvider { localDay },
        )
        advanceUntilIdle()
        val capReached = AdDecision.Suppress(SuppressionReason.DailyCapReached(2))

        showAndDismiss(fixture)
        localDay = 100
        showAndDismiss(fixture)

        assertEquals(2, fixture.sink.ofType<AdEvent.Impression>().size)
        assertEquals(capReached, fixture.ads.decision(interstitial).first())

        // Midnight in the new time zone is still the day those shows were counted on.
        localDay = 101
        assertEquals(capReached, fixture.ads.decision(interstitial).first())

        localDay = 102
        assertEquals(AdDecision.Allow, fixture.ads.decision(interstitial).first())
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

    @Test
    fun two_overlapping_shows_cannot_skip_the_minimum_interval() = runTest {
        val fixture = build(
            policy = AdPolicy(
                warmUp = WarmUp.None,
                frequency = FrequencyCap(minInterval = 3.minutes),
            ),
        )
        advanceUntilIdle()

        // Two triggers at once, such as a double tap. Nothing is preloaded, so both have to load.
        var first: AdShowOutcome? = null
        var second: AdShowOutcome? = null
        launch { first = fixture.ads.show(interstitial) }
        launch { second = fixture.ads.show(interstitial) }
        advanceUntilIdle()

        fixture.provider.completeShow(interstitial)
        advanceUntilIdle()

        assertEquals(AdShowOutcome.Completed(reward = null), first)
        assertEquals(listOf(interstitial), fixture.provider.shownPlacements)
        val queued = assertIs<AdShowOutcome.Suppressed>(second)
        assertIs<SuppressionReason.MinIntervalNotElapsed>(queued.reason)
    }

    @Test
    fun the_sdk_starts_before_the_first_banner_is_requested() = runTest {
        val fixture = build()
        advanceUntilIdle()

        val handle = fixture.ads.createBanner(
            context = AdViewFactoryContext.stub(),
            placement = banner,
            widthDp = 320,
            sizing = BannerSizing.AnchoredAdaptive,
        )

        // Nothing is dispatched in between: the SDK's request configuration, such as a
        // child-directed audience, has to be in place before this banner's first request.
        assertNotNull(handle)
        assertEquals(1, fixture.provider.initializeCount)
    }

    @Test
    fun a_throwing_host_sink_and_logger_never_escape_the_runtime() = runTest {
        val fixture = build(
            hostSinks = listOf(AdEventSink { error("sink failed") }),
            logger = AdLogger { _, _, _, _ -> error("logger failed") },
        )
        advanceUntilIdle()

        var outcome: AdShowOutcome? = null
        launch { outcome = fixture.ads.show(interstitial) }
        advanceUntilIdle()
        // The dismissal is an SDK callback; on iOS an exception escaping it terminates the app.
        fixture.provider.completeShow(interstitial)
        advanceUntilIdle()

        assertEquals(AdShowOutcome.Completed(reward = null), outcome)
    }

    @Test
    fun placements_stay_suppressed_until_the_remote_config_arrives() = runTest {
        val remote = MutableSharedFlow<AdRemoteConfig>(replay = 1)
        // The source never completes, so the system runs in a scope this test cancels.
        val adsScope = CoroutineScope(StandardTestDispatcher(testScheduler))
        try {
            val fixture = build(remoteConfig = AdRemoteConfigSource { remote }, scope = adsScope)
            advanceUntilIdle()

            assertEquals(
                AdDecision.Suppress(SuppressionReason.StateNotLoaded),
                fixture.ads.decision(interstitial).first(),
            )

            remote.tryEmit(AdRemoteConfig.Permissive)
            advanceUntilIdle()
            assertEquals(AdDecision.Allow, fixture.ads.decision(interstitial).first())
        } finally {
            adsScope.cancel()
        }
    }

    @Test
    fun a_failing_remote_config_source_disables_ads_instead_of_crashing() = runTest {
        val fixture = build(
            remoteConfig = AdRemoteConfigSource {
                flow<AdRemoteConfig> { throw IllegalStateException("fetch failed") }
            },
        )
        advanceUntilIdle()

        assertEquals(
            AdDecision.Suppress(SuppressionReason.GlobalKillSwitch),
            fixture.ads.decision(interstitial).first(),
        )
    }

    @Test
    fun a_provider_configured_for_another_audience_is_refused() = runTest {
        val childDirected = AdAudienceConfig(
            ageRestriction = AdAudienceConfig.AgeRestriction.ChildDirected,
            maxAdContentRating = AdAudienceConfig.MaxAdContentRating.G,
        )
        // Sends its SDK the general audience, as a provider factory left at its default would.
        val generalAudienceProvider = object : AdProvider by FakeAdProvider() {
            override val audience: AdAudienceConfig = AdAudienceConfig.GeneralAudience
        }

        val bootstrap = AdsSystem.create(
            AdsConfig(
                placements = listOf(AdPlacement(interstitial, AdFormat.Interstitial)),
                units = AdUnitRegistry.testOnly(),
                provider = generalAudienceProvider,
                platform = AdPlatform.Android,
                audience = childDirected,
                scope = this,
            )
        )

        assertEquals(
            listOf(
                AdConfigProblem.AudienceMismatch(
                    declared = childDirected,
                    provider = AdAudienceConfig.GeneralAudience,
                )
            ),
            assertIs<AdsBootstrap.Misconfigured>(bootstrap).problems,
        )
    }

    // Sessions. Only time spent in the background ends a session: the default threshold is 30 minutes.

    @Test
    fun a_foreground_that_does_not_follow_a_background_is_not_a_new_session() = runTest {
        val clock = MutableAdClock()
        val fixture = build(clock = clock, policy = AdPolicy(warmUp = WarmUp(minSessionOrdinal = 2)))
        advanceUntilIdle()

        fixture.ads.onAppForegrounded()
        // Forty minutes in the app, then a second foreground with no background in between: iOS
        // returning from .inactive after a system alert, or an Activity resumed after a dialog.
        clock.advance(40.minutes)
        fixture.ads.onAppForegrounded()
        advanceUntilIdle()

        assertEquals(
            AdDecision.Suppress(SuppressionReason.WarmUpSessions(remaining = 1)),
            fixture.ads.decision(interstitial).first(),
        )
    }

    @Test
    fun a_foreground_that_does_not_follow_a_background_keeps_the_session_cap() = runTest {
        val clock = MutableAdClock()
        val fixture = build(clock = clock, policy = OncePerSession)
        advanceUntilIdle()
        fixture.ads.onAppForegrounded()
        showOnce(fixture)

        clock.advance(40.minutes)
        fixture.ads.onAppForegrounded()
        advanceUntilIdle()

        assertEquals(SessionCapReached, fixture.ads.decision(interstitial).first())
    }

    @Test
    fun a_background_longer_than_the_threshold_starts_a_new_session() = runTest {
        val clock = MutableAdClock()
        val fixture = build(clock = clock, policy = OncePerSession)
        advanceUntilIdle()
        fixture.ads.onAppForegrounded()
        showOnce(fixture)

        clock.advance(10.minutes)
        fixture.ads.onAppBackgrounded()
        clock.advance(31.minutes)
        fixture.ads.onAppForegrounded()
        advanceUntilIdle()

        assertEquals(AdDecision.Allow, fixture.ads.decision(interstitial).first())
    }

    @Test
    fun a_background_shorter_than_the_threshold_continues_the_session() = runTest {
        val clock = MutableAdClock()
        val fixture = build(clock = clock, policy = OncePerSession)
        advanceUntilIdle()
        fixture.ads.onAppForegrounded()
        showOnce(fixture)

        clock.advance(10.minutes)
        fixture.ads.onAppBackgrounded()
        clock.advance(10.minutes)
        fixture.ads.onAppForegrounded()
        advanceUntilIdle()

        assertEquals(SessionCapReached, fixture.ads.decision(interstitial).first())
    }

    @Test
    fun a_repeated_background_keeps_the_time_the_user_left() = runTest {
        val clock = MutableAdClock()
        val fixture = build(clock = clock, policy = OncePerSession)
        advanceUntilIdle()
        fixture.ads.onAppForegrounded()
        showOnce(fixture)

        // Left at 10 minutes. A second background, such as a host forwarding both iOS .inactive
        // and .background, must not restart the clock.
        clock.advance(10.minutes)
        fixture.ads.onAppBackgrounded()
        clock.advance(20.minutes)
        fixture.ads.onAppBackgrounded()
        clock.advance(11.minutes)
        fixture.ads.onAppForegrounded()
        advanceUntilIdle()

        assertEquals(AdDecision.Allow, fixture.ads.decision(interstitial).first())
    }

    @Test
    fun a_process_started_in_the_background_starts_a_session_when_the_user_opens_it() = runTest {
        val clock = MutableAdClock()
        // Created for a push or a widget, with no foreground. The user opens the app two hours later.
        val fixture = build(clock = clock, policy = AdPolicy(warmUp = WarmUp(minSessionOrdinal = 2)))
        advanceUntilIdle()

        clock.advance(2.hours)
        fixture.ads.onAppForegrounded()
        advanceUntilIdle()

        assertEquals(AdDecision.Allow, fixture.ads.decision(interstitial).first())
    }

    @Test
    fun time_away_counts_from_the_first_background_when_launch_reports_no_foreground() = runTest {
        // iOS posts no will-enter-foreground notification at launch, so a host forwarding it reports
        // a background first. Two hours in the app, then five minutes away: the same session.
        val clock = MutableAdClock()
        val fixture = build(clock = clock, policy = OncePerSession)
        advanceUntilIdle()
        showOnce(fixture)

        clock.advance(2.hours)
        fixture.ads.onAppBackgrounded()
        clock.advance(5.minutes)
        fixture.ads.onAppForegrounded()
        advanceUntilIdle()

        assertEquals(SessionCapReached, fixture.ads.decision(interstitial).first())
    }

    /** Shows the interstitial once and dismisses it, which uses up [OncePerSession]. */
    private fun TestScope.showOnce(fixture: Fixture) {
        showAndDismiss(fixture)
        assertEquals(listOf(interstitial), fixture.provider.shownPlacements, "shown once")
    }

    private fun TestScope.showAndDismiss(fixture: Fixture) {
        launch { fixture.ads.show(interstitial) }
        advanceUntilIdle()
        fixture.provider.completeShow(interstitial)
        advanceUntilIdle()
    }

    private class Fixture(
        val ads: AdsSystem,
        val provider: FakeAdProvider,
        val clock: MutableAdClock,
        val sink: RecordingEventSink,
    )

    private companion object {
        val OncePerSession = AdPolicy(warmUp = WarmUp.None, frequency = FrequencyCap(perSession = 1))
        val SessionCapReached = AdDecision.Suppress(SuppressionReason.SessionCapReached(cap = 1))
    }
}
