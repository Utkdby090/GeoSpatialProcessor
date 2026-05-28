package com.geospatial.processing.utils

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ProjectArchiver {

    /**
     * Packages a project directory into a single .geox file for sharing.
     */
    fun exportProject(projectDir: File, destinationZip: File): Boolean {
        return try {
            FileOutputStream(destinationZip).use { fos ->
                ZipOutputStream(fos).use { zos ->
                    projectDir.walkTopDown().forEach { file ->
                        if (file.isFile) {
                            // Calculate the relative path so the folder structure is preserved in the zip
                            val entryName = projectDir.name + "/" + file.relativeTo(projectDir).path.replace("\\", "/")
                            zos.putNextEntry(ZipEntry(entryName))
                            FileInputStream(file).use { fis -> fis.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Unzips a .geox file into the user's current Master Workspace.
     */
    fun importProject(geoxFile: File, workspaceDir: File): File? {
        return try {
            var rootFolderName: String? = null

            FileInputStream(geoxFile).use { fis ->
                ZipInputStream(fis).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val newFile = File(workspaceDir, entry.name)

                        // Capture the root project folder name from the zip
                        if (rootFolderName == null) {
                            rootFolderName = entry.name.substringBefore("/")
                        }

                        if (entry.isDirectory) {
                            newFile.mkdirs()
                        } else {
                            newFile.parentFile?.mkdirs()
                            FileOutputStream(newFile).use { fos ->
                                zis.copyTo(fos)
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
            // Return the newly extracted project directory
            if (rootFolderName != null) File(workspaceDir, rootFolderName) else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}