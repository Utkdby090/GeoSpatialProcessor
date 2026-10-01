// Electric-grid / telecom inspection plugin: schema, CSV import and PDF report generation.
// Depends only on :core (the plugin API); it never touches the database.
plugins {
    id("geospatial.kotlin-jvm")
}

dependencies {
    api(project(":core"))

    implementation(libs.slf4j.api)
    implementation(libs.commons.csv)
    implementation(libs.openpdf)
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)
}
