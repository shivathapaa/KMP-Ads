package io.github.shivathapaa.kmpads.admob

import android.app.Activity
import android.content.Context
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.OnUserEarnedRewardListener
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAdLoadCallback
import io.github.shivathapaa.kmpads.config.AdFormat
import io.github.shivathapaa.kmpads.config.AdPlacementId
import io.github.shivathapaa.kmpads.error.AdError
import io.github.shivathapaa.kmpads.error.AdLogLevel
import io.github.shivathapaa.kmpads.error.AdLogger
import io.github.shivathapaa.kmpads.event.AdReward
import io.github.shivathapaa.kmpads.provider.AdLoadOutcome
import io.github.shivathapaa.kmpads.provider.AdLoadRequest
import io.github.shivathapaa.kmpads.provider.FullScreenAdListener
import io.github.shivathapaa.kmpads.provider.FullScreenAdLoader
import io.github.shivathapaa.kmpads.runtime.AdClock
import kotlinx.coroutines.CompletableDeferred
import kotlin.time.Duration.Companion.hours
import kotlin.time.TimeSource
import com.google.android.gms.ads.AdError as GmaAdError

private const val TAG = "kmp-ads/admob"

/** App-open ads expire four hours after they load. */
private val APP_OPEN_TTL = 4.hours

/**
 * Loads and presents the four full-screen formats. Ads load with the application context and are
 * presented into the current foreground Activity.
 */
internal class AdMobFullScreenLoader(
    private val appContext: Context,
    private val activities: ForegroundActivityTracker,
    private val clock: AdClock,
    private val logger: AdLogger,
) : FullScreenAdLoader {
    private val loaded = mutableMapOf<AdPlacementId, LoadedAd>()

    override suspend fun load(request: AdLoadRequest): AdLoadOutcome {
        val startedAt = TimeSource.Monotonic.markNow()
        val result = CompletableDeferred<AdLoadOutcome>()
        val unitId = request.unitId.value

        fun succeed(ad: LoadedAd) {
            loaded[request.placement] = ad
            result.complete(AdLoadOutcome.Loaded(startedAt.elapsedNow()))
        }

        fun fail(error: LoadAdError) {
            logger.log(AdLogLevel.Warn, TAG, "load ${request.format}: ${error.message}", null)
            result.complete(AdLoadOutcome.Failed(adMobErrorFor(error.code)))
        }

        when (request.format) {
            AdFormat.Interstitial -> InterstitialAd.load(
                appContext,
                unitId,
                newAdRequest(),
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: InterstitialAd) =
                        succeed(LoadedAd.Interstitial(ad, clock.nowMillis()))

                    override fun onAdFailedToLoad(error: LoadAdError) = fail(error)
                },
            )

            AdFormat.Rewarded -> RewardedAd.load(
                appContext,
                unitId,
                newAdRequest(),
                object : RewardedAdLoadCallback() {
                    override fun onAdLoaded(ad: RewardedAd) =
                        succeed(LoadedAd.Rewarded(ad, clock.nowMillis()))

                    override fun onAdFailedToLoad(error: LoadAdError) = fail(error)
                },
            )

            AdFormat.RewardedInterstitial -> RewardedInterstitialAd.load(
                appContext,
                unitId,
                newAdRequest(),
                object : RewardedInterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: RewardedInterstitialAd) =
                        succeed(LoadedAd.RewardedInterstitial(ad, clock.nowMillis()))

                    override fun onAdFailedToLoad(error: LoadAdError) = fail(error)
                },
            )

            AdFormat.AppOpen -> AppOpenAd.load(
                appContext,
                unitId,
                newAdRequest(),
                object : AppOpenAd.AppOpenAdLoadCallback() {
                    override fun onAdLoaded(ad: AppOpenAd) =
                        succeed(LoadedAd.AppOpen(ad, clock.nowMillis()))

                    override fun onAdFailedToLoad(error: LoadAdError) = fail(error)
                },
            )

            AdFormat.Banner, AdFormat.Native ->
                return AdLoadOutcome.Failed(AdError.InvalidRequest())
        }

        return result.await()
    }

    override fun isReady(placement: AdPlacementId): Boolean {
        val ad = loaded[placement] ?: return false
        if (ad.isExpired(clock.nowMillis())) {
            loaded.remove(placement)
            return false
        }
        return true
    }

    override fun show(placement: AdPlacementId, listener: FullScreenAdListener) {
        val ad = loaded.remove(placement)
        if (ad == null) {
            listener.onShowFailed(AdError.InvalidRequest())
            return
        }
        val activity = activities.current
        if (activity == null) {
            // No Activity to present into, for example while the app is moving to the background.
            listener.onShowFailed(AdError.NoPresentationHost())
            return
        }
        ad.present(activity, listener, logger)
    }

    override fun discard(placement: AdPlacementId) {
        loaded.remove(placement)
    }
}

