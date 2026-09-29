package io.github.shivathapaa.kmpads.runtime

import io.github.shivathapaa.kmpads.config.AdPlacementId
import io.github.shivathapaa.kmpads.error.AdError
import io.github.shivathapaa.kmpads.event.AdEvent
import io.github.shivathapaa.kmpads.policy.AdDecision
import io.github.shivathapaa.kmpads.policy.SuppressionReason
import io.github.shivathapaa.kmpads.provider.AdViewFactoryContext
import io.github.shivathapaa.kmpads.provider.BannerAdHandle
import io.github.shivathapaa.kmpads.provider.BannerSizing
import io.github.shivathapaa.kmpads.provider.NativeAdHandle
import io.github.shivathapaa.kmpads.provider.NativeAdStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flowOf

/**
 * An [AdsSystem] that never shows ads and cannot fail. Used when no provider is supplied, in
 * previews and tests, and when a misconfiguration is degraded through [AdsBootstrap.orNoOp].
 */
public object NoOpAdsSystem : AdsSystem {
    private val eventFlow = MutableSharedFlow<AdEvent>()

    override val events: SharedFlow<AdEvent> = eventFlow.asSharedFlow()

    private val suppressed: AdDecision =
        AdDecision.Suppress(SuppressionReason.ProviderLacksFormat)

    override fun decision(placement: AdPlacementId): Flow<AdDecision> = flowOf(suppressed)

    override suspend fun preload(placement: AdPlacementId): AdPreloadOutcome =
        AdPreloadOutcome.NotSupported

    override suspend fun show(placement: AdPlacementId): AdShowOutcome =
        AdShowOutcome.Failed(AdError.NoProvider())

    override fun reservedBannerHeightDp(
        context: AdViewFactoryContext,
        widthDp: Int,
        sizing: BannerSizing,
    ): Int = 0

    override fun createBanner(
        context: AdViewFactoryContext,
        placement: AdPlacementId,
        widthDp: Int,
        sizing: BannerSizing,
    ): BannerAdHandle? = null

    override suspend fun loadNative(
        context: AdViewFactoryContext,
        placement: AdPlacementId,
        style: NativeAdStyle,
    ): NativeAdHandle? = null

    override fun onAppForegrounded(): Unit = Unit

    override fun onAppBackgrounded(): Unit = Unit

    override fun notifyFirstRunComplete(): Unit = Unit

    override suspend fun shutdown(): Unit = Unit
}
