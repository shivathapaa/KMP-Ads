package io.github.shivathapaa.kmpads.bridge

import kotlin.native.ObjCName

/**
 * Reports the result of [KMPAdsHost.start].
 *
 * The callback interfaces in this file are implemented by the library and called by the Swift host
 * on the main thread. `message` and `errorMessage` values are used for logging only.
 */

@ObjCName("KMPAdsStartCallback", exact = true)
public interface KMPAdsStartCallback {
    /**
     * @param canRequestAds the consent gate as the SDK sees it immediately after initialisation.
     * @param adapterSummary mediation adapter states, for logging. Null when there is nothing to say.
     */
    public fun onStarted(canRequestAds: Boolean, adapterSummary: String?)

    public fun onStartFailed(code: Int, message: String)
}

@ObjCName("KMPAdsConsentCallback", exact = true)
public interface KMPAdsConsentCallback {
    /**
     * Called when a consent gather, a privacy-options presentation or a tracking request completes,
     * including when no form was shown.
     *
     * @param canRequestAds whether ads may be requested now. It changes from true to false when the
     *   user withdraws consent.
     * @param privacyOptionsRequired whether the app must show a privacy-options entry point.
     * @param errorMessage for logging, or null on success. A failure leaves [canRequestAds] false.
     */
    public fun onConsentResolved(
        canRequestAds: Boolean,
        privacyOptionsRequired: Boolean,
        errorMessage: String?,
    )
}

@ObjCName("KMPAdsLoadCallback", exact = true)
public interface KMPAdsLoadCallback {
    public fun onLoaded(format: String, unitId: String)
    public fun onFailedToLoad(format: String, unitId: String, code: Int, message: String)
}

/** Reports the state of a banner or native ad view. */
@ObjCName("KMPAdsSurfaceCallback", exact = true)
public interface KMPAdsSurfaceCallback {
    /** @param heightPoints the view's final height in points. On iOS one point equals one dp. */
    public fun onSurfaceLoaded(widthPoints: Double, heightPoints: Double)

    public fun onSurfaceFailed(code: Int, message: String)
    public fun onSurfaceImpression()
    public fun onSurfaceClick()
}

@ObjCName("KMPAdsShowCallback", exact = true)
public interface KMPAdsShowCallback {
    public fun onShown(format: String, unitId: String)
    public fun onImpression(format: String, unitId: String)
    public fun onClick(format: String, unitId: String)

    /** Rewarded and rewarded-interstitial only. Fires before [onDismissed]. */
    public fun onUserEarnedReward(format: String, unitId: String, rewardType: String, amount: Int)

    public fun onDismissed(format: String, unitId: String)
    public fun onFailedToShow(format: String, unitId: String, code: Int, message: String)
}
