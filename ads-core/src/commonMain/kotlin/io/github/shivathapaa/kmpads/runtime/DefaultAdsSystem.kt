package io.github.shivathapaa.kmpads.runtime

import io.github.shivathapaa.kmpads.config.AdFormat
import io.github.shivathapaa.kmpads.config.AdPlacementId
import io.github.shivathapaa.kmpads.config.AdUnitId
import io.github.shivathapaa.kmpads.config.AdUnitMode
import io.github.shivathapaa.kmpads.error.AdError
import io.github.shivathapaa.kmpads.error.AdLogLevel
import io.github.shivathapaa.kmpads.error.AdLogger
import io.github.shivathapaa.kmpads.error.nonThrowing
import io.github.shivathapaa.kmpads.event.AdEvent
import io.github.shivathapaa.kmpads.event.AdReward
import io.github.shivathapaa.kmpads.event.AdRevenue
import io.github.shivathapaa.kmpads.policy.AdDecision
import io.github.shivathapaa.kmpads.policy.AdPlacement
import io.github.shivathapaa.kmpads.policy.AdPolicyEngine
import io.github.shivathapaa.kmpads.policy.AdRemoteConfig
import io.github.shivathapaa.kmpads.policy.AdSignalKey
import io.github.shivathapaa.kmpads.policy.PlacementCounters
import io.github.shivathapaa.kmpads.policy.PolicyInput
import io.github.shivathapaa.kmpads.policy.SessionSnapshot
import io.github.shivathapaa.kmpads.policy.SuppressionReason
import io.github.shivathapaa.kmpads.provider.AdInitOutcome
import io.github.shivathapaa.kmpads.provider.AdLoadOutcome
import io.github.shivathapaa.kmpads.provider.AdLoadRequest
import io.github.shivathapaa.kmpads.provider.AdViewFactoryContext
import io.github.shivathapaa.kmpads.provider.BannerAdHandle
import io.github.shivathapaa.kmpads.provider.BannerAdListener
import io.github.shivathapaa.kmpads.provider.BannerAdRequest
import io.github.shivathapaa.kmpads.provider.BannerSizing
import io.github.shivathapaa.kmpads.provider.FullScreenAdListener
import io.github.shivathapaa.kmpads.provider.NativeAdHandle
import io.github.shivathapaa.kmpads.provider.NativeAdListener
import io.github.shivathapaa.kmpads.provider.NativeAdLoadOutcome
import io.github.shivathapaa.kmpads.provider.NativeAdRequest
import io.github.shivathapaa.kmpads.provider.NativeAdStyle
import io.github.shivathapaa.kmpads.storage.AdStateCodec
import io.github.shivathapaa.kmpads.storage.PersistedState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val TAG = "kmp-ads"
private const val EVENT_BUFFER = 64
private val WRITE_DEBOUNCE = 2.seconds

/** The runtime state, held as one immutable snapshot. */
private data class RuntimeState(
    val loaded: Boolean,
    val firstLaunchAtMillis: Long,
    val sessionOrdinal: Int,
    val sessionStartedAtMillis: Long,
    /** When the user last arrived or left. Persisted: the next launch measures time away from it. */
    val lastForegroundAtMillis: Long,
    /**
     * When the app went to the background, or null while it is in the foreground. Set by the first
     * background after a foreground and kept by a repeated one. Not persisted.
     */
    val backgroundedAtMillis: Long?,
    /** Whether a foreground has been reported in this process. */
    val foregroundSeen: Boolean,
    val firstRunComplete: Boolean,
    val counters: Map<AdPlacementId, PlacementCounters>,
)

internal class DefaultAdsSystem(private val config: AdsConfig) : AdsSystem {
    private val placements: Map<AdPlacementId, AdPlacement> =
        config.placements.associateBy { it.id }

    /** Declared before `init`, which can log before construction returns. */
    private val logger: AdLogger = config.logger.nonThrowing()

