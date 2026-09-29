# Integration guide

A step-by-step guide to adding kmp-ads to a Kotlin Multiplatform app on Android and iOS. Read
[policy.md](policy.md) alongside it when you get to placements, and [ios-bridge.md](ios-bridge.md)
when you get to the Swift adapter.

---

## 0. The mental model (read this first)

Three ideas shape the API.

**You declare placements, not ad calls.** A *placement* is one named opportunity — `"home_banner"`,
`"level_complete_interstitial"`. It carries a *format* (banner, interstitial, …) and a *policy* (how
often, under what conditions). You never write "show an ad here"; you ask a placement whether it may
show, and the policy engine answers.

**Policy is data, not code.** You name a boolean — a *signal* — pass it in as a `Flow<Boolean>`, and
list it on a placement. The library never needs to know what the boolean means.

**The ad SDK is injected, and on iOS it lives in your app.** `ads-core` names no network. Android
gets an AdMob provider from `ads-admob-android`; iOS reaches AdMob through a Swift adapter *in your
app target* that conforms to a Kotlin protocol. Nothing in the published library links GoogleMobileAds
on iOS — your Xcode project does, via SPM.

What you own vs. what the library owns:

| You own | The library owns |
|---|---|
| Placement ids, formats, policies | The decision: allow or a *named* suppression reason |
| Signal booleans (consent, premium, network, your own) | Frequency caps, warm-up, session/day counting |
| Ad unit ids (through a registry, never in source) | Fail-closed defaults, persistence, event stream |
| The Swift adapter (iOS) and the manifest id (Android) | The provider ports and the Compose slots |

---

## 1. Compatibility and prerequisites

| Requirement | Version |
|---|---|
| Kotlin | 2.4 or newer |
| Compose Multiplatform (`ads-compose`) | 1.12 or newer |
| Android compileSdk | 35 or newer |
| Android minSdk | 24 |
| JVM target | 11 or newer |
| GoogleMobileAds (iOS, SPM) | 13.7 or newer |
| GoogleUserMessagingPlatform (iOS, SPM) | 3.1 or newer, if the adapter imports `UserMessagingPlatform` |

`ads-compose` has no `iosX64` target, because Compose Multiplatform publishes none.

The five published artifacts, group `io.github.shivathapaa.kmpads`:

| Artifact | Add it to | Purpose |
|---|---|---|
| `ads-core` | `commonMain` | Placements, policy engine, `AdsSystem`, ports, the iOS provider |
| `ads-compose` | `commonMain` | `AdBannerSlot`, `AdNativeSlot`, `LocalAds`, `rememberAdDecision` |
| `ads-bridge-ios` | `iosMain` (`api`) | The Swift-implementable protocol. **Exported into your framework** |
| `ads-admob-android` | `androidMain` | The AdMob provider + UMP consent. Android only |
| `ads-testing` | `commonTest` | `FakeAdProvider`, `MutableAdClock`, `RecordingEventSink` |

---

## 2. Add the repositories

kmp-ads is on Maven Central, and the AdMob SDK is on Google's Maven repository. In
`settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

If your `google()` block uses a content filter, it must include `com.google.android.gms` and
`com.google.android.ump`.

---

## 3. Declare the dependencies in your version catalog

`gradle/libs.versions.toml`:

```toml
[versions]
kmpads = "0.2.0"

