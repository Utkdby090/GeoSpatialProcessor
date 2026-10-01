import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Shared setup for every module: Java 17 bytecode and JUnit 5 tests via kotlin("test").
plugins {
    id("org.jetbrains.kotlin.jvm")
}

group = "com.geospatial.processing"
version = "1.0.0"

// Deliberately NOT `kotlin { jvmToolchain(17) }`: with a toolchain, the Conveyor plugin imports that JDK
// into the package, which clashes with the Amazon Corretto JDK chosen in conveyor.conf.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    "testImplementation"(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
