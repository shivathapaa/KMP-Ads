package io.github.shivathapaa.kmpads.sample

import android.app.Application
import android.util.Log
import androidx.compose.runtime.Composable
import io.github.shivathapaa.kmpads.admob.AdMobAndroid
import io.github.shivathapaa.kmpads.config.AdAudienceConfig
import io.github.shivathapaa.kmpads.config.AdPlatform
import io.github.shivathapaa.kmpads.consent.AdConsentController
import io.github.shivathapaa.kmpads.error.AdLogLevel
import io.github.shivathapaa.kmpads.error.AdLogger
import io.github.shivathapaa.kmpads.runtime.AdsSystem
import io.github.shivathapaa.kmpads.storage.AdStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The Android ad setup for the sample. Create it in `Application.onCreate`; the ad SDK is
 * initialised later, on the first ad request the policies allow.
 */
public class SampleAds(application: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val logger = AdLogger { level, tag, message, cause ->
        when (level) {
            AdLogLevel.Debug -> Log.d(tag, message, cause)
            AdLogLevel.Info -> Log.i(tag, message, cause)
            AdLogLevel.Warn -> Log.w(tag, message, cause)
            AdLogLevel.Error -> Log.e(tag, message, cause)
        }
    }

    private val adMob = AdMobAndroid.install(
        application = application,
        // The audience declared in the app's store listing.
        audience = AdAudienceConfig.GeneralAudience,
        logger = logger,
    )

    public val consentController: AdConsentController = adMob.consentController

    public val ads: AdsSystem = createSampleAdsSystem(
        provider = adMob.provider,
        platform = AdPlatform.Android,
        scope = scope,
        storage = SharedPreferencesAdStorage(application),
        // Ads are requested only after consent is obtained.
        consentObtained = consentController.state.map { it.canRequestAds },
    )

    /** Starts consent gathering. Call it once onboarding and permission prompts have finished. */
    public fun startConsentFlow() {
        scope.launch { consentController.gather() }
    }

    public fun onAppForegrounded(): Unit = ads.onAppForegrounded()

    public fun onAppBackgrounded(): Unit = ads.onAppBackgrounded()
}

/** [AdStorage] over `SharedPreferences`. */
private class SharedPreferencesAdStorage(application: Application) : AdStorage {
    private val preferences =
        application.getSharedPreferences("kmpads_sample", android.content.Context.MODE_PRIVATE)

    override suspend fun read(): String? = preferences.getString(AdStorage.SUGGESTED_KEY, null)

    override suspend fun write(value: String) {
        preferences.edit().putString(AdStorage.SUGGESTED_KEY, value).apply()
    }
}

/** The entry point the Activity calls. */
@Composable
public fun SampleAppRoot(sample: SampleAds) {
    SampleApp(ads = sample.ads, consent = sample.consentController)
}
