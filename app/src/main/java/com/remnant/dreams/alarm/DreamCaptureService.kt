package com.remnant.dreams.alarm

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.remnant.dreams.R
import com.remnant.dreams.RemnantApp
import com.remnant.dreams.data.DreamDatabase
import com.remnant.dreams.data.DreamEntry
import com.remnant.dreams.data.PrefsManager
import com.remnant.dreams.data.TranscriptPlaceholders
import com.remnant.dreams.ui.JournalActivity
import com.remnant.dreams.worker.TranscriptionWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

/**
 * Records the dream and hands the recording off to be transcribed.
 *
 * The microphone is held by one component and one only. This service used to run a
 * SpeechRecognizer and a MediaRecorder against the microphone at the same time, on the
 * theory that Android 10's concurrent capture would feed both. It does not: the platform
 * arbitrates, one client gets the audio and the other gets silence, and on the hardware
 * this was tested against the recogniser lost every single time. The recording came back
 * with the user's voice clearly on it while the recogniser reported ERROR_NO_MATCH sixteen
 * times over the same fifty-two seconds. Every capture for three months produced audio and
 * no words.
 *
 * So the recorder takes the microphone alone, and recognition happens afterwards, against
 * the finished file, where nothing is competing with it -- see [TranscriptionWorker]. That
 * also means the decision to keep or bin a capture can hang off the recorder's own signal
 * level rather than off a recogniser that may not be hearing anything at all: see
 * [CapturePolicy].
 */
