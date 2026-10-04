package com.geospatial.processing.utils

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Writes that cannot leave a half-written file behind. Content goes to a temporary file in the same folder and is moved
 * over the target in one step, so a crash, a full disk or a second writer leaves either the old file or the new one.
 * (Writing in place leaves a truncated file; deleting first and then renaming loses the old one in between.)
 */
object SafeFiles {

    fun writeText(target: File, text: String) = writeBytes(target, text.toByteArray(Charsets.UTF_8))

    /** Replaces [target] with [bytes], creating its folder if needed. Throws [IOException] naming the file on failure. */
    fun writeBytes(target: File, bytes: ByteArray) {
        val tmp = temporaryNextTo(target)
        try {
            target.absoluteFile.parentFile?.mkdirs()
            tmp.writeBytes(bytes)
            moveReplacing(tmp, target)
        } catch (e: IOException) {
            throw IOException("Could not write $target: ${e.message}", e)
        } finally {
            tmp.delete() // gone already after a successful move
        }
    }

    /** Moves [source] over [target]; atomically where the file system can, and never leaving [target] missing. */
    fun moveReplacing(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** A name for a temporary file beside [target], unique per call (threads and processes can write at once). */
    fun temporaryNextTo(target: File): File =
        File(target.absoluteFile.parentFile, "${target.name}.${UUID.randomUUID()}.tmp")
}
