package com.geospatial.processing.auth

import com.geospatial.processing.utils.AppDirs
import com.geospatial.processing.utils.HardwareUtil
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.prefs.Preferences

/** Shared, machine-bound signed state used by [ClockGuard] and TrialManager. */
object AppSecurity {
    /** HardwareUtil spawns PowerShell on every call – resolve it once per run. */
    val machineId: String by lazy { HardwareUtil.getMachineId() }

    val store: SecureStateStore by lazy {
        SecureStateStore(
            prefs = Preferences.userRoot().node("com/geospatial/processing/state"),
            file = File(AppDirs.dataDir, "state.dat"),
            secret = { machineId },
        )
    }
}

/**
 * Detects the system clock being wound back to stretch a trial or an expiring license.
 *
 * Remembers the latest time the app has ever seen. License and trial checks use
 * [trustedNowMillis] = max(system clock, last seen), so winding the clock back gains nothing.
 */
class ClockGuard(private val store: SecureStateStore, private val now: () -> Long = System::currentTimeMillis) {

    companion object {
        private const val KEY_LAST_SEEN = "last_seen"
        /** Allowed backwards drift (time-zone changes, NTP corrections, travel). */
        const val TOLERANCE_MS = 36L * 60 * 60 * 1000

        val default: ClockGuard by lazy { ClockGuard(AppSecurity.store) }
    }

    /** Records the current time and returns true if the clock is clearly earlier than before. */
    fun checkAndRecord(): Boolean {
        val current = now()
        val read = store.read(KEY_LAST_SEEN)
        // A corrupted copy is not treated as an attack here (licensed users must not be locked out
        // by a damaged file); it is simply overwritten. TrialManager is stricter.
        val lastSeen = read.values.maxOrNull()
        val rolledBack = lastSeen != null && current < lastSeen - TOLERANCE_MS
        store.write(KEY_LAST_SEEN, maxOf(current, lastSeen ?: current))
        return rolledBack
    }

    fun trustedNowMillis(): Long = maxOf(now(), store.read(KEY_LAST_SEEN).values.maxOrNull() ?: 0L)

    fun trustedToday(): LocalDate =
        LocalDate.ofInstant(Instant.ofEpochMilli(trustedNowMillis()), ZoneId.systemDefault())
}
