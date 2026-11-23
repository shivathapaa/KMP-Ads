package io.github.shivathapaa.kmpads.admob

import io.github.shivathapaa.kmpads.config.AdFormat
import io.github.shivathapaa.kmpads.config.AdPlacementId
import io.github.shivathapaa.kmpads.config.AdPlatform
import io.github.shivathapaa.kmpads.config.GoogleTestUnits
import io.github.shivathapaa.kmpads.error.AdError
import io.github.shivathapaa.kmpads.error.AdLogger
import io.github.shivathapaa.kmpads.event.AdRevenue
import io.github.shivathapaa.kmpads.provider.AdViewFactoryContext
import io.github.shivathapaa.kmpads.provider.BannerAdListener
import io.github.shivathapaa.kmpads.provider.BannerAdRequest
import io.github.shivathapaa.kmpads.provider.BannerSizing
import kotlin.test.Test
import kotlin.test.assertEquals

class AdMobBannerLoaderTest {
    @Test
    fun a_banner_without_an_activity_context_reserves_no_height() {
        val handle = AdMobBannerLoader(AdLogger.NoOp).createBanner(
            context = AdViewFactoryContext.stub(),
            request = BannerAdRequest(
                placement = AdPlacementId("banner"),
                unitId = GoogleTestUnits.forFormat(AdPlatform.Android, AdFormat.Banner),
                widthDp = 360,
                sizing = BannerSizing.AnchoredAdaptive,
            ),
            listener = NoOpBannerListener,
        )

        // With no context there is no banner to size, so nothing is reserved.
        assertEquals(0, handle.reservedHeightDp)
    }

    private object NoOpBannerListener : BannerAdListener {
        override fun onLoaded(): Unit = Unit
        override fun onLoadFailed(error: AdError): Unit = Unit
        override fun onImpression(): Unit = Unit
        override fun onClicked(): Unit = Unit
        override fun onRevenue(revenue: AdRevenue): Unit = Unit
    }
}
