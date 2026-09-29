package io.github.shivathapaa.kmpads.admob

import android.app.Application
import com.google.android.gms.ads.MobileAds
import io.github.shivathapaa.kmpads.config.AdAudienceConfig
import io.github.shivathapaa.kmpads.error.AdLogLevel
import io.github.shivathapaa.kmpads.error.AdLogger
import io.github.shivathapaa.kmpads.provider.AdInitOutcome
import io.github.shivathapaa.kmpads.provider.AdNetworkId
import io.github.shivathapaa.kmpads.provider.AdProvider
import io.github.shivathapaa.kmpads.provider.BannerAdLoader
import io.github.shivathapaa.kmpads.provider.FullScreenAdLoader
import io.github.shivathapaa.kmpads.provider.NativeAdLoader
import io.github.shivathapaa.kmpads.runtime.AdClock
import kotlinx.coroutines.CompletableDeferred

private const val TAG = "kmp-ads/admob"

/** Created by [AdMobAndroid.install]. */
internal class AdMobAdProvider(
    private val application: Application,
    private val audience: AdAudienceConfig,
    activities: ForegroundActivityTracker,
    clock: AdClock,
    private val testDeviceIds: List<String>,
    private val muteAds: Boolean,
    private val logger: AdLogger,
) : AdProvider {
    override val id: AdNetworkId = AdNetworkId("admob-android")

    override val fullScreen: FullScreenAdLoader =
        AdMobFullScreenLoader(application.applicationContext, activities, clock, logger)

    override val banner: BannerAdLoader = AdMobBannerLoader(logger)

    override val native: NativeAdLoader = AdMobNativeLoader(logger)

    override suspend fun initialize(): AdInitOutcome {
        // Applied before initialisation, so the first request carries the audience and test devices.
        MobileAds.setRequestConfiguration(audience.toRequestConfiguration(testDeviceIds))

        val result = CompletableDeferred<AdInitOutcome>()
        MobileAds.initialize(application.applicationContext) { status ->
            // Must follow initialisation: the SDK throws if app muting is set first.
            if (muteAds) MobileAds.setAppMuted(true)

            val summary = status.adapterStatusMap.entries.joinToString { (name, adapter) ->
                "$name=${adapter.initializationState}"
            }
            logger.log(AdLogLevel.Info, TAG, "initialized: $summary", null)
            result.complete(AdInitOutcome.Ready)
        }
        return result.await()
    }

    override fun onAppForegrounded(): Unit = Unit

    override fun onAppBackgrounded(): Unit = Unit

    override fun dispose(): Unit = Unit
}
