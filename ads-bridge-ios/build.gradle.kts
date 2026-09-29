plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

description = "Swift-implementable ad host protocols for kmp-ads on iOS."

kotlin {
    iosArm64()
    iosSimulatorArm64()
    iosX64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