[libraries]
kmpads-core          = { module = "io.github.shivathapaa.kmpads:ads-core",          version.ref = "kmpads" }
kmpads-compose       = { module = "io.github.shivathapaa.kmpads:ads-compose",       version.ref = "kmpads" }
kmpads-bridge-ios    = { module = "io.github.shivathapaa.kmpads:ads-bridge-ios",    version.ref = "kmpads" }
kmpads-admob-android = { module = "io.github.shivathapaa.kmpads:ads-admob-android", version.ref = "kmpads" }
kmpads-testing       = { module = "io.github.shivathapaa.kmpads:ads-testing",       version.ref = "kmpads" }
```

Wire them into the shared module. **`implementation`, not `api`, for everything except the iOS
bridge** — the bridge must be `api` because it is `export()`ed into the framework header (§4):

```kotlin
sourceSets {
    commonMain.dependencies {
        implementation(libs.kmpads.core)
        implementation(libs.kmpads.compose)
    }
    androidMain.dependencies {
        implementation(libs.kmpads.admob.android)
    }
    iosMain.dependencies {
        api(libs.kmpads.bridge.ios)          // api, because export() requires it
    }
    commonTest.dependencies {
        implementation(libs.kmpads.testing)
    }
}
```

---

## 4. iOS: export the bridge and pass the host by constructor

Kotlin/Native adds everything reachable from an exported module to your Objective-C header.
`ads-bridge-ios` has no dependencies, so exporting it adds a handful of protocols. Do not export
anything that reaches `ads-core`.

In the module that produces the iOS framework:

```kotlin
kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"           // your framework name; the Swift names are invariant of it
            isStatic = true
            export(libs.kmpads.bridge.ios)    // not transitiveExport = true
        }
    }
}
```

Take the host as a **parameter** on your entry point and thread it down by constructor. Kotlin default
arguments are not exported to Objective-C, so Swift must pass something explicitly — `nil` included,
which is a legitimate ad-free build:

```kotlin
// commonMain or iosMain — the exported entry point
fun MainViewController(adsHost: KMPAdsHost?): UIViewController { /* build your Compose UI */ }
```

```swift
MainKt.MainViewController(adsHost: AdMobHost())   // or: adsHost: nil  → ad-free, still compiles
```

**Keep `ads-core` types out of any exported public API.** One public `val ads: AdsSystem` on an
exported class pulls `SharedFlow`, `StateFlow` and `CoroutineScope` into the header. Make the system
`internal` and expose primitive-only methods. `sample/composeApp/src/iosMain/…/SampleEntryPoint.kt`
shows the shape to copy.

---

## 5. Define placements and policies

A placement is an id + a format + a policy. Keep them in one object so every call site names the same
instance.

```kotlin
object Placements {
    val HomeBanner = AdPlacement(
        id = AdPlacementId("home_banner"),
        format = AdFormat.Banner,
        policy = AdPolicy.InlineDefault,          // gates + premium rule, no interval/session caps
    )

    val FeedNative = AdPlacement(
        id = AdPlacementId("feed_native"),
        format = AdFormat.Native,
        policy = AdPolicy.InlineDefault,
    )

    val LevelComplete = AdPlacement(
        id = AdPlacementId("level_complete_interstitial"),
        format = AdFormat.Interstitial,
        policy = AdPolicy.FullScreenDefault,      // 3-min interval, 3/session, 12/day, warm-up, cooldown
    )

    val RewardedRefill = AdPlacement(
        id = AdPlacementId("rewarded_refill"),
        format = AdFormat.Rewarded,
        policy = AdPolicy.FullScreenDefault,
    )

    val all = listOf(HomeBanner, FeedNative, LevelComplete, RewardedRefill)
}
```

The six formats: `Banner`, `Native`, `Interstitial`, `Rewarded`, `RewardedInterstitial`, `AppOpen`.
Banner and native are *inline* (rendered in your layout via a Compose slot); the other four are
*full-screen* (loaded then presented via `preload`/`show`).

The two policy presets, and what they gate:

- **`AdPolicy.InlineDefault`** — `requireAll = {ConsentObtained, NetworkAvailable}`,
  `suppressWhen = {UserIsPremium}`. No frequency caps (an inline surface refreshes itself).
- **`AdPolicy.FullScreenDefault`** — the same gates, plus `frequency` (3-minute interval, 3/session,
  12/day), a `cooldownAfterDismiss` of 30s, and a `warmUp` (not before the 3rd session, 1 day since
  install, 45s into the session).

Tune with `copy`, and state the reason at the site:

```kotlin
// Warm-up off because this is an onboarding reward the user explicitly asked for.
policy = AdPolicy.FullScreenDefault.copy(warmUp = WarmUp.None)
```

A host-specific condition ("audio is playing", "in a paid tournament") is **never an `if` in your ad
code**. It is a signal — see §7 — that you declare and list in the placement's `suppressWhen`.

---

## 6. Register ad unit ids (test vs live)

Unit ids never appear in your source. They reach the library through an `AdUnitRegistry`, and which
arm is read is decided by a *mode*.

**During development, and in every debug/CI build, use test units:**

```kotlin
val units = AdUnitRegistry.testOnly()   // every placement resolves to a Google demo unit
```

`testOnly()` is `AdUnitMode.ForceTestUnits`: the live map is never consulted, so a live id cannot
reach the SDK even if one is registered. AdMob enforces invalid traffic per account, and live
impressions from a debug build or a CI run can disable every app on the account.

**For a signed release, register live ids per placement, per platform:**

```kotlin
val result = AdUnitRegistry.Builder(AdUnitMode.LiveUnits)
    .unit(Placements.HomeBanner.id,   android = "ca-app-pub-…/…", ios = "ca-app-pub-…/…")
    .unit(Placements.LevelComplete.id, android = "ca-app-pub-…/…", ios = "ca-app-pub-…/…")
    // A placement with no id for the current platform is disabled there — not an error.
    .build()

