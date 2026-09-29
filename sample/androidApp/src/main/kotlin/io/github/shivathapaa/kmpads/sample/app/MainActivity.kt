package io.github.shivathapaa.kmpads.sample.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.shivathapaa.kmpads.sample.SampleAppRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent { SampleAppRoot((application as SampleApplication).sampleAds) }
    }

    /**
     * Gathers consent. The form needs a resumed Activity, so this runs in `onResume`; gathering is
     * idempotent, so repeating it on every resume is safe.
     */
    override fun onResume() {
        super.onResume()
        (application as SampleApplication).sampleAds.startConsentFlow()
    }
}
