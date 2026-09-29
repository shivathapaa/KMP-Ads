package io.github.shivathapaa.kmpads.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.github.shivathapaa.kmpads.provider.AdPlatformView
import io.github.shivathapaa.kmpads.provider.AdViewFactoryContext
import io.github.shivathapaa.kmpads.provider.androidViewOrNull
import io.github.shivathapaa.kmpads.provider.asAdViewFactoryContext

@Composable
internal actual fun PlatformAdSurface(view: AdPlatformView, modifier: Modifier) {
    val androidView = view.androidViewOrNull() ?: return
    AndroidView(
        factory = { androidView },
        modifier = modifier,
    )
}

/** The current Activity context from `LocalContext`, which Android ad views require. */
@Composable
internal actual fun rememberAdViewFactoryContext(): AdViewFactoryContext {
    val context = LocalContext.current
    return remember(context) { context.asAdViewFactoryContext() }
}
