package io.github.shivathapaa.kmpads.admob

import android.app.Activity
import android.content.Context
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import io.github.shivathapaa.kmpads.consent.AdDebugGeography

/**
 * The UMP calls [UmpConsentController] makes. It exists so a host test can stand in for UMP's static
 * entry points, which need a device. Failures carry UMP's message for the log only.
 */
internal interface UmpClient {
    val canRequestAds: Boolean
    val privacyOptionsRequired: Boolean

    fun requestConsentInfoUpdate(
        activity: Activity,
        debugGeography: AdDebugGeography,
        testDeviceIdentifiers: List<String>,
        onSuccess: () -> Unit,
        onFailure: (message: String?) -> Unit,
    )

    /** [onComplete] receives `null` once the form was shown or was not required. */
    fun loadAndShowConsentFormIfRequired(activity: Activity, onComplete: (errorMessage: String?) -> Unit)

    fun showPrivacyOptionsForm(activity: Activity, onComplete: (errorMessage: String?) -> Unit)

    fun reset()
}

/** [UmpClient] over Google's User Messaging Platform SDK. */
internal class GoogleUmpClient(private val context: Context) : UmpClient {
    private val consentInformation: ConsentInformation =
        UserMessagingPlatform.getConsentInformation(context)

    override val canRequestAds: Boolean
        get() = consentInformation.canRequestAds()

    override val privacyOptionsRequired: Boolean
        get() = consentInformation.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    override fun requestConsentInfoUpdate(
        activity: Activity,
        debugGeography: AdDebugGeography,
        testDeviceIdentifiers: List<String>,
        onSuccess: () -> Unit,
        onFailure: (message: String?) -> Unit,
    ) {
        val parameters = ConsentRequestParameters.Builder()
            .apply {
                if (debugGeography != AdDebugGeography.Disabled || testDeviceIdentifiers.isNotEmpty()) {
                    setConsentDebugSettings(
                        ConsentDebugSettings.Builder(context)
                            .setDebugGeography(debugGeography.toUmpGeography())
                            .apply { testDeviceIdentifiers.forEach(::addTestDeviceHashedId) }
                            .build()
                    )
                }
            }
            .build()

        consentInformation.requestConsentInfoUpdate(
            activity,
            parameters,
            { onSuccess() },
            { error -> onFailure(error.message) },
        )
    }

    override fun loadAndShowConsentFormIfRequired(
        activity: Activity,
        onComplete: (errorMessage: String?) -> Unit,
    ) {
        UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { error ->
            onComplete(error?.message)
        }
    }

    override fun showPrivacyOptionsForm(
        activity: Activity,
        onComplete: (errorMessage: String?) -> Unit,
    ) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { error -> onComplete(error?.message) }
    }

    override fun reset() {
        consentInformation.reset()
    }
}

private fun AdDebugGeography.toUmpGeography(): Int = when (this) {
    AdDebugGeography.Disabled -> ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_DISABLED
    AdDebugGeography.Eea -> ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA
    AdDebugGeography.Other -> ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_OTHER
    AdDebugGeography.RegulatedUsState ->
        ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_REGULATED_US_STATE
}