class DreamCaptureService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var isCaptureActive = false
    private var heardSpeech = false
    private var peakAmplitude = 0
    private var lastSpeechTime = 0L
    private var captureStartTime = 0L
    private var monitorRunning = false

    // Row id of the entry for this capture. 0 means "not saved yet". Only ever read or
    // written inside a saveScope block (see enqueueWrite).
    @Volatile
    private var draftEntryId = 0L

    // Tail of the write chain. Only touched on the main thread, which is where every
    // capture callback and every stopAndSave/dismiss call runs.
    private var pendingWrite: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        // A microphone foreground service without RECORD_AUDIO is a SecurityException on
        // Android 14, thrown out of startForeground and straight through to an "Application
        // Error" dialog. The permission is revocable at any time from Settings, so the
        // service has to expect to find it gone and bow out quietly instead of crashing.
        if (!hasMicrophonePermission()) {
            Log.w(TAG, "Microphone permission not granted -- capture cannot run")
            bowOut()
            return
        }

        try {
            startForeground(NOTIFICATION_ID, buildNotification("Hearing your dream..."))
        } catch (e: Exception) {
            // Any other reason the platform refuses the foreground service is still not
            // worth taking the app down for.
            Log.e(TAG, "Could not start capture in the foreground: ${e.message}")
            bowOut()
        }
    }

    /** Stands the service down without capturing, leaving tomorrow's alarm intact. */
    private fun bowOut() {
        isCaptureActive = false
        AlarmScheduler.rescheduleForTomorrow(this)
        broadcastCaptureComplete()
        stopSelf()
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CAPTURE -> {
                // onCreate may already have stood the service down.
                if (!isCaptureActive && hasMicrophonePermission()) {
                    startCapture()
                }
            }
            ACTION_STOP -> stopAndSave()
            ACTION_DISMISS -> dismiss()
        }
        return START_NOT_STICKY
    }

    private fun startCapture() {
        isCaptureActive = true
        captureStartTime = System.currentTimeMillis()
        lastSpeechTime = captureStartTime

        if (!startAudioRecording()) {
            // Without the recorder there is no microphone and no capture. Nothing to save.
            Log.e(TAG, "Capture aborted -- recorder would not start")
            bowOut()
            return
        }

        startLevelMonitor()
    }

    /** @return true when the recorder is running and the microphone is ours. */
    private fun startAudioRecording(): Boolean {
        return try {
            val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            val fileName = "dream_${dateFormat.format(Date())}.m4a"
            val audioDir = File(filesDir, AUDIO_DIR).apply { mkdirs() }
            audioFile = File(audioDir, fileName)

            mediaRecorder = (if (Build.VERSION.SDK_INT >= 31) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(audioFile?.absolutePath)
                prepare()
                start()
            }
            Log.d(TAG, "Recorder started -- microphone held by this service alone")
            true
        } catch (e: Exception) {
            Log.e(TAG, "MediaRecorder failed to start: ${e.message}")
            mediaRecorder = null
            audioFile?.delete()
            audioFile = null
            false
        }
    }

    /**
     * Watches the signal coming off the recorder and applies [CapturePolicy].
     *
     * getMaxAmplitude() reports the loudest sample since the previous call, which is enough
     * to tell a person talking from an empty room -- and unlike the recogniser it is reading
     * the microphone this service actually holds.
     */
    private fun startLevelMonitor() {
        if (monitorRunning) return
        monitorRunning = true

        serviceScope.launch {
            var announced = false

            while (monitorRunning && isCaptureActive) {
                delay(LEVEL_POLL_MS)

                val amplitude = try {
                    mediaRecorder?.maxAmplitude ?: 0
                } catch (e: Exception) {
                    Log.w(TAG, "Could not read recorder level: ${e.message}")
                    0
                }

                if (amplitude > peakAmplitude) peakAmplitude = amplitude

                val now = System.currentTimeMillis()
                if (CapturePolicy.isSpeech(amplitude)) {
                    if (!heardSpeech) Log.d(TAG, "Speech detected at level $amplitude")
                    heardSpeech = true
                    lastSpeechTime = now
                    if (!announced) {
                        announced = true
                        updateNotification("Recording your dream...")
                    }
                }

                when (
                    CapturePolicy.decide(
                        heardSpeech = heardSpeech,
                        elapsedMs = now - captureStartTime,
                        sinceSpeechMs = now - lastSpeechTime
                    )
                ) {
                    CapturePolicy.Decision.CONTINUE -> Unit
                    CapturePolicy.Decision.SAVE -> {
                        stopAndSave()
                        return@launch
                    }
                    CapturePolicy.Decision.DISCARD -> {
                        Log.d(TAG, "Nobody spoke (peak=$peakAmplitude) -- discarding capture")
                        dismiss()
                        return@launch
                    }
                }
            }
        }
    }

    /**
     * Queues a database write on [saveScope], after every write queued before it.
     *
     * Chaining on the previous job is what keeps [draftEntryId] race-free: the chain is
     * built on the main thread, so the order is the order the writes were asked for, and a
     * block cannot start until the block that may have assigned the row id has finished.
     */
    private fun enqueueWrite(block: suspend () -> Unit) {
        val previous = pendingWrite
        pendingWrite = saveScope.launch {
            previous?.join()
            try {
                block()
            } catch (e: Exception) {
                Log.e(TAG, "Dream write failed: ${e.message}")
            }
        }
    }

    private fun stopAndSave() {
        if (!isCaptureActive) return
        isCaptureActive = false
        monitorRunning = false

        val recorded = cleanupRecorder()

        val duration = ((System.currentTimeMillis() - captureStartTime) / 1000).toInt()
        val savedAudioPath = audioFile?.takeIf { recorded && it.exists() && it.length() > 0 }
            ?.absolutePath

        // Peak level is worth having in the log: it is the difference between "the room was
        // quiet" and "the microphone was not reaching us", which is exactly the question
        // that went unanswered for three months.
        Log.d(TAG, "Saving dream: ${duration}s, peak=$peakAmplitude, audio=${savedAudioPath != null}")

        if (savedAudioPath == null) {
            // Nothing playable and nothing transcribable. An empty row helps nobody.
            Log.w(TAG, "Nothing was recorded -- saving no entry")
            audioFile?.delete()
            bowOut()
            return
        }

        val context = applicationContext
        enqueueWrite {
            var rowId = draftEntryId
            try {
                val dao = DreamDatabase.getInstance(context).dreamDao()
                val existing = if (draftEntryId == 0L) null else dao.getDreamById(draftEntryId)

                if (existing == null) {
                    rowId = dao.insert(
                        DreamEntry(
                            // The words are not known yet -- TranscriptionWorker fills them
                            // in once it has read the finished recording. Until then the
                            // entry says what it honestly has: the audio.
                            transcription = TranscriptPlaceholders.NO_TRANSCRIPT_WITH_AUDIO,
                            audioPath = savedAudioPath,
                            durationSeconds = duration,
                            isFragment = true
                        )
                    )
                    draftEntryId = rowId
                } else {
                    dao.update(
                        existing.copy(
                            audioPath = savedAudioPath,
                            durationSeconds = duration
                        )
                    )
                    rowId = existing.id
                }

                val prefs = PrefsManager(context)
                prefs.totalCaptures += 1
                updateStreak(prefs)
            } finally {
                // The alarm must be rescheduled and the service stopped even if the write
                // blew up, or the user gets no alarm tomorrow.
                withContext(Dispatchers.Main) {
                    if (rowId != 0L) {
                        TranscriptionWorker.enqueue(context, rowId, savedAudioPath)
                    }
                    AlarmScheduler.rescheduleForTomorrow(context)
                    broadcastCaptureComplete()
                    stopSelf()
                }
            }
        }
    }

    /**
     * Ends the capture keeping nothing. Used when nobody spoke -- a recording of an empty
     * room is not a dream, and saving one costs the user an entry, the storage and a day's
     * streak they did not earn.
     */
    private fun dismiss() {
        if (!isCaptureActive) return
        isCaptureActive = false
        monitorRunning = false

        cleanupRecorder()

        audioFile?.delete()
        audioFile = null

        AlarmScheduler.rescheduleForTomorrow(this)
        broadcastCaptureComplete()
        stopSelf()
    }

    /**
     * Stops the recorder so the file is finalised and playable.
     *
     * @return true when the recording was closed properly. A recorder stopped before it
     *   captured anything throws, and leaves an MPEG-4 with no moov atom that nothing can
     *   play, so the file is binned rather than handed on as if it were a dream.
     */
    private fun cleanupRecorder(): Boolean {
        val recorder = mediaRecorder ?: return false
        mediaRecorder = null

        return try {
            recorder.stop()
            recorder.release()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Recorder would not stop cleanly -- recording unusable: ${e.message}")
            try {
                recorder.release()
            } catch (_: Exception) {
            }
            audioFile?.delete()
            audioFile = null
            false
        }
    }

    private fun broadcastCaptureComplete() {
        val intent = Intent(ACTION_CAPTURE_COMPLETE).apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun updateStreak(prefs: PrefsManager) {
        val today = LocalDate.now().toString()
        val yesterday = LocalDate.now().minusDays(1).toString()

        when (prefs.lastCaptureDate) {
            today -> { /* Already captured today */ }
            yesterday -> {
                prefs.currentStreak += 1
                prefs.lastCaptureDate = today
            }
            else -> {
                prefs.currentStreak = 1
                prefs.lastCaptureDate = today
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val journalIntent = Intent(this, JournalActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, journalIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, RemnantApp.CHANNEL_CAPTURE_STATUS)
            .setContentTitle("Remnant")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // User swiped from recents while capture was active -- keep what we have
        if (isCaptureActive) {
            Log.w(TAG, "Task removed during active capture -- saving")
            stopAndSave()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isCaptureActive = false
        monitorRunning = false
        // serviceScope only -- saveScope is left alone so any queued write still lands.
        serviceScope.cancel()
        try {
            mediaRecorder?.release()
        } catch (_: Exception) {
        }
        mediaRecorder = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DreamCapture"
        const val NOTIFICATION_ID = 2001
        const val ACTION_START_CAPTURE = "com.remnant.dreams.START_CAPTURE"
        const val ACTION_STOP = "com.remnant.dreams.STOP"
        const val ACTION_DISMISS = "com.remnant.dreams.DISMISS"
        const val ACTION_CAPTURE_COMPLETE = "com.remnant.dreams.CAPTURE_COMPLETE"

        /** Directory under filesDir holding the recordings. */
        const val AUDIO_DIR = "audio"

        /** How often the recorder's level is sampled while a capture runs. */
        private const val LEVEL_POLL_MS = 500L

        // Database writes have to outlive the service. serviceScope is cancelled in
        // onDestroy -- correct for the level monitor, fatal for a save still in flight --
        // so every Room and prefs write runs here instead, on a scope nothing cancels.
        private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Starts a capture, if the microphone is available to us.
         *
         * @return false when RECORD_AUDIO has been revoked, so the caller can tell the user
         *   rather than starting a service that can only stand itself back down.
         */
        fun startCapture(context: Context): Boolean {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                Log.w(TAG, "Not starting capture -- microphone permission revoked")
                return false
            }

            val intent = Intent(context, DreamCaptureService::class.java).apply {
                action = ACTION_START_CAPTURE
            }
            context.startForegroundService(intent)
            return true
        }
    }
}
