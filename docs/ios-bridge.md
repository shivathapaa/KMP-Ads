# iOS setup

kmp-ads links no ad SDK on iOS. Your app adds Google Mobile Ads with Swift Package Manager and
implements `KMPAdsHost`, a Kotlin protocol exported from your framework, in Swift. The sample's
[`AdMobHost.swift`](../sample/iosApp/iosApp/AdMobHost.swift) is a complete implementation to copy.

## Export the bridge

Only `ads-bridge-ios` is exported. It has no dependencies, so it adds a handful of protocols to your
Objective-C header.

```kotlin
kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
            export(libs.kmpads.bridge.ios)
        }
    }
    sourceSets {
        iosMain.dependencies { api(libs.kmpads.bridge.ios) }          // export requires api
        commonMain.dependencies { implementation(libs.kmpads.core) }  // not api
    }
}
```

Keep `ads-core` types out of the public API of anything you export: every type reachable from an
exported class is added to the header. Build the ads system in an `internal` class and expose methods
that take and return primitives, as the sample's `SampleEntryPoint` does.

## Pass the host in

Take the host as a parameter of your iOS entry point and pass it down by constructor:

```kotlin
fun MainViewController(adsHost: KMPAdsHost?): UIViewController { /* build your UI */ }
```

```swift
MainKt.MainViewController(adsHost: AdMobHost())
```

Passing `nil` is supported: every request fails immediately with `KMPAdsProtocol.ERROR_CODE_NO_HOST`,
`canRequestAds` stays false and no ad view is created, so the app runs without ads.

## The protocol contract

- The library calls every `KMPAdsHost` method on the main thread, and the adapter must invoke every
  callback on the main thread.
- `protocolVersion()` must return `KMPAdsProtocol.VERSION`. If it does not, the library disables ads
  and logs an error. When a kmp-ads release changes the protocol, update your copy of the adapter
  from the sample (see [Upgrading the adapter](#upgrading-the-adapter)).
- Ad formats cross the bridge as the `FORMAT_` string constants in `KMPAdsProtocol`.
- Banners are sized by the library. `adaptiveBannerHeightPoints` is called first, synchronously, so
  the slot can reserve the height. `makeBanner` then receives a `BANNER_` sizing constant and that
  reserved height, and must build the banner at exactly that size. Both methods must use
  `largeAnchoredAdaptiveBanner(width:)` for anchored adaptive banners; any other pair of sizes leaves
  the creative and its slot at different heights.
- Call `onSurfaceLoaded` when the banner receives an ad. It drives the slot's load state
  (`collapseUntilLoaded`, `onLoadStateChanged`); without it a banner is reported as never loaded.
- The adapter resolves its own presenting view controller from the foreground window scene.
- Native ads are rendered in the SDK's `NativeAdView`, which registers clicks and impressions.

## Upgrading the adapter

A release that bumps `KMPAdsProtocol.VERSION` changes `KMPAdsHost`, so an adapter written for the
previous protocol no longer compiles. Replace your copy with the sample's `AdMobHost.swift` and
re-apply your framework `import`. If you have changed the adapter, apply the protocol's changes by
hand instead:

### Protocol 1 → 2

`makeBanner` gained `sizing` and `heightPoints`. Build the size from them, and switch
`adaptiveBannerHeightPoints` to the large adaptive size so the two agree:

```swift
public func adaptiveBannerHeightPoints(widthPoints: Double) -> Double {
    Double(largeAnchoredAdaptiveBanner(width: CGFloat(widthPoints)).size.height)
}

public func makeBanner(
    unitId: String,
    widthPoints: Double,
    sizing: String,
    heightPoints: Double,
    callback: any KMPAdsSurfaceCallback
) -> UIView {
    let size: AdSize
    switch sizing {
    case KMPAdsProtocol.shared.BANNER_ANCHORED_ADAPTIVE:
        size = largeAnchoredAdaptiveBanner(width: CGFloat(widthPoints))
    case KMPAdsProtocol.shared.BANNER_STANDARD:
        size = AdSizeBanner
    case KMPAdsProtocol.shared.BANNER_FIXED_HEIGHT:
        size = adSizeFor(cgSize: CGSize(width: widthPoints, height: heightPoints))
    default:
        callback.onSurfaceFailed(code: -1, message: "unknown banner sizing")
        return UIView()
    }
    // Unchanged from here: create the BannerView with `size`, set the delegate, load.
}
```

The banner delegate must call `onSurfaceLoaded` from `bannerViewDidReceiveAd`. The sample's adapter
already did; the library now relies on it for the banner's load state.

## App configuration

- `GADApplicationIdentifier` in `Info.plist`. The app crashes on initialisation without it.
- `SKAdNetworkItems` in `Info.plist`, with the current list from Google's AdMob iOS documentation.
- `CADisableMinimumFrameDurationOnPhone` in `Info.plist`, which Compose Multiplatform requires.
- `INFOPLIST_KEY_NSUserTrackingUsageDescription` in both the Debug and the Release configurations.
