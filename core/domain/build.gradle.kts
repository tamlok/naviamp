import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

val generatedNaviampVersionSources = layout.buildDirectory.dir("generated/naviampVersion/commonMain")
val generateNaviampVersion by tasks.registering {
    val versionFile = rootProject.layout.projectDirectory.file("VERSION")
    val buildNumberFile = rootProject.layout.projectDirectory.file("VERSION_CODE")
    val applicationIdFile = rootProject.layout.projectDirectory.file("APPLICATION_ID")
    val repositoryFile = rootProject.layout.projectDirectory.file("RELEASE_REPOSITORY")
    val applicationNameFile = rootProject.layout.projectDirectory.file("APPLICATION_NAME")
    val outputDir = generatedNaviampVersionSources
    inputs.file(versionFile)
    inputs.file(buildNumberFile)
    inputs.file(applicationIdFile)
    inputs.file(repositoryFile)
    inputs.file(applicationNameFile)
    outputs.dir(outputDir)
    doLast {
        val version = versionFile.asFile.readText().trim()
        val buildNumber = buildNumberFile.asFile.readText().trim()
        val escapedVersion = version.replace("\\", "\\\\").replace("\"", "\\\"")
        val applicationId = applicationIdFile.asFile.readText().trim().replace("\\", "\\\\").replace("\"", "\\\"")
        val repository = repositoryFile.asFile.readText().trim().replace("\\", "\\\\").replace("\"", "\\\"")
        val applicationName = applicationNameFile.asFile.readText().trim().replace("\\", "\\\\").replace("\"", "\\\"")
        val packageDir = outputDir.get().file("app/naviamp/domain/network").asFile
        packageDir.mkdirs()
        packageDir.resolve("NaviampClientIdentity.kt").writeText(
            """
            package app.naviamp.domain.network

            const val NaviampAppVersion = "$escapedVersion"
            const val NaviampAppBuildNumber = "$buildNumber"
            const val NaviampApplicationId = "$applicationId"
            const val NaviampReleaseRepository = "$repository"
            const val NaviampClientName = "$applicationName"
            const val NaviampUserAgent = "$applicationName/$escapedVersion"
            const val NaviampProjectUserAgent = "$applicationName/$escapedVersion (https://github.com/$repository)"
            """.trimIndent(),
        )
    }
}

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
}

kotlin {
    android {
        namespace = "app.naviamp.domain"
        compileSdk = 37
        minSdk = 26
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        withHostTest {}
    }
    jvm()
    iosArm64()
    iosSimulatorArm64()
    val tvosEnabled = providers.gradleProperty("naviamp.tvos.enabled").orNull == "true"
    if (tvosEnabled) {
        tvosArm64()
        tvosSimulatorArm64()
        applyDefaultHierarchyTemplate()
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generatedNaviampVersionSources)
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.core)
            }
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }

        androidMain.dependencies {
            implementation(libs.ktor.client.cio)
        }

        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        if (tvosEnabled) getByName("tvosMain").dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

tasks.withType<KotlinCompilationTask<*>>().configureEach {
    dependsOn(generateNaviampVersion)
}
