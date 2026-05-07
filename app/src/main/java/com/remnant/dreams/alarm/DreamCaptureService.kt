package com.remnant.dreams.alarm

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import com.remnant.dreams.R
import com.remnant.dreams.RemnantApp
import com.remnant.dreams.data.DreamDatabase
import com.remnant.dreams.data.DreamEntry
import com.remnant.dreams.data.PrefsManager
import com.remnant.dreams.ui.JournalActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

class DreamCaptureService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var speechRecognizer: SpeechRecognizer? = null
    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var isListening = false
    private var isCaptureActive = false
    private var hasSpeechBeenDetected = false
    private var accumulatedTranscription = StringBuilder()
    private var lastSpeechTime = 0L
    private var captureStartTime = 0L
    private var silenceCheckRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification("Hearing your dream..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CAPTURE -> {
                if (!isCaptureActive) {
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

        // Start SpeechRecognizer first (gets mic priority for transcription).
        // Then try MediaRecorder for raw audio backup.
        // On Android 10+ (our minSdk 29), audio sharing is supported.
        // If MediaRecorder can't get the mic, it fails gracefully and we continue
        // with transcription only. Raw audio is best-effort.
        startSpeechRecognition()

        // Delay MediaRecorder start slightly to let SpeechRecognizer claim the mic first
        serviceScope.launch {
            delay(500)
            startAudioRecording()
        }

        startSilenceMonitor()
    }

    private fun startAudioRecording() {
        try {
            val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            val fileName = "dream_${dateFormat.format(Date())}.m4a"
            val audioDir = File(filesDir, "audio").apply { mkdirs() }
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
            Log.d(TAG, "MediaRecorder started successfully")
        } catch (e: Exception) {
            // Audio recording is best-effort -- SpeechRecognizer handles transcription
            Log.w(TAG, "MediaRecorder failed (mic may be exclusive to SpeechRecognizer): ${e.message}")
            mediaRecorder = null
            audioFile?.delete()
            audioFile = null
        }
    }

    private fun startSpeechRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "Speech recognition not available on this device")
            return
        }

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(createRecognitionListener())
            }
            startListening()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create SpeechRecognizer: ${e.message}")
        }
    }

    private fun startListening() {
        if (isListening) return
        isListening = true
        lastSpeechTime = System.currentTimeMillis()

        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000)
        }

        try {
            speechRecognizer?.startListening(recognizerIntent)
        } catch (e: Exception) {
            Log.e(TAG, "startListening failed: ${e.message}")
            isListening = false
        }
    }

    private fun restartListening() {
        isListening = false
        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {}

        serviceScope.launch {
            delay(300)
            if (!isListening && isCaptureActive) {
                startListening()
            }
        }
    }

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) {
                Log.d(TAG, "Ready for speech")
            }

            override fun onBeginningOfSpeech() {
                hasSpeechBeenDetected = true
                lastSpeechTime = System.currentTimeMillis()
                updateNotification("Recording your dream...")
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                isListening = false
            }

            override fun onError(error: Int) {
                isListening = false
                Log.d(TAG, "SpeechRecognizer error: $error")

                if (!isCaptureActive) return

                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        if (hasSpeechBeenDetected) {
                            val silenceTime = System.currentTimeMillis() - lastSpeechTime
                            if (silenceTime > SILENCE_TIMEOUT_MS) {
                                stopAndSave()
                            } else {
                                restartListening()
                            }
                        } else {
                            val elapsed = System.currentTimeMillis() - captureStartTime
                            if (elapsed > INITIAL_WAIT_MS) {
                                dismiss()
                            } else {
                                restartListening()
                            }
                        }
                    }
                    else -> {
                        if (hasSpeechBeenDetected && accumulatedTranscription.isNotEmpty()) {
                            stopAndSave()
                        } else {
                            dismiss()
                        }
                    }
                }
            }

            override fun onResults(results: android.os.Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val text = matches[0]
                    if (text.isNotBlank()) {
                        if (accumulatedTranscription.isNotEmpty()) {
                            accumulatedTranscription.append(" ")
                        }
                        accumulatedTranscription.append(text)
                        lastSpeechTime = System.currentTimeMillis()
                        hasSpeechBeenDetected = true
                        Log.d(TAG, "Transcription segment: ${text.take(50)}...")
                    }
                }
                restartListening()
            }

            override fun onPartialResults(partialResults: android.os.Bundle?) {
                lastSpeechTime = System.currentTimeMillis()
                hasSpeechBeenDetected = true
            }

            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        }
    }

    private fun startSilenceMonitor() {
        if (silenceCheckRunning) return
        silenceCheckRunning = true

        serviceScope.launch {
            while (silenceCheckRunning && isCaptureActive) {
                delay(2000)
                val now = System.currentTimeMillis()

                if (hasSpeechBeenDetected) {
                    val silenceTime = now - lastSpeechTime
                    if (silenceTime > SILENCE_TIMEOUT_MS) {
                        stopAndSave()
                        return@launch
                    }
                } else {
                    val elapsed = now - captureStartTime
                    if (elapsed > INITIAL_WAIT_MS) {
                        dismiss()
                        return@launch
                    }
                }

                val totalElapsed = now - captureStartTime
                if (totalElapsed > MAX_CAPTURE_MS) {
                    stopAndSave()
                    return@launch
                }
            }
        }
    }

    private fun stopAndSave() {
        if (!isCaptureActive) return
        isCaptureActive = false
        silenceCheckRunning = false

        cleanupRecognizer()
        cleanupRecorder()

        val transcription = accumulatedTranscription.toString().trim()
        val duration = ((System.currentTimeMillis() - captureStartTime) / 1000).toInt()
        val isFragment = transcription.length < 50

        Log.d(TAG, "Saving dream: ${transcription.length} chars, ${duration}s, fragment=$isFragment")

        if (transcription.isNotEmpty() || audioFile?.exists() == true) {
            serviceScope.launch {
                val entry = DreamEntry(
                    transcription = transcription.ifEmpty { "Couldn't catch the words -- tap to play the recording." },
                    audioPath = audioFile?.absolutePath,
                    durationSeconds = duration,
                    isFragment = isFragment
                )
                val db = DreamDatabase.getInstance(this@DreamCaptureService)
                db.dreamDao().insert(entry)

                val prefs = PrefsManager(this@DreamCaptureService)
                prefs.totalCaptures += 1
                updateStreak(prefs)

                AlarmScheduler.rescheduleForTomorrow(this@DreamCaptureService)
                broadcastCaptureComplete()
                stopSelf()
            }
        } else {
            AlarmScheduler.rescheduleForTomorrow(this)
            broadcastCaptureComplete()
            stopSelf()
        }
    }

    private fun dismiss() {
        if (!isCaptureActive) return
        isCaptureActive = false
        silenceCheckRunning = false

        cleanupRecognizer()
        cleanupRecorder()

        audioFile?.let { if (it.length() == 0L) it.delete() }

        AlarmScheduler.rescheduleForTomorrow(this)
        broadcastCaptureComplete()
        stopSelf()
    }

    private fun cleanupRecognizer() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
        isListening = false
    }

    private fun cleanupRecorder() {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (_: Exception) {
            audioFile?.delete()
            audioFile = null
        }
        mediaRecorder = null
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
        // User swiped from recents while capture was active -- save what we have
        if (isCaptureActive) {
            Log.w(TAG, "Task removed during active capture -- saving")
            stopAndSave()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isCaptureActive = false
        silenceCheckRunning = false
        serviceScope.cancel()
        try { speechRecognizer?.destroy() } catch (_: Exception) {}
        try { mediaRecorder?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DreamCapture"
        const val NOTIFICATION_ID = 2001
        const val ACTION_START_CAPTURE = "com.remnant.dreams.START_CAPTURE"
        const val ACTION_STOP = "com.remnant.dreams.STOP"
        const val ACTION_DISMISS = "com.remnant.dreams.DISMISS"
        const val ACTION_CAPTURE_COMPLETE = "com.remnant.dreams.CAPTURE_COMPLETE"
        private const val INITIAL_WAIT_MS = 15_000L
        private const val SILENCE_TIMEOUT_MS = 8_000L
        private const val MAX_CAPTURE_MS = 600_000L

        fun startCapture(context: Context) {
            val intent = Intent(context, DreamCaptureService::class.java).apply {
                action = ACTION_START_CAPTURE
            }
            context.startForegroundService(intent)
        }
    }
}
