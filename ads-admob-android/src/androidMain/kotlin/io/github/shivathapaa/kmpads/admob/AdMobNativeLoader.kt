package io.github.shivathapaa.kmpads.admob

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView
import io.github.shivathapaa.kmpads.error.AdError
import io.github.shivathapaa.kmpads.error.AdLogLevel
import io.github.shivathapaa.kmpads.error.AdLogger
import io.github.shivathapaa.kmpads.provider.AdPlatformView
import io.github.shivathapaa.kmpads.provider.AdViewFactoryContext
import io.github.shivathapaa.kmpads.provider.NativeAdAssets
import io.github.shivathapaa.kmpads.provider.NativeAdHandle
import io.github.shivathapaa.kmpads.provider.NativeAdListener
import io.github.shivathapaa.kmpads.provider.NativeAdLoadOutcome
import io.github.shivathapaa.kmpads.provider.NativeAdLoader
import io.github.shivathapaa.kmpads.provider.NativeAdRequest
import io.github.shivathapaa.kmpads.provider.NativeAdStyle
import io.github.shivathapaa.kmpads.provider.androidContextOrNull
import io.github.shivathapaa.kmpads.provider.asAdPlatformView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val TAG = "kmp-ads/admob"

/**
 * The AdMob native ad loader. Builds the SDK's [NativeAdView], binds the assets to it and returns
 * the finished view through [NativeAdHandle.view].
 */
internal class AdMobNativeLoader(private val logger: AdLogger) : NativeAdLoader {
    override suspend fun load(
        context: AdViewFactoryContext,
        request: NativeAdRequest,
        listener: NativeAdListener,
    ): NativeAdLoadOutcome {
        // The Activity context from the interop factory; native ad clicks require it.
        val activityContext = context.androidContextOrNull()
            ?: return NativeAdLoadOutcome.Failed(AdError.NoPresentationHost())

        return suspendCancellableCoroutine { continuation ->
            // Main-thread only. Destroys an ad that arrives after the load was cancelled.
            var settled = false

            fun settle(outcome: NativeAdLoadOutcome) {
                if (settled) return
                settled = true
                // A value dropped by cancellation still holds a NativeAd, so it is destroyed here.
                continuation.resume(outcome) { _, value, _ ->
                    (value as? NativeAdLoadOutcome.Loaded)?.handle?.dispose()
                }
            }

            val adLoader = AdLoader.Builder(activityContext, request.unitId.value)
                .forNativeAd { nativeAd ->
                    if (settled) {
                        // Cancelled between request and fill: nothing else will destroy this ad.
                        nativeAd.destroy()
                        return@forNativeAd
                    }
                    nativeAd.setOnPaidEventListener { value ->
                        listener.onRevenue(value.toAdRevenue(nativeAd.responseInfo))
                    }
                    val view = bindNativeAdView(activityContext, nativeAd, request.style)
                    settle(
                        NativeAdLoadOutcome.Loaded(
                            AdMobNativeHandle(nativeAd, view, nativeAd.toAssets()),
                        ),
                    )
                }
                .withAdListener(object : AdListener() {
                    override fun onAdFailedToLoad(error: LoadAdError) {
                        logger.log(AdLogLevel.Warn, TAG, "native: ${error.message}", null)
                        settle(NativeAdLoadOutcome.Failed(adMobErrorFor(error.code)))
                    }

                    override fun onAdImpression() = listener.onImpression()

                    override fun onAdClicked() = listener.onClicked()
                })
                .withNativeAdOptions(
                    NativeAdOptions.Builder()
                        .setAdChoicesPlacement(NativeAdOptions.ADCHOICES_TOP_RIGHT)
                        .build(),
                )
                .build()

            // A load in flight cannot be cancelled; `settled` destroys a late ad instead.
            continuation.invokeOnCancellation { settled = true }

            adLoader.loadAd(newAdRequest())
        }
    }
}

private class AdMobNativeHandle(
    private val nativeAd: NativeAd,
    private val nativeAdView: NativeAdView,
    override val assets: NativeAdAssets,
) : NativeAdHandle {
    override val view: AdPlatformView = nativeAdView.asAdPlatformView()

    /** Destroys the [NativeAd]. */
    override fun dispose() {
        nativeAd.destroy()
    }
}