private sealed interface LoadedAd {
    val loadedAtMillis: Long

    fun isExpired(nowMillis: Long): Boolean = false

    fun present(activity: Activity, listener: FullScreenAdListener, logger: AdLogger)

    data class Interstitial(val ad: InterstitialAd, override val loadedAtMillis: Long) : LoadedAd {
        override fun present(
            activity: Activity,
            listener: FullScreenAdListener,
            logger: AdLogger,
        ) {
            ad.fullScreenContentCallback = listener.asContentCallback(logger)
            ad.setOnPaidEventListener { value ->
                listener.onRevenue(value.toAdRevenue(ad.responseInfo))
            }
            ad.show(activity)
        }
    }

    data class Rewarded(val ad: RewardedAd, override val loadedAtMillis: Long) : LoadedAd {
        override fun present(
            activity: Activity,
            listener: FullScreenAdListener,
            logger: AdLogger,
        ) {
            ad.fullScreenContentCallback = listener.asContentCallback(logger)
            ad.setOnPaidEventListener { value ->
                listener.onRevenue(value.toAdRevenue(ad.responseInfo))
            }
            ad.show(activity, listener.asRewardListener())
        }
    }

    data class RewardedInterstitial(
        val ad: RewardedInterstitialAd,
        override val loadedAtMillis: Long,
    ) : LoadedAd {
        override fun present(
            activity: Activity,
            listener: FullScreenAdListener,
            logger: AdLogger,
        ) {
            ad.fullScreenContentCallback = listener.asContentCallback(logger)
            ad.setOnPaidEventListener { value ->
                listener.onRevenue(value.toAdRevenue(ad.responseInfo))
            }
            ad.show(activity, listener.asRewardListener())
        }
    }

    data class AppOpen(val ad: AppOpenAd, override val loadedAtMillis: Long) : LoadedAd {
        override fun isExpired(nowMillis: Long): Boolean =
            nowMillis - loadedAtMillis > APP_OPEN_TTL.inWholeMilliseconds

        override fun present(
            activity: Activity,
            listener: FullScreenAdListener,
            logger: AdLogger,
        ) {
            ad.fullScreenContentCallback = listener.asContentCallback(logger)
            ad.setOnPaidEventListener { value ->
                listener.onRevenue(value.toAdRevenue(ad.responseInfo))
            }
            ad.show(activity)
        }
    }
}

private fun FullScreenAdListener.asContentCallback(logger: AdLogger) =
    object : FullScreenContentCallback() {
        override fun onAdShowedFullScreenContent() = onShown()

        override fun onAdImpression() = onImpression()

        override fun onAdClicked() = onClicked()

        override fun onAdDismissedFullScreenContent() = onDismissed()

        override fun onAdFailedToShowFullScreenContent(error: GmaAdError) {
            logger.log(AdLogLevel.Warn, TAG, "show failed: ${error.message}", null)
            onShowFailed(adMobErrorFor(error.code))
        }
    }

private fun FullScreenAdListener.asRewardListener() = OnUserEarnedRewardListener { item ->
    onRewardEarned(AdReward(type = item.type, amount = item.amount))
}
