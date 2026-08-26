package com.remnant.dreams.alarm

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
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
import androidx.core.content.ContextCompat
import com.remnant.dreams.R
import com.remnant.dreams.RemnantApp
import com.remnant.dreams.data.PrefsManager
import com.remnant.dreams.ui.JournalActivity

/**
 * Rings the alarm when notifications are not available.
 *
 * The alarm normally arrives as a high-priority notification with a full-screen intent.
 * With notifications refused there is no notification to carry that intent, so the exact
 * alarm would fire into silence and the app would fail at the one job it has. The exact
 * alarm reaches [AlarmReceiver] either way; a broadcast from an exact alarm may start a
 * foreground service, and a foreground service runs perfectly well with its notification
 * suppressed -- so this service makes the noise. The screen comes afterwards, when the
 * user opens the app and [JournalActivity] routes them into [AlarmActivity].
 *
 * A fallback, not a replacement: with notifications allowed, nothing here runs.
 */
class AlarmRingtoneService : Service() {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())

    private val ringOutRunnable = Runnable {
        Log.d(TAG, "Fallback alarm rang out")
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val timeLeft = AlarmRingingState.ringingTimeLeftMs(
            PrefsManager(this).alarmRingingSince,
            System.currentTimeMillis()
        )

        if (timeLeft <= 0L) {
            // Nothing outstanding: a stale restart, or the alarm was answered already.
            stopSelf()
            return START_NOT_STICKY
        }

        if (player == null) {
            startAlarmTone()
            startVibration()
        }

        handler.removeCallbacks(ringOutRunnable)
        handler.postDelayed(ringOutRunnable, timeLeft)

        // Never sticky: a restart after a process kill would ring at some unrelated hour.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(ringOutRunnable)
        try {
            player?.stop()
            player?.release()
        } catch (_: Exception) {}
        player = null
        vibrator?.cancel()
        vibrator = null
        super.onDestroy()
    }

    /**
     * The notification a foreground service is required to have. The user has switched
     * notifications off, so nobody sees it -- it exists so the service may run, and it is
     * built properly in case they switch them back on while the alarm is still going.
     */
    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, JournalActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, RemnantApp.CHANNEL_ALARM)
            .setContentTitle("Remnant")
            .setContentText("Alarm ringing -- open Remnant to capture your dream")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    private fun startAlarmTone() {
        try {
            // The device's own alarm sound on the alarm stream, so the user's ringtone
            // choice and alarm volume are respected exactly as on the full-screen path.
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                // Being in the foreground does not hold the CPU awake on its own, and a
                // dozing device is exactly the situation this service exists for.
                setWakeMode(this@AlarmRingtoneService, PowerManager.PARTIAL_WAKE_LOCK)
                setDataSource(this@AlarmRingtoneService, alarmUri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fallback alarm tone failed: ${e.message}")
        }
    }

    private fun startVibration() {
        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        val pattern = longArrayOf(0, 500, 500)
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
    }

    companion object {
        private const val TAG = "AlarmRingtone"
        private const val NOTIFICATION_ID = 1002

        /**
         * Starts the fallback ring. Safe to call from the exact-alarm broadcast, which is
         * exempt from the background foreground-service start restrictions.
         */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, AlarmRingtoneService::class.java)
                )
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException on a device that does not
                // honour the exemption. There is nothing further to fall back to.
                Log.e(TAG, "Fallback alarm could not start: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AlarmRingtoneService::class.java))
        }
    }
}
