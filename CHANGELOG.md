# Changelog

All notable changes to kmp-ads. The version scheme is [Semantic Versioning](https://semver.org); any
change to the `ads-bridge-ios` protocol is a major release.

## 2.0.0

### Upgrading

- **Every placement requires `AdSignalKey.ConsentObtained`**, including one declared without a
  policy. If you never registered the consent signal, every placement is now suppressed with
  `SignalMissing(ads.consent_obtained)`: map it from your consent controller.
- **Declare the same audience on both sides.** `AdsSystem.create` compares `AdsConfig.audience`
  with the provider's and reports `AdConfigProblem.AudienceMismatch` when they differ. On iOS, pass
  it to `iosAdProvider(host, audience = …)`, which defaults to the general audience. An exhaustive
  `when` over `AdConfigProblem` needs a branch for the new case.
- **iOS: update your copy of `AdMobHost.swift`.** `protocolVersion()` now returns the literal `2`
  instead of `KMPAdsProtocol.shared.VERSION`, which always matched and so hid an out-of-date
  adapter. The adapter also sets `ageRestrictedTreatment` instead of the deprecated age tags and no
  longer sets `paidEventHandler`, so it builds without deprecation warnings against Google Mobile
  Ads 13.13. The bridge protocol itself is unchanged.
- **Forward the background, not focus.** Call `onAppForegrounded()` and `onAppBackgrounded()` from
  `ProcessLifecycleOwner` ON_START and ON_STOP on Android, and from
  `UIApplication.willEnterForegroundNotification` and `didEnterBackgroundNotification` on iOS. If
  you copied the sample's SwiftUI `scenePhase` handler, replace it.
- **Call `AdConsentController.gather()` on every foreground until it succeeds.** A failed gather
  is now retried by the next call; a completed one is still not repeated.
- iOS ads are muted once the SDK starts, as on Android. Pass `muteAds = false` to `iosAdProvider`
  to keep ad audio.
- Every placement is suppressed until your `AdRemoteConfigSource` emits its first value. If the
  fetch can be slow, emit a cached configuration first.

### Fixed

- iOS: an inline ad took every touch that began on it. A list could not be scrolled from a banner
  or native ad, a swipe that began on a banner could open it as a click, and a tap meant to stop a
  fling went to the ad as a click. A scroll that starts on an ad now scrolls the list, and an ad
  accepts no touches until it has been still for 150 ms, so a tap on a moving ad only stops the
  list.
- Two `onAppForegrounded()` calls with no `onAppBackgrounded()` between them started a new session
  once 30 minutes had passed in the app, which advanced warm-up and reset the per-session caps. The
  sample's SwiftUI wiring did this after every system alert. Only time in the background now ends a
  session, and a repeated `onAppBackgrounded()` keeps the time the user left.
- A consent gather that failed, for example offline on a fresh install, was never retried in that
  process, so every placement, rewarded ads included, stayed suppressed until the app was killed.
  This affected both the iOS and the UMP controller.
- A placement declared without a policy, or with a policy that did not list `ConsentObtained`,
  could request ads before consent. The new `ConsentRule` requires consent for every placement.
- iOS: `AdsConfig.audience` was never read and `iosAdProvider` defaulted to the general audience,
  so a child-directed app that declared its audience only in `AdsConfig` started the SDK as a
  general-audience app.
- Two overlapping `show` calls, such as a double tap, could present one after the other and skip
  the cooldown, minimum interval and session cap. The policy is checked again just before
  presenting.
- With a scope on a dispatching dispatcher, such as `Dispatchers.Main`, the first banner could be
  requested before the SDK's age restriction and content rating were applied. Initialisation now
  starts before the banner is built.
- `AdBannerSlot` and `AdNativeSlot` with `collapseWhenSuppressed = false` never requested an ad if
  the placement was suppressed when they first composed, and kept a live ad on screen after the
  placement became suppressed, for example after consent was withdrawn or premium bought.
- A host `AdLogger` that threw could escape an SDK callback, which on iOS terminates the app.
  Loggers are now guarded like event sinks.
- A remote-config source that threw crashed the host's scope. It now resolves to
  `AdRemoteConfig.AllDisabled`, and placements stay suppressed until the first value arrives
  instead of using the permissive default.
- iOS: `KMPAdsHost.setAppMuted` was never called.
- Android: a banner created without an Activity context reported its width as its reserved height.
- `checkNoNativeInterop` missed `cinterops.creating`, `cinterops.create(…)` and the
  `native.cocoapods` plugin, and now also fails on committed `.a`, `.framework` and `.xcframework`
  files.
- `checkNoLiteralAdUnitIds` now scans build scripts, `.properties`, `.xcconfig` and `.pbxproj`
  files, where app ids usually live. `local.properties` is skipped.

### Changed

- iOS: a tap reaches an inline ad up to 150 ms later than before, while Compose checks whether it
  starts a scroll.
- Built with Kotlin 2.4.21, Compose Multiplatform 1.12.2 and kotlinx-serialization 1.13.0, which
  are now the floors for consumers. See [docs/registry.md](docs/registry.md).

### Sample

- The audience is declared once, as `SampleAudience`, and passed to both platform providers.
- iOS: lifecycle comes from the foreground and background notifications instead of `scenePhase`,
  and consent is gathered again on each foreground until it succeeds.
- iOS: resolves Google Mobile Ads 13.13.0. The minimum stays 13.7.

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
