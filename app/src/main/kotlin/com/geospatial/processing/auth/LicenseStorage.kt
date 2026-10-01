package com.geospatial.processing.auth



import java.util.prefs.Preferences

object LicenseStorage {
    private val prefs = Preferences.userRoot().node("com.geospatial.app")
    private const val KEY_LICENSE = "saved_license_key"

    fun saveLicense(licenseString: String) {
        prefs.put(KEY_LICENSE, licenseString)
    }

    fun getLicense(): String? {
        return prefs.get(KEY_LICENSE, null)
    }

    fun clearLicense() {
        prefs.remove(KEY_LICENSE)
    }
}