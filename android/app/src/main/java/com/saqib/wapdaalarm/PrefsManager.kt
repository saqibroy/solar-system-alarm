package com.saqib.wapdaalarm

import android.content.Context
import androidx.core.content.edit

class PrefsManager(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "http://158.180.30.164:8088").orEmpty()
        set(value) = prefs.edit { putString(KEY_SERVER_URL, value.trim().trimEnd('/')) }

    var registrationSecret: String
        get() = prefs.getString(KEY_REGISTRATION_SECRET, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_REGISTRATION_SECRET, value.trim()) }

    var fcmToken: String
        get() = prefs.getString(KEY_FCM_TOKEN, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_FCM_TOKEN, value) }

    var isRegistered: Boolean
        get() = prefs.getBoolean(KEY_REGISTERED, false)
        set(value) = prefs.edit { putBoolean(KEY_REGISTERED, value) }

    var isAlarmRunning: Boolean
        get() = prefs.getBoolean(KEY_ALARM_RUNNING, false)
        set(value) = prefs.edit { putBoolean(KEY_ALARM_RUNNING, value) }

    var lastEvent: String
        get() = prefs.getString(KEY_LAST_EVENT, "No alarm events yet").orEmpty()
        set(value) = prefs.edit { putString(KEY_LAST_EVENT, value) }

    var lastRegistrationStatus: String
        get() = prefs.getString(KEY_LAST_REGISTRATION_STATUS, "Not registered yet").orEmpty()
        set(value) = prefs.edit { putString(KEY_LAST_REGISTRATION_STATUS, value) }

    var restoredNotificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_RESTORED_NOTIFICATIONS, true)
        set(value) = prefs.edit { putBoolean(KEY_RESTORED_NOTIFICATIONS, value) }

    var phoneRole: String
        get() = prefs.getString(KEY_PHONE_ROLE, AlertCatalog.ROLE_DAD).orEmpty()
        set(value) = prefs.edit { putString(KEY_PHONE_ROLE, value) }

    var lastServerPollAt: String
        get() = prefs.getString(KEY_LAST_SERVER_POLL_AT, "Waiting for server status").orEmpty()
        set(value) = prefs.edit { putString(KEY_LAST_SERVER_POLL_AT, value) }

    var lastLineFailState: String
        get() = prefs.getString(KEY_LAST_LINE_FAIL_STATE, "Unknown").orEmpty()
        set(value) = prefs.edit { putString(KEY_LAST_LINE_FAIL_STATE, value) }

    var lastPushResult: String
        get() = prefs.getString(KEY_LAST_PUSH_RESULT, "Waiting for first push").orEmpty()
        set(value) = prefs.edit { putString(KEY_LAST_PUSH_RESULT, value) }

    fun alertMode(alarm: String): String =
        prefs.getString(alertModeKey(alarm), AlertCatalog.defaultMode(alarm, phoneRole)).orEmpty()

    fun setAlertMode(alarm: String, mode: String) {
        prefs.edit { putString(alertModeKey(alarm), mode) }
    }

    fun applyRole(role: String) {
        prefs.edit {
            putString(KEY_PHONE_ROLE, role)
            AlertCatalog.rules.forEach { rule ->
                putString(alertModeKey(rule.alarm), AlertCatalog.defaultMode(rule.alarm, role))
            }
        }
    }

    fun saveServerSettings(serverUrl: String, registrationSecret: String) {
        prefs.edit {
            putString(KEY_SERVER_URL, serverUrl.trim().trimEnd('/'))
            putString(KEY_REGISTRATION_SECRET, registrationSecret.trim())
            putBoolean(KEY_REGISTERED, false)
        }
    }

    private companion object {
        const val PREFS_NAME = "wapda_alarm_prefs"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_REGISTRATION_SECRET = "registration_secret"
        const val KEY_FCM_TOKEN = "fcm_token"
        const val KEY_REGISTERED = "registered"
        const val KEY_ALARM_RUNNING = "alarm_running"
        const val KEY_LAST_EVENT = "last_event"
        const val KEY_LAST_REGISTRATION_STATUS = "last_registration_status"
        const val KEY_RESTORED_NOTIFICATIONS = "restored_notifications"
        const val KEY_PHONE_ROLE = "phone_role"
        const val KEY_LAST_SERVER_POLL_AT = "last_server_poll_at"
        const val KEY_LAST_LINE_FAIL_STATE = "last_line_fail_state"
        const val KEY_LAST_PUSH_RESULT = "last_push_result"

        fun alertModeKey(alarm: String): String = "alert_mode_${alarm.lowercase()}"
    }
}

object AlertMode {
    const val ALARM = "alarm"
    const val NOTIFICATION = "notification"
    const val OFF = "off"
}
