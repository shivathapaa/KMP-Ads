package io.github.shivathapaa.kmpads.admob

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * Tracks the current foreground Activity, which full-screen ads and consent forms present into.
 * Holds it weakly and returns null when there is none.
 */
internal class ForegroundActivityTracker : Application.ActivityLifecycleCallbacks {
    private var reference: WeakReference<Activity> = WeakReference(null)

    val current: Activity?
        get() = reference.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    override fun onActivityResumed(activity: Activity) {
        reference = WeakReference(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (reference.get() === activity) reference = WeakReference(null)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?): Unit = Unit

    override fun onActivityStarted(activity: Activity): Unit = Unit

    override fun onActivityPaused(activity: Activity): Unit = Unit

    override fun onActivityStopped(activity: Activity): Unit = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle): Unit = Unit
}
