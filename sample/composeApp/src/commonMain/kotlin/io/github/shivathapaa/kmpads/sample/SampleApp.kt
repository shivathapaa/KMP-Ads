package io.github.shivathapaa.kmpads.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.shivathapaa.kmpads.compose.AdBannerSlot
import io.github.shivathapaa.kmpads.compose.AdNativeSlot
import io.github.shivathapaa.kmpads.compose.LocalAds
import io.github.shivathapaa.kmpads.compose.rememberAdDecision
import io.github.shivathapaa.kmpads.config.AdPlacementId
import io.github.shivathapaa.kmpads.consent.AdConsentController
import io.github.shivathapaa.kmpads.event.AdEvent
import io.github.shivathapaa.kmpads.policy.AdDecision
import io.github.shivathapaa.kmpads.provider.NativeAdStyle
import io.github.shivathapaa.kmpads.runtime.AdsSystem
import kotlinx.coroutines.launch

/** The sample screen: every format, the live policy decisions and the event stream. */
@Composable
internal fun SampleApp(ads: AdsSystem, consent: AdConsentController) {
    MaterialTheme {
        CompositionLocalProvider(LocalAds provides ads) {
            SampleContent(consent)
        }
    }
}

@Composable
private fun SampleContent(consent: AdConsentController) {
    val ads = LocalAds.current
    val scope = rememberCoroutineScope()
    val log = remember { mutableStateListOf<String>() }
    val consentState by consent.state.collectAsState()

    fun act(label: String, block: suspend () -> Any) {
        scope.launch { log.add(0, "$label -> ${block()}") }
    }

    LaunchedEffect(ads) {
        ads.events.collect { event -> log.add(0, event.describe()) }
    }

    Scaffold(
        bottomBar = {
            // The banner sits in the bottom bar and reserves its height before an ad loads.
            AdBannerSlot(placement = SamplePlacements.Banner.id)
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("kmp-ads sample", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "All placements use Google's demo ad units. Nothing here can produce a billable " +
                        "impression.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // The live decision for each format, including the suppression reason.
            item { SectionLabel("decisions") }
            item { DecisionRow("banner", SamplePlacements.Banner.id) }
            item { DecisionRow("interstitial", SamplePlacements.Interstitial.id) }
            item { DecisionRow("rewarded", SamplePlacements.Rewarded.id) }
            item { DecisionRow("rewarded interstitial", SamplePlacements.RewardedInterstitial.id) }
            item { DecisionRow("app open", SamplePlacements.AppOpen.id) }
            item { DecisionRow("native", SamplePlacements.Native.id) }

            // Full-screen formats are preloaded, then shown.
            item { SectionLabel("full-screen") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { act("preload") { ads.preload(SamplePlacements.Interstitial.id) } }) {
                        Text("Preload")
                    }
                    Button(onClick = { act("show") { ads.show(SamplePlacements.Interstitial.id) } }) {
                        Text("Interstitial")
                    }
                    Button(onClick = { act("show") { ads.show(SamplePlacements.Rewarded.id) } }) {
                        Text("Rewarded")
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { act("show") { ads.show(SamplePlacements.RewardedInterstitial.id) } }) {
                        Text("Rew. Int.")
                    }
                    Button(onClick = { act("show") { ads.show(SamplePlacements.AppOpen.id) } }) {
                        Text("App Open")
                    }
                }
            }

            // collapseWhenSuppressed = false keeps the card visible; apps usually keep the default.
            item { SectionLabel("native (inline)") }
            item {
                AdNativeSlot(
                    placement = SamplePlacements.Native.id,
                    style = SampleNativeStyle,
                    modifier = Modifier.fillMaxWidth().height(320.dp),
                    collapseWhenSuppressed = false,
                    placeholder = {
                        Text(
                            "loading native ad…",
                            modifier = Modifier.align(Alignment.Center),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                )
            }

            item { HorizontalDivider() }
            item {
                Text(
                    "consent: canRequestAds=${consentState.canRequestAds}, " +
                        "privacyOptionsRequired=${consentState.privacyOptionsRequired}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { scope.launch { consent.gather() } }) {
                        Text("Gather consent")
                    }
                    // An app shows this only when privacyOptionsRequired is true.
                    OutlinedButton(onClick = { scope.launch { consent.showPrivacyOptions() } }) {
                        Text("Privacy options")
                    }
                }
            }

            item { HorizontalDivider() }
            item { SectionLabel("events") }
            items(log) { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall)
}

/** Native ad styling: `0xAARRGGBB` colours and dp/sp sizes. */
private val SampleNativeStyle: NativeAdStyle = NativeAdStyle(
    backgroundArgb = 0xFFF2F2F7,
    primaryTextArgb = 0xFF000000,
    secondaryTextArgb = 0xFF6E6E73,
    ctaBackgroundArgb = 0xFF0A84FF,
    ctaTextArgb = 0xFFFFFFFF,
    cornerRadiusDp = 12,
    headlineTextSizeSp = 16f,
    bodyTextSizeSp = 13f,
)

@Composable
private fun DecisionRow(label: String, placement: AdPlacementId) {
    val decision by rememberAdDecision(placement)
    Card(modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.padding(12.dp), contentAlignment = Alignment.CenterStart) {
            Text(
                text = "$label: " + when (val current = decision) {
                    AdDecision.Allow -> "allowed"
                    is AdDecision.Suppress -> "suppressed — ${current.reason}"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun AdEvent.describe(): String {
    val name = this::class.simpleName ?: "AdEvent"
    val detail = when (this) {
        is AdEvent.Suppressed -> " ${reason}"
        is AdEvent.LoadFailed -> " ${error}"
        is AdEvent.ShowFailed -> " ${error}"
        is AdEvent.RewardEarned -> " ${reward}"
        is AdEvent.Revenue -> " ${revenue.valueMicros}µ ${revenue.currencyCode}"
        else -> ""
    }
    return "${placement.value}/$name$detail"
}
