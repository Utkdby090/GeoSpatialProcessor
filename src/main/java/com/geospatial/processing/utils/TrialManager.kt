package com.geospatial.processing.utils

import com.geospatial.processing.auth.AppSecurity
import com.geospatial.processing.auth.ClockGuard
import com.geospatial.processing.auth.SecureStateStore
import java.time.Instant
import java.util.prefs.Preferences

/**
 * 15-day offline trial.
 *
 * Changes from the original:
 *  - Trial start is HMAC-signed and stored in two locations (registry + app data file);
 *    deleting one is repaired from the other, editing either one ends the trial.
 *  - Clock rollback (beyond a 36h tolerance) ends the trial.
 *  - Existing users keep their original start date (read once from the old registry key).
 *  - `resetTrialForTesting()` is gone – shipping a reset function in production is a bypass.
 *    For testing, delete %LOCALAPPDATA%\GeoFlux\state.dat and the registry node by hand.
 */
class TrialPolicy(
    private val store: SecureStateStore,
    private val clock: ClockGuard,
    private val legacyStartMillis: () -> Long? = { null },
    private val now: () -> Long = System::currentTimeMillis,
    private val trialDays: Long = 15,
) {
    private companion object {
        const val KEY_TRIAL_START = "trial_start"
        const val DAY_MS = 24L * 60 * 60 * 1000
    }

    fun isTrialExpired(): Boolean {
        if (clock.checkAndRecord()) return true

        val read = store.read(KEY_TRIAL_START)
        if (read.tampered) return true

        val start = startMillis(read) ?: return true
        val elapsedDays = (clock.trustedNowMillis() - start) / DAY_MS
        return elapsedDays >= trialDays || elapsedDays < 0
    }

    fun getDaysRemaining(): Long {
        val read = store.read(KEY_TRIAL_START)
        if (read.tampered) return 0
        val start = read.values.minOrNull() ?: legacyStartMillis() ?: return trialDays
        val elapsedDays = (clock.trustedNowMillis() - start) / DAY_MS
        return (trialDays - elapsedDays).coerceIn(0, trialDays)
    }

    /** Earliest known start; plants the trial on first launch and re-plants any deleted copy. */
    private fun startMillis(read: SecureStateStore.Read): Long? {
        val candidates = read.values + listOfNotNull(legacyStartMillis())
        val start = candidates.minOrNull() ?: now()
        store.write(KEY_TRIAL_START, start)
        return start
    }
}

object TrialManager {
    private val policy by lazy {
        TrialPolicy(
            store = AppSecurity.store,
            clock = ClockGuard.default,
            legacyStartMillis = ::readLegacyStart,
        )
    }

    fun isTrialExpired(): Boolean = policy.isTrialExpired()
    fun getDaysRemaining(): Long = policy.getDaysRemaining()

    /** v1 stored an unsigned ISO timestamp here. Read-only: used so existing trials are not reset. */
    private fun readLegacyStart(): Long? = try {
        Preferences.userRoot().node("com.geospatial.processing.trial")
            .get("first_launch_timestamp", null)
            ?.let { Instant.parse(it).toEpochMilli() }
    } catch (e: Exception) {
        null
    }
}
