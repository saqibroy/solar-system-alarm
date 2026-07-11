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
                    "Connected - watching for power alerts"
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
            "alarm_start" -> startAlarm(alarm, severity, data["message"])
            "alarm_stop" -> stopAlarm(alarm, data["message"])
            "daily_summary" -> showInformationalMessage(alarm, data["message"].orEmpty())
            "server_status" -> updateServerStatus(data)
            else -> Log.w(TAG, "Ignoring unknown FCM type=$type")
        }
    }

    private fun startAlarm(alarm: String, severity: String, serverMessage: String?) {
        val message = serverMessage?.takeIf { it.isNotBlank() } ?: AlertCatalog.activeMessageFor(alarm)
        val prefs = PrefsManager(this)
        updateServerStatusSnapshot(alarm = alarm, lineFailActive = if (alarm == "LINE_FAIL") true else null)
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

    private fun stopAlarm(alarm: String, serverMessage: String?) {
        val prefs = PrefsManager(this)
        val body = serverMessage?.takeIf { it.isNotBlank() } ?: AlertCatalog.clearedMessageFor(alarm)
        prefs.lastEvent = "$alarm cleared"
        updateServerStatusSnapshot(alarm = alarm, lineFailActive = if (alarm == "LINE_FAIL") false else null)
        if (prefs.isAlarmRunning) {
            val intent = Intent(this, AlarmForegroundService::class.java).setAction(AlarmActions.ACTION_STOP)
            ContextCompat.startForegroundService(this, intent)
        }
        if (prefs.restoredNotificationsEnabled && prefs.alertMode(alarm) != AlertMode.OFF) {
            showStatusNotification(alarm, "${AlertCatalog.titleFor(alarm)} cleared", body)
        }
    }

    private fun showInformationalMessage(alarm: String, message: String) {
        val prefs = PrefsManager(this)
        if (prefs.alertMode(alarm) == AlertMode.OFF) return
        val body = message.ifBlank { AlertCatalog.titleFor(alarm) }
        prefs.lastEvent = "${AlertCatalog.titleFor(alarm)} received"
        showStatusNotification(alarm, AlertCatalog.titleFor(alarm), body)
    }

    private fun updateServerStatus(data: Map<String, String>) {
        val prefs = PrefsManager(this)
        prefs.lastServerPollAt = data["last_poll_at"].orEmpty().ifBlank { data["timestamp"].orEmpty() }
        prefs.lastLineFailState = if (data["line_fail_active"] == "true") "Active" else "Clear"
        prefs.lastPushResult = data["last_push_result"].orEmpty().ifBlank { "Status heartbeat received" }
        prefs.lastEvent = data["message"].orEmpty().ifBlank { "Server status updated" }
        sendBroadcast(Intent(AlarmActions.ACTION_STATE_CHANGED).setPackage(packageName))
    }

    private fun updateServerStatusSnapshot(alarm: String, lineFailActive: Boolean?) {
        val prefs = PrefsManager(this)
        if (lineFailActive != null) {
            prefs.lastLineFailState = if (lineFailActive) "Active" else "Clear"
        }
        prefs.lastPushResult = "$alarm push received"
        sendBroadcast(Intent(AlarmActions.ACTION_STATE_CHANGED).setPackage(packageName))
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

    private fun activeTitle(alarm: String): String = AlertCatalog.titleFor(alarm)

    private fun notificationIdFor(alarm: String): Int =
        AlarmActions.RESTORED_NOTIFICATION_ID + kotlin.math.abs(alarm.hashCode() % 500)

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
