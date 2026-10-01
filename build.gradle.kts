// Root project: no code. Modules and their direction of dependency:
//   :app -> :plugins:telecom -> :data -> :core
//   :app -> :data, :core
// Plugins are declared here once (apply false) so every module shares the same classloader.
// The Kotlin JVM plugin comes from buildSrc (convention plugin `geospatial.kotlin-jvm`).
plugins {
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.conveyor) apply false
}
