rootProject.name = "geographicalSpatialDataProcessing"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

include(":core", ":data", ":plugins:telecom", ":app")
