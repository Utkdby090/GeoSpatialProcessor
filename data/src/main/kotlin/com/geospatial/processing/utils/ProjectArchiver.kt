package com.geospatial.processing.utils

import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object ProjectArchiver {

    private val log = LoggerFactory.getLogger(ProjectArchiver::class.java)

    sealed class ImportResult {
        data class Success(val projectDir: File) : ImportResult()
        data class Failure(val reason: String) : ImportResult()
    }

    /** Files that must never be shipped inside a .geox (live SQLite side-files, OS junk). */
    private val EXCLUDED_SUFFIXES = listOf("-wal", "-shm", "-journal", ".tmp")
    private val EXCLUDED_NAMES = setOf("thumbs.db", ".ds_store", "desktop.ini")

    private const val MAX_ENTRIES = 500_000
    private const val DISK_SAFETY_MARGIN_BYTES = 512L * 1024 * 1024 // keep 512 MB free
    private const val COPY_BUFFER = 64 * 1024

    /**
     * Packages a project directory into a single .geox file.
     * The project must be CLOSED (not the active database) when this runs.
     */
    fun exportProject(projectDir: File, destinationZip: File): Boolean {
        // A ".tmp" name is never packed (see isExcluded), even when the destination lies inside the project folder.
        val tmp = SafeFiles.temporaryNextTo(destinationZip)
        val destination = destinationZip.absoluteFile.canonicalFile
        return try {
            destination.parentFile?.mkdirs()
            ZipOutputStream(tmp.outputStream().buffered()).use { zos ->
                projectDir.walkTopDown()
                    .filter { it.isFile && !isExcluded(it) && it.canonicalFile != destination }
                    .forEach { file ->
                        val relative = file.relativeTo(projectDir).invariantSeparatorsPath
                        zos.putNextEntry(ZipEntry("${projectDir.name}/$relative"))
                        FileInputStream(file).use { it.copyTo(zos, COPY_BUFFER) }
                        zos.closeEntry()
                    }
            }
            // Replace the destination only now that the archive is complete, in one step: an earlier export is never
            // deleted before the new one exists.
            SafeFiles.moveReplacing(tmp, destination)
            true
        } catch (e: Exception) {
            log.error("Exporting {} to {} failed", projectDir, destinationZip, e)
            tmp.delete()
            false
        }
    }

    /**
     * Safely unpacks a .geox file into [workspaceDir].
     *
     * Security & integrity guarantees:
     *  - Every entry must resolve INSIDE the new project folder (blocks zip-slip "../" and absolute paths).
     *  - The archive must contain exactly one root folder and a project.json.
     *  - Existing projects are never overwritten: a name clash imports as "Name_2", "Name_3"…
     *  - Extraction happens in a staging folder and is moved into place only when complete,
     *    so a failed import never leaves a half-written project behind.
     *  - Refuses archives that would not fit on disk.
     */
    fun importProject(geoxFile: File, workspaceDir: File): ImportResult {
        if (!geoxFile.isFile) return ImportResult.Failure("File not found: ${geoxFile.name}")

        val workspace = workspaceDir.canonicalFile
        removeStaleStagingFolders(workspace)
        val staging = File(workspace, ".import-${UUID.randomUUID()}")

        return try {
            ZipFile(geoxFile).use { zip ->
                val entries = zip.entries().toList()
                if (entries.isEmpty()) return ImportResult.Failure("The archive is empty.")
                if (entries.size > MAX_ENTRIES) return ImportResult.Failure("The archive contains too many files.")

                // 1. Exactly one, well-formed root folder.
                val names = entries.map { it.name.replace('\\', '/') }
                val roots = names.map { it.substringBefore('/') }.toSet()
                val root = roots.singleOrNull()
                    ?: return ImportResult.Failure("Not a valid .geox project (multiple root folders).")
                if (!isSafeFolderName(root)) {
                    return ImportResult.Failure("Not a valid .geox project (invalid project folder name).")
                }
                if ("$root/project.json" !in names) {
                    return ImportResult.Failure("Not a valid .geox project (project.json is missing).")
                }

                // 2. Disk space check (declared sizes; actual bytes are also capped while copying).
                val declared = entries.sumOf { maxOf(it.size, 0L) }
                val budget = workspace.usableSpace - DISK_SAFETY_MARGIN_BYTES
                if (declared > budget) return ImportResult.Failure("Not enough free disk space to import this project.")

                // 3. Extract into staging with a path check on every entry.
                staging.mkdirs()
                val stagingRoot = staging.canonicalFile
                var written = 0L

                for ((entry, name) in entries.zip(names)) {
                    if (name.length <= root.length + 1) continue // the root folder entry itself
                    val relative = name.substring(root.length + 1)

                    val dest = File(stagingRoot, relative).canonicalFile
                    if (!dest.path.startsWith(stagingRoot.path + File.separator)) {
                        throw SecurityException("Blocked unsafe path in archive: $name")
                    }

                    if (entry.isDirectory) {
                        dest.mkdirs()
                        continue
                    }
                    dest.parentFile.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(dest).use { output ->
                            val buffer = ByteArray(COPY_BUFFER)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                written += read
                                if (written > budget) throw IOException("Archive expands beyond available disk space.")
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }

                // 4. Move into place under a non-conflicting name.
                val target = uniqueProjectDir(workspace, root)
                if (!stagingRoot.renameTo(target)) {
                    throw IOException("Could not move the imported project into the workspace.")
                }
                ImportResult.Success(target)
            }
        } catch (e: SecurityException) {
            ImportResult.Failure("This file was rejected because it tries to write outside the project folder.")
        } catch (e: java.util.zip.ZipException) {
            ImportResult.Failure("The file is not a valid .geox archive.")
        } catch (e: Exception) {
            log.error("Importing {} failed", geoxFile, e)
            ImportResult.Failure("Import failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    // --- helpers --------------------------------------------------------------------------------

    private const val STALE_STAGING_MILLIS = 24L * 60 * 60 * 1000

    /**
     * An import that was interrupted (the app was closed or crashed) leaves its ".import-*" folder in the workspace,
     * with a partly unpacked project taking up disk space. Anything older than a day cannot belong to a running import.
     */
    private fun removeStaleStagingFolders(workspace: File) {
        val cutoff = System.currentTimeMillis() - STALE_STAGING_MILLIS
        workspace.listFiles { f -> f.isDirectory && f.name.startsWith(".import-") && f.lastModified() < cutoff }
            ?.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun isExcluded(file: File): Boolean {
        val name = file.name.lowercase()
        return name in EXCLUDED_NAMES || EXCLUDED_SUFFIXES.any { name.endsWith(it) }
    }

    internal fun isSafeFolderName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." &&
            !name.startsWith(".") && name.none { it in "\\/:*?\"<>|" || it.code < 32 }

    private fun uniqueProjectDir(workspace: File, baseName: String): File {
        var candidate = File(workspace, baseName)
        var n = 2
        while (candidate.exists()) {
            candidate = File(workspace, "${baseName}_$n")
            n++
        }
        return candidate
    }
}
