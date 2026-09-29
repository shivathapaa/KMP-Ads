plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.kotlinSerialization)
}

description = "Placements, the policy engine and the AdsSystem runtime for Kotlin Multiplatform ads."

kotlin {
    android {
        withHostTest {
            isReturnDefaultValues = true
        }
    }

    jvm {
        compilerOptions {
            freeCompilerArgs.add("-Xjdk-release=11")
        }
    }

    iosArm64()
    iosSimulatorArm64()
    iosX64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        iosMain.dependencies {
            api(projects.adsBridgeIos)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
