package com.geospatial.processing.utils


import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.prefs.Preferences

object TrialManager {
    // This creates a hidden registry key specific to your app
    private val prefs = Preferences.userRoot().node("com.geospatial.processing.trial")
    private const val FIRST_LAUNCH_KEY = "first_launch_timestamp"


// TRIAL 15 DAY PERIOD
    private const val TRIAL_DAYS = 15

    /**
     * Checks if the 15-day offline trial has expired.
     */
    fun isTrialExpired(): Boolean {
        val firstLaunchStr = prefs.get(FIRST_LAUNCH_KEY, null)

        if (firstLaunchStr == null) {
            // BOOM. First time opening the app. Plant the time bomb.
            prefs.put(FIRST_LAUNCH_KEY, Instant.now().toString())
            return false // Trial just started, let them in.
        }

        return try {
            val firstLaunchTime = Instant.parse(firstLaunchStr)
            val daysElapsed = ChronoUnit.DAYS.between(firstLaunchTime, Instant.now())

            // If they change their PC clock backward to cheat, daysElapsed might be negative.
            // We lock them out if daysElapsed >= 15 OR if it's less than 0 (time travel tampering).
            daysElapsed >= TRIAL_DAYS || daysElapsed < 0
        } catch (e: Exception) {
            // If they try to manually hack the registry string and break it, lock the app.
            true
        }
    }

    /**
     * Gives you (the developer) a way to see how many days are left.
     */
    fun getDaysRemaining(): Long {
        val firstLaunchStr = prefs.get(FIRST_LAUNCH_KEY, null) ?: return TRIAL_DAYS.toLong()
        return try {
            val firstLaunchTime = Instant.parse(firstLaunchStr)
            val daysElapsed = ChronoUnit.DAYS.between(firstLaunchTime, Instant.now())
            val remaining = TRIAL_DAYS - daysElapsed
            if (remaining < 0) 0 else remaining
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Use this ONLY for your own testing to reset the bomb.
     */
    fun resetTrialForTesting() {
        prefs.remove(FIRST_LAUNCH_KEY)
    }
}