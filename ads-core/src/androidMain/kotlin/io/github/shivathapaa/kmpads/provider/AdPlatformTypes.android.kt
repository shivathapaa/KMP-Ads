package io.github.shivathapaa.kmpads.provider

import android.content.Context
import android.view.View

/**
 * Wraps the context a provider builds views with. Pass the Activity context: ad views built with the
 * application context measure incorrectly and their clicks do not open.
 */
public fun Context.asAdViewFactoryContext(): AdViewFactoryContext = AdViewFactoryContext(this)

/** The Android context, or null on other platforms. */
public fun AdViewFactoryContext.androidContextOrNull(): Context? = platform as? Context

public fun View.asAdPlatformView(): AdPlatformView = AdPlatformView(this)

public fun AdPlatformView.androidViewOrNull(): View? = platform as? View
