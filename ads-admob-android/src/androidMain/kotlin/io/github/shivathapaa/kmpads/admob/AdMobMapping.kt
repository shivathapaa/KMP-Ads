package io.github.shivathapaa.kmpads.admob

import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdValue
import com.google.android.gms.ads.AgeRestrictedTreatment
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.gms.ads.ResponseInfo
import io.github.shivathapaa.kmpads.config.AdAudienceConfig
import io.github.shivathapaa.kmpads.error.AdError
import io.github.shivathapaa.kmpads.event.AdRevenue
import io.github.shivathapaa.kmpads.event.RevenuePrecision

/** Maps an AdMob error code to an [AdError]. */
internal fun adMobErrorFor(code: Int): AdError = when (code) {
    AdRequest.ERROR_CODE_NO_FILL -> AdError.NoFill(code)
    AdRequest.ERROR_CODE_NETWORK_ERROR -> AdError.NetworkUnavailable(code)
    AdRequest.ERROR_CODE_INVALID_REQUEST -> AdError.InvalidRequest(code)
    AdRequest.ERROR_CODE_INTERNAL_ERROR -> AdError.Internal(code)
    else -> AdError.Internal(code)
}

internal fun AdValue.toAdRevenue(responseInfo: ResponseInfo?): AdRevenue = AdRevenue(
    valueMicros = valueMicros,
    currencyCode = currencyCode,
    precision = when (precisionType) {
        AdValue.PrecisionType.ESTIMATED -> RevenuePrecision.Estimated
        AdValue.PrecisionType.PUBLISHER_PROVIDED -> RevenuePrecision.PublisherProvided
        AdValue.PrecisionType.PRECISE -> RevenuePrecision.Precise
        else -> RevenuePrecision.Unknown
    },
    mediationNetwork = responseInfo?.loadedAdapterResponseInfo?.adSourceName,
)

/** Converts the audience declaration into the SDK's request configuration. */
internal fun AdAudienceConfig.toRequestConfiguration(
    testDeviceIds: List<String>,
): RequestConfiguration = RequestConfiguration.Builder()
    .setTestDeviceIds(testDeviceIds)
    .setAgeRestrictedTreatment(
        when (ageRestriction) {
            AdAudienceConfig.AgeRestriction.Unspecified -> AgeRestrictedTreatment.UNSPECIFIED
            AdAudienceConfig.AgeRestriction.ChildDirected -> AgeRestrictedTreatment.CHILD
            AdAudienceConfig.AgeRestriction.Teen -> AgeRestrictedTreatment.TEEN
        }
    )
    .setMaxAdContentRating(
        when (maxAdContentRating) {
            AdAudienceConfig.MaxAdContentRating.Unspecified -> ""
            AdAudienceConfig.MaxAdContentRating.G -> RequestConfiguration.MAX_AD_CONTENT_RATING_G
            AdAudienceConfig.MaxAdContentRating.PG -> RequestConfiguration.MAX_AD_CONTENT_RATING_PG
            AdAudienceConfig.MaxAdContentRating.T -> RequestConfiguration.MAX_AD_CONTENT_RATING_T
            AdAudienceConfig.MaxAdContentRating.MA -> RequestConfiguration.MAX_AD_CONTENT_RATING_MA
        }
    )
    .build()

internal fun newAdRequest(): AdRequest = AdRequest.Builder().build()
