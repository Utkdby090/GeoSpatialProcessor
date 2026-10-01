// Persistence: Exposed tables/repositories, project & workspace databases, .geox archives.
plugins {
    id("geospatial.kotlin-jvm")
}

dependencies {
    api(project(":core"))

    // Exposed types (Database, Table) appear in this module's public API.
    api(libs.exposed.core)
    api(libs.exposed.dao)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.sqlite.jdbc)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)
}
