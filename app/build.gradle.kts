// Desktop application: Compose UI, licensing/trial gate, packaging (ProGuard, Conveyor).
plugins {
    id("geospatial.kotlin-jvm")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.conveyor)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(project(":plugins:telecom"))

    // Pulls the correct native UI artifacts for the OS you build on (Windows for Conveyor builds).
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)
}

compose.desktop {
    application {
        mainClass = "com.geospatial.processing.MainKt"

        buildTypes.release.proguard {
            // ProGuard is ON for the final release build
            version.set(libs.versions.proguard)
            isEnabled.set(true)
            obfuscate.set(true)
            optimize.set(true)
            configurationFiles.from(project.file("proguard-rules.pro"))
        }
        nativeDistributions {
            // Keep the pre-modules package name (it was the root project name) for installers.
            packageName = rootProject.name
            modules("java.sql", "java.naming")
        }
    }
}

// Run from the repository root, as before modularisation, so relative paths such as
// logback's logs/geospatial-app.log land in the same place.
tasks.withType<JavaExec>().configureEach {
    workingDir = rootDir
}