// Surface every malformed id at once rather than one crash at a time.
if (result.problems.isNotEmpty()) log(result.problems)
val units = result.registry
```

`Builder.unit` validates each id: a malformed id or one from Google's demo publisher is rejected at
configuration time. Pull the strings from remote config or `BuildConfig`, never from a literal in
source.

---

## 7. Wire the signals

You tell the library that a boolean exists and what it is called, never what it means.

Four keys are well known. The built-in presets use the first three, so register all three:

| Key | Meaning you supply |
|---|---|
| `AdSignalKey.ConsentObtained` | the consent gate — **never a constant `true`** |
| `AdSignalKey.UserIsPremium` | your entitlement; `flowOf(false)` if the app has no premium tier |
| `AdSignalKey.NetworkAvailable` | connectivity |
| `AdSignalKey.AppInForeground` | app foreground state; reserved but not referenced by the default presets |

```kotlin
val signals: Map<AdSignalKey, Flow<Boolean>> = mapOf(
    AdSignalKey.ConsentObtained  to consentController.state.map { it.canRequestAds },
    AdSignalKey.UserIsPremium    to entitlements.map { it.isSubscribed },   // flowOf(false) without billing
    AdSignalKey.NetworkAvailable to connectivity.map { it.isOnline },
)
```

Declare your own for host conditions:

```kotlin
val AudioIsPlaying = AdSignalKey("myapp.audio_is_playing")
// …register it in the signals map, then list it on the placement:
policy = AdPolicy.FullScreenDefault.copy(suppressWhen = setOf(AdSignalKey.UserIsPremium, AudioIsPlaying))
```

`suppressWhen` is *OR-of-NOTs* (any listed signal being true suppresses); `requireAll` is an *AND*
(every listed signal must be true). A placement naming a signal you never registered fails closed —
it suppresses with `SignalMissing` rather than showing.

---

## 8. Build the AdsSystem

Everything comes together in one `AdsConfig`. No DI framework is imposed — one `single { }`, one
`@Provides`, or one `val`.

```kotlin
fun buildAds(
    provider: AdProvider,          // platform-specific (§9 Android, §10 iOS)
    platform: AdPlatform,          // AdPlatform.Android or AdPlatform.Ios
    scope: CoroutineScope,
    storage: AdStorage,            // your key-value store, ~6 lines (below)
    consentController: AdConsentController,
    entitlements: Flow<Boolean>,
    connectivity: Flow<Boolean>,
    isDebug: Boolean,
): AdsSystem = AdsSystem.create(
    AdsConfig(
        placements = Placements.all,
        units = if (isDebug) AdUnitRegistry.testOnly() else liveRegistry(),
        provider = provider,
        platform = platform,
        audience = AdAudienceConfig.GeneralAudience,   // REQUIRED, no default — see policy.md
        scope = scope,
        storage = storage,
        signals = mapOf(
            AdSignalKey.ConsentObtained  to consentController.state.map { it.canRequestAds },
            AdSignalKey.UserIsPremium    to entitlements,
            AdSignalKey.NetworkAvailable to connectivity,
        ),
        hostDeclaresDebugBuild = isDebug,
    )
).orNoOp { problems ->
    // Fail loudly in debug; run without ads in release.
    if (isDebug) error("ads misconfigured: $problems") else log(problems)
}
```

`AdsConfig` carries more optional knobs (`clock`, `dayKeyProvider`, `remoteConfig`, `extraRules`,
`sinks`, `logger`, `session`) — all have sane defaults; reach for them only when you have a reason.

**`audience` is required.** An app whose store listing declares a child audience may serve only
certified SDKs and no personalised ads. See [policy.md](policy.md).

The storage port is the whole persistence contract — read one string, write one string. Back it with
DataStore, SharedPreferences, `NSUserDefaults`, whatever you already have:

```kotlin
class MyAdStorage(private val prefs: SharedPreferences) : AdStorage {
    override suspend fun read(): String? = prefs.getString(AdStorage.SUGGESTED_KEY, null)
    override suspend fun write(value: String) { prefs.edit().putString(AdStorage.SUGGESTED_KEY, value).apply() }
}
```

Until the stored counters are restored, every placement is suppressed with `StateNotLoaded`.

---

## 9. Android host

`AndroidManifest.xml` — required; omitting it is a hard crash on SDK initialisation. Note the **tilde**
(`~`) in an application id vs. the slash (`/`) in a unit id:

```xml
<meta-data
    android:name="com.google.android.gms.ads.APPLICATION_ID"
    android:value="${admobAppId}" />   <!-- inject per build type via manifestPlaceholders -->
