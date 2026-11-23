package io.github.shivathapaa.kmpads.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import io.github.shivathapaa.kmpads.provider.AdPlatformView
import io.github.shivathapaa.kmpads.provider.AdViewFactoryContext
import io.github.shivathapaa.kmpads.provider.adViewFactoryContext
import io.github.shivathapaa.kmpads.provider.uiViewOrNull

@Composable
internal actual fun PlatformAdSurface(view: AdPlatformView, modifier: Modifier) {
    val uiView = view.uiViewOrNull() ?: return
    val gate = remember(uiView) { AdTouchGate(uiView) }
    UIKitView(
        factory = { gate },
        // Every scroll frame moves the slot; the gate turns touches away until it is still.
        modifier = modifier.onGloballyPositioned { gate.onPositioned(it.positionInWindow()) },
        properties = UIKitInteropProperties(
            // Compose sees each touch first and hands it to the ad only if no scroll starts within
            // the delay. NonCooperative gave the ad every touch that began on it: the list could not
            // be scrolled from an ad, and a swipe that began on a banner opened it as a click.
            interactionMode = UIKitInteropInteractionMode.Cooperative(),
            // Let VoiceOver traverse the SDK's own accessibility tree rather than Compose semantics.
            isNativeAccessibilityEnabled = true,
        ),
    )
}

/** Empty on iOS: the Swift adapter resolves its own presenting view controller. */
@Composable
internal actual fun rememberAdViewFactoryContext(): AdViewFactoryContext =
    remember { adViewFactoryContext() }
