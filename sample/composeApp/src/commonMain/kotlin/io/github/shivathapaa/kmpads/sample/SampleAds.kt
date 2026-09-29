package io.github.shivathapaa.kmpads.sample

import io.github.shivathapaa.kmpads.config.AdAudienceConfig
import io.github.shivathapaa.kmpads.config.AdFormat
import io.github.shivathapaa.kmpads.config.AdPlacementId
import io.github.shivathapaa.kmpads.config.AdPlatform
import io.github.shivathapaa.kmpads.config.AdUnitMode
import io.github.shivathapaa.kmpads.config.AdUnitRegistry
import io.github.shivathapaa.kmpads.policy.AdPlacement
import io.github.shivathapaa.kmpads.policy.AdPolicy
import io.github.shivathapaa.kmpads.policy.AdSignalKey
import io.github.shivathapaa.kmpads.provider.AdProvider
import io.github.shivathapaa.kmpads.runtime.AdsConfig
import io.github.shivathapaa.kmpads.runtime.AdsSystem
import io.github.shivathapaa.kmpads.storage.AdStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** The sample's placements. Every placement uses Google's demo units. */
internal object SamplePlacements {
    val Banner: AdPlacement = AdPlacement(
        id = AdPlacementId("sample_banner"),
        format = AdFormat.Banner,
        policy = AdPolicy.InlineDefault,
    )

    val Interstitial: AdPlacement = AdPlacement(
        id = AdPlacementId("sample_interstitial"),
        format = AdFormat.Interstitial,
        // Warm-up is off so ads show immediately in the sample; keep the default in an app.
        policy = AdPolicy.FullScreenDefault.copy(
            warmUp = io.github.shivathapaa.kmpads.policy.WarmUp.None,
        ),
    )

    val Rewarded: AdPlacement = AdPlacement(
        id = AdPlacementId("sample_rewarded"),
        format = AdFormat.Rewarded,
        policy = AdPolicy.FullScreenDefault.copy(
            warmUp = io.github.shivathapaa.kmpads.policy.WarmUp.None,
        ),
    )

    val RewardedInterstitial: AdPlacement = AdPlacement(
        id = AdPlacementId("sample_rewarded_interstitial"),
        format = AdFormat.RewardedInterstitial,
        policy = AdPolicy.FullScreenDefault.copy(
            warmUp = io.github.shivathapaa.kmpads.policy.WarmUp.None,
        ),
    )

    // App-open ads usually show on returning to the app; the sample shows one from a button.
    val AppOpen: AdPlacement = AdPlacement(
        id = AdPlacementId("sample_app_open"),
        format = AdFormat.AppOpen,
        policy = AdPolicy.FullScreenDefault.copy(
            warmUp = io.github.shivathapaa.kmpads.policy.WarmUp.None,
        ),
    )

    // Native is an inline format, so it uses the inline policy.
    val Native: AdPlacement = AdPlacement(
        id = AdPlacementId("sample_native"),
        format = AdFormat.Native,
        policy = AdPolicy.InlineDefault,
    )

    val All: List<AdPlacement> = listOf(
        Banner,
        Interstitial,
        Rewarded,
        RewardedInterstitial,
        AppOpen,
        Native,
    )
}

/**
 * Builds the sample's ads system. Map a premium entitlement into [AdSignalKey.UserIsPremium] to
 * hide ads for paying users.
 */
internal fun createSampleAdsSystem(
    provider: AdProvider,
    platform: AdPlatform,
    scope: CoroutineScope,
    storage: AdStorage,
    isPremium: Flow<Boolean> = flowOf(false),
    consentObtained: Flow<Boolean> = flowOf(true),
    networkAvailable: Flow<Boolean> = flowOf(true),
): AdsSystem = AdsSystem.create(
    AdsConfig(
        placements = SamplePlacements.All,
        units = AdUnitRegistry.testOnly(),
        provider = provider,
        platform = platform,
        audience = AdAudienceConfig.GeneralAudience,
        scope = scope,
        storage = storage,
        signals = mapOf(
            AdSignalKey.UserIsPremium to isPremium,
            AdSignalKey.ConsentObtained to consentObtained,
            AdSignalKey.NetworkAvailable to networkAvailable,
        ),
        hostDeclaresDebugBuild = true,
    )
).orNoOp { problems -> error("sample ads misconfigured: $problems") }
