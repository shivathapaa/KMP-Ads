package io.github.shivathapaa.kmpads.config

import io.github.shivathapaa.kmpads.error.AdConfigProblem
import kotlin.jvm.JvmInline

/** The platform an ad unit is served on. Ad unit ids are never shared across platforms. */
public enum class AdPlatform { Android, Ios }

/** The ad formats this library knows how to describe. A provider may support any subset. */
public enum class AdFormat {
    Banner,
    Interstitial,
    Rewarded,
    RewardedInterstitial,
    Native,
    AppOpen,
}

/** Identifies one ad opportunity in an app. Chosen by the host; never interpreted by the library. */
@JvmInline
public value class AdPlacementId(public val value: String)

/**
 * An ad unit id: either a Google [Test] unit or a validated [Live] unit.
 *
 * Neither can be created from a raw `String` directly. [Test] values come from [GoogleTestUnits], and
 * [Live] values from [live], which validates the id.
 */
public sealed interface AdUnitId {
    public val value: String

    /** A Google demo ad unit. Never billable. */
    @ConsistentCopyVisibility
    public data class Test internal constructor(override val value: String) : AdUnitId

    /** A real, billable unit id. Only reachable through [live]. */
    @ConsistentCopyVisibility
    public data class Live internal constructor(override val value: String) : AdUnitId

    public companion object {
        private val ADMOB_UNIT_SHAPE = Regex("""^ca-app-pub-\d{16}/\d{10}$""")

        /** Google's demo publisher id. */
        internal const val DEMO_PUBLISHER: String = "ca-app-pub-3940256099942544"

        /** Returns a [Live] unit, or `null` when [raw] is malformed or is one of Google's demo units. */
        public fun live(raw: String): Live? = when {
            !ADMOB_UNIT_SHAPE.matches(raw) -> null
            raw.startsWith(DEMO_PUBLISHER) -> null
            else -> Live(raw)
        }
    }
}

/**
 * Google's demo ad units for each platform and format. See
 * https://developers.google.com/admob/android/test-ads and
 * https://developers.google.com/admob/ios/test-ads.
 */
public object GoogleTestUnits {
    /** Google's demo Android application id, for debug builds only. */
    public const val ANDROID_APP_ID: String = "ca-app-pub-3940256099942544~3347511713"
    public const val IOS_APP_ID: String = "ca-app-pub-3940256099942544~1458002511"

    private val android: Map<AdFormat, AdUnitId.Test> = mapOf(
        AdFormat.Banner to AdUnitId.Test("ca-app-pub-3940256099942544/9214589741"),
        AdFormat.Interstitial to AdUnitId.Test("ca-app-pub-3940256099942544/1033173712"),
        AdFormat.Rewarded to AdUnitId.Test("ca-app-pub-3940256099942544/5224354917"),
        AdFormat.RewardedInterstitial to AdUnitId.Test("ca-app-pub-3940256099942544/5354046379"),
        AdFormat.Native to AdUnitId.Test("ca-app-pub-3940256099942544/2247696110"),
        AdFormat.AppOpen to AdUnitId.Test("ca-app-pub-3940256099942544/9257395921"),
    )

    private val ios: Map<AdFormat, AdUnitId.Test> = mapOf(
        AdFormat.Banner to AdUnitId.Test("ca-app-pub-3940256099942544/2435281174"),
        AdFormat.Interstitial to AdUnitId.Test("ca-app-pub-3940256099942544/4411468910"),
        AdFormat.Rewarded to AdUnitId.Test("ca-app-pub-3940256099942544/1712485313"),
        AdFormat.RewardedInterstitial to AdUnitId.Test("ca-app-pub-3940256099942544/6978759866"),
        AdFormat.Native to AdUnitId.Test("ca-app-pub-3940256099942544/3986624511"),
        AdFormat.AppOpen to AdUnitId.Test("ca-app-pub-3940256099942544/5575463023"),
    )

