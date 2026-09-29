package io.github.shivathapaa.kmpads.sample

import androidx.compose.ui.window.ComposeUIViewController
import io.github.shivathapaa.kmpads.bridge.KMPAdsHost
import io.github.shivathapaa.kmpads.config.AdPlatform
import io.github.shivathapaa.kmpads.consent.AdConsentController
import io.github.shivathapaa.kmpads.error.AdLogger
import io.github.shivathapaa.kmpads.ios.iosAdProvider
import io.github.shivathapaa.kmpads.ios.iosConsentController
import io.github.shivathapaa.kmpads.ios.requestIosTrackingAuthorization
import io.github.shivathapaa.kmpads.runtime.AdsSystem
import io.github.shivathapaa.kmpads.storage.AdStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIViewController

/**
 * The iOS entry point Swift calls. The [KMPAdsHost] is passed in by constructor; pass `nil` to run
 * without ads.
 *
 * Keep `ads-core` types out of this class's public API. Every public type is exported to the
 * framework header, and `checkObjCExport` fails if ad system types appear there.
 */
public class SampleIosEntryPoint(host: KMPAdsHost?) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val adsHost = host

    private val consentController: AdConsentController = iosConsentController(host)

    private val ads: AdsSystem = createSampleAdsSystem(
        provider = iosAdProvider(host, logger = AdLogger.NoOp),
        platform = AdPlatform.Ios,
        scope = scope,
        storage = UserDefaultsAdStorage(),
        // Ads are requested only after consent is obtained.
        consentObtained = consentController.state.map { it.canRequestAds },
    )

    public fun viewController(): UIViewController = ComposeUIViewController {
        SampleApp(ads = ads, consent = consentController)
    }

    /** Gathers consent, then requests tracking authorization. */
    public fun startConsentFlow() {
        scope.launch {
            consentController.gather()
            requestIosTrackingAuthorization(adsHost)
        }
    }

    public fun onAppForegrounded(): Unit = ads.onAppForegrounded()

    public fun onAppBackgrounded(): Unit = ads.onAppBackgrounded()
}

/** [AdStorage] over `NSUserDefaults`. */
private class UserDefaultsAdStorage : AdStorage {
    private val defaults = NSUserDefaults.standardUserDefaults

    override suspend fun read(): String? = defaults.stringForKey(AdStorage.SUGGESTED_KEY)

    override suspend fun write(value: String) {
        defaults.setObject(value, AdStorage.SUGGESTED_KEY)
    }
}
