package io.github.shivathapaa.kmpads.admob

import android.content.Context
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import io.github.shivathapaa.kmpads.consent.AdConsentController
import io.github.shivathapaa.kmpads.consent.AdConsentState
import io.github.shivathapaa.kmpads.consent.AdDebugGeography
import io.github.shivathapaa.kmpads.error.AdLogLevel
import io.github.shivathapaa.kmpads.error.AdLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "kmp-ads/ump"

/**
 * Consent through Google's User Messaging Platform. A consent failure leaves `canRequestAds` false,
 * so every placement is suppressed.
 */
internal class UmpConsentController(
    private val context: Context,
    private val activities: ForegroundActivityTracker,
    private val logger: AdLogger,
) : AdConsentController {
    private val consentInformation: ConsentInformation =
        UserMessagingPlatform.getConsentInformation(context)

    private val backing = MutableStateFlow(currentState())

    override val state: StateFlow<AdConsentState> = backing.asStateFlow()

    /** Serialises consent gathering so the form is never shown twice at once. */
    private val gate = Mutex()
    private var gathered = false

    override suspend fun gather(
        debugGeography: AdDebugGeography,
        testDeviceIdentifiers: List<String>,
    ): AdConsentState = gate.withLock {
        if (gathered) return@withLock backing.value
        gathered = true

        val activity = activities.current
        if (activity == null) {
            logger.log(AdLogLevel.Warn, TAG, "no activity to gather consent in", null)
            gathered = false
            return@withLock backing.value
        }

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

        val infoUpdated = CompletableDeferred<Unit>()
        consentInformation.requestConsentInfoUpdate(
            activity,
            parameters,
            { infoUpdated.complete(Unit) },
            { error ->
                logger.log(AdLogLevel.Warn, TAG, "info update failed: ${error.message}", null)
                infoUpdated.complete(Unit)
            },
        )
        infoUpdated.await()

        val formShown = CompletableDeferred<Unit>()
        UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { error ->
            if (error != null) {
                logger.log(AdLogLevel.Warn, TAG, "form failed: ${error.message}", null)
            }
            formShown.complete(Unit)
        }
        formShown.await()

        currentState().also { backing.value = it }
    }

    override suspend fun showPrivacyOptions(): AdConsentState {
        val activity = activities.current ?: return backing.value
        val dismissed = CompletableDeferred<Unit>()
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { error ->
            if (error != null) {
                logger.log(AdLogLevel.Warn, TAG, "privacy form failed: ${error.message}", null)
            }
            dismissed.complete(Unit)
        }
        dismissed.await()

        // Re-read afterwards: consent can be revoked here, which tears down live ad views.
        return currentState().also { backing.value = it }
    }

    override fun reset() {
        consentInformation.reset()
        gathered = false
        backing.value = currentState()
    }

    private fun currentState(): AdConsentState {
        val privacyOptionsRequired =
            consentInformation.privacyOptionsRequirementStatus ==
                ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
        return AdConsentState(
            canRequestAds = consentInformation.canRequestAds(),
            privacyOptionsRequired = privacyOptionsRequired,
            status = when {
                consentInformation.canRequestAds() -> AdConsentState.Status.Obtained
                privacyOptionsRequired -> AdConsentState.Status.Required
                else -> AdConsentState.Status.Unknown
            },
        )
    }
}

private fun AdDebugGeography.toUmpGeography(): Int = when (this) {
    AdDebugGeography.Disabled -> ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_DISABLED
    AdDebugGeography.Eea -> ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA
    AdDebugGeography.Other -> ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_OTHER
    AdDebugGeography.RegulatedUsState ->
        ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_REGULATED_US_STATE
}
