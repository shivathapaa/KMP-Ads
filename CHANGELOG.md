# Changelog

All notable changes to kmp-ads. The version scheme is [Semantic Versioning](https://semver.org); any
change to the `ads-bridge-ios` protocol is a major release.

## 1.0.0

### Upgrading

- **iOS: copy `AdMobHost.swift` again.** The bridge protocol is now version 2, so an adapter written
  for 0.x does not compile. Replace it with the sample's adapter, or apply the changes by hand from
  [Upgrading the adapter](docs/ios-bridge.md#upgrading-the-adapter).
- Android needs no changes.

### Breaking

- `KMPAdsHost.makeBanner` takes `sizing` and `heightPoints`, so the Swift adapter builds the banner
  the slot asked for. `KMPAdsProtocol.VERSION` is `2`, and `KMPAdsProtocol` adds the
  `BANNER_ANCHORED_ADAPTIVE`, `BANNER_STANDARD` and `BANNER_FIXED_HEIGHT` constants.

### Fixed

- iOS: `BannerSizing.Standard320x50` and `BannerSizing.FixedHeight` were ignored. The adapter always
  built an anchored adaptive banner while the slot reserved 50 or the fixed height, so the creative
  and its slot had different heights.
- iOS: a banner was always reported as loaded, so `collapseUntilLoaded` and `onLoadStateChanged` did
  nothing. The load state now follows the ad, and a failed refresh keeps a loaded banner loaded, as
  on Android.

### Changed

- iOS: anchored adaptive banners use Google's large anchored adaptive size, as Android already did.
  The previous size API is deprecated in Google Mobile Ads 13.7. iOS banners can be taller than in
  0.x for the same width.

### Sample

- A "banner sizes" section shows `Standard320x50` and `FixedHeight` alongside the adaptive bottom
  banner.
- The iOS sample logs to the Xcode console.

## 0.2.0

First release on Maven Central.
