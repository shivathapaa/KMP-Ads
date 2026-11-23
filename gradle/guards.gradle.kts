val guardStamps: Provider<Directory> = layout.buildDirectory.dir("guards")
val repoRoot: File = rootDir

/** Line comments, block comments and KDoc. */
val commentPattern = Regex("""/\*[\s\S]*?\*/|//[^\n]*""")

fun sourceTree(vararg includes: String): ConfigurableFileTree = fileTree(rootDir) {
    includes.forEach { include(it) }
    exclude("**/build/**", "**/.gradle/**", "**/.kotlin/**")
    exclude("gradle/guards.gradle.kts")
}

/**
 * Fails if any module declares cinterop, linker options or CocoaPods, or if the repository holds a
 * `.def` file or a prebuilt native library. Each puts a native link dependency into a klib.
 */
val checkNoNativeInterop = tasks.register("checkNoNativeInterop") {
    group = "verification"
    description = "Fails on cinterop, linkerOpts, CocoaPods, .def files or native libraries."

    val buildScripts = sourceTree("**/*.gradle.kts")
    val nativeFiles = sourceTree("**/*.def", "**/*.a", "**/*.framework/**", "**/*.xcframework/**")
    val stamp = guardStamps.map { it.file("no-native-interop.txt") }
    val root = repoRoot
    val comments = commentPattern

    inputs.files(buildScripts).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(nativeFiles).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(stamp)

    doLast {
        // Any mention, not only a block: `cinterops.creating`, `cinterops.create(…)` and the
        // `native.cocoapods` plugin id all declare native interop.
        val forbidden = Regex("""\bcinterops?\b|\blinkerOpts\b|\bcocoapods\b""")
        val offenders = buildScripts.files
            .filter { forbidden.containsMatchIn(it.readText().replace(comments, "")) }
            .map { it.relativeTo(root).path }
            .plus(nativeFiles.files.map { "${it.relativeTo(root).path} (native file)" })

        check(offenders.isEmpty()) {
            "Native interop is not allowed in kmp-ads:\n" +
                offenders.joinToString("\n") { "  - $it" }
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}

/** Fails if `commonMain` references `AndroidView` or `UIKitView`. */
val checkNoPlatformInteropInCommon = tasks.register("checkNoPlatformInteropInCommon") {
    group = "verification"
    description = "Fails if commonMain references AndroidView or UIKitView."

    val commonSources = sourceTree("**/src/commonMain/**/*.kt")
    val stamp = guardStamps.map { it.file("no-platform-interop-in-common.txt") }
    val root = repoRoot
    val comments = commentPattern

    inputs.files(commonSources).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(stamp)

    doLast {
        val forbidden = Regex("""\b(AndroidView|UIKitView)\s*\(""")
        val offenders = commonSources.files
            .filter { forbidden.containsMatchIn(it.readText().replace(comments, "")) }
            .map { it.relativeTo(root).path }

        check(offenders.isEmpty()) {
            "Platform view interop must stay in androidMain/iosMain:\n" +
                offenders.joinToString("\n") { "  - $it" }
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}

/** Fails on a hardcoded AdMob id that does not belong to Google's demo publisher. */
val checkNoLiteralAdUnitIds = tasks.register("checkNoLiteralAdUnitIds") {
    group = "verification"
    description = "Fails on a hardcoded LIVE ca-app-pub id anywhere in the repository."

    // Build scripts and project files too: manifest placeholders, BuildConfig fields, xcconfig and
    // Xcode build settings are where app ids usually live. local.properties is per-machine and
    // gitignored, so it is not source.
    val sources = sourceTree(
        "**/src/**/*.kt",
        "**/src/**/*.xml",
        "**/*.plist",
        "**/*.swift",
        "**/*.gradle.kts",
        "**/*.properties",
        "**/*.xcconfig",
        "**/*.pbxproj",
    ).apply { exclude("**/local.properties") }
    val stamp = guardStamps.map { it.file("no-literal-ad-unit-ids.txt") }
    val root = repoRoot

    inputs.files(sources).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(stamp)

    doLast {
        val demoPublisher = "ca-app-pub-3940256099942544"
        val pattern = Regex("""ca-app-pub-\d{16}[/~]\d{10}""")

        val offenders = sources.files.mapNotNull { file ->
            val live = pattern.findAll(file.readText())
                .map { it.value }
                .filterNot { it.startsWith(demoPublisher) }
                .toSet()
            if (live.isEmpty()) null else "${file.relativeTo(root).path}: ${live.joinToString()}"
        }

        check(offenders.isEmpty()) {
            "Live AdMob ids found. Ids belong in a host-supplied AdUnitRegistry, never in source:\n" +
                offenders.joinToString("\n") { "  - $it" }
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}

/** Checks the Swift names exported in the sample framework's Objective-C header. */
val checkObjCExport = tasks.register("checkObjCExport") {
    group = "verification"
    description = "Asserts the exported Swift names in the sample framework's Objective-C header."

    dependsOn(":sample:composeApp:linkDebugFrameworkIosSimulatorArm64")

    val header = layout.projectDirectory.file(
        "sample/composeApp/build/bin/iosSimulatorArm64/debugFramework/" +
            "SampleAds.framework/Headers/SampleAds.h"
    )
    val stamp = guardStamps.map { it.file("objc-export.txt") }
    val comments = commentPattern
    outputs.file(stamp)

    doLast {
        val text = header.asFile.readText().replace(comments, "")

        val required = listOf(
            "@protocol KMPAdsHost",
            "@protocol KMPAdsSurfaceCallback",
            "@protocol KMPAdsShowCallback",
            "@protocol KMPAdsLoadCallback",
            "@protocol KMPAdsConsentCallback",
            "@protocol KMPAdsStartCallback",
            """swift_name("makeBanner(unitId:widthPoints:sizing:heightPoints:callback:)")""",
            """swift_name("showFullScreen(format:unitId:callback:)")""",
        )
        val forbidden = listOf(
            "Ads_bridge_ios",
            "SampleAdsKMPAdsHost",
            "Kotlinx_coroutines_core",
            "Ads_coreAdsSystem",
        )

        val problems = required.filterNot { it in text }.map { "MISSING:  $it" } +
            forbidden.filter { it in text }.map { "LEAKED:   $it" }

        check(problems.isEmpty()) {
            "Objective-C export contract broken in SampleAds.h:\n" +
                problems.joinToString("\n") { "  - $it" }
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}

tasks.named("check") {
    dependsOn(
        checkNoNativeInterop,
        checkNoPlatformInteropInCommon,
        checkNoLiteralAdUnitIds,
    )
}

// checkObjCExport links an iOS framework, so it runs separately on macOS.