```

Use Google's demo application id `ca-app-pub-3940256099942544~3347511713` in debug. If you also ship
Firebase Analytics, which declares the same `AD_SERVICES_CONFIG` property, resolve the manifest merge:

```xml
<property android:name="android.adservices.AD_SERVICES_CONFIG"
          android:resource="@xml/gma_ad_services_config"
          tools:replace="android:resource" />
```

Install from `Application.onCreate`. This does not initialise the SDK; that happens on the first ad
request the policies allow, which by default is after consent:

```kotlin
class App : Application() {
    lateinit var ads: AdsSystem
    lateinit var consent: AdConsentController

    override fun onCreate() {
        super.onCreate()
        val adMob = AdMobAndroid.install(
            application = this,
            audience = AdAudienceConfig.GeneralAudience,   // required
            // muteAds defaults to true — leave it on in any app that plays audio.
            logger = myAdLogger,
        )
        consent = adMob.consentController
        ads = buildAds(
            provider = adMob.provider,
            platform = AdPlatform.Android,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            storage = MyAdStorage(getSharedPreferences("ads", MODE_PRIVATE)),
            consentController = consent,
            entitlements = billing.isSubscribed,
            connectivity = network.isOnline,
            isDebug = BuildConfig.DEBUG,
        )
    }
}
```

Two Android specifics:

- **Consent needs a resumed Activity.** Call `consent.gather()` from `Activity.onResume` (after any
  onboarding gate), never from `onCreate` or `Application.onCreate` — there is no presenter yet.
- The `com.google.android.gms.permission.AD_ID` permission is merged in by the SDK. Removing it is a
  per-app decision with real consequences (non-personalised ads only, different Data-safety
  declaration); the library never removes it for you.

---

## 10. iOS host

1. **SPM packages** on the app target: `swift-package-manager-google-mobile-ads.git`, plus
   `swift-package-manager-google-user-messaging-platform.git` if your adapter imports
   `UserMessagingPlatform` directly.
2. **Copy `sample/iosApp/iosApp/AdMobHost.swift`.** It is the reference `KMPAdsHost` implementation
   and compiles against any framework name; change only the framework `import` line.
3. **`Info.plist`:** `GADApplicationIdentifier` (with the `~`), `SKAdNetworkItems`, and
   `CADisableMinimumFrameDurationOnPhone` (Compose Multiplatform's own plist check aborts the process
   at launch without it).
4. **`INFOPLIST_KEY_NSUserTrackingUsageDescription` in both the Debug and the Release
   configurations.** Without it in Release, the tracking prompt never appears in production.
5. **Build the Kotlin collaborators inside your iOS entry point**, host passed in, everything
   `internal`:

```kotlin
class IosEntryPoint(host: KMPAdsHost?) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val consent = iosConsentController(host)
    private val ads = buildAds(
        provider = iosAdProvider(host, logger = AdLogger.NoOp),
        platform = AdPlatform.Ios,
        scope = scope,
        storage = UserDefaultsAdStorage(),
        consentController = consent,
        entitlements = entitlements,
        connectivity = connectivity,
        isDebug = /* your debug flag */,
    )

    fun viewController(): UIViewController = ComposeUIViewController { App(ads, consent) }

    fun startConsentFlow() {
        scope.launch {
            consent.gather()
            requestIosTrackingAuthorization(host)   // AFTER the form, only while the app is active
        }
    }
}
```

The Swift adapter resolves its own presenting view controller. See [ios-bridge.md](ios-bridge.md) for
the protocol contract.

---

## 11. Placing ads

### Provide the system once, at the composition root

```kotlin
CompositionLocalProvider(LocalAds provides ads) {
    AppContent()
}
```

### Banner (inline)

The slot reserves its height before requesting an ad, so a loading ad does not move the content
around it.

```kotlin
Scaffold(
    bottomBar = { AdBannerSlot(placement = Placements.HomeBanner.id) },
) { /* … */ }
```

`collapseWhenSuppressed` defaults to `true`: a suppressed slot takes no space and composes nothing.
For a bottom-anchored banner that should take no space until an ad has loaded, set
`collapseUntilLoaded = true`.

### Native (inline)

Renders through the SDK's own native ad view, which registers clicks and impressions. The
creative's size is unknown until it loads, so you size the slot:

```kotlin
val nativeStyle = NativeAdStyle(
    backgroundArgb = 0xFFF2F2F7, primaryTextArgb = 0xFF000000, secondaryTextArgb = 0xFF6E6E73,
    ctaBackgroundArgb = 0xFF0A84FF, ctaTextArgb = 0xFFFFFFFF,
    cornerRadiusDp = 12, headlineTextSizeSp = 16f, bodyTextSizeSp = 13f,
)

