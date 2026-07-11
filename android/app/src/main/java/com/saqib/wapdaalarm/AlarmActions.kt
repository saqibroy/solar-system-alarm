package com.saqib.wapdaalarm

object AlarmActions {
    const val ACTION_START = "com.saqib.wapdaalarm.action.START"
    const val ACTION_STOP = "com.saqib.wapdaalarm.action.STOP"
    const val ACTION_TEST = "com.saqib.wapdaalarm.action.TEST"
    const val ACTION_STATE_CHANGED = "com.saqib.wapdaalarm.action.STATE_CHANGED"
    const val EXTRA_DURATION_MS = "duration_ms"
    const val EXTRA_ALARM_NAME = "alarm_name"
    const val EXTRA_SEVERITY = "severity"
    const val EXTRA_MESSAGE = "message"

    const val NOTIFICATION_CHANNEL_ID = "grid_power_alarm"
    const val NOTIFICATION_ID = 1001
    const val FCM_TOPIC = "wapda-alarm-alerts"

    const val TEST_DURATION_MS = 10_000L
    const val AUTO_TIMEOUT_MS = 30 * 60 * 1000L
}
