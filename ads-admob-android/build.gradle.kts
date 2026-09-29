plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
}

description = "Google AdMob provider for kmp-ads on Android."

kotlin {
    android {
        withHostTest { isReturnDefaultValues = true }
    }

    sourceSets {
        androidMain.dependencies {
            api(projects.adsCore)
            implementation(libs.play.services.ads)
            implementation(libs.user.messaging.platform)
            implementation(libs.kotlinx.coroutines.core)
        }
        getByName("androidHostTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
