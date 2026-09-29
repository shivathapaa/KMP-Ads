package io.github.shivathapaa.kmpads.consent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The consent state an app acts on. */
public data class AdConsentState(
    /** Whether ads may be requested. */
    val canRequestAds: Boolean = false,
    /** Whether the app must show a privacy-options entry point. Show it only when this is true. */
    val privacyOptionsRequired: Boolean = false,
    val status: Status = Status.Unknown,
) {
    public enum class Status { Unknown, Required, NotRequired, Obtained, Failed }
}

/** Debug-only geography forcing, so the regulated path can be exercised without a VPN. */
public enum class AdDebugGeography { Disabled, Eea, Other, RegulatedUsState }

/**
 * Gathers consent and reopens the privacy options form.
 *
 * The order at startup is:
 *
 * ```
 * 1. gather()                       — request consent info; show the form if one is required
 * 2. [iOS] tracking authorization   — after the form, while the app is active
 * 3. SDK initialisation             — once canRequestAds is true
 * 4. first ad request
 * ```
 *
 * The runtime handles step 3: it initialises the provider on the first request the policies allow.
 * A failure is never fatal. It leaves [AdConsentState.canRequestAds] false, so no ads are requested.
 */
public interface AdConsentController {
    public val state: StateFlow<AdConsentState>

    /**
     * Idempotent per process. Presents a full-screen form, so call it after onboarding and permission
     * prompts have finished.
     */
    public suspend fun gather(
        debugGeography: AdDebugGeography = AdDebugGeography.Disabled,
        testDeviceIdentifiers: List<String> = emptyList(),
    ): AdConsentState

    /**
     * Reopens the privacy options form, for example from a settings screen. Afterwards
     * [AdConsentState.canRequestAds] may have changed from true to false.
     */
    public suspend fun showPrivacyOptions(): AdConsentState

    /** Debug only. Clears stored consent so the flow can be exercised again. */
    public fun reset()
}

/** A controller that never allows ad requests. Use it until a real controller is wired. */
public object NoOpAdConsentController : AdConsentController {
    private val backing = MutableStateFlow(AdConsentState())

    override val state: StateFlow<AdConsentState> = backing.asStateFlow()

    override suspend fun gather(
        debugGeography: AdDebugGeography,
        testDeviceIdentifiers: List<String>,
    ): AdConsentState = backing.value

    override suspend fun showPrivacyOptions(): AdConsentState = backing.value

    override fun reset(): Unit = Unit
}
