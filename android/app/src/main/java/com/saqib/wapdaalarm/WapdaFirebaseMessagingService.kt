package com.saqib.wapdaalarm

import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class WapdaFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        val prefs = PrefsManager(this)
        prefs.fcmToken = token
        if (!RegistrationSecret.isValid(prefs.registrationSecret)) {
            prefs.isRegistered = false
            prefs.lastRegistrationStatus = "Enter the correct registration secret to connect"
            return
        }
        FirebaseMessaging.getInstance().subscribeToTopic(AlarmActions.FCM_TOPIC)
            .addOnCompleteListener { task ->
                prefs.isRegistered = task.isSuccessful
                prefs.lastRegistrationStatus = if (task.isSuccessful) {
                    "Connected - watching for LINE_FAIL alerts"
                } else {
                    "Cloud subscription failed: ${task.exception?.message}"
                }
            }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val type = data["type"].orEmpty()
        val alarm = data["alarm"].orEmpty().ifBlank { "LINE_FAIL" }
        val severity = data["severity"].orEmpty().ifBlank { "critical" }
        Log.i(TAG, "FCM message received type=$type alarm=$alarm severity=$severity")

        when (type) {
            "alarm_start" -> startAlarm(alarm, severity)
            "alarm_stop" -> stopAlarm(alarm)
            else -> Log.w(TAG, "Ignoring unknown FCM type=$type")
        }
    }

    private fun startAlarm(alarm: String, severity: String) {
        val message = when (alarm) {
            "PV_LOSS" -> "Solar input is lost. Check load and battery drain."
            else -> "Grid power is out. Turn off the air conditioner before the battery drains."
        }
        val intent = Intent(this, AlarmForegroundService::class.java)
            .setAction(AlarmActions.ACTION_START)
            .putExtra(AlarmActions.EXTRA_ALARM_NAME, alarm)
            .putExtra(AlarmActions.EXTRA_SEVERITY, severity)
            .putExtra(AlarmActions.EXTRA_MESSAGE, message)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopAlarm(alarm: String) {
        PrefsManager(this).lastEvent = "$alarm cleared; stopping alarm"
        val intent = Intent(this, AlarmForegroundService::class.java).setAction(AlarmActions.ACTION_STOP)
        ContextCompat.startForegroundService(this, intent)
    }

    private companion object {
        const val TAG = "WapdaFcmService"
    }
}
