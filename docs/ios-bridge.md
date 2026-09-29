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
  and reports a diagnostic event. When a kmp-ads release changes the protocol, update your copy of
  the adapter from the sample.
- Ad formats cross the bridge as the `FORMAT_` string constants in `KMPAdsProtocol`.
- The adapter resolves its own presenting view controller from the foreground window scene.
- Native ads are rendered in the SDK's `NativeAdView`, which registers clicks and impressions.

## App configuration

- `GADApplicationIdentifier` in `Info.plist`. The app crashes on initialisation without it.
- `SKAdNetworkItems` in `Info.plist`, with the current list from Google's AdMob iOS documentation.
- `CADisableMinimumFrameDurationOnPhone` in `Info.plist`, which Compose Multiplatform requires.
- `INFOPLIST_KEY_NSUserTrackingUsageDescription` in both the Debug and the Release configurations.
