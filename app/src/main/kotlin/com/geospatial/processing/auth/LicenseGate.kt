package com.geospatial.processing.auth

import com.geospatial.processing.utils.TrialManager

/** Result of the startup licence/trial check. */
sealed interface AuthState {
    data object Authorized : AuthState
    /** [reason] is shown as the lock window's title. */
    data class Locked(val reason: String) : AuthState {
        val isSubscriptionExpired: Boolean get() = reason.contains("Subscription")
    }
}

/** Text for the status bar at the bottom of the main window. */
data class LicenseBanner(val message: String, val isWarning: Boolean)

/** Licence and trial decisions, kept behind an interface so AppViewModel can be tested without them. */
interface LicenseGate {
    /** Records the clock (anti-rollback), then checks the saved licence, then the trial. */
    fun evaluateOnStartup(): AuthState

    /** Verifies and stores [key]. Returns true if the app may now be used. */
    fun activate(key: String): Boolean

    fun banner(): LicenseBanner
}

/** The production gate: same rules as before, moved out of Main.kt. */
class DefaultLicenseGate : LicenseGate {

    override fun evaluateOnStartup(): AuthState {
        // Record "last seen" time for everyone (licensed or trial), so winding the
        // system clock back can't extend a license or trial.
        ClockGuard.default.checkAndRecord()

        val savedLicense = LicenseStorage.getLicense()
        if (savedLicense != null) {
            return when (LicenseManager.verifyLicense(savedLicense)) {
                is LicenseManager.LicenseStatus.Valid -> AuthState.Authorized
                is LicenseManager.LicenseStatus.Expired -> {
                    LicenseStorage.clearLicense()
                    AuthState.Locked("Subscription Expired - Renewal Required")
                }
                else -> {
                    LicenseStorage.clearLicense()
                    AuthState.Locked("Invalid License - Activation Required")
                }
            }
        }

        return if (!TrialManager.isTrialExpired()) AuthState.Authorized
               else AuthState.Locked("Trial Expired - Activation Required")
    }

    override fun activate(key: String): Boolean {
        if (LicenseManager.verifyLicense(key) !is LicenseManager.LicenseStatus.Valid) return false
        LicenseStorage.saveLicense(key)
        return true
    }

    override fun banner(): LicenseBanner {
        val savedLicense = LicenseStorage.getLicense()
        if (savedLicense != null) {
            return if (LicenseManager.verifyLicense(savedLicense) is LicenseManager.LicenseStatus.Valid) {
                val daysLeft = LicenseManager.getSubscriptionDaysRemaining(savedLicense)
                LicenseBanner("Subscription Active: $daysLeft days remaining", isWarning = daysLeft <= 5)
            } else {
                LicenseBanner("License Expired or Invalid!", isWarning = true)
            }
        }
        return if (!TrialManager.isTrialExpired()) {
            val trialDaysLeft = TrialManager.getDaysRemaining()
            LicenseBanner("Trial Mode: $trialDaysLeft days left", isWarning = trialDaysLeft <= 3)
        } else {
            LicenseBanner("Trial Expired. Please activate a license.", isWarning = true)
        }
    }
}
