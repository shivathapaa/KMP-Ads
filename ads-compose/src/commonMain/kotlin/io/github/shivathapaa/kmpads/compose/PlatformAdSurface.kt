package io.github.shivathapaa.kmpads.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.shivathapaa.kmpads.provider.AdPlatformView
import io.github.shivathapaa.kmpads.provider.AdViewFactoryContext

/**
 * Hosts a provider-created platform view. A view that is not a platform view here, such as a fake in
 * a preview, renders as an empty box.
 */
@Composable
internal expect fun PlatformAdSurface(view: AdPlatformView, modifier: Modifier)

/** The construction context a provider needs; empty on iOS. */
@Composable
internal expect fun rememberAdViewFactoryContext(): AdViewFactoryContext