    public fun forFormat(platform: AdPlatform, format: AdFormat): AdUnitId.Test {
        val table = if (platform == AdPlatform.Android) android else ios
        return table.getValue(format)
    }
}

/** Which ad units are used. Under [ForceTestUnits], registered live units are never read. */
public sealed interface AdUnitMode {
    /** Test units only. The default for any build not identified as a release. */
    public data object ForceTestUnits : AdUnitMode

    public data object LiveUnits : AdUnitMode

    /**
     * Live units in a debug build. Requires a [reason] and reports a diagnostic event each time a unit
     * is resolved. Intended for short fill checks on a registered test device.
     */
    public data class LiveUnitsInDebugBuild(val reason: String) : AdUnitMode
}

/** A missing id for the current platform disables that placement there. It is not an error. */
public data class AdUnitIds(val android: AdUnitId.Live?, val ios: AdUnitId.Live?)

/**
 * The ad unit ids for each placement and platform. Ids are validated once by [Builder], and problems
 * are collected rather than thrown.
 */
public class AdUnitRegistry internal constructor(
    private val live: Map<AdPlacementId, AdUnitIds>,
    internal val mode: AdUnitMode,
) {
    public fun resolve(
        placement: AdPlacementId,
        format: AdFormat,
        platform: AdPlatform,
    ): AdUnitId? = when (mode) {
        AdUnitMode.ForceTestUnits -> GoogleTestUnits.forFormat(platform, format)
        else -> live[placement]?.let { if (platform == AdPlatform.Android) it.android else it.ios }
    }

    public class Builder(private val mode: AdUnitMode) {
        private val entries: MutableMap<AdPlacementId, AdUnitIds> = mutableMapOf()
        private val problems: MutableList<AdConfigProblem> = mutableListOf()

        public fun unit(
            placement: AdPlacementId,
            android: String? = null,
            ios: String? = null,
        ): Builder {
            if (entries.containsKey(placement)) {
                problems += AdConfigProblem.DuplicatePlacement(placement)
            }
            entries[placement] = AdUnitIds(
                android = android.toLive(placement, AdPlatform.Android),
                ios = ios.toLive(placement, AdPlatform.Ios),
            )
            return this
        }

        public fun build(): AdUnitRegistryResult =
            AdUnitRegistryResult(AdUnitRegistry(entries.toMap(), mode), problems.toList())

        private fun String?.toLive(placement: AdPlacementId, platform: AdPlatform): AdUnitId.Live? {
            if (this == null) return null
            val parsed = AdUnitId.live(this)
            if (parsed == null) problems += AdConfigProblem.MalformedUnitId(placement, platform)
            return parsed
        }
    }

    public companion object {
        /** A registry in which every placement resolves to a Google demo unit. For tests. */
        public fun testOnly(): AdUnitRegistry = AdUnitRegistry(emptyMap(), AdUnitMode.ForceTestUnits)
    }
}

/** The registry plus everything wrong with the declaration that produced it. */
public data class AdUnitRegistryResult(
    val registry: AdUnitRegistry,
    val problems: List<AdConfigProblem>,
)

/**
 * The audience the app declares in its store listing. Required, because apps directed at children
 * cannot serve personalised ads.
 */
public data class AdAudienceConfig(
    val ageRestriction: AgeRestriction,
    val maxAdContentRating: MaxAdContentRating,
) {
    /** The SDK's age-restriction setting. */
    public enum class AgeRestriction {
        /** No restriction declared. Correct only for a genuinely general-audience app. */
        Unspecified,

        /** The app's audience includes children. Personalised ads are not served. */
        ChildDirected,

        /** Users below the age of consent in their jurisdiction. */
        Teen,
    }

    public enum class MaxAdContentRating { Unspecified, G, PG, T, MA }

    public companion object {
        /** A general-audience app with no age restriction declared. */
        public val GeneralAudience: AdAudienceConfig = AdAudienceConfig(
            ageRestriction = AgeRestriction.Unspecified,
            maxAdContentRating = MaxAdContentRating.Unspecified,
        )
    }
}
