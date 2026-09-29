# kmp-ads

[![Maven Central](https://img.shields.io/maven-central/v/io.github.shivathapaa.kmpads/ads-core)](https://central.sonatype.com/namespace/io.github.shivathapaa.kmpads)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

A Kotlin Multiplatform ads library for Android and iOS, with Compose Multiplatform ad slots and a
Google AdMob provider.

You declare placements and the policies they follow. The library decides whether each placement may
show, tells you why when it may not, and takes care of frequency caps, warm-up, consent gating and
persistence.

## Features

- **Placements and policies as data.** Frequency caps, warm-up, cooldowns and premium suppression
  are configuration, evaluated by a pure policy engine.
- **Named suppression reasons.** Every missing ad has a reason, such as
  `RequiredSignalFalse(ads.consent_obtained)` or `DailyCapReached(12)`.
- **Compose Multiplatform slots.** `AdBannerSlot` reserves its height before the ad loads, so the
  layout does not jump. `AdNativeSlot` renders through the SDK's own native ad view.
- **Every AdMob format.** Banner, native, interstitial, rewarded, rewarded interstitial and app open.
- **Consent built in.** Google's User Messaging Platform on Android, and the same flow through a
  Swift adapter on iOS. No ad is requested until consent resolves.
- **Safe test ads.** Debug builds resolve every placement to Google's demo units, and live ids are
  validated at startup.
- **No native interop on iOS.** The library links no ad SDK. Your app adds Google Mobile Ads with
  Swift Package Manager and implements one Swift protocol.
- **Testable.** `ads-testing` provides a fake provider, a controllable clock and an event recorder.

## Modules

| Artifact | Add to | Contents | Platforms |
|---|---|---|---|
| `ads-core` | `commonMain` | Placements, policies, `AdsSystem`, events, the iOS provider | Android, iOS, JVM |
| `ads-compose` | `commonMain` | `AdBannerSlot`, `AdNativeSlot`, `LocalAds`, `rememberAdDecision` | Android, iOS |
| `ads-admob-android` | `androidMain` | The AdMob provider and UMP consent for Android | Android |
| `ads-bridge-ios` | `iosMain` | The Swift protocol your iOS app implements | iOS |
| `ads-testing` | `commonTest` | `FakeAdProvider`, `MutableAdClock`, `RecordingEventSink` | Android, iOS, JVM |

## Installation

kmp-ads is published to Maven Central. The AdMob SDK comes from Google's Maven repository, so keep
both repositories:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

```toml
# gradle/libs.versions.toml
[versions]
kmpads = "0.2.0"

[libraries]
kmpads-core = { module = "io.github.shivathapaa.kmpads:ads-core", version.ref = "kmpads" }
kmpads-compose = { module = "io.github.shivathapaa.kmpads:ads-compose", version.ref = "kmpads" }
kmpads-admob-android = { module = "io.github.shivathapaa.kmpads:ads-admob-android", version.ref = "kmpads" }
kmpads-bridge-ios = { module = "io.github.shivathapaa.kmpads:ads-bridge-ios", version.ref = "kmpads" }
kmpads-testing = { module = "io.github.shivathapaa.kmpads:ads-testing", version.ref = "kmpads" }
```

```kotlin
// the shared module's build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kmpads.core)
            implementation(libs.kmpads.compose)
        }
        androidMain.dependencies {
            implementation(libs.kmpads.admob.android)
        }
        iosMain.dependencies {
            api(libs.kmpads.bridge.ios)
        }
        commonTest.dependencies {
            implementation(libs.kmpads.testing)
        }
    }
}
```

`ads-bridge-ios` is an `api` dependency because your iOS framework exports it. See
[iOS setup](docs/ios-bridge.md).

## Quick start

Declare your placements:

```kotlin
object Placements {
    val HomeBanner = AdPlacement(
        id = AdPlacementId("home_banner"),
        format = AdFormat.Banner,
        policy = AdPolicy.InlineDefault,
    )
    val LevelComplete = AdPlacement(
        id = AdPlacementId("level_complete"),
        format = AdFormat.Interstitial,
        policy = AdPolicy.FullScreenDefault,
    )
    val all = listOf(HomeBanner, LevelComplete)
}
```

Create the ads system. On Android:

```kotlin
class App : Application() {
    lateinit var ads: AdsSystem
    lateinit var consent: AdConsentController

    override fun onCreate() {
        super.onCreate()
        val adMob = AdMobAndroid.install(
            application = this,
            audience = AdAudienceConfig.GeneralAudience,
        )
        consent = adMob.consentController
        ads = AdsSystem.create(
            AdsConfig(
                placements = Placements.all,
                units = AdUnitRegistry.testOnly(),
                provider = adMob.provider,
                platform = AdPlatform.Android,
                audience = AdAudienceConfig.GeneralAudience,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                signals = mapOf(
                    AdSignalKey.ConsentObtained to consent.state.map { it.canRequestAds },
                    AdSignalKey.NetworkAvailable to flowOf(true),
                    AdSignalKey.UserIsPremium to flowOf(false),
                ),
                hostDeclaresDebugBuild = BuildConfig.DEBUG,
            )
        ).orNoOp()
    }
}
```

Gather consent once an Activity is resumed:

```kotlin
override fun onResume() {
    super.onResume()
    lifecycleScope.launch { (application as App).consent.gather() }
}
```

Provide the system at the root of your UI and place ads:

```kotlin
CompositionLocalProvider(LocalAds provides ads) {
    Scaffold(
        bottomBar = { AdBannerSlot(placement = Placements.HomeBanner.id) },
    ) { padding ->
        // Screen content
    }
}
```

Show a full-screen ad at a natural break:

```kotlin
scope.launch {
    when (val outcome = ads.show(Placements.LevelComplete.id)) {
        is AdShowOutcome.Completed -> outcome.reward?.let { grantReward(it) }
        is AdShowOutcome.Suppressed -> log("Not shown: ${outcome.reason}")
        AdShowOutcome.NotReady -> log("Nothing loaded yet")
        is AdShowOutcome.Failed -> log("Failed: ${outcome.error}")
    }
}
```

On iOS the ads system is built the same way, with `iosAdProvider(host)` and
`iosConsentController(host)`. The host is a small Swift adapter over Google Mobile Ads that you copy
from the sample. The [integration guide](docs/integration.md) covers both platforms step by step.

## Sample

The sample app shows every format, the live decision for each placement and the event stream. It
uses Google's demo ad units only.

```bash
./gradlew :sample:androidApp:installDebug   # Android
open sample/iosApp/iosApp.xcodeproj         # iOS, then run in Xcode
```

The iOS project resolves Google Mobile Ads through Swift Package Manager and builds the Kotlin
framework itself.

## Documentation

- [Integration guide](docs/integration.md): setup on Android and iOS, placements, signals, ad unit
  ids and testing.
- [iOS setup](docs/ios-bridge.md): the Swift adapter and the framework export.
- [Ad policy](docs/policy.md): placement rules, consent and invalid-traffic safety.

## Compatibility

| Requirement | Version |
|---|---|
| Kotlin | 2.4 or newer |
| Compose Multiplatform (`ads-compose`) | 1.12 or newer |
| Android | minSdk 24, compileSdk 35 or newer |
| iOS | Google Mobile Ads 13.7 and User Messaging Platform 3.1 or newer, through Swift Package Manager |

## License

```
Copyright 2026 Shiva Thapa

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
