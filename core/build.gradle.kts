// Domain models, the plugin API and image resolution. Pure Kotlin: no Compose, no database.
plugins {
    id("geospatial.kotlin-jvm")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.kotlinx.serialization.json)
    implementation(libs.metadata.extractor)
}
