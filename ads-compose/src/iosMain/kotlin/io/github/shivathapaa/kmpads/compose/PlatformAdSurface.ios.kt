package io.github.shivathapaa.kmpads.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
    UIKitView(
        factory = { uiView },
        modifier = modifier,
        properties = UIKitInteropProperties(
            // Ads receive taps directly instead of waiting for a parent scroll container.
            interactionMode = UIKitInteropInteractionMode.NonCooperative,
            // Let VoiceOver traverse the SDK's own accessibility tree rather than Compose semantics.
            isNativeAccessibilityEnabled = true,
        ),
    )
}

/** Empty on iOS: the Swift adapter resolves its own presenting view controller. */
@Composable
internal actual fun rememberAdViewFactoryContext(): AdViewFactoryContext =
    remember { adViewFactoryContext() }
