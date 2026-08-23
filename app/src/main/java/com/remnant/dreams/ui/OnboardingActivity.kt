package com.remnant.dreams.ui

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.remnant.dreams.alarm.AlarmScheduler
import com.remnant.dreams.data.PrefsManager
import com.remnant.dreams.databinding.ActivityOnboardingBinding
import com.remnant.dreams.tts.ApiKeys
import com.remnant.dreams.tts.CloudTtsGenerator
import com.remnant.dreams.tts.VoiceOption
import kotlinx.coroutines.launch
import java.util.Locale

class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private lateinit var prefs: PrefsManager
    private var selectedHour = 7
    private var selectedMinute = 0

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            completeOnboarding()
        } else {
            Toast.makeText(this, "Microphone permission is required to capture dreams", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EdgeToEdgeUtil.enable(this)

        prefs = PrefsManager(this)

        // Skip if onboarding already done
        if (prefs.onboardingComplete) {
            startActivity(Intent(this, JournalActivity::class.java))
            finish()
            return
        }

        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EdgeToEdgeUtil.applySystemBarInsets(binding.root)

        binding.btnSetTime.setOnClickListener {
            showTimePicker()
        }

        binding.btnStart.setOnClickListener {
            val name = binding.editName.text.toString().trim()
            if (name.isEmpty()) {
                binding.editName.error = "What should we call you?"
                return@setOnClickListener
            }

            prefs.userName = name
            prefs.alarmHour = selectedHour
            prefs.alarmMinute = selectedMinute

            requestPermissions()
        }

        updateTimeDisplay()
    }

    private fun showTimePicker() {
        TimePickerDialog(this, { _, hour, minute ->
            selectedHour = hour
            selectedMinute = minute
            updateTimeDisplay()
        }, selectedHour, selectedMinute, false).show()
    }

    private fun updateTimeDisplay() {
        val amPm = if (selectedHour < 12) "AM" else "PM"
        val displayHour = when {
            selectedHour == 0 -> 12
            selectedHour > 12 -> selectedHour - 12
            else -> selectedHour
        }
        binding.btnSetTime.text = String.format(Locale.US, "%d:%02d %s", displayHour, selectedMinute, amPm)
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needsPermission = permissions.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needsPermission) {
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            completeOnboarding()
        }
    }

    private fun completeOnboarding() {
        prefs.onboardingComplete = true
        prefs.alarmEnabled = true

        // Check exact alarm permission on Android 12+
        checkExactAlarmPermission()

        // Schedule the alarm
        AlarmScheduler.schedule(this)

        // Request battery optimisation exemption
        requestBatteryExemption()

        // Pre-cache the Cloud TTS prompt (background, non-blocking)
        cacheVoicePrompt()

        startActivity(Intent(this, JournalActivity::class.java))
        finish()
    }

    private fun checkExactAlarmPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            val alarmManager = getSystemService(android.app.AlarmManager::class.java)
            if (!alarmManager.canScheduleExactAlarms()) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {}
            }
        }
    }

    private fun cacheVoicePrompt() {
        val apiKey = ApiKeys.GOOGLE_CLOUD_TTS
        if (apiKey.isEmpty()) return

        val name = prefs.userName.ifEmpty { "there" }
        val voiceId = prefs.selectedVoiceId.ifEmpty { VoiceOption.DEFAULT.id }
        val voice = VoiceOption.findById(voiceId)
        val cacheKey = "$voiceId|$name"

        if (prefs.promptCacheKey == cacheKey) return // Already cached

        lifecycleScope.launch {
            val ttsGen = CloudTtsGenerator(this@OnboardingActivity)

            // Cache dream prompt
            val dreamText = "Good morning $name. What did you dream about last night?"
            val dreamResult = ttsGen.generatePrompt(dreamText, voice, apiKey, CloudTtsGenerator.PROMPT_DREAM)

            // Cache wake check prompt (for companion mode)
            val wakeText = "Are you awake, $name?"
            ttsGen.generatePrompt(wakeText, voice, apiKey, CloudTtsGenerator.PROMPT_WAKE_CHECK)

            if (dreamResult != null) {
                prefs.promptCacheKey = cacheKey
            }
        }
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (_: Exception) {}
        }
    }
}
