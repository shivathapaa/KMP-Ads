package io.github.shivathapaa.kmpads.ios

import io.github.shivathapaa.kmpads.bridge.KMPAdsConsentCallback
import io.github.shivathapaa.kmpads.bridge.KMPAdsHost
import io.github.shivathapaa.kmpads.bridge.KMPAdsProtocol
import io.github.shivathapaa.kmpads.consent.AdConsentController
import io.github.shivathapaa.kmpads.consent.AdConsentState
import io.github.shivathapaa.kmpads.consent.AdDebugGeography
import io.github.shivathapaa.kmpads.consent.NoOpAdConsentController
import io.github.shivathapaa.kmpads.error.AdLogLevel
import io.github.shivathapaa.kmpads.error.AdLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val CONSENT_TAG = "kmp-ads/ios-consent"

/**
 * Consent through the Swift adapter. A `null` host yields [NoOpAdConsentController], which never
 * allows ad requests.
 */
public fun iosConsentController(
    host: KMPAdsHost?,
    logger: AdLogger = AdLogger.NoOp,
): AdConsentController = host?.let { IosConsentController(it, logger) } ?: NoOpAdConsentController

internal class IosConsentController(
    private val host: KMPAdsHost,
    private val logger: AdLogger,
) : AdConsentController {
    private val backing = MutableStateFlow(
        AdConsentState(
            canRequestAds = host.canRequestAds(),
            privacyOptionsRequired = host.isPrivacyOptionsRequired(),
        )
    )

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

        val result = CompletableDeferred<AdConsentState>()
        host.gatherConsent(
            debugGeography = debugGeography.toBridgeName(),
            testDeviceIdentifiers = testDeviceIdentifiers,
            callback = consentCallback(result),
        )
        result.await().also { backing.value = it }
    }

    override suspend fun showPrivacyOptions(): AdConsentState {
        val result = CompletableDeferred<AdConsentState>()
        host.presentPrivacyOptionsForm(consentCallback(result))
        return result.await().also { backing.value = it }
    }

    override fun reset() {
        host.resetConsent()
        gathered = false
        backing.value = AdConsentState()
    }

    private fun consentCallback(
        result: CompletableDeferred<AdConsentState>,
    ): KMPAdsConsentCallback = object : KMPAdsConsentCallback {
        override fun onConsentResolved(
            canRequestAds: Boolean,
            privacyOptionsRequired: Boolean,
            errorMessage: String?,
        ) {
            if (errorMessage != null) {
                logger.log(AdLogLevel.Warn, CONSENT_TAG, "consent: $errorMessage", null)
            }
            result.complete(
                AdConsentState(
                    canRequestAds = canRequestAds,
                    privacyOptionsRequired = privacyOptionsRequired,
                    status = when {
                        errorMessage != null -> AdConsentState.Status.Failed
                        canRequestAds -> AdConsentState.Status.Obtained
                        else -> AdConsentState.Status.Required
                    },
                )
            )
        }
    }
}

/**
 * Requests App Tracking Transparency authorization and returns the consent state afterwards. Call
 * it after the consent form, while the app is active, otherwise iOS ignores the request.
 *
 * A denial is not a failure: ads still serve, without tracking. Do not re-prompt or offer
 * incentives for permission.
 */
public suspend fun requestIosTrackingAuthorization(
    host: KMPAdsHost?,
    logger: AdLogger = AdLogger.NoOp,
): AdConsentState {
    if (host == null) return AdConsentState()
    val result = CompletableDeferred<AdConsentState>()
    host.requestTrackingAuthorization(
        object : KMPAdsConsentCallback {
            override fun onConsentResolved(
                canRequestAds: Boolean,
                privacyOptionsRequired: Boolean,
                errorMessage: String?,
            ) {
                if (errorMessage != null) {
                    logger.log(AdLogLevel.Info, CONSENT_TAG, "att: $errorMessage", null)
                }
                result.complete(
                    AdConsentState(
                        canRequestAds = canRequestAds,
                        privacyOptionsRequired = privacyOptionsRequired,
                        status = if (canRequestAds) {
                            AdConsentState.Status.Obtained
                        } else {
                            AdConsentState.Status.Required
                        },
                    )
                )
            }
        },
    )
    return result.await()
}

private fun AdDebugGeography.toBridgeName(): String = when (this) {
    AdDebugGeography.Disabled -> KMPAdsProtocol.GEOGRAPHY_DISABLED
    AdDebugGeography.Eea -> KMPAdsProtocol.GEOGRAPHY_EEA
    AdDebugGeography.Other -> KMPAdsProtocol.GEOGRAPHY_OTHER
    AdDebugGeography.RegulatedUsState -> KMPAdsProtocol.GEOGRAPHY_REGULATED_US_STATE
}
