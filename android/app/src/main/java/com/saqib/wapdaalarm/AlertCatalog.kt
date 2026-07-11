package com.saqib.wapdaalarm

data class AlertRule(
    val alarm: String,
    val title: String,
    val subtitle: String,
    val defaultDadMode: String,
    val defaultMonitorMode: String,
)

object AlertCatalog {
    const val ROLE_DAD = "dad"
    const val ROLE_MONITOR = "monitor"

    val rules = listOf(
        AlertRule(
            alarm = "LINE_FAIL",
            title = "Grid failure",
            subtitle = "WAPDA is out; switch to battery",
            defaultDadMode = AlertMode.ALARM,
            defaultMonitorMode = AlertMode.NOTIFICATION,
        ),
        AlertRule(
            alarm = "BATTERY_LOW",
            title = "Battery low",
            subtitle = "Low battery while grid is out",
            defaultDadMode = AlertMode.ALARM,
            defaultMonitorMode = AlertMode.NOTIFICATION,
        ),
        AlertRule(
            alarm = "HIGH_LOAD",
            title = "High load",
            subtitle = "Heavy load while on battery",
            defaultDadMode = AlertMode.ALARM,
            defaultMonitorMode = AlertMode.NOTIFICATION,
        ),
        AlertRule(
            alarm = "PV_LOSS",
            title = "Solar input loss",
            subtitle = "PV is low during daytime",
            defaultDadMode = AlertMode.NOTIFICATION,
            defaultMonitorMode = AlertMode.NOTIFICATION,
        ),
        AlertRule(
            alarm = "STALE_DATA",
            title = "Cloud data stale",
            subtitle = "Inverter has not updated recently",
            defaultDadMode = AlertMode.NOTIFICATION,
            defaultMonitorMode = AlertMode.NOTIFICATION,
        ),
        AlertRule(
            alarm = "DAILY_SUMMARY",
            title = "Daily summary",
            subtitle = "Solar, load, battery and outage digest",
            defaultDadMode = AlertMode.NOTIFICATION,
            defaultMonitorMode = AlertMode.NOTIFICATION,
        ),
    )

    fun titleFor(alarm: String): String =
        rules.firstOrNull { it.alarm == alarm }?.title ?: alarm.replace('_', ' ')

    fun activeMessageFor(alarm: String): String =
        when (alarm) {
            "LINE_FAIL" -> "Grid power is out. Turn off the air conditioner before the battery drains."
            "BATTERY_LOW" -> "Battery is low while grid power is out. Reduce load now."
            "HIGH_LOAD" -> "Load is high while running on battery. Turn off heavy appliances."
            "PV_LOSS" -> "Solar input is low during daytime while the battery is draining."
            "STALE_DATA" -> "ShineMonitor data is stale. Check internet/cloud connectivity."
            else -> "$alarm alert is active."
        }

    fun clearedMessageFor(alarm: String): String =
        when (alarm) {
            "LINE_FAIL" -> "LINE_FAIL cleared. Electricity is back."
            "BATTERY_LOW" -> "Battery-low condition cleared."
            "HIGH_LOAD" -> "High-load condition cleared."
            "PV_LOSS" -> "PV loss cleared."
            "STALE_DATA" -> "Inverter cloud data is fresh again."
            else -> "$alarm cleared."
        }

    fun defaultMode(alarm: String, role: String): String {
        val rule = rules.firstOrNull { it.alarm == alarm }
        return when (role) {
            ROLE_MONITOR -> rule?.defaultMonitorMode ?: AlertMode.NOTIFICATION
            else -> rule?.defaultDadMode ?: AlertMode.ALARM
        }
    }
}
