package io.github.shivathapaa.kmpads.admob

import android.app.Application
import io.github.shivathapaa.kmpads.config.AdAudienceConfig
import io.github.shivathapaa.kmpads.consent.AdConsentController
import io.github.shivathapaa.kmpads.error.AdLogger
import io.github.shivathapaa.kmpads.provider.AdProvider
import io.github.shivathapaa.kmpads.runtime.AdClock

/**
 * The Android entry point for the AdMob provider and the UMP consent controller.
 *
 * Call [install] once from `Application.onCreate`. The ad SDK is initialised later, on the first ad
 * request your policies allow, which by default is after consent resolves.
 */
public class AdMobAndroid private constructor(
    public val provider: AdProvider,
    public val consentController: AdConsentController,
    private val application: Application,
    private val activities: ForegroundActivityTracker,
) {
    /** Unregisters the lifecycle callbacks. Intended for tests. */
    public fun uninstall() {
        application.unregisterActivityLifecycleCallbacks(activities)
        provider.dispose()
    }

    public companion object {
        /**
         * @param audience the audience declared for the app in its store listing. Apps directed at
         *   children cannot serve personalised ads.
         * @param muteAds mutes ad audio. Recommended for apps that play audio.
         * @param testDeviceIds devices that receive test ads. Keep these out of source control.
         */
        public fun install(
            application: Application,
            audience: AdAudienceConfig,
            clock: AdClock = AdClock.System,
            testDeviceIds: List<String> = emptyList(),
            muteAds: Boolean = true,
            logger: AdLogger = AdLogger.NoOp,
        ): AdMobAndroid {
            val activities = ForegroundActivityTracker()
            application.registerActivityLifecycleCallbacks(activities)

            return AdMobAndroid(
                provider = AdMobAdProvider(
                    application = application,
                    audience = audience,
                    activities = activities,
                    clock = clock,
                    testDeviceIds = testDeviceIds,
                    muteAds = muteAds,
                    logger = logger,
                ),
                consentController = UmpConsentController(
                    context = application.applicationContext,
                    activities = activities,
                    logger = logger,
                ),
                application = application,
                activities = activities,
            )
        }
    }
}