private fun NativeAd.toAssets(): NativeAdAssets = NativeAdAssets(
    headline = headline,
    body = body,
    callToAction = callToAction,
    advertiser = advertiser,
    starRating = starRating,
    hasIcon = icon != null,
    hasMedia = mediaContent != null,
    mediaAspectRatio = mediaContent?.aspectRatio,
)

/**
 * Builds the SDK's [NativeAdView], binds each asset to its slot and then calls
 * [NativeAdView.setNativeAd], which must come last. The "Ad" badge sits on the left, clear of the
 * AdChoices overlay at the top right.
 */
private fun bindNativeAdView(
    context: Context,
    nativeAd: NativeAd,
    style: NativeAdStyle,
): NativeAdView {
    val backgroundColor = style.backgroundArgb.toInt()
    val primaryColor = style.primaryTextArgb.toInt()
    val secondaryColor = style.secondaryTextArgb.toInt()

    val adView = NativeAdView(context)

    val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        val pad = context.dp(12)
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            setColor(backgroundColor)
            cornerRadius = context.dp(style.cornerRadiusDp).toFloat()
        }
    }

    // Header row: "Ad" badge, then icon, then the (headline, advertiser) column.
    val header = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    // The "Ad" attribution label, on the left so it never overlaps the AdChoices overlay.
    val adBadge = TextView(context).apply {
        text = "Ad"
        textSize = 10f
        setTextColor(style.ctaTextArgb.toInt())
        val v = context.dp(4)
        val h = context.dp(6)
        setPadding(h, v, h, v)
        background = GradientDrawable().apply {
            setColor(style.ctaBackgroundArgb.toInt())
            cornerRadius = context.dp(3).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            marginEnd = context.dp(8)
        }
    }

    val iconView = ImageView(context).apply {
        layoutParams = LinearLayout.LayoutParams(context.dp(40), context.dp(40)).apply {
            marginEnd = context.dp(8)
        }
    }

    val titleColumn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
    }
    val headlineView = TextView(context).apply {
        textSize = style.headlineTextSizeSp
        setTextColor(primaryColor)
        setTypeface(typeface, Typeface.BOLD)
        maxLines = 2
    }
    val advertiserView = TextView(context).apply {
        textSize = style.bodyTextSizeSp
        setTextColor(secondaryColor)
        maxLines = 1
    }
    titleColumn.addView(headlineView)
    titleColumn.addView(advertiserView)

    header.addView(adBadge)
    header.addView(iconView)
    header.addView(titleColumn)

    val bodyView = TextView(context).apply {
        textSize = style.bodyTextSizeSp
        setTextColor(secondaryColor)
        // Two lines leave room for the media view's 120x120dp minimum.
        maxLines = 2
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = context.dp(8)
        }
    }

    // Fills the remaining height, with the 120x120dp minimum AdMob requires for video.
    val mediaView = MediaView(context).apply {
        minimumWidth = context.dp(120)
        minimumHeight = context.dp(120)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, 1f).apply {
            topMargin = context.dp(8)
        }
    }

    val callToActionView = Button(context).apply {
        isAllCaps = false
        setTextColor(style.ctaTextArgb.toInt())
        background = GradientDrawable().apply {
            setColor(style.ctaBackgroundArgb.toInt())
            cornerRadius = context.dp(style.cornerRadiusDp).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = context.dp(8)
        }
    }

    card.addView(header)
    card.addView(bodyView)
    card.addView(mediaView)
    card.addView(callToActionView)
    adView.addView(card)

    // Populate assets and hide the slots the creative did not fill.
    headlineView.text = nativeAd.headline
    bind(advertiserView, nativeAd.advertiser)
    bind(bodyView, nativeAd.body)
    bind(callToActionView, nativeAd.callToAction)
    nativeAd.icon?.drawable?.let { iconView.setImageDrawable(it) } ?: run {
        iconView.visibility = View.GONE
    }

    // Registering the views is what counts clicks and impressions; it must precede setNativeAd.
    adView.headlineView = headlineView
    adView.advertiserView = advertiserView
    adView.bodyView = bodyView
    adView.callToActionView = callToActionView
    adView.iconView = iconView
    adView.mediaView = mediaView

    adView.setNativeAd(nativeAd)
    return adView
}

private fun bind(view: TextView, value: String?) {
    if (value.isNullOrEmpty()) {
        view.visibility = View.GONE
    } else {
        view.visibility = View.VISIBLE
        view.text = value
    }
}

private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
