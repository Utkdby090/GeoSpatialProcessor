plugins {
    kotlin("jvm") version "1.9.21"
    id("org.jetbrains.compose") version "1.5.11"
    // --- 1. THE CONVEYOR PLUGIN ---
    id("dev.hydraulic.conveyor") version "1.12"

    kotlin("plugin.serialization") version "1.9.21"
}

group = "com.geospatial.processing"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    google()
}

dependencies {
    // This single line handles pulling the correct Windows UI artifacts
    // for Conveyor when you build on your Windows machine.
    implementation(compose.desktop.currentOs)

    // --- Database Layer (JetBrains Exposed + SQLCipher) ---
    implementation("org.jetbrains.exposed:exposed-core:0.50.1")
    implementation("org.jetbrains.exposed:exposed-dao:0.50.1")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.50.1")
    implementation("org.jetbrains.exposed:exposed-java-time:0.50.1")
    implementation("io.github.willena:sqlite-jdbc:3.45.1.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    implementation("org.jetbrains.compose.material:material-icons-extended-desktop:1.6.0")

    // --- Concurrency ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")

    // --- Logging ---
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("ch.qos.logback:logback-classic:1.4.14")

    //proGuard
    // --- File Processing ---
    implementation("org.apache.commons:commons-csv:1.10.0")
    implementation("com.github.librepdf:openpdf:1.3.30")

    // --- Cryptography for OpenPDF (Required for ProGuard Verifier) ---
    implementation("org.bouncycastle:bcprov-jdk18on:1.77")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.77")
}

configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin" && requested.name.startsWith("kotlin-stdlib")) {
            useVersion("1.9.21")
        }
    }
}


// --- UNIFIED COMPOSE BLOCK ---
compose.desktop {
    application {
        mainClass = "com.geospatial.processing.MainKt"

        buildTypes.release.proguard {
            // ProGuard is ON for the final release build
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