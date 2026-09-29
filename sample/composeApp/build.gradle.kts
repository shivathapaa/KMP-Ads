plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

description = "Sample app for kmp-ads. Not published."

kotlin {
    android {
        namespace = "io.github.shivathapaa.kmpads.sample"
    }

    listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "SampleAds"
            isStatic = true
            export(projects.adsBridgeIos)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.adsCore)
            implementation(projects.adsCompose)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.kotlinx.coroutines.core)
        }
        iosMain.dependencies {
            api(projects.adsBridgeIos)
        }
        androidMain.dependencies {
            implementation(projects.adsAdmobAndroid)
        }
    }
}
