package io.github.shivathapaa.kmpads.bridge

import kotlin.native.ObjCName
import platform.UIKit.UIView

/**
 * The ad SDK adapter that the host app implements in Swift.
 *
 * The library calls every method on the main thread, and the adapter must invoke every callback on
 * the main thread. Changes to this interface break existing adapters, so it is versioned by
 * [KMPAdsProtocol.VERSION].
 *
 * Supplying no host is supported: no ad views are created and the app runs without ads.
 */
@ObjCName("KMPAdsHost", exact = true)
public interface KMPAdsHost {
    /**
     * Must return [KMPAdsProtocol.VERSION]. On a mismatch the library disables ads and reports a
     * diagnostic.
     */
    public fun protocolVersion(): Int

    /**
     * Initialises the ad SDK. Called once, after consent has been resolved.
     *
     * @param testDeviceIdentifiers `identifierForVendor` values that receive test ads on a physical
     *   device. Simulators always receive test ads. Leave empty in release builds.
     * @param ageRestriction one of [KMPAdsProtocol.AGE_UNSPECIFIED], [KMPAdsProtocol.AGE_CHILD] or
     *   [KMPAdsProtocol.AGE_TEEN].
     * @param maxAdContentRating one of `""` (unspecified), `"G"`, `"PG"`, `"T"`, `"MA"`.
     */
    public fun start(
        testDeviceIdentifiers: List<String>,
        ageRestriction: String,
        maxAdContentRating: String,
        callback: KMPAdsStartCallback,
    )

    /** Mutes every ad creative app-wide. */
    public fun setAppMuted(muted: Boolean)

    /** Whether the consent SDK permits ad requests. Synchronous and safe to poll. */
    public fun canRequestAds(): Boolean

    /**
     * Whether the app must show a privacy-options entry point. Show the entry point only when this is
     * true.
     */
    public fun isPrivacyOptionsRequired(): Boolean

    /**
     * Refreshes consent information and presents the consent form if one is required. The adapter
     * resolves the presenting view controller itself.
     *
     * @param debugGeography `""` in release. In debug: `"eea"`, `"other"`, or `"regulatedUsState"`.
     */
    public fun gatherConsent(
        debugGeography: String,
        testDeviceIdentifiers: List<String>,
        callback: KMPAdsConsentCallback,
    )

    /** Reopen the privacy-options form, from a Settings row. */
    public fun presentPrivacyOptionsForm(callback: KMPAdsConsentCallback)

    /** Debug only. Clears stored consent so the form can be exercised again. */
    public fun resetConsent()

    /**
     * Requests App Tracking Transparency authorization. Must run after the consent form and while the
     * app is active, otherwise iOS ignores the request. No-op below iOS 14.
     */
    public fun requestTrackingAuthorization(callback: KMPAdsConsentCallback)

    /**
     * The height, in points, of an anchored adaptive banner at [widthPoints]. Synchronous and does not
     * use the network.
     */
    public fun adaptiveBannerHeightPoints(widthPoints: Double): Double

    /**
     * @param widthPoints the width Compose measured, in points.
     * @return a view that is already loading. A failed load reports through [callback] and the view
     *   stays empty.
     */
    public fun makeBanner(
        unitId: String,
        widthPoints: Double,
        callback: KMPAdsSurfaceCallback,
    ): UIView

    /**
     * @param templateId an app-chosen key naming the native template to render. The ad must be
     *   rendered in the SDK's native ad view so that clicks and impressions are registered.
     */
    public fun makeNative(
        unitId: String,
        templateId: String,
        callback: KMPAdsSurfaceCallback,
    ): UIView

    /** Called when the view is released. Must release the delegate and every SDK object. */
    public fun disposeSurface(view: UIView)

    /** @param format one of the `FORMAT_` constants in [KMPAdsProtocol]. */
    public fun loadFullScreen(format: String, unitId: String, callback: KMPAdsLoadCallback)

    public fun isFullScreenReady(format: String, unitId: String): Boolean

    public fun showFullScreen(format: String, unitId: String, callback: KMPAdsShowCallback)

    /** Drop a loaded-but-unshown ad, e.g. once it has aged past the SDK's expiry window. */
    public fun discardFullScreen(format: String, unitId: String)
}

/** String constants shared by the library and the Swift adapter. */
@ObjCName("KMPAdsProtocol", exact = true)
public object KMPAdsProtocol {
    /** The [KMPAdsHost] protocol version. See [KMPAdsHost.protocolVersion]. */
    public const val VERSION: Int = 1

    public const val FORMAT_INTERSTITIAL: String = "interstitial"
    public const val FORMAT_REWARDED: String = "rewarded"
    public const val FORMAT_REWARDED_INTERSTITIAL: String = "rewardedInterstitial"
    public const val FORMAT_APP_OPEN: String = "appOpen"

    public const val GEOGRAPHY_DISABLED: String = ""
    public const val GEOGRAPHY_EEA: String = "eea"
    public const val GEOGRAPHY_OTHER: String = "other"
    public const val GEOGRAPHY_REGULATED_US_STATE: String = "regulatedUsState"

    public const val RATING_UNSPECIFIED: String = ""
    public const val RATING_G: String = "G"
    public const val RATING_PG: String = "PG"
    public const val RATING_T: String = "T"
    public const val RATING_MA: String = "MA"

    public const val AGE_UNSPECIFIED: String = ""
    public const val AGE_CHILD: String = "child"
    public const val AGE_TEEN: String = "teen"

    /** Reported by the library, never by an SDK, when no host was supplied. */
    public const val ERROR_CODE_NO_HOST: Int = -100

    /** Reported by the library when [KMPAdsHost.protocolVersion] does not match [VERSION]. */
    public const val ERROR_CODE_PROTOCOL_MISMATCH: Int = -101
}
