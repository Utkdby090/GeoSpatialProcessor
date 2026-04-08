package com.geospatial.processing.auth

import com.geospatial.processing.utils.HardwareUtil
import java.security.KeyFactory
import java.security.Signature
import java.time.LocalDate
import java.util.Base64

object LicenseManager {

    // IMPORTANT: Paste your Spring Boot PUBLIC key here
    private const val PUBLIC_KEY_BASE64 = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA1b1hetj1sg1AVAsF1Sjhvp+Hha8tn3KLJ+cx8ZJpTmxLErz1L2RwqCOlZsfeiwzPTiTulHBTW9mCSctvTakzfVE3Lw5fDmaY7aOSk38RCwCf6vXuuWe1Ny901kiMOFNwMg9o0PWOkeFidkqUHSgBXIREDqobzGbamZoxcx2DqxiwZA82n9jrpE6gAEOWpUr2ufu1V2iTkpGgw3EMIfz2R3yieqXrH18q3wfRDR/gxqOt/QY5EVUXfKmja5lsxLyPliE5uWz052wAbxkTEoVkzXVh85y16i9suVdaGh1lEsjca4kNQa+WDmoIfdkhV63+8GgnJfLKq64rLpONAXO0hwIDAQAB"

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
}