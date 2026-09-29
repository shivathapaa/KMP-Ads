package io.github.shivathapaa.kmpads.admob

import android.content.Context
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import io.github.shivathapaa.kmpads.error.AdError
import io.github.shivathapaa.kmpads.error.AdLogLevel
import io.github.shivathapaa.kmpads.error.AdLogger
import io.github.shivathapaa.kmpads.provider.AdPlatformView
import io.github.shivathapaa.kmpads.provider.AdViewFactoryContext
import io.github.shivathapaa.kmpads.provider.BannerAdHandle
import io.github.shivathapaa.kmpads.provider.BannerAdListener
import io.github.shivathapaa.kmpads.provider.BannerAdLoader
import io.github.shivathapaa.kmpads.provider.BannerAdRequest
import io.github.shivathapaa.kmpads.provider.BannerSizing
import io.github.shivathapaa.kmpads.provider.androidContextOrNull
import io.github.shivathapaa.kmpads.provider.asAdPlatformView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val STANDARD_BANNER_HEIGHT_DP = 50

internal class AdMobBannerLoader(private val logger: AdLogger) : BannerAdLoader {
    /** Synchronous and network-free: the adaptive height depends only on the width and the device. */
    override fun reservedHeightDp(
        context: AdViewFactoryContext,
        widthDp: Int,
        sizing: BannerSizing,
    ): Int {
        val androidContext = context.androidContextOrNull() ?: return 0
        return when (sizing) {
            BannerSizing.AnchoredAdaptive -> adaptiveSize(androidContext, widthDp).height
            BannerSizing.Standard320x50 -> STANDARD_BANNER_HEIGHT_DP
            is BannerSizing.FixedHeight -> sizing.heightDp
        }
    }

    override fun createBanner(
        context: AdViewFactoryContext,
        request: BannerAdRequest,
        listener: BannerAdListener,
    ): BannerAdHandle {
        // The Activity context from the interop factory; ad views require it.
        val androidContext = context.androidContextOrNull()
            ?: return DeadBannerHandle(request.widthDp)

        // Set by the SDK's load callbacks. The AdView exists before it has loaded.
        val loaded = MutableStateFlow(false)

        val adView = AdView(androidContext).apply {
            adUnitId = request.unitId.value
            setAdSize(
                when (val sizing = request.sizing) {
                    BannerSizing.AnchoredAdaptive -> adaptiveSize(androidContext, request.widthDp)
                    BannerSizing.Standard320x50 -> AdSize.BANNER
                    is BannerSizing.FixedHeight -> AdSize(request.widthDp, sizing.heightDp)
                }
            )
            adListener = object : AdListener() {
                override fun onAdLoaded() {
                    loaded.value = true
                    listener.onLoaded()
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    // A refresh failure keeps the current banner on screen, so `loaded` is left as it is.
                    logger.log(AdLogLevel.Warn, TAG, "banner: ${error.message}", null)
                    listener.onLoadFailed(adMobErrorFor(error.code))
                }

                override fun onAdImpression() = listener.onImpression()

                override fun onAdClicked() = listener.onClicked()
            }
            setOnPaidEventListener { value ->
                listener.onRevenue(value.toAdRevenue(responseInfo))
            }
        }

        return AdMobBannerHandle(
            adView = adView,
            reservedHeightDp = reservedHeightDp(context, request.widthDp, request.sizing),
            isLoaded = loaded.asStateFlow(),
        )
    }

    /** The large anchored adaptive size for [widthDp], computed without a network call. */
    private fun adaptiveSize(context: Context, widthDp: Int): AdSize =
        AdSize.getLargeAnchoredAdaptiveBannerAdSize(context, widthDp)

    private companion object {
        const val TAG = "kmp-ads/admob"
    }
}

private class AdMobBannerHandle(
    private val adView: AdView,
    override val reservedHeightDp: Int,
    override val isLoaded: StateFlow<Boolean>,
) : BannerAdHandle {
    override val view: AdPlatformView = adView.asAdPlatformView()

    override fun load() {
        adView.loadAd(newAdRequest())
    }

    override fun pause() {
        adView.pause()
    }

    override fun resume() {
        adView.resume()
    }

    /** Releases the AdView and its refresh timer. */
    override fun dispose() {
        adView.destroy()
    }
}

/** Returned when no Activity context is available. Keeps its reserved height and shows nothing. */
private class DeadBannerHandle(override val reservedHeightDp: Int) : BannerAdHandle {
    override val view: AdPlatformView = AdPlatformView.stub()
    override val isLoaded: StateFlow<Boolean> = MutableStateFlow(false)
    override fun load(): Unit = Unit
    override fun pause(): Unit = Unit
    override fun resume(): Unit = Unit
    override fun dispose(): Unit = Unit
}
