import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension
import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
    base
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.androidKmpLibrary) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.mavenPublish) apply false
}

val libraryGroup: String = providers.gradleProperty("GROUP").get()
val libraryVersion: String = providers.gradleProperty("VERSION_NAME").get()

subprojects {
    val isPublished = !path.startsWith(":sample")

    if (isPublished) {
        group = libraryGroup
        version = libraryVersion
    }

    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<KotlinMultiplatformExtension> {
            applyDefaultHierarchyTemplate()

            if (isPublished) {
                explicitApi()
            }

            compilerOptions {
                freeCompilerArgs.addAll(
                    "-Xexpect-actual-classes",
                    "-opt-in=kotlin.experimental.ExperimentalObjCName",
                )
            }

            // Metadata compilations are excluded: they report warnings about dependency packaging.
            targets.matching { it.platformType != KotlinPlatformType.common }.configureEach {
                compilations.configureEach {
                    compileTaskProvider.configure {
                        compilerOptions { allWarningsAsErrors.set(true) }
                    }
                }
            }

            targets.configureEach {
                compilations.configureEach {
                    compileTaskProvider.configure {
                        compilerOptions {
                            if (this is KotlinJvmCompilerOptions) jvmTarget.set(JvmTarget.JVM_11)
                        }
                    }
                }
            }
        }
    }

    plugins.withId("com.android.kotlin.multiplatform.library") {
        extensions.configure<KotlinMultiplatformAndroidComponentsExtension> {
            finalizeDsl { android: KotlinMultiplatformAndroidLibraryExtension ->
                android.namespace = "$libraryGroup.${project.name.replace("-", "")}"
                android.compileSdk = libs.versions.android.compileSdk.get().toInt()
                android.minSdk = libs.versions.android.minSdk.get().toInt()
                android.aarMetadata {
                    minCompileSdk = libs.versions.android.aarMinCompileSdk.get().toInt()
                }
                android.packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
                android.optimization {
                    consumerKeepRules.publish = true
                    val keepRules = project.file("consumer-proguard-rules.pro")
                    if (keepRules.isFile) consumerKeepRules.file(keepRules)
                }
            }
        }
    }

    if (isPublished) {
        plugins.withId("org.jetbrains.kotlin.multiplatform") {
            apply(plugin = "com.vanniktech.maven.publish")

            extensions.configure<MavenPublishBaseExtension> {
                configure(KotlinMultiplatform(sourcesJar = SourcesJar.Sources()))
                publishToMavenCentral()

                // Sign only when a key is provided, so builds without credentials still publish locally.
                val hasSigningKey = providers.gradleProperty("signingInMemoryKey").isPresent ||
                    providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey").isPresent
                if (hasSigningKey) {
                    signAllPublications()
                }

                pom {
                    name.set(project.name)
                    description.set(provider { project.description })
                }
            }
        }
    }
}

apply(from = "gradle/guards.gradle.kts")
