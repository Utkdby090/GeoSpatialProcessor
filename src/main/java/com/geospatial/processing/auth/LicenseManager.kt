package com.geospatial.processing.auth

import com.geospatial.processing.utils.HardwareUtil
import java.security.KeyFactory
import java.security.Signature
import java.time.LocalDate
import java.util.Base64
import java.time.temporal.ChronoUnit

object LicenseManager {

    // IMPORTANT: Paste your Spring Boot PUBLIC key here
    private const val PUBLIC_KEY_BASE64 = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA8XGvR5J3Upt2LkS+loli6jrr7zAfL7QqBkaqIXGikAnRzGpw9Vu5WoMrhzwG7L34ltzACv+jsYZyl++l02vzlTKPCkGdpPslNhoqcCaPB1+PalOY1XvgYRQiT0TcYazd8Klz5o8qQ3M5KugyI1Klbw45zWHIKZY26S/D5wxQY71NAPq40Zl+708CfZdZyaFXJtYrkMcXbdoNmoo82uprtvMfJEWVLzVm7ab5cm+2c5Tae6Jv8wHnOi2Sxtapl4DJ0/jKuw1sGU1oUR7jZHL0OjizMH/zRTt8eYRoP2Kv4BYFNVXUsiILIIFqstd1s8aeXAEbvQncRGWTqJUAmRAvGQIDAQAB"

    sealed class LicenseStatus {
        object Valid : LicenseStatus()
        object Expired : LicenseStatus()
        object InvalidMachine : LicenseStatus()
        object TamperedOrInvalid : LicenseStatus()
    }

    fun verifyLicense(licenseString: String): LicenseStatus {
        try {
            val cleanLicense = licenseString.trim()
            val parts = cleanLicense.split(".")

            if (parts.size != 2) return LicenseStatus.TamperedOrInvalid

            val encodedPayload = parts[0]
            val encodedSignature = parts[1]

            // 1. Rebuild Public Key (Standard Decoder for the key string)
            // 1. Rebuild Public Key (Sanitized to prevent 5f/underscore crashes)
            val sanitizedPubKey = PUBLIC_KEY_BASE64
                .replace("-", "+")
                .replace("_", "/")
                .replace("\\s".toRegex(), "") // Removes any accidental spaces/newlines

            val keyBytes = Base64.getDecoder().decode(sanitizedPubKey)
            val spec = java.security.spec.X509EncodedKeySpec(keyBytes)
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(spec)
            // 2. Verify Signature using URL-SAFE Decoder
            val rsaSignature = Signature.getInstance("SHA256withRSA")
            rsaSignature.initVerify(publicKey)
            rsaSignature.update(Base64.getUrlDecoder().decode(encodedPayload))

            if (!rsaSignature.verify(Base64.getUrlDecoder().decode(encodedSignature))) {
                println("DEBUG: Signature Verification Failed")
                return LicenseStatus.TamperedOrInvalid
            }

            // 3. Decode Payload ("MachineID|YYYY-MM-DD") using URL-SAFE Decoder
            val payloadString = String(Base64.getUrlDecoder().decode(encodedPayload))
            val payloadParts = payloadString.split("|")

            if (payloadParts.size != 2) return LicenseStatus.TamperedOrInvalid

            val licenseMachineId = payloadParts[0]
            val expirationDateStr = payloadParts[1]

            // 4. Hardware Check
            if (licenseMachineId != HardwareUtil.getMachineId()) {
                return LicenseStatus.InvalidMachine
            }

            // 5. Expiration Date Check
            try {
                val expiryDate = LocalDate.parse(expirationDateStr)
                if (LocalDate.now().isAfter(expiryDate)) {
                    return LicenseStatus.Expired
                }
            } catch (e: Exception) {
                return LicenseStatus.TamperedOrInvalid
            }

            return LicenseStatus.Valid

        } catch (e: Exception) {
            println("DEBUG: Cryptographic Verification Failed.")
            e.printStackTrace()
            return LicenseStatus.TamperedOrInvalid
        }
    }
    /**
     * Decodes a VALID license to tell the UI how many days are left in the subscription.
     */
    fun getSubscriptionDaysRemaining(licenseString: String): Long {
        return try {
            val cleanLicense = licenseString.trim()
            val parts = cleanLicense.split(".")
            if (parts.size != 2) return 0

            val encodedPayload = parts[0]
            val payloadString = String(Base64.getUrlDecoder().decode(encodedPayload))
            val payloadParts = payloadString.split("|")

            if (payloadParts.size != 2) return 0

            val expirationDateStr = payloadParts[1]
            val expiryDate = LocalDate.parse(expirationDateStr)

            // Calculate days between today and the expiry date
            val daysLeft = ChronoUnit.DAYS.between(LocalDate.now(), expiryDate)

            // Return days left, but don't drop below 0
            if (daysLeft < 0) 0 else daysLeft
        } catch (e: Exception) {
            0
        }
    }
}