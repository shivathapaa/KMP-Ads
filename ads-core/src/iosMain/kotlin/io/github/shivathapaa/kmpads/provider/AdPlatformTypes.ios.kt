package io.github.shivathapaa.kmpads.provider

import platform.UIKit.UIView

/**
 * iOS needs no construction context: the Swift adapter resolves its own presenting view controller.
 */
public fun adViewFactoryContext(): AdViewFactoryContext = AdViewFactoryContext(null)

/** Wraps a `UIView` as an [AdPlatformView]. */
public fun UIView.asAdPlatformView(): AdPlatformView = AdPlatformView(this)

public fun AdPlatformView.uiViewOrNull(): UIView? = platform as? UIView
