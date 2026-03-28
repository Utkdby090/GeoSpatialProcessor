package com.geospatial.processing.auth

import java.net.NetworkInterface

object HardwareUtil {
    fun getMachineId(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces().toList()
            for (network in interfaces) {
                val mac = network.hardwareAddress
                // Grab the first valid MAC address we find
                if (mac != null && mac.isNotEmpty()) {
                    return mac.joinToString("-") { byte -> "%02X".format(byte) }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "UNKNOWN-MACHINE-ID"
    }
}