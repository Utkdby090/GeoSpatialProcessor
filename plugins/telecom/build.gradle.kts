// Electric-grid / telecom inspection plugin: CSV import and PDF report generation.
plugins {
    id("geospatial.kotlin-jvm")
}

dependencies {
    api(project(":core"))
    // Temporary: the CSV/PDF services still talk to GeoRepository. Phase 1 stage 4 turns them into
    // plugin strategies that only depend on :core, and this dependency goes away.
    implementation(project(":data"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)
    implementation(libs.commons.csv)
    implementation(libs.openpdf)
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)
}
