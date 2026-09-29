package io.github.shivathapaa.kmpads.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.shivathapaa.kmpads.config.AdPlacementId
import io.github.shivathapaa.kmpads.policy.AdDecision
import io.github.shivathapaa.kmpads.provider.AdViewFactoryContext
import io.github.shivathapaa.kmpads.provider.BannerAdHandle
import io.github.shivathapaa.kmpads.provider.BannerSizing
import io.github.shivathapaa.kmpads.provider.NativeAdHandle
import io.github.shivathapaa.kmpads.provider.NativeAdStyle
import io.github.shivathapaa.kmpads.runtime.AdsSystem
import io.github.shivathapaa.kmpads.runtime.NoOpAdsSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The [AdsSystem] for this composition, provided once at the app root. Defaults to [NoOpAdsSystem],
 * so previews and tests without a provider show no ads.
 */
public val LocalAds: ProvidableCompositionLocal<AdsSystem> =
    staticCompositionLocalOf { NoOpAdsSystem as AdsSystem }

/** The live decision for a placement, for UI that hides a whole section rather than just an ad. */
@Composable
public fun rememberAdDecision(
    placement: AdPlacementId,
    ads: AdsSystem = LocalAds.current,
): State<AdDecision> {
    val flow = remember(ads, placement) { ads.decision(placement) }
    return flow.collectAsState(initial = INITIAL_DECISION)
}

/**
 * An anchored adaptive banner.
 *
 * By default the slot reserves the banner's height before the request goes out, so a loading ad
 * does not move the content below it.
 *
 * @param collapseWhenSuppressed when true (the default), a suppressed slot takes no space and
 *   composes nothing.
 * @param onLoadStateChanged called with `true` once a creative has loaded and `false` while it has
 *   not (no fill, a failed request or a reload). Use it to hide your own decoration around the
 *   slot. Called with `false` when the slot leaves the composition.
 * @param collapseUntilLoaded when true, the slot has zero height until a creative loads instead of
 *   reserving the banner's height. Suits a bottom-anchored banner; leave it false for a banner
 *   between content, where the reserved height keeps the list from jumping.
 */
@Composable
public fun AdBannerSlot(
    placement: AdPlacementId,
    modifier: Modifier = Modifier,
    ads: AdsSystem = LocalAds.current,
    sizing: BannerSizing = BannerSizing.AnchoredAdaptive,
    collapseWhenSuppressed: Boolean = true,
    collapseUntilLoaded: Boolean = false,
    onLoadStateChanged: (isLoaded: Boolean) -> Unit = {},
    placeholder: @Composable BoxScope.() -> Unit = {},
) {
    val decision by rememberAdDecision(placement, ads)
    if (decision !is AdDecision.Allow && collapseWhenSuppressed) return

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val context = rememberAdViewFactoryContext()
        val widthDp = maxWidth.value.toInt()
        val reservedHeightDp = remember(ads, widthDp, sizing) {
            ads.reservedBannerHeightDp(context, widthDp, sizing)
        }
        if (reservedHeightDp <= 0) return@BoxWithConstraints

        val handle = rememberBannerHandle(ads, placement, widthDp, sizing, context)
        val view = handle?.view
        // A banner view exists before it has loaded, so collapse on the load signal.
        val loaded by (handle?.isLoaded ?: NOT_LOADED).collectAsState()
        NotifyLoadState(loaded, onLoadStateChanged)

        val boxHeight = if (collapseUntilLoaded && !loaded) 0.dp else reservedHeightDp.dp
        Box(Modifier.fillMaxWidth().height(boxHeight)) {
            if (view == null) placeholder() else PlatformAdSurface(view, Modifier.matchParentSize())
        }
    }
}

/** Creates one banner per (placement, width, sizing) and disposes it with the composition. */
@Composable
private fun rememberBannerHandle(
    ads: AdsSystem,
    placement: AdPlacementId,
    widthDp: Int,
    sizing: BannerSizing,
    context: io.github.shivathapaa.kmpads.provider.AdViewFactoryContext,
): BannerAdHandle? {
    var handle by remember { mutableStateOf<BannerAdHandle?>(null) }

    DisposableEffect(ads, placement, widthDp, sizing) {
        val created = ads.createBanner(context, placement, widthDp, sizing)
        created?.load()
        handle = created
        onDispose {
            created?.dispose()
            handle = null
        }
    }
    return handle
}

/**
 * A native ad, rendered in the provider's native ad view.
 *
 * The creative's size is unknown until it loads, so size the slot with [modifier].
 *
 * @param collapseWhenSuppressed when true (the default), a suppressed slot composes nothing. When
 *   false, [placeholder] fills the slot until a creative arrives.
 * @param onLoadStateChanged called with `true` once a creative has loaded and `false` while it has
 *   not (no fill, a failed request or a reload). Use it to hide your own decoration around the
 *   slot. Called with `false` when the slot leaves the composition.
 */
@Composable
public fun AdNativeSlot(
    placement: AdPlacementId,
    style: NativeAdStyle,
    modifier: Modifier = Modifier,
    ads: AdsSystem = LocalAds.current,
    collapseWhenSuppressed: Boolean = true,
    onLoadStateChanged: (isLoaded: Boolean) -> Unit = {},
    placeholder: @Composable BoxScope.() -> Unit = {},
) {
    val decision by rememberAdDecision(placement, ads)
    if (decision !is AdDecision.Allow && collapseWhenSuppressed) return

    val context = rememberAdViewFactoryContext()
    val view = rememberNativeHandle(ads, placement, style, context)?.view

    NotifyLoadState(view != null, onLoadStateChanged)

    Box(modifier) {
        if (view == null) placeholder() else PlatformAdSurface(view, Modifier.matchParentSize())
    }
}

/** Reports a slot's load state to `onLoadStateChanged`, and `false` when the slot is disposed. */
@Composable
private fun NotifyLoadState(isLoaded: Boolean, onLoadStateChanged: (Boolean) -> Unit) {
    val callback by rememberUpdatedState(onLoadStateChanged)
    LaunchedEffect(isLoaded) { callback(isLoaded) }
    DisposableEffect(Unit) { onDispose { callback(false) } }
}

/** Loads one native ad per (placement, style, context) and disposes it with the composition. */
@Composable
private fun rememberNativeHandle(
    ads: AdsSystem,
    placement: AdPlacementId,
    style: NativeAdStyle,
    context: AdViewFactoryContext,
): NativeAdHandle? {
    var handle by remember { mutableStateOf<NativeAdHandle?>(null) }
    val scope = rememberCoroutineScope()

    DisposableEffect(ads, placement, style, context) {
        var created: NativeAdHandle? = null
        val job = scope.launch {
            created = ads.loadNative(context, placement, style)
            handle = created
        }
        onDispose {
            job.cancel()
            created?.dispose()
            handle = null
        }
    }
    return handle
}

/** Suppressed until the first real decision arrives, so nothing flashes on the first frame. */
private val INITIAL_DECISION: AdDecision =
    AdDecision.Suppress(io.github.shivathapaa.kmpads.policy.SuppressionReason.StateNotLoaded)

/** Load state for a slot that has no handle yet. */
private val NOT_LOADED: StateFlow<Boolean> = MutableStateFlow(false)
