package io.github.shivathapaa.kmpads.storage

import io.github.shivathapaa.kmpads.config.AdPlacementId
import io.github.shivathapaa.kmpads.policy.PlacementCounters
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The persisted wire format. Internal; no public type is `@Serializable`. */
@Serializable
internal data class PersistedStateDto(
    val version: Int = CURRENT_VERSION,
    val firstLaunchAtMillis: Long,
    val sessionOrdinal: Int,
    val lastForegroundAtMillis: Long,
    val firstRunComplete: Boolean = false,
    val placements: Map<String, PersistedCountersDto> = emptyMap(),
) {
    internal companion object {
        const val CURRENT_VERSION: Int = 1
    }
}

@Serializable
internal data class PersistedCountersDto(
    val lastShownAtMillis: Long? = null,
    val lastDismissedAtMillis: Long? = null,
    val dayKey: Int = Int.MIN_VALUE,
    val shownToday: Int = 0,
    val shownEver: Int = 0,
)

/** What the runtime keeps across process death, decoded. */
internal data class PersistedState(
    val firstLaunchAtMillis: Long,
    val sessionOrdinal: Int,
    val lastForegroundAtMillis: Long,
    val firstRunComplete: Boolean,
    val counters: Map<AdPlacementId, PlacementCounters>,
)

internal object AdStateCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Decodes persisted state. A decode failure or an unknown version is treated as a fresh install,
     * which restarts warm-up.
     */
    fun decode(raw: String?, nowMillis: Long): PersistedState? {
        if (raw.isNullOrBlank()) return null
        val dto = runCatching { json.decodeFromString<PersistedStateDto>(raw) }.getOrNull()
            ?: return null
        if (dto.version != PersistedStateDto.CURRENT_VERSION) return null
        return PersistedState(
            firstLaunchAtMillis = dto.firstLaunchAtMillis.takeIf { it > 0 } ?: nowMillis,
            sessionOrdinal = dto.sessionOrdinal.coerceAtLeast(0),
            lastForegroundAtMillis = dto.lastForegroundAtMillis,
            firstRunComplete = dto.firstRunComplete,
            counters = dto.placements.mapKeys { AdPlacementId(it.key) }
                .mapValues { (_, counters) ->
                    PlacementCounters(
                        lastShownAtMillis = counters.lastShownAtMillis,
                        lastDismissedAtMillis = counters.lastDismissedAtMillis,
                        dayKey = counters.dayKey,
                        shownToday = counters.shownToday,
                        // Per-process by definition, so never restored.
                        shownThisSession = 0,
                        shownEver = counters.shownEver,
                    )
                },
        )
    }

    fun encode(state: PersistedState): String = json.encodeToString(
        PersistedStateDto(
            firstLaunchAtMillis = state.firstLaunchAtMillis,
            sessionOrdinal = state.sessionOrdinal,
            lastForegroundAtMillis = state.lastForegroundAtMillis,
            firstRunComplete = state.firstRunComplete,
            placements = state.counters.entries.associate { (placement, counters) ->
                placement.value to PersistedCountersDto(
                    lastShownAtMillis = counters.lastShownAtMillis,
                    lastDismissedAtMillis = counters.lastDismissedAtMillis,
                    dayKey = counters.dayKey,
                    shownToday = counters.shownToday,
                    shownEver = counters.shownEver,
                )
            },
        )
    )
}