AdNativeSlot(
    placement = Placements.FeedNative.id,
    style = nativeStyle,
    modifier = Modifier.fillMaxWidth().height(320.dp),
    // collapseWhenSuppressed = false keeps the box and shows `placeholder` until the ad loads.
    placeholder = { Text("loading…", Modifier.align(Alignment.Center)) },
)
```

Colours are `0xAARRGGBB` longs and sizes are plain numbers.

### Full-screen (interstitial, rewarded, rewarded-interstitial, app-open)

`preload` warms one up, and `show` checks the policy itself. Both are `suspend`. `preload` is for
full-screen formats: banner and native slots load when composed, so `preload` returns
`AdPreloadOutcome.NotSupported` for them.

```kotlin
scope.launch {
    // Optional: warm it up ahead of the moment you want it.
    ads.preload(Placements.LevelComplete.id)
}

scope.launch {
    when (val outcome = ads.show(Placements.LevelComplete.id)) {
        is AdShowOutcome.Completed  -> outcome.reward?.let { grant(it) }   // reward is non-null only for rewarded formats
        is AdShowOutcome.Suppressed -> log("not shown: ${outcome.reason}") // a NAMED reason, not silence
        AdShowOutcome.NotReady      -> log("nothing loaded yet")
        is AdShowOutcome.Failed     -> log("failed: ${outcome.error}")
    }
}
```

App-open ads are conventionally shown on a cold-start / foreground return; `show` works from any
trigger. Rewarded and rewarded-interstitial deliver `Completed(reward)` — grant only on a non-null
reward.

### Read a decision to hide a whole section

When a suppression should collapse UI *around* the ad (a whole "sponsored" section), read the decision:

```kotlin
val decision by rememberAdDecision(Placements.FeedNative.id)
if (decision is AdDecision.Allow) {
    SectionHeader("Sponsored")
    AdNativeSlot(/* … */)
}
```

`AdDecision` is `Allow` or `Suppress(reason)`; the reason is a `SuppressionReason` (e.g.
`RequiredSignalFalse(ConsentObtained)`, `DailyCapReached(12)`, `ProviderLacksFormat`).

### Observe the event stream

```kotlin
LaunchedEffect(ads) {
    ads.events.collect { event -> analytics.record(event) }   // Loaded, Impression, Clicked, Revenue, Suppressed, …
}
```

Events carry no SDK strings — an `AdError` exposes only `networkCode: Int?` and `cause: Throwable?`.

---

## 12. Runtime ordering

```
1. consent.gather()            needs a RESUMED Activity / view controller
2. [iOS] requestIosTracking…   AFTER the consent form, and only while the app is active
3. SDK initialisation          automatic, on the first unsuppressed request
4. first ad
```

iOS ignores a tracking prompt requested during launch or over the consent form, and the status then
stays `notDetermined`.

Forward app lifecycle so session counting and app-open work:

```kotlin
ads.onAppForegrounded()   // and ads.onAppBackgrounded()
ads.notifyFirstRunComplete()   // lets WarmUp.requireFirstRunComplete become true
// ads.shutdown() on teardown if you own the scope
```

---

## 13. Going live — checklist

- [ ] Switch the registry from `testOnly()` to `Builder(AdUnitMode.LiveUnits)` with real ids, gated on
      your release flag.
- [ ] `hostDeclaresDebugBuild` is `true` only in debug builds, where asking for live units is
      reported as a configuration problem.
- [ ] Real `GADApplicationIdentifier` / manifest `APPLICATION_ID` for release.
- [ ] `audience` matches your store listing's target-audience declaration.
- [ ] `AdSignalKey.ConsentObtained` is wired to real consent state, never a constant `true`.
- [ ] Confirmed no `ca-app-pub-…` literal anywhere in your source.

---

## 14. Testing against it

`ads-testing` provides `FakeAdProvider`, `MutableAdClock`, `RecordingEventSink`. The policy engine
itself needs none of them — it is a pure function you can call directly.

Pass the `TestScope` itself as `AdsConfig.scope`, not `backgroundScope`: `advanceUntilIdle()` does not
run `backgroundScope` work, so every placement would stay suppressed with `StateNotLoaded`. Full-screen
loads and shows run on `Dispatchers.Main`, so tests that show ads set a test main dispatcher with
`Dispatchers.setMain(StandardTestDispatcher())`.

```kotlin
@Test fun banner_allowed_once_consented() = runTest {
    val ads = AdsSystem.create(
        AdsConfig(
            placements = Placements.all,
            units = AdUnitRegistry.testOnly(),
            provider = FakeAdProvider(),
            platform = AdPlatform.Android,
            audience = AdAudienceConfig.GeneralAudience,
            scope = this,                         // the TestScope, NOT backgroundScope
            signals = mapOf(AdSignalKey.ConsentObtained to flowOf(true), /* … */),
        )
    ).orNoOp()
    advanceUntilIdle()
    // assert on ads.decision(...) / a RecordingEventSink
}
```

---

## 15. Troubleshooting

| Symptom | Cause |
|---|---|
| `Could not find …:ads-core` | `mavenCentral()` missing from `dependencyResolutionManagement` (§2) |
| Every placement `Suppress(StateNotLoaded)` in a test | passed `backgroundScope` instead of the `TestScope` (§14) |
| Every placement `Suppress(RequiredSignalFalse(ads.consent_obtained))` | consent not gathered yet, or wired to nothing — this is the gate working |
| `Suppress(SignalMissing(...))` | a placement lists a signal not present in the `signals` map |
| `Suppress(ProviderLacksFormat)` | the platform's provider does not serve that format |
| Hard crash on Android launch | missing `APPLICATION_ID` meta-data, or a `/` where a `~` belongs |
| iOS aborts at launch | `CADisableMinimumFrameDurationOnPhone` missing from `Info.plist` |
| ATT prompt never appears | requested during launch / over the consent form, or not while active (§12) |
| Objective-C header is huge | an exported type exposes an `ads-core` type, or `transitiveExport = true` is set (§4) |
| The Swift adapter won't compile | reconcile against your GoogleMobileAds SPM version — see the `AdMobHost.swift` header |

---

## Where to go next

- [policy.md](policy.md) — what may show where, warm-up, frequency caps, consent, invalid-traffic
- [ios-bridge.md](ios-bridge.md) — the Swift adapter and the framework export
