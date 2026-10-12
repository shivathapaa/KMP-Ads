# Registry

A snapshot of every version the build uses. The source of truth is `gradle/libs.versions.toml` (and
`gradle.properties` for the library itself); update this file in the same commit.

Every pin is the **floor a consumer must meet**, not the newest release. Raise one only when every
app that consumes the library can already build against it.

## Library coordinate

| | |
|---|---|
| group | `io.github.shivathapaa.kmpads` |
| version | 0.2.0 |
| bridge protocol (`KMPAdsProtocol.VERSION`) | 2 |
| artifacts | `ads-core`, `ads-compose`, `ads-bridge-ios`, `ads-admob-android`, `ads-testing` |
| repository | Maven Central |

## Build and dependency pins

| Dependency | Version | Notes |
|---|---|---|
| Kotlin | 2.4.20 | a consumer's Kotlin must be able to read this klib metadata |
| Android Gradle Plugin | 9.4.1 | |
| Gradle wrapper | 9.7.1 | |
| Compose Multiplatform | 1.12.1 | `ads-compose` |
| Compose Material 3 | 1.12.0-alpha03 | sample only; separate version line from Compose Multiplatform |
| kotlinx-coroutines | 1.11.0 | |
| kotlinx-serialization-json | 1.11.0 | internal: the persisted policy state |
| vanniktech maven-publish | 0.37.0 | publishing only |
| compileSdk | 37 | |
| minSdk | 24 | a library floor above an app's floor is a manifest-merger error |
| AAR `minCompileSdk` | 35 | below compileSdk, so a consumer can lag one SDK |
| JVM target | 11 | JVM-11 bytecode inlines into JVM 11 and 21 consumers; the reverse does not |
| play-services-ads | 25.5.0 | `ads-admob-android` |
| user-messaging-platform | 4.0.0 | `ads-admob-android` |
| androidx activity-compose | 1.13.0 | sample only |
| androidx lifecycle | 2.11.0 | sample only |

## iOS (resolved by Xcode, not Gradle)

| | Version |
|---|---|
| GoogleMobileAds SPM | 13.7.0 (`upToNextMajor`); has `largeAnchoredAdaptiveBanner(width:)`, which the adapter uses |
| GoogleUserMessagingPlatform SPM | 3.1.0 (`upToNextMajor`) |
| Sample deployment target | iOS 16.0 |

## Targets

| Module | android | iosArm64 | iosSimulatorArm64 | iosX64 | jvm |
|---|:-:|:-:|:-:|:-:|:-:|
| `ads-core` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `ads-bridge-ios` | | ✓ | ✓ | ✓ | |
| `ads-compose` | ✓ | ✓ | ✓ | | |
| `ads-admob-android` | ✓ | | | | |
| `ads-testing` | ✓ | ✓ | ✓ | ✓ | ✓ |

`ads-compose` has no `iosX64` because Compose Multiplatform publishes none, and no `jvm` because on
Compose Multiplatform that means desktop and Skiko. The sample has no `iosX64` either, so build it
for arm64 simulators only.

## Upgrade checklist

1. Bump `gradle/libs.versions.toml` and this file together.
2. Run the verification battery in [releasing.md](releasing.md#verify).
3. Read Google's release notes for deprecations on both platforms. Prefer the replacement API as soon
   as the pinned floor has it: a deprecated call is a warning in every consumer's build.
4. Re-check Google's demo ad unit ids and the SKAdNetwork identifier list; both change.
