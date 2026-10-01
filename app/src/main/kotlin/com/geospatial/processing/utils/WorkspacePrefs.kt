package com.geospatial.processing.utils

import java.io.File
import java.util.prefs.Preferences

object WorkspacePrefs {
    // Creates a dedicated preferences node for your application on the host OS
    private val prefs = Preferences.userRoot().node("com.geospatial.processing")
    private const val KEY_WORKSPACE_PATH = "last_workspace_path"

    fun saveWorkspace(path: String) {
        prefs.put(KEY_WORKSPACE_PATH, path)
    }

    fun getLastWorkspace(): File? {
        val path = prefs.get(KEY_WORKSPACE_PATH, null) ?: return null
        val dir = File(path)
        // Only return it if the directory actually still exists on the hard drive
        return if (dir.exists() && dir.isDirectory) dir else null
    }

    fun clearWorkspace() {
        prefs.remove(KEY_WORKSPACE_PATH)
    }
}