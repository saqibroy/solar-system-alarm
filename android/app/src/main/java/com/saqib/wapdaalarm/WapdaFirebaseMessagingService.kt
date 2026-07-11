package com.saqib.wapdaalarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
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
        val prefs = PrefsManager(this)
        when (prefs.alertMode(alarm)) {
            AlertMode.OFF -> {
                prefs.lastEvent = "$alarm alert ignored"
                return
            }
            AlertMode.NOTIFICATION -> {
                prefs.lastEvent = "$alarm notification received"
                showStatusNotification(alarm, activeTitle(alarm), message)
                return
            }
        }
        val intent = Intent(this, AlarmForegroundService::class.java)
            .setAction(AlarmActions.ACTION_START)
            .putExtra(AlarmActions.EXTRA_ALARM_NAME, alarm)
            .putExtra(AlarmActions.EXTRA_SEVERITY, severity)
            .putExtra(AlarmActions.EXTRA_MESSAGE, message)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopAlarm(alarm: String) {
        val prefs = PrefsManager(this)
        prefs.lastEvent = "$alarm cleared; grid power restored"
        if (prefs.isAlarmRunning) {
            val intent = Intent(this, AlarmForegroundService::class.java).setAction(AlarmActions.ACTION_STOP)
            ContextCompat.startForegroundService(this, intent)
        }
        if (prefs.restoredNotificationsEnabled && prefs.alertMode(alarm) != AlertMode.OFF) {
            val title = if (alarm == "PV_LOSS") "Solar input restored" else "Grid power restored"
            val body = if (alarm == "PV_LOSS") {
                "PV loss has cleared."
            } else {
                "LINE_FAIL has cleared. Electricity is back."
            }
            showStatusNotification(alarm, title, body)
        }
    }

    private fun showStatusNotification(alarm: String, title: String, body: String) {
        createStatusChannel()
        val openPendingIntent = PendingIntent.getActivity(
            this,
            4,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, AlarmActions.STATUS_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(this).notify(notificationIdFor(alarm), notification)
        }
    }

    private fun activeTitle(alarm: String): String =
        if (alarm == "PV_LOSS") "Solar input lost" else "Grid power out"

    private fun notificationIdFor(alarm: String): Int =
        if (alarm == "PV_LOSS") AlarmActions.RESTORED_NOTIFICATION_ID + 1 else AlarmActions.RESTORED_NOTIFICATION_ID

    private fun createStatusChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val notificationManager = getSystemService<NotificationManager>() ?: return
        val channel = NotificationChannel(
            AlarmActions.STATUS_NOTIFICATION_CHANNEL_ID,
            "Grid power status",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifications when grid power alarms clear"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        notificationManager.createNotificationChannel(channel)
    }

    private companion object {
        const val TAG = "WapdaFcmService"
    }
}
