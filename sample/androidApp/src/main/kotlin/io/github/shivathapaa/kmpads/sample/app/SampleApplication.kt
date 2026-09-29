package io.github.shivathapaa.kmpads.sample.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.shivathapaa.kmpads.sample.SampleAds

class SampleApplication : Application() {
    lateinit var sampleAds: SampleAds
        private set

    override fun onCreate() {
        super.onCreate()

        // Installs the provider. The ad SDK is initialised later, on the first allowed request.
        sampleAds = SampleAds(this)

        // Foreground and background events drive sessions, which frequency caps and warm-up count.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = sampleAds.onAppForegrounded()

                override fun onStop(owner: LifecycleOwner) = sampleAds.onAppBackgrounded()
            }
        )
    }
}
