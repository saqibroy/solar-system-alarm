package com.saqib.wapdaalarm

import android.os.Build

data class OemGuidance(
    val brand: String,
    val instructions: List<String>,
)

object OemGuidanceProvider {
    fun current(): OemGuidance? {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        val name = "$manufacturer $brand"

        return when {
            "xiaomi" in name || "redmi" in name || "poco" in name -> OemGuidance(
                brand = "Xiaomi / Redmi / Poco",
                instructions = listOf(
                    "Enable Autostart for WAPDA Alarm.",
                    "Set Battery saver to No restrictions for WAPDA Alarm.",
                    "After opening this app once, lock it in Recents."
                )
            )
            "oppo" in name || "realme" in name || "oneplus" in name -> OemGuidance(
                brand = "Oppo / Realme / OnePlus",
                instructions = listOf(
                    "Allow background activity for WAPDA Alarm.",
                    "Disable battery optimization for WAPDA Alarm.",
                    "Enable Auto launch or Startup if the phone offers it."
                )
            )
            "vivo" in name || "iqoo" in name -> OemGuidance(
                brand = "Vivo / iQOO",
                instructions = listOf(
                    "Allow Auto-start for WAPDA Alarm.",
                    "Set background power consumption to unrestricted.",
                    "Keep the app locked in Recents after setup."
                )
            )
            "samsung" in name -> OemGuidance(
                brand = "Samsung",
                instructions = listOf(
                    "Set WAPDA Alarm to Unrestricted battery.",
                    "Remove WAPDA Alarm from Sleeping apps and Deep sleeping apps."
                )
            )
            else -> null
        }
    }
}