    private val eventFlow = MutableSharedFlow<AdEvent>(
        replay = 0,
        extraBufferCapacity = EVENT_BUFFER,
        // Emitting from an ad callback must never suspend.
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val events: SharedFlow<AdEvent> = eventFlow.asSharedFlow()

    /** The first foreground of a process measures time away from here. */
    private val createdAtMillis: Long = config.clock.nowMillis()

    private val state = MutableStateFlow(
        RuntimeState(
            // Every placement is suppressed until the counters are restored.
            loaded = false,
            firstLaunchAtMillis = createdAtMillis,
            sessionOrdinal = 1,
            sessionStartedAtMillis = createdAtMillis,
            lastForegroundAtMillis = createdAtMillis,
            backgroundedAtMillis = null,
            foregroundSeen = false,
            firstRunComplete = false,
            counters = emptyMap(),
        )
    )

    private val signals = MutableStateFlow<Map<AdSignalKey, Boolean>>(emptyMap())

    /** Null until the remote source first emits; every placement is suppressed until then. */
    private val remote = MutableStateFlow<AdRemoteConfig?>(null)

    private val initMutex = Mutex()
    private var initialized = false

    /** One full-screen presentation at a time, process-wide. */
    private val showMutex = Mutex()

    private var writeJob: Job? = null

    init {
        config.scope.launch { restoreState() }
        observeSignals()
        config.scope.launch {
            // A source that fails resolves to AllDisabled instead of throwing into the host's scope.
            flow { emitAll(config.remoteConfig.observe()) }
                .catch { failure ->
                    logger.log(AdLogLevel.Warn, TAG, "remote config failed; ads disabled", failure)
                    emit(AdRemoteConfig.AllDisabled)
                }
                .collect { remote.value = it }
        }
    }

    override fun decision(placement: AdPlacementId): Flow<AdDecision> =
        combine(state, signals, remote) { _, _, _ -> decisionFor(placement) }
            .distinctUntilChanged()

    private fun decisionFor(placementId: AdPlacementId): AdDecision {
        val placement = placements[placementId]
            ?: return AdDecision.Suppress(SuppressionReason.UnitIdMissing)
        return AdPolicyEngine.evaluate(
            input = policyInput(placement),
            rules = AdPolicyEngine.DefaultRules + config.extraRules,
        )
    }

    private fun policyInput(placement: AdPlacement): PolicyInput {
        val now = config.clock.nowMillis()
        val snapshot = state.value
        val received = remote.value
        // Before the first remote value, StateLoadedRule suppresses; AllDisabled covers host rules.
        val remoteConfig = received ?: AdRemoteConfig.AllDisabled
        val patched = remoteConfig.overrides[placement.id]?.applyTo(placement.policy)
            ?: placement.policy

        return PolicyInput(
            placement = placement,
            policy = patched,
            counters = snapshot.counters[placement.id] ?: PlacementCounters(),
            session = SessionSnapshot(
                ordinal = snapshot.sessionOrdinal,
                startedAtMillis = snapshot.sessionStartedAtMillis,
                firstLaunchAtMillis = snapshot.firstLaunchAtMillis,
                firstRunComplete = snapshot.firstRunComplete,
            ),
            signals = signals.value,
            globalEnabled = remoteConfig.globalEnabled,
            remotelyDisabled = placement.id in remoteConfig.disabledPlacements,
            unitIdPresent = resolveUnit(placement) != null,
            providerSupportsFormat = supportsFormat(placement.format),
            stateLoaded = snapshot.loaded && received != null,
            nowMillis = now,
            dayKey = config.dayKeyProvider.dayKey(now),
        )
    }

    private fun supportsFormat(format: AdFormat): Boolean = when (format) {
        AdFormat.Banner -> config.provider.banner != null
        AdFormat.Native -> config.provider.native != null
        else -> config.provider.fullScreen != null
    }

    private fun resolveUnit(placement: AdPlacement): AdUnitId? =
        config.units.resolve(placement.id, placement.format, config.platform)

    override suspend fun preload(placement: AdPlacementId): AdPreloadOutcome {
        val declared = placements[placement] ?: return AdPreloadOutcome.NotSupported
        val loader = config.provider.fullScreen ?: return AdPreloadOutcome.NotSupported
        val unit = resolveUnit(declared) ?: return AdPreloadOutcome.NotSupported

        when (val decision = decisionFor(placement)) {
            is AdDecision.Suppress -> {
                emit(AdEvent.Suppressed(now(), placement, declared.format, decision.reason))
                return AdPreloadOutcome.Suppressed(decision.reason)
            }

            AdDecision.Allow -> Unit
        }

        (ensureInitialized() as? AdInitOutcome.Failed)?.let {
            return AdPreloadOutcome.Failed(it.error)
        }
        if (loader.isReady(placement)) return AdPreloadOutcome.Ready

        emit(AdEvent.RequestStarted(now(), placement, declared.format))
        warnIfLiveUnitsInDebug(placement, declared.format)

        // The ad SDKs require full-screen loads on the main thread.
        return when (
            val outcome = withContext(Dispatchers.Main.immediate) {
                loader.load(AdLoadRequest(placement, declared.format, unit))
            }
        ) {
            is AdLoadOutcome.Loaded -> {
                emit(AdEvent.Loaded(now(), placement, declared.format, outcome.latency))
                AdPreloadOutcome.Ready
            }

            is AdLoadOutcome.Failed -> {
                emit(AdEvent.LoadFailed(now(), placement, declared.format, outcome.error))
                AdPreloadOutcome.Failed(outcome.error)
            }
        }
    }

    override suspend fun show(placement: AdPlacementId): AdShowOutcome {
        val declared = placements[placement]
            ?: return AdShowOutcome.Failed(AdError.InvalidRequest())
        val loader = config.provider.fullScreen
            ?: return AdShowOutcome.Failed(AdError.NoProvider())

        // The policy is checked here so a caller cannot race its own frequency cap.
        when (val decision = decisionFor(placement)) {
            is AdDecision.Suppress -> {
                emit(AdEvent.Suppressed(now(), placement, declared.format, decision.reason))
                return AdShowOutcome.Suppressed(decision.reason)
            }

            AdDecision.Allow -> Unit
        }

        if (!loader.isReady(placement)) {
            when (val preloaded = preload(placement)) {
                AdPreloadOutcome.Ready -> Unit
                is AdPreloadOutcome.Failed -> return AdShowOutcome.Failed(preloaded.error)
                is AdPreloadOutcome.Suppressed -> return AdShowOutcome.Suppressed(preloaded.reason)
                AdPreloadOutcome.NotSupported -> return AdShowOutcome.NotReady
            }
        }
        if (!loader.isReady(placement)) return AdShowOutcome.NotReady

        // Presents on the main thread. The mutex is taken first, so a queued show waits off-main.
        return showMutex.withLock {
            // Checked again under the lock: a show queued behind another, such as a double tap,
            // must see the counters that one recorded.
            when (val decision = decisionFor(placement)) {
                is AdDecision.Suppress -> {
                    emit(AdEvent.Suppressed(now(), placement, declared.format, decision.reason))
                    return@withLock AdShowOutcome.Suppressed(decision.reason)
                }

                AdDecision.Allow -> Unit
            }
            if (!loader.isReady(placement)) return@withLock AdShowOutcome.NotReady
            withContext(Dispatchers.Main.immediate) { present(declared, loader) }
        }
    }

    private suspend fun present(
        placement: AdPlacement,
        loader: io.github.shivathapaa.kmpads.provider.FullScreenAdLoader,
    ): AdShowOutcome = suspendCancellableCoroutine { continuation ->
        var earnedReward: AdReward? = null
        var settled = false

        fun settle(outcome: AdShowOutcome) {
            // A provider may report both a failure and a dismissal; resume only once.
            if (settled) return
            settled = true
            continuation.resume(outcome)
        }

        loader.show(
            placement.id,
            object : FullScreenAdListener {
                override fun onShown() {
                    recordShown(placement.id)
                    emit(AdEvent.Opened(now(), placement.id, placement.format))
                }

                override fun onImpression() {
                    emit(AdEvent.Impression(now(), placement.id, placement.format))
                }

                override fun onClicked() {
                    emit(AdEvent.Clicked(now(), placement.id, placement.format))
                }

                override fun onRewardEarned(reward: AdReward) {
                    // A reward arrives before the dismissal, so it is kept until the show completes.
                    earnedReward = reward
                    emit(AdEvent.RewardEarned(now(), placement.id, placement.format, reward))
                }

                override fun onRevenue(revenue: AdRevenue) {
                    emit(AdEvent.Revenue(now(), placement.id, placement.format, revenue))
                }

                override fun onDismissed() {
                    recordDismissed(placement.id)
                    emit(AdEvent.Dismissed(now(), placement.id, placement.format))
                    settle(AdShowOutcome.Completed(earnedReward))
                }

                override fun onShowFailed(error: AdError) {
                    emit(AdEvent.ShowFailed(now(), placement.id, placement.format, error))
                    settle(AdShowOutcome.Failed(error))
                }
            },
        )
    }

    override fun reservedBannerHeightDp(
        context: AdViewFactoryContext,
        widthDp: Int,
        sizing: BannerSizing,
    ): Int = config.provider.banner?.reservedHeightDp(context, widthDp, sizing) ?: 0

    override fun createBanner(
        context: AdViewFactoryContext,
        placement: AdPlacementId,
        widthDp: Int,
        sizing: BannerSizing,
    ): BannerAdHandle? {
        val declared = placements[placement] ?: return null
        val loader = config.provider.banner ?: return null
        val unit = resolveUnit(declared) ?: return null

        when (val decision = decisionFor(placement)) {
            is AdDecision.Suppress -> {
                emit(AdEvent.Suppressed(now(), placement, declared.format, decision.reason))
                return null
            }

            AdDecision.Allow -> Unit
        }

        // Banners have no suspending entry point, so initialisation is started here and runs until
        // it first suspends, whatever the scope's dispatcher: the SDK's request configuration, such
        // as a child-directed audience, is applied before this banner's first request.
        config.scope.launch(start = CoroutineStart.UNDISPATCHED) { ensureInitialized() }
        warnIfLiveUnitsInDebug(placement, declared.format)
        emit(AdEvent.RequestStarted(now(), placement, declared.format))

        return loader.createBanner(
            context = context,
            request = BannerAdRequest(placement, unit, widthDp, sizing),
            listener = object : BannerAdListener {
                override fun onLoaded() {
                    emit(AdEvent.Loaded(now(), placement, declared.format, 0.milliseconds))
                }

                override fun onLoadFailed(error: AdError) {
                    emit(AdEvent.LoadFailed(now(), placement, declared.format, error))
                }

                override fun onImpression() {
                    recordShown(placement)
                    emit(AdEvent.Impression(now(), placement, declared.format))
                }

                override fun onClicked() {
                    emit(AdEvent.Clicked(now(), placement, declared.format))
                }

                override fun onRevenue(revenue: AdRevenue) {
                    emit(AdEvent.Revenue(now(), placement, declared.format, revenue))
                }
            },
        )
    }

    override suspend fun loadNative(
        context: AdViewFactoryContext,
        placement: AdPlacementId,
        style: NativeAdStyle,
    ): NativeAdHandle? {
        val declared = placements[placement] ?: return null
        val loader = config.provider.native ?: return null
        val unit = resolveUnit(declared) ?: return null

        when (val decision = decisionFor(placement)) {
            is AdDecision.Suppress -> {
                emit(AdEvent.Suppressed(now(), placement, declared.format, decision.reason))
                return null
            }

            AdDecision.Allow -> Unit
        }

        (ensureInitialized() as? AdInitOutcome.Failed)?.let { return null }
        warnIfLiveUnitsInDebug(placement, declared.format)
        emit(AdEvent.RequestStarted(now(), placement, declared.format))

        val outcome = loader.load(
            context = context,
            request = NativeAdRequest(placement, unit, style),
            listener = object : NativeAdListener {
                override fun onImpression() {
                    recordShown(placement)
                    emit(AdEvent.Impression(now(), placement, declared.format))
                }

                override fun onClicked() {
                    emit(AdEvent.Clicked(now(), placement, declared.format))
                }

                override fun onRevenue(revenue: AdRevenue) {
                    emit(AdEvent.Revenue(now(), placement, declared.format, revenue))
                }
            },
        )

        return when (outcome) {
            is NativeAdLoadOutcome.Loaded -> {
                emit(AdEvent.Loaded(now(), placement, declared.format, 0.milliseconds))
                outcome.handle
            }

            is NativeAdLoadOutcome.Failed -> {
                emit(AdEvent.LoadFailed(now(), placement, declared.format, outcome.error))
                null
            }
        }
    }

    override fun onAppForegrounded() {
        val now = config.clock.nowMillis()
        state.update { current ->
            // Only time in the background ends a session. A foreground with no background before
            // it, such as iOS returning from .inactive after a system alert, is the same visit. A
            // process's first foreground counts from creation, so a process the system started in
            // the background still begins a session when the user opens the app.
            val awaySince = current.backgroundedAtMillis
                ?: createdAtMillis.takeUnless { current.foregroundSeen }
            val isNewSession = awaySince != null &&
                (now - awaySince).milliseconds >= config.session.inactivityThreshold
            val returned = current.copy(
                lastForegroundAtMillis = now,
                backgroundedAtMillis = null,
                foregroundSeen = true,
            )
            if (!isNewSession) {
                returned
            } else {
                returned.copy(
                    sessionOrdinal = current.sessionOrdinal + 1,
                    sessionStartedAtMillis = now,
                    // Per-session counters reset with the session, by definition.
                    counters = current.counters.mapValues { it.value.copy(shownThisSession = 0) },
                )
            }
        }
        config.provider.onAppForegrounded()
        scheduleWrite()
    }

    override fun onAppBackgrounded() {
        val now = config.clock.nowMillis()
        state.update { current ->
            // The user left at the first background. A repeated one, such as a host that forwards
            // both iOS .inactive and .background, must not shorten the time away.
            val leftAt = current.backgroundedAtMillis ?: now
            current.copy(backgroundedAtMillis = leftAt, lastForegroundAtMillis = leftAt)
        }
        config.provider.onAppBackgrounded()
        config.scope.launch { flushWrite() }
    }

    override fun notifyFirstRunComplete() {
        state.update { it.copy(firstRunComplete = true) }
        scheduleWrite()
    }

    override suspend fun shutdown() {
        flushWrite()
        config.provider.dispose()
    }

    private fun now(): Long = config.clock.nowMillis()

    private fun emit(event: AdEvent) {
        eventFlow.tryEmit(event)
        config.sinks.forEach { sink ->
            // A host sink that throws must not take down an SDK callback with it.
            runCatching { sink.onEvent(event) }.onFailure { failure ->
                logger.log(AdLogLevel.Warn, TAG, "event sink threw", failure)
            }
        }
    }

    private fun observeSignals() {
        if (config.signals.isEmpty()) return
        val keys = config.signals.keys.toList()
        val flows = keys.map { key -> config.signals.getValue(key).map { key to it } }
        config.scope.launch {
            combine(flows) { pairs -> pairs.toMap() }.collect { signals.value = it }
        }
    }

    private suspend fun ensureInitialized(): AdInitOutcome {
        if (initialized) return AdInitOutcome.Ready
        return initMutex.withLock {
            if (initialized) {
                AdInitOutcome.Ready
            } else {
                // Initialised on the first allowed request; by default that is after consent.
                val outcome = config.provider.initialize()
                if (outcome is AdInitOutcome.Ready) initialized = true
                outcome
            }
        }
    }

    private fun warnIfLiveUnitsInDebug(placement: AdPlacementId, format: AdFormat) {
        if (config.units.mode is AdUnitMode.LiveUnitsInDebugBuild) {
            emit(AdEvent.Diagnostic.LiveUnitsInDebugBuild(now(), placement, format))
        }
    }

    private fun recordShown(placement: AdPlacementId) {
        val now = config.clock.nowMillis()
        val dayKey = config.dayKeyProvider.dayKey(now)
        state.update { current ->
            val previous = current.counters[placement] ?: PlacementCounters()
            val shownToday = if (previous.dayKey == dayKey) previous.shownToday + 1 else 1
            current.copy(
                counters = current.counters + (
                    placement to previous.copy(
                        lastShownAtMillis = now,
                        dayKey = dayKey,
                        shownToday = shownToday,
                        shownThisSession = previous.shownThisSession + 1,
                        shownEver = previous.shownEver + 1,
                    )
                    ),
            )
        }
        scheduleWrite()
    }

    private fun recordDismissed(placement: AdPlacementId) {
        val now = config.clock.nowMillis()
        state.update { current ->
            val previous = current.counters[placement] ?: PlacementCounters()
            current.copy(
                counters = current.counters +
                    (placement to previous.copy(lastDismissedAtMillis = now)),
            )
        }
        scheduleWrite()
    }

    private suspend fun restoreState() {
        val now = config.clock.nowMillis()
        val restored = runCatching { config.storage.read() }
            .onFailure { failure ->
                if (failure is CancellationException) throw failure
                logger.log(AdLogLevel.Warn, TAG, "state read failed", failure)
            }
            .getOrNull()
            .let { AdStateCodec.decode(it, now) }

        state.update { current ->
            if (restored == null) {
                current.copy(loaded = true)
            } else {
                val backgroundedFor = (now - restored.lastForegroundAtMillis).milliseconds
                val continuing = backgroundedFor < config.session.inactivityThreshold
                current.copy(
                    loaded = true,
                    firstLaunchAtMillis = restored.firstLaunchAtMillis,
                    sessionOrdinal = restored.sessionOrdinal + if (continuing) 0 else 1,
                    sessionStartedAtMillis = now,
                    lastForegroundAtMillis = now,
                    firstRunComplete = restored.firstRunComplete,
                    counters = restored.counters,
                )
            }
        }
        scheduleWrite()
    }

    /** Debounced, so an impression never waits on a storage write. */
    private fun scheduleWrite() {
        writeJob?.cancel()
        writeJob = config.scope.launch {
            delay(WRITE_DEBOUNCE)
            persist()
        }
    }

    private suspend fun flushWrite() {
        writeJob?.cancel()
        writeJob = null
        persist()
    }

    private suspend fun persist() {
        val snapshot = state.value
        if (!snapshot.loaded) return
        val encoded = AdStateCodec.encode(
            PersistedState(
                firstLaunchAtMillis = snapshot.firstLaunchAtMillis,
                sessionOrdinal = snapshot.sessionOrdinal,
                lastForegroundAtMillis = snapshot.lastForegroundAtMillis,
                firstRunComplete = snapshot.firstRunComplete,
                counters = snapshot.counters,
            )
        )
        runCatching { config.storage.write(encoded) }.onFailure { failure ->
            if (failure is CancellationException) throw failure
            logger.log(AdLogLevel.Warn, TAG, "state write failed", failure)
        }
    }
}
