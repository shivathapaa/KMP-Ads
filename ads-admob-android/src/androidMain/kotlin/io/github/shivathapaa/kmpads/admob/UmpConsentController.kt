package io.github.shivathapaa.kmpads.admob

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
 * so every placement is suppressed until a later [gather] succeeds.
 */
internal class UmpConsentController(
    private val ump: UmpClient,
    private val activities: ForegroundActivityTracker,
    private val logger: AdLogger,
) : AdConsentController {
    private val backing = MutableStateFlow(currentState())

    override val state: StateFlow<AdConsentState> = backing.asStateFlow()

    /** Serialises consent gathering so the form is never shown twice at once. */
    private val gate = Mutex()

    /** Set only by a gather that completed, so a failed one is asked again by the next call. */
    private var gathered = false

    override suspend fun gather(
        debugGeography: AdDebugGeography,
        testDeviceIdentifiers: List<String>,
    ): AdConsentState = gate.withLock {
        if (gathered) return@withLock backing.value

        val activity = activities.current
        if (activity == null) {
            logger.log(AdLogLevel.Warn, TAG, "no activity to gather consent in", null)
            return@withLock backing.value
        }

        val infoUpdated = CompletableDeferred<Boolean>()
        ump.requestConsentInfoUpdate(
            activity = activity,
            debugGeography = debugGeography,
            testDeviceIdentifiers = testDeviceIdentifiers,
            onSuccess = { infoUpdated.complete(true) },
            onFailure = { message ->
                logger.log(AdLogLevel.Warn, TAG, "info update failed: $message", null)
                infoUpdated.complete(false)
            },
        )
        val infoUpToDate = infoUpdated.await()

        val formCompleted = CompletableDeferred<Boolean>()
        ump.loadAndShowConsentFormIfRequired(activity) { errorMessage ->
            if (errorMessage != null) {
                logger.log(AdLogLevel.Warn, TAG, "form failed: $errorMessage", null)
            }
            formCompleted.complete(errorMessage == null)
        }

        // A failed update or form, such as no network on a fresh install, leaves canRequestAds false
        // until a later call succeeds.
        gathered = formCompleted.await() && infoUpToDate
        currentState().also { backing.value = it }
    }

    override suspend fun showPrivacyOptions(): AdConsentState {
        val activity = activities.current ?: return backing.value
        val dismissed = CompletableDeferred<Unit>()
        ump.showPrivacyOptionsForm(activity) { errorMessage ->
            if (errorMessage != null) {
                logger.log(AdLogLevel.Warn, TAG, "privacy form failed: $errorMessage", null)
            }
            dismissed.complete(Unit)
        }
        dismissed.await()

        // Re-read afterwards: consent can be revoked here, which tears down live ad views.
        return currentState().also { backing.value = it }
    }

    override fun reset() {
        ump.reset()
        gathered = false
        backing.value = currentState()
    }

    private fun currentState(): AdConsentState {
        val canRequestAds = ump.canRequestAds
        val privacyOptionsRequired = ump.privacyOptionsRequired
        return AdConsentState(
            canRequestAds = canRequestAds,
            privacyOptionsRequired = privacyOptionsRequired,
            status = when {
                canRequestAds -> AdConsentState.Status.Obtained
                privacyOptionsRequired -> AdConsentState.Status.Required
                else -> AdConsentState.Status.Unknown
            },
        )
    }
}
