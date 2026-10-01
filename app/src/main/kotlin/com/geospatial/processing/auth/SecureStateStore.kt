package com.geospatial.processing.auth

import java.io.File
import java.util.Base64
import java.util.Properties
import java.util.prefs.Preferences
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Small tamper-evident key/value store for licensing state (trial start, last-seen clock).
 *
 * Each value is HMAC-signed with a key bound to this machine, and written to TWO places:
 * the Java Preferences store (Windows registry) and a file in the app data folder.
 * Deleting one location is repaired from the other; editing a value breaks its signature.
 *
 * Be realistic about what this buys you: an offline app can never fully stop someone who deletes
 * every copy or patches the binary. This raises the bar from "delete one registry key" to
 * "find and wipe multiple signed locations". Real enforcement comes from server-side activation (Phase 4).
 */
class SecureStateStore(
    private val prefs: Preferences,
    private val file: File,
    secret: () -> String,
) {
    data class Read(val values: List<Long>, val tampered: Boolean)

    private val mac: Mac by lazy {
        Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(("gf-state-v1|" + secret()).toByteArray(), "HmacSHA256"))
        }
    }

    /** Reads every copy of [key]. `tampered` = at least one copy exists but fails its signature. */
    @Synchronized
    fun read(key: String): Read {
        val raw = listOfNotNull(prefs.get(key, null), loadFile().getProperty(key))
        val values = mutableListOf<Long>()
        var tampered = false
        for (entry in raw) {
            val parsed = verify(key, entry)
            if (parsed == null) tampered = true else values += parsed
        }
        return Read(values, tampered)
    }

    /** Writes [value] to every location. */
    @Synchronized
    fun write(key: String, value: Long) {
        val signed = sign(key, value)
        try {
            prefs.put(key, signed)
            prefs.flush()
        } catch (e: Exception) { /* registry unavailable – file copy still holds */ }
        try {
            val props = loadFile()
            props.setProperty(key, signed)
            file.parentFile?.mkdirs()
            file.outputStream().use { props.store(it, null) }
        } catch (e: Exception) { /* file unavailable – registry copy still holds */ }
    }

    private fun sign(key: String, value: Long): String = "$value:${hmac("$key|$value")}"

    private fun verify(key: String, entry: String): Long? {
        val value = entry.substringBefore(':', "").toLongOrNull() ?: return null
        val signature = entry.substringAfter(':', "")
        val expected = hmac("$key|$value")
        return if (java.security.MessageDigest.isEqual(signature.toByteArray(), expected.toByteArray())) value else null
    }

    private fun hmac(data: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(data.toByteArray()))

    private fun loadFile(): Properties = Properties().apply {
        if (file.isFile) try { file.inputStream().use { load(it) } } catch (e: Exception) { }
    }
}
