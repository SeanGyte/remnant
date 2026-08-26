package com.remnant.dreams.alarm

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.remnant.dreams.R
import com.remnant.dreams.ui.EdgeToEdgeUtil
import com.remnant.dreams.data.PrefsManager
import com.remnant.dreams.databinding.ActivityAlarmBinding
import com.remnant.dreams.tts.CloudTtsGenerator
import com.remnant.dreams.tts.VoiceOption
import java.io.File
import java.util.Locale

class AlarmActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlarmBinding
    private lateinit var prefs: PrefsManager
    private var alarmPlayer: MediaPlayer? = null
    private var promptPlayer: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var vibrator: Vibrator? = null
    private var wakeCheckRecognizer: SpeechRecognizer? = null
    private var alarmDismissed = false
    private var isCompanionMode = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val captureCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isFinishing) finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EdgeToEdgeUtil.enable(this)
        binding = ActivityAlarmBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EdgeToEdgeUtil.applySystemBarInsets(binding.root)

        prefs = PrefsManager(this)
        isCompanionMode = prefs.companionMode

        // The alarm screen is up, so the fallback ringer has done its job -- whichever
        // route got us here, this alarm is no longer outstanding.
        prefs.alarmRingingSince = 0L
        AlarmRingtoneService.stop(this)

        NotificationManagerCompat.from(this).cancel(AlarmReceiver.ALARM_NOTIFICATION_ID)

        registerReceiver(
            captureCompleteReceiver,
            IntentFilter(DreamCaptureService.ACTION_CAPTURE_COMPLETE),
            Context.RECEIVER_NOT_EXPORTED
        )

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })

        setupWakeScreen()

        if (isCompanionMode) {
            startCompanionMode()
        } else {
            startStandaloneMode()
        }
    }

    // ── Standalone Mode (existing behaviour) ──────────────────────────────

    private fun startStandaloneMode() {
        Log.d(TAG, "Starting in standalone mode")
        startAlarmTone()
        startVibration()

        binding.btnDismiss.setOnClickListener { dismissAlarm() }
        binding.btnSkip.setOnClickListener { skipCapture() }
    }

    // ── Companion Mode ────────────────────────────────────────────────────

    private fun startCompanionMode() {
        Log.d(TAG, "Starting in companion mode")

        // No alarm tone, no vibration. Screen lights up softly.
        // Hide buttons -- voice detection handles the flow.
        binding.btnDismiss.visibility = View.GONE
        binding.btnSkip.visibility = View.GONE
        binding.textStatus.text = ""
        binding.textCompanionHint.visibility = View.GONE

        // Lower screen brightness for a soft wake
        window.attributes = window.attributes.apply {
            screenBrightness = 0.15f
        }

        // Play "Are you awake, {name}?" then listen for voice
        playWakeCheck()
    }

    /**
     * Play the "Are you awake?" prompt via cached Cloud TTS or fallback on-device TTS.
     * When playback finishes, start listening for any voice activity.
     */
    private fun playWakeCheck() {
        val cachedWakeCheck = cachedCloudPrompt(CloudTtsGenerator.PROMPT_WAKE_CHECK)
        if (cachedWakeCheck != null) {
            try {
                promptPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    setDataSource(cachedWakeCheck.absolutePath)
                    prepare()
                    setOnCompletionListener { startWakeCheckListener() }
                    start()
                }
                Log.d(TAG, "Playing cached wake check prompt")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Failed to play cached wake check: ${e.message}")
            }
        }

        // Fallback to on-device TTS
        Log.d(TAG, "Falling back to on-device TTS for wake check")
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setSpeechRate(0.9f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == "wake_check") {
                            runOnUiThread { startWakeCheckListener() }
                        }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        runOnUiThread { startWakeCheckListener() }
                    }
                })

                val name = prefs.userName.ifEmpty { "there" }
                tts?.speak(
                    "Are you awake, $name?",
                    TextToSpeech.QUEUE_FLUSH, null, "wake_check"
                )
            } else {
                runOnUiThread { startWakeCheckListener() }
            }
        }
    }

    /**
     * Start a lightweight SpeechRecognizer that just detects ANY voice activity.
     * On any speech detected: transition to dream prompt.
     * After 15 seconds of silence: dismiss silently.
     */
    private fun startWakeCheckListener() {
        binding.textCompanionHint.visibility = View.VISIBLE
        binding.textCompanionHint.text = getString(R.string.companion_listening)

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "SpeechRecognizer not available -- falling back to button")
            companionFallbackToButton()
            return
        }

        try {
            wakeCheckRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(createWakeCheckListener())
            }

            val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 15000)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 15000)
            }

            wakeCheckRecognizer?.startListening(recognizerIntent)
            Log.d(TAG, "Wake check listener started")

            // Hard timeout: 15 seconds of no voice -> silently dismiss
            mainHandler.postDelayed(companionTimeoutRunnable, COMPANION_TIMEOUT_MS)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start wake check recognizer: ${e.message}")
            companionFallbackToButton()
        }
    }

    private val companionTimeoutRunnable = Runnable {
        Log.d(TAG, "Companion mode timeout -- no voice detected, dismissing")
        cleanupWakeCheckRecognizer()
        silentDismiss()
    }

    private fun createWakeCheckListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "Wake check: ready for speech")
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "Wake check: voice detected!")
                onWakeCheckVoiceDetected()
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                Log.d(TAG, "Wake check recognizer error: $error")
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        // No voice heard -- silent dismiss
                        mainHandler.removeCallbacks(companionTimeoutRunnable)
                        cleanupWakeCheckRecognizer()
                        silentDismiss()
                    }
                    else -> {
                        // Other error -- fall back to button UI
                        mainHandler.removeCallbacks(companionTimeoutRunnable)
                        cleanupWakeCheckRecognizer()
                        companionFallbackToButton()
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                // Any result at all means the user spoke -- they're awake
                Log.d(TAG, "Wake check: got results")
                onWakeCheckVoiceDetected()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                // Partial results also mean voice activity
                Log.d(TAG, "Wake check: partial results -- voice detected")
                onWakeCheckVoiceDetected()
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    /**
     * Voice was detected during wake check. User is awake.
     * Stop the recognizer, play the dream prompt, then start capture.
     */
    private var wakeCheckTriggered = false

    private fun onWakeCheckVoiceDetected() {
        if (wakeCheckTriggered) return
        wakeCheckTriggered = true

        mainHandler.removeCallbacks(companionTimeoutRunnable)
        cleanupWakeCheckRecognizer()

        // Raise brightness slightly now the user is confirmed awake
        window.attributes = window.attributes.apply {
            screenBrightness = 0.5f
        }

        binding.textCompanionHint.visibility = View.GONE
        binding.textStatus.text = getString(R.string.alarm_preparing)

        // Play the dream prompt, then start capture
        playPrompt()
    }

    /**
     * If SpeechRecognizer isn't available in companion mode, fall back to showing
     * the "I'm Awake" button so the user can still proceed.
     */
    private fun companionFallbackToButton() {
        Log.w(TAG, "Companion mode falling back to button UI")
        binding.textCompanionHint.visibility = View.GONE
        binding.btnDismiss.visibility = View.VISIBLE
        binding.btnSkip.visibility = View.VISIBLE
        binding.btnDismiss.setOnClickListener { dismissAlarm() }
        binding.btnSkip.setOnClickListener { skipCapture() }

        // Raise brightness so buttons are visible
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    private fun cleanupWakeCheckRecognizer() {
        try {
            wakeCheckRecognizer?.stopListening()
            wakeCheckRecognizer?.cancel()
            wakeCheckRecognizer?.destroy()
        } catch (_: Exception) {}
        wakeCheckRecognizer = null
    }

    /**
     * Silently dismiss -- user didn't respond in companion mode, presumably still asleep.
     */
    private fun silentDismiss() {
        Log.d(TAG, "Silent dismiss -- user appears to still be asleep")
        AlarmScheduler.rescheduleForTomorrow(this)
        finish()
    }

    // ── Shared: Wake Screen ───────────────────────────────────────────────

    private fun setupWakeScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(KeyguardManager::class.java)
            km.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ── Standalone: Alarm Tone & Vibration ────────────────────────────────

    private fun startAlarmTone() {
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            alarmPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@AlarmActivity, alarmUri)
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) {}
    }

    private fun startVibration() {
        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            val vm = getSystemService(VibratorManager::class.java)
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        val pattern = longArrayOf(0, 500, 500)
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
    }

    // ── Standalone: Dismiss & Skip ────────────────────────────────────────

    private fun dismissAlarm() {
        if (alarmDismissed) return
        alarmDismissed = true

        stopAlarmTone()
        stopVibration()

        binding.btnDismiss.isEnabled = false
        binding.btnSkip.isEnabled = false
        binding.textStatus.text = getString(R.string.alarm_preparing)

        playPrompt()
    }

    private fun skipCapture() {
        alarmDismissed = true
        stopAlarmTone()
        stopVibration()
        cleanupWakeCheckRecognizer()
        mainHandler.removeCallbacks(companionTimeoutRunnable)
        AlarmScheduler.rescheduleForTomorrow(this)
        finish()
    }

    // ── Shared: Dream Prompt & Capture ────────────────────────────────────

    /**
     * Cached Cloud prompt audio, but only while a Cloud voice is actually selected --
     * selecting one is the consent to use it, so unselecting it drops back to the phone's
     * own voice even if old audio is still sitting in the cache.
     */
    private fun cachedCloudPrompt(promptType: String): File? =
        if (VoiceOption.selectedOrNull(prefs.selectedVoiceId) == null) null
        else CloudTtsGenerator(this).getCachedPrompt(promptType)

    private fun playPrompt() {
        // Try cached Cloud TTS dream prompt first
        val cachedPrompt = cachedCloudPrompt(CloudTtsGenerator.PROMPT_DREAM)
        if (cachedPrompt != null) {
            try {
                promptPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    setDataSource(cachedPrompt.absolutePath)
                    prepare()
                    setOnCompletionListener { startDreamCapture() }
                    start()
                }
                Log.d(TAG, "Playing cached Cloud TTS dream prompt")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Failed to play cached prompt: ${e.message}")
            }
        }

        // Fallback to on-device TTS
        Log.d(TAG, "Falling back to on-device TTS")
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setSpeechRate(0.9f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == "dream_prompt") {
                            runOnUiThread { startDreamCapture() }
                        }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        runOnUiThread { startDreamCapture() }
                    }
                })

                val name = prefs.userName.ifEmpty { "there" }
                tts?.speak(
                    "Good morning $name. What did you dream about last night?",
                    TextToSpeech.QUEUE_FLUSH, null, "dream_prompt"
                )
            } else {
                runOnUiThread { startDreamCapture() }
            }
        }
    }

    private fun startDreamCapture() {
        binding.textStatus.text = getString(R.string.alarm_listening)
        binding.btnDismiss.visibility = View.GONE
        binding.textCompanionHint.visibility = View.GONE
        binding.btnSkip.text = "Done"
        binding.btnSkip.visibility = View.VISIBLE
        binding.btnSkip.isEnabled = true
        binding.btnSkip.setOnClickListener {
            val stopIntent = Intent(this, DreamCaptureService::class.java).apply {
                action = DreamCaptureService.ACTION_STOP
            }
            startService(stopIntent)
            finish()
        }

        if (!DreamCaptureService.startCapture(this)) {
            // The microphone has been switched off since setup. Say so rather than showing
            // a listening screen that cannot hear anything.
            binding.textStatus.text = getString(R.string.alarm_microphone_off)
            binding.btnSkip.text = "Close"
            mainHandler.postDelayed({ if (!isFinishing) finish() }, 8_000)
            return
        }

        mainHandler.postDelayed({
            if (!isFinishing) finish()
        }, 620_000)
    }

    // ── Cleanup ───────────────────────────────────────────────────────────

    private fun stopAlarmTone() {
        try {
            alarmPlayer?.stop()
            alarmPlayer?.release()
        } catch (_: Exception) {}
        alarmPlayer = null
    }

    private fun stopVibration() {
        vibrator?.cancel()
    }

    override fun onDestroy() {
        try { unregisterReceiver(captureCompleteReceiver) } catch (_: Exception) {}
        mainHandler.removeCallbacksAndMessages(null)
        stopAlarmTone()
        cleanupWakeCheckRecognizer()
        try { promptPlayer?.release() } catch (_: Exception) {}
        tts?.shutdown()

        if (::prefs.isInitialized && prefs.alarmEnabled) {
            AlarmScheduler.rescheduleForTomorrow(this)
        }

        super.onDestroy()
    }

    companion object {
        private const val TAG = "AlarmActivity"
        private const val COMPANION_TIMEOUT_MS = 15_000L
    }
}
