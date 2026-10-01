plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.conveyor)
}

group = "com.geospatial.processing"
version = "1.0.0"

repositories {
    mavenCentral()
    google()
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Pulls the correct native UI artifacts for the OS you build on (Windows for Conveyor builds).
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    // --- Database Layer (JetBrains Exposed + SQLite) ---
    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.sqlite.jdbc)
    implementation(libs.gson)
    implementation(libs.kotlinx.serialization.json)

    // --- Concurrency ---
    implementation(libs.kotlinx.coroutines.core)

    // --- Logging ---
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)

    // --- File Processing ---
    implementation(libs.commons.csv)
    implementation(libs.openpdf)
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)

    // --- Testing ---
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
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
            modules("java.sql", "java.naming")
        }
    }
}
