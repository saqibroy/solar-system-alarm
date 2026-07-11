package com.saqib.wapdaalarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService

class AlarmForegroundService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: PrefsManager

    private var mediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var originalAlarmVolume: Int? = null
    private var timeoutMessage: String = "Alarm auto-stopped after 30 minutes"
    private var alarmName: String = "LINE_FAIL"
    private var severity: String = "critical"
    private var notificationMessage: String = "Turn off AC now"

    private val timeoutRunnable = Runnable {
        prefs.lastEvent = timeoutMessage
        stopAlarm()
    }

    override fun onCreate() {
        super.onCreate()
        prefs = PrefsManager(this)
        createAlarmChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            AlarmActions.ACTION_STOP -> {
                startForeground(AlarmActions.NOTIFICATION_ID, buildForegroundNotification())
                stopAlarm()
            }
            AlarmActions.ACTION_TEST -> {
                alarmName = "TEST"
                severity = "test"
                notificationMessage = "Local alarm test"
                startAlarm(intent.getLongExtra(AlarmActions.EXTRA_DURATION_MS, AlarmActions.TEST_DURATION_MS))
            }
            else -> {
                alarmName = intent?.getStringExtra(AlarmActions.EXTRA_ALARM_NAME) ?: "LINE_FAIL"
                severity = intent?.getStringExtra(AlarmActions.EXTRA_SEVERITY) ?: "critical"
                notificationMessage = intent?.getStringExtra(AlarmActions.EXTRA_MESSAGE) ?: messageForAlarm(alarmName)
                startAlarm(AlarmActions.AUTO_TIMEOUT_MS)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopPlaybackAndRestore()
        super.onDestroy()
    }

    private fun startAlarm(durationMs: Long) {
        startForeground(AlarmActions.NOTIFICATION_ID, buildForegroundNotification())

        if (mediaPlayer?.isPlaying == true) {
            scheduleTimeout(durationMs)
            return
        }

        prefs.isAlarmRunning = true
        prefs.lastEvent = if (durationMs == AlarmActions.TEST_DURATION_MS) {
            "Test alarm started for 10 seconds"
        } else {
            "$alarmName alarm is ringing"
        }
        sendBroadcast(Intent(AlarmActions.ACTION_STATE_CHANGED).setPackage(packageName))

        acquireWakeLock()
        maximizeAlarmVolume()
        startVibration()
        startPlayback()
        scheduleTimeout(durationMs)
    }

    private fun stopAlarm() {
        stopPlaybackAndRestore()
        prefs.isAlarmRunning = false
        sendBroadcast(Intent(AlarmActions.ACTION_STATE_CHANGED).setPackage(packageName))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopPlaybackAndRestore() {
        handler.removeCallbacks(timeoutRunnable)

        mediaPlayer?.runCatching {
            stop()
            release()
        }
        mediaPlayer = null

        stopVibration()
        abandonAudioFocus()
        restoreAlarmVolume()

        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun startPlayback() {
        runCatching {
            val player = resources.openRawResourceFd(R.raw.alarm).use { afd ->
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    isLooping = true
                    setVolume(1f, 1f)
                    prepare()
                    start()
                }
            }
            mediaPlayer = player
        }.onFailure {
            Log.e(TAG, "Failed to play bundled alarm", it)
            prefs.lastEvent = "Failed to play bundled alarm: ${it.message}"
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService<PowerManager>() ?: return
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:GridPowerAlarm").apply {
            setReferenceCounted(false)
            acquire(AlarmActions.AUTO_TIMEOUT_MS + 60_000L)
        }
    }

    private fun maximizeAlarmVolume() {
        val audioManager = getSystemService<AudioManager>() ?: return
        originalAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { }
                .build()
            audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
    }

    private fun restoreAlarmVolume() {
        val audioManager = getSystemService<AudioManager>() ?: return
        originalAlarmVolume?.let { audioManager.setStreamVolume(AudioManager.STREAM_ALARM, it, 0) }
        originalAlarmVolume = null
    }

    private fun abandonAudioFocus() {
        val audioManager = getSystemService<AudioManager>() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        audioFocusRequest = null
    }

    private fun startVibration() {
        val pattern = longArrayOf(0L, 600L, 400L, 900L)
        val effect = VibrationEffect.createWaveform(pattern, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService<VibratorManager>()?.defaultVibrator?.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            getSystemService<Vibrator>()?.vibrate(effect)
        }
    }

    private fun stopVibration() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService<VibratorManager>()?.defaultVibrator?.cancel()
        } else {
            @Suppress("DEPRECATION")
            getSystemService<Vibrator>()?.cancel()
        }
    }

    private fun scheduleTimeout(durationMs: Long) {
        timeoutMessage = if (durationMs == AlarmActions.TEST_DURATION_MS) {
            "Test alarm finished after 10 seconds"
        } else {
            "Alarm auto-stopped after 30 minutes"
        }
        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, durationMs)
    }

    private fun buildForegroundNotification(): android.app.Notification {
        val stopIntent = Intent(this, AlarmForegroundService::class.java).setAction(AlarmActions.ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPendingIntent = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmPendingIntent = PendingIntent.getActivity(
            this,
            3,
            Intent(this, AlarmActivity::class.java)
                .putExtra(AlarmActions.EXTRA_ALARM_NAME, alarmName)
                .putExtra(AlarmActions.EXTRA_MESSAGE, notificationMessage),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = AlertCatalog.titleFor(alarmName)

        return NotificationCompat.Builder(this, AlarmActions.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(notificationMessage)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notificationMessage))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openPendingIntent)
            .setFullScreenIntent(alarmPendingIntent, true)
            .addAction(R.drawable.ic_launcher, "STOP", stopPendingIntent)
            .build()
    }

    private fun messageForAlarm(name: String): String =
        AlertCatalog.activeMessageFor(name)

    private fun createAlarmChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val notificationManager = getSystemService<NotificationManager>() ?: return

        val channel = NotificationChannel(
            AlarmActions.NOTIFICATION_CHANNEL_ID,
            "Grid power alarm",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Persistent alarm while grid power is out"
            if (notificationManager.isNotificationPolicyAccessGranted) {
                setBypassDnd(true)
            }
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }

        notificationManager.createNotificationChannel(channel)
    }

    private companion object {
        const val TAG = "AlarmService"
    }
}
