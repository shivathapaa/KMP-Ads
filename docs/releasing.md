# Releasing

For the maintainer cutting a version. kmp-ads is published to **Maven Central** with the
`com.vanniktech.maven.publish` plugin, configured for every non-sample module in the root
`build.gradle.kts`. The publish itself runs in GitHub Actions when a GitHub Release is published.

**A version on Maven Central can never be changed or deleted.** Every step before the publish exists
to make that one irreversible step boring.

## Versioning

[Semantic Versioning](https://semver.org):

| Change | Bump |
|---|---|
| Bug fix, internal refactor | patch |
| New, source-compatible public API (a slot parameter, a provider, a sizing) | minor |
| **Any change to `ads-bridge-ios`** (`KMPAdsHost`, a callback, `KMPAdsProtocol`) | **major**, plus `KMPAdsProtocol.VERSION` |
| A source-incompatible change to any other public API | major |

Never publish the same version twice, anywhere. Gradle caches modules by coordinate, including from
`mavenLocal()` and `file://` repositories, so a republished version is silently ignored by anyone
who resolved it before.

The version appears in these places, all updated in the release commit:

| File | What |
|---|---|
| `gradle.properties` | `VERSION_NAME`, the value the build stamps on every module |
| `docs/registry.md` | the `version` row |
| `README.md`, `docs/integration.md` | the `kmpads = "…"` install snippets |
| `CHANGELOG.md` | the new section |

## Changing the iOS bridge

`ads-bridge-ios` is implemented in Swift inside every consuming app, so a change breaks code you do
not control. In one change:

1. Edit `KMPAdsHost.kt` / `KMPAdsCallbacks.kt`, staying inside what Objective-C can express: no
   `suspend`, `Flow`, generics, enums, overloads, default arguments, value classes or throwing
   functions, and `@ObjCName(name, exact = true)` on every type.
2. Bump `KMPAdsProtocol.VERSION`.
3. Update `sample/iosApp/iosApp/AdMobHost.swift`, the reference adapter.
4. Update the swift names in `checkObjCExport` (`gradle/guards.gradle.kts`).
5. Add a "Protocol N → N+1" section to [ios-bridge.md](ios-bridge.md#upgrading-the-adapter).
6. Major version bump; commit with `!` and a `BREAKING CHANGE:` footer.

## Verify

Run everything; use `set -o pipefail` so a failure is not hidden by a pipe.

```bash
set -o pipefail
./gradlew build check checkObjCExport          # every module, the guards, the Swift ABI contract
./gradlew :ads-core:linkDebugTestIosSimulatorArm64   # the Kotlin/Native test-link gate
./gradlew :ads-core:iosSimulatorArm64Test :ads-testing:iosSimulatorArm64Test
```

The Swift adapter is not built by Gradle. Build the sample in Xcode, or:

```bash
xcodebuild -project sample/iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build
```

(With `-destination 'generic/platform=iOS Simulator'`, also pass `ARCHS=arm64`, as CI does: the
sample has no `x86_64` simulator target.)

Then **run** the sample on both platforms. Building is not running: check that every format loads,
that each banner's creative matches its slot, and that the console shows no protocol mismatch.

### Inspect the artifacts

Publish to a throwaway directory, never to `~/.m2`:

```bash
./gradlew publishToMavenLocal -Dmaven.repo.local=/tmp/kmpads-m2
find /tmp/kmpads-m2/io/github/shivathapaa/kmpads -name '*.pom' | head
```

Check that every module and platform variant is there at the new version, with a sources jar, a
javadoc jar, a `.module` file and a POM carrying the name, description, URL, licence, developer and
SCM. The POM fields come from `gradle.properties` (`POM_*`) and each module's `description`.

## Publish

Publishing runs in GitHub Actions (`.github/workflows/publish.yml`) when a **GitHub Release is
published**. Pushing a tag alone publishes nothing, and neither does saving a draft release. The
workflow:

1. checks that the release tag, without a leading `v`, equals `VERSION_NAME`, and stops if not;
2. runs the same build, `checkObjCExport` and iOS sample build as CI;
3. runs `publishAndReleaseToMavenCentral` for every module, signed with the secrets of the
   `maven-central` environment.

Tags are the bare version (`1.0.0`); the GitHub Release is titled with a `v` (`v1.0.0`).

```bash
git commit -m "chore(release): X.Y.Z"
git tag -a X.Y.Z -m "kmp-ads X.Y.Z"
git push origin main X.Y.Z

# Publishing the release starts the publish workflow. Paste the version's CHANGELOG.md section as notes.
gh release create X.Y.Z --title "vX.Y.Z" --notes-file notes.md
gh run watch                                  # or follow it in the Actions tab
```

If the bridge protocol changed, put the "copy `AdMobHost.swift` again" instruction first in the
notes: a consumer will otherwise discover it as a compile error. Central takes a while to sync after
the workflow finishes; the badge in the README follows.

### Publishing from a machine instead

Only if the workflow is unavailable. Credentials stay out of the repository, in
`~/.gradle/gradle.properties`:

```properties
mavenCentralUsername=<Central Portal token user>
mavenCentralPassword=<Central Portal token password>
signingInMemoryKey=<ASCII-armoured private key>
signingInMemoryKeyId=<last 8 characters of the key id>
signingInMemoryKeyPassword=<key passphrase>
```

The build signs only when `signingInMemoryKey` is present, and Central rejects unsigned artifacts.
`./gradlew publishToMavenCentral` uploads for review in the Central Portal;
`./gradlew publishAndReleaseToMavenCentral` releases directly.

## Mistakes

A bad release cannot be removed. Fix forward with the next patch version, and say in its changelog
entry which version to skip.
