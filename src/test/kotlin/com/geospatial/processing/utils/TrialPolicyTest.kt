package com.geospatial.processing.utils

import com.geospatial.processing.auth.ClockGuard
import com.geospatial.processing.auth.SecureStateStore
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.prefs.Preferences
import org.junit.jupiter.api.AfterEach
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrialPolicyTest {

    private val day = 24L * 60 * 60 * 1000
    private val node = Preferences.userRoot().node("com/geospatial/processing/test-${System.nanoTime()}")
    private val file = File(Files.createTempDirectory("state").toFile(), "state.dat")
    private var clockMillis = 1_700_000_000_000L

    private fun store(secret: String = "machine-A") = SecureStateStore(node, file, { secret })
    private fun policy(s: SecureStateStore = store(), legacy: Long? = null): TrialPolicy {
        val clock = ClockGuard(s) { clockMillis }
        return TrialPolicy(s, clock, legacyStartMillis = { legacy }, now = { clockMillis })
    }

    @AfterEach
    fun cleanup() { node.removeNode(); file.delete() }

    @Test
    fun `fresh trial is active and expires after 15 days`() {
        assertFalse(policy().isTrialExpired())
        clockMillis += 14 * day
        assertFalse(policy().isTrialExpired())
        clockMillis += 1 * day
        assertTrue(policy().isTrialExpired())
    }

    @Test
    fun `deleting the registry copy does not reset the trial`() {
        policy().isTrialExpired()
        clockMillis += 20 * day
        node.clear()
        assertTrue(policy().isTrialExpired())
    }

    @Test
    fun `deleting the file copy does not reset the trial`() {
        policy().isTrialExpired()
        clockMillis += 20 * day
        file.delete()
        assertTrue(policy().isTrialExpired())
    }

    @Test
    fun `editing the stored start date ends the trial`() {
        policy().isTrialExpired()
        node.put("trial_start", "${clockMillis + 100 * day}:forgedsignature")
        assertTrue(policy().isTrialExpired())
    }

    @Test
    fun `state copied from another machine is rejected`() {
        policy(store("machine-A")).isTrialExpired()
        assertTrue(policy(store("machine-B")).isTrialExpired())
    }

    @Test
    fun `winding the clock back ends the trial`() {
        policy().isTrialExpired()
        clockMillis += 10 * day
        policy().isTrialExpired()
        clockMillis -= 9 * day
        assertTrue(policy().isTrialExpired())
    }

    @Test
    fun `small backwards clock corrections are tolerated`() {
        policy().isTrialExpired()
        clockMillis -= 2 * 60 * 60 * 1000 // 2 hours
        assertFalse(policy().isTrialExpired())
    }

    @Test
    fun `existing v1 trial users keep their original start date`() {
        val p = policy(legacy = clockMillis - 10 * day)
        assertFalse(p.isTrialExpired())
        assertEquals(5, p.getDaysRemaining())
        clockMillis += 5 * day
        assertTrue(p.isTrialExpired())
    }
}
