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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.remnant.dreams.AppScope
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
        // A permission we already held isn't in the result map, so fall back to a live check
        // rather than assuming -- a cancelled prompt returns an empty map.
        val micGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: isGranted(Manifest.permission.RECORD_AUDIO)
        val notificationsDenied = permissions[Manifest.permission.POST_NOTIFICATIONS] == false

        when {
            // Notifications don't block onboarding, but the alarm is delivered as one, so say
            // so before moving on. The microphone dialog wins if both were denied.
            micGranted && notificationsDenied -> showNotificationsBlockedDialog()
            // Only the microphone is required -- without it there is nothing to record.
            micGranted -> completeOnboarding()
            // Android still shows the prompt, so tapping Start again is worth doing.
            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                Toast.makeText(
                    this,
                    "Remnant needs the microphone to record your dream. Tap Start to allow it.",
                    Toast.LENGTH_LONG
                ).show()
            }
            // Android has stopped asking -- the only way back is the app's settings page.
            else -> showMicrophoneBlockedDialog()
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
            prefs.cloudVoiceOptIn = binding.checkCloudVoice.isChecked

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

        val needsPermission = permissions.any { !isGranted(it) }

        if (needsPermission) {
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            completeOnboarding()
        }
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Explains a permission the user has switched off and offers the app's settings page --
     * the only route back once Android has stopped prompting. [onDismiss] runs however the
     * dialog is closed: button, back press or a tap outside.
     */
    private fun showPermissionSettingsDialog(
        title: String,
        message: String,
        dismissLabel: String,
        onDismiss: () -> Unit = {}
    ) {
        var openSettings = false
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Open app settings") { _, _ -> openSettings = true }
            .setNegativeButton(dismissLabel, null)
            .setOnDismissListener {
                // Carry on first, then open settings, so the settings page lands on top of
                // anything [onDismiss] started rather than buried under it.
                onDismiss()
                if (openSettings) openAppSettings()
            }
            .show()
    }

    /** Shown when Android has stopped prompting for the microphone (denied twice). */
    private fun showMicrophoneBlockedDialog() {
        showPermissionSettingsDialog(
            title = "Microphone is switched off",
            message = "Remnant records what you say when you wake up, so it can't capture " +
                "anything without the microphone. Your phone won't ask again -- you can turn " +
                "it on under Permissions in Remnant's app settings.",
            dismissLabel = "Not now"
        )
    }

    /**
     * Shown when the microphone is granted but notifications aren't. Onboarding finishes
     * either way -- however this dialog is closed, we carry on into the app.
     */
    private fun showNotificationsBlockedDialog() {
        showPermissionSettingsDialog(
            title = "Notifications are switched off",
            message = "The morning alarm arrives as a notification, so with notifications off " +
                "it won't appear and there'll be nothing to capture. You can turn them on " +
                "under Notifications in Remnant's app settings.",
            dismissLabel = "Continue without",
            onDismiss = { completeOnboarding() }
        )
    }

    private fun openAppSettings() {
        try {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", packageName, null)
            )
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "Couldn't open your app settings. You can change Remnant's permissions from your phone's Settings app.",
                Toast.LENGTH_LONG
            ).show()
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
        // The Cloud voice sends the user's name to Google, so it only runs if they asked for it.
        // With no cached prompt the alarm reads the greeting with the on-device voice instead.
        if (!prefs.cloudVoiceOptIn) return

        val apiKey = ApiKeys.GOOGLE_CLOUD_TTS
        if (apiKey.isEmpty()) return

        val name = prefs.userName.ifEmpty { "there" }
        val voiceId = prefs.selectedVoiceId.ifEmpty { VoiceOption.DEFAULT.id }
        val voice = VoiceOption.findById(voiceId)
        val cacheKey = "$voiceId|$name"

        if (prefs.promptCacheKey == cacheKey) return // Already cached

        // Application-scoped on purpose: this screen finishes moments later, and a
        // lifecycleScope job would be cancelled part-way through caching. The application
        // context keeps the finished activity out of the coroutine.
        val appContext = applicationContext
        AppScope.io.launch {
            val ttsGen = CloudTtsGenerator(appContext)

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
