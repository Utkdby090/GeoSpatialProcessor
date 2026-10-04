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
    implementation(libs.mapcompose) {
        // Its published runtime metadata names the macOS-arm64 Compose runtime; currentOs above already picks the right one.
        exclude(group = "org.jetbrains.compose.desktop", module = "desktop-jvm-macos-arm64")
    }
    implementation(libs.kotlinx.io.core)

    // --- Architecture: ViewModels + Koin DI ---
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.koin.core)
    implementation(libs.koin.compose)
    implementation(libs.koin.compose.viewmodel)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing) // Dispatchers.Main for viewModelScope
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.koin.test)
    testImplementation(libs.openpdf) // reads the exported PDFs back in tests
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
            // java.net.http is for the map's tile downloads.
            modules("java.sql", "java.naming", "jdk.unsupported", "java.management", "java.desktop", "java.net.http")
        }
    }
}

// Run from the repository root, as before modularisation, so relative paths such as
// logback's logs/geospatial-app.log land in the same place.
tasks.withType<JavaExec>().configureEach {
    workingDir = rootDir
}
