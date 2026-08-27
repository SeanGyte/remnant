package com.remnant.dreams.ui

import android.Manifest
import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import com.remnant.dreams.AppScope
import com.remnant.dreams.R
import com.remnant.dreams.alarm.AlarmScheduler
import com.remnant.dreams.alarm.CompanionAlarm
import com.remnant.dreams.data.PrefsManager
import com.remnant.dreams.databinding.ActivityOnboardingBinding
import com.remnant.dreams.tts.ApiKeys
import com.remnant.dreams.tts.CloudTtsGenerator
import com.remnant.dreams.tts.VoiceOption
import kotlinx.coroutines.launch
import java.util.Calendar
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
        // rather than assuming.
        val micGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: isGranted(Manifest.permission.RECORD_AUDIO)
        val notificationsDenied = permissions[Manifest.permission.POST_NOTIFICATIONS] == false
        // We only launch the prompt when something is actually missing, so every answered
        // prompt comes back with at least one entry. An empty map means it was dismissed
        // without an answer: nothing was denied, and Android will ask again.
        val promptDismissed = permissions.isEmpty()

        when {
            // Notifications don't block onboarding, but the alarm is delivered as one, so say
            // so before moving on. The microphone dialog wins if both were denied.
            micGranted && notificationsDenied -> showNotificationsBlockedDialog()
            // Only the microphone is required -- without it there is nothing to record.
            micGranted -> completeOnboarding()
            // Android still shows the prompt, so tapping Start again is worth doing.
            promptDismissed || shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
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
        // Start is pinned below the scrolling setup steps, so the keyboard raised for the
        // name has to push it up rather than sit on top of it.
        EdgeToEdgeUtil.applySystemBarInsets(binding.root, includeIme = true)

        binding.btnSetTime.setOnClickListener {
            showTimePicker()
        }

        binding.btnChooseVoice.setOnClickListener {
            showVoicePicker()
        }

        binding.btnUseDeviceAlarm.setOnClickListener {
            useRecommendedWakeTime()
        }

        binding.btnCompanionAfterAlarm.setOnClickListener {
            useDeviceAlarmTime()
        }

        // Take the error down the moment they start supplying what it asked for.
        binding.editName.doAfterTextChanged { binding.layoutName.error = null }

        // The name is the last thing the keyboard is open for, so its action key finishes
        // the screen rather than hunting for a next field there isn't one of.
        binding.editName.setOnEditorActionListener { _, actionId, event ->
            val enterPressed = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_DONE || enterPressed) {
                attemptStart()
                true
            } else {
                false
            }
        }

        binding.btnStart.setOnClickListener {
            attemptStart()
        }

        updateTimeDisplay()
        updateVoiceDisplay()
    }

    /**
     * Everything the Start button does, shared with the keyboard's action key so the two
     * can't drift apart -- an empty name has to complain the same way whichever one is used.
     */
    private fun attemptStart() {
        val name = binding.editName.text.toString().trim()
        if (name.isEmpty()) {
            // The error belongs on the TextInputLayout, not the edit text nested in it:
            // a TextView error is a popup Android only raises while the field has
            // focus, and on a fresh install nothing is focused, so Start looked like a
            // dead button. The layout draws its error under the field either way.
            binding.layoutName.error = getString(R.string.onboarding_name_required)
            binding.editName.requestFocus()
            WindowCompat.getInsetsController(window, binding.editName)
                .show(WindowInsetsCompat.Type.ime())
            return
        }

        prefs.userName = name
        prefs.alarmHour = selectedHour
        prefs.alarmMinute = selectedMinute

        requestPermissions()
    }

    /**
     * Opens the voice picker. The install already has an assigned Cloud voice, so skipping
     * this step keeps it; the picker is where the user hears the others or switches to the
     * phone's own voice, which is the only setting under which Remnant sends nothing.
     */
    private fun showVoicePicker() {
        // Previews greet the user by name, but the name is only saved on Start, so carry
        // across whatever has been typed so far.
        val typedName = binding.editName.text.toString().trim()
        if (typedName.isNotEmpty()) prefs.userName = typedName

        val dialog = VoicePreviewDialogFragment()
        dialog.onVoiceSelected = { voice ->
            prefs.selectedVoiceId = voice?.id ?: VoiceOption.DEVICE_VOICE_ID
            updateVoiceDisplay()
        }
        dialog.show(supportFragmentManager, VoicePreviewDialogFragment.FRAGMENT_TAG)
    }

    private fun updateVoiceDisplay() {
        val voice = VoiceOption.selectedOrNull(prefs.selectedVoiceId)
        binding.btnChooseVoice.text = if (voice == null) {
            getString(R.string.voice_device_name)
        } else {
            "${voice.friendlyName} -- ${voice.description}"
        }
    }

    private fun showTimePicker() {
        TimePickerDialog(this, { _, hour, minute ->
            selectedHour = hour
            selectedMinute = minute
            updateTimeDisplay()
        }, selectedHour, selectedMinute, false).show()
    }

    private fun updateTimeDisplay() {
        binding.btnSetTime.text = formatClock(selectedHour, selectedMinute)
        updateAlarmConflict()
    }

    /**
     * Flags the thing the journal banner used to be the first to mention: the phone already
     * rings earlier, so Remnant would sit out the actual wake-up and only ask about the
     * dream a long time afterwards. Better said here, while the time is still being picked,
     * than as a surprise once setup is done. Hidden entirely when there is no clash, so it
     * costs nothing on the small screens this layout is tight on.
     */
    private fun updateAlarmConflict() {
        val deviceAlarmMs = deviceAlarmTimeMs()
        val ourAlarmMs = nextOccurrenceOf(selectedHour, selectedMinute)

        if (deviceAlarmMs == null || !CompanionAlarm.isCheckInStale(deviceAlarmMs, ourAlarmMs)) {
            binding.textAlarmConflict.visibility = View.GONE
            binding.btnUseDeviceAlarm.visibility = View.GONE
            binding.btnCompanionAfterAlarm.visibility = View.GONE
            return
        }

        binding.textAlarmConflict.text = getString(
            R.string.onboarding_alarm_conflict,
            formatClock(deviceAlarmMs),
            formatClock(ourAlarmMs)
        )
        // The recommendation leads, because a dream asked about after the household alarm
        // has already gone. The quiet companion setup stays available underneath it.
        binding.btnUseDeviceAlarm.text = getString(
            R.string.onboarding_alarm_wake_before,
            formatClock(CompanionAlarm.recommendedWakeBefore(deviceAlarmMs))
        )
        binding.btnCompanionAfterAlarm.text = getString(
            R.string.onboarding_alarm_use_device,
            formatClock(CompanionAlarm.checkInTimeAfter(deviceAlarmMs))
        )
        binding.textAlarmConflict.visibility = View.VISIBLE
        binding.btnUseDeviceAlarm.visibility = View.VISIBLE
        binding.btnCompanionAfterAlarm.visibility = View.VISIBLE
    }

    /** Takes the recommendation: wake half an hour before the alarm the phone already has. */
    private fun useRecommendedWakeTime() {
        val deviceAlarm = deviceAlarmTimeMs() ?: return
        applyTime(CompanionAlarm.recommendedWakeBefore(deviceAlarm))
    }

    /** Takes the quiet option: let the phone wake them, and ask straight afterwards. */
    private fun useDeviceAlarmTime() {
        val deviceAlarm = deviceAlarmTimeMs() ?: return
        applyTime(CompanionAlarm.checkInTimeAfter(deviceAlarm))
    }

    private fun applyTime(timeMs: Long) {
        val picked = Calendar.getInstance().apply { timeInMillis = timeMs }
        selectedHour = picked.get(Calendar.HOUR_OF_DAY)
        selectedMinute = picked.get(Calendar.MINUTE)
        updateTimeDisplay()
    }

    /** The phone's own next alarm, or null when nothing else is set to wake the user. */
    private fun deviceAlarmTimeMs(): Long? =
        getSystemService(AlarmManager::class.java)?.nextAlarmClock?.triggerTime

    /** The next time [hour]:[minute] comes round, matching how AlarmScheduler works it out. */
    private fun nextOccurrenceOf(hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis

    private fun formatClock(timeMs: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = timeMs }
        return formatClock(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    }

    private fun formatClock(hour: Int, minute: Int): String {
        val amPm = if (hour < 12) "AM" else "PM"
        val displayHour = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        return String.format(Locale.US, "%d:%02d %s", displayHour, minute, amPm)
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
            message = "Your alarm will still sound -- Remnant rings without a notification " +
                "when it has to. What you won't get is the alarm screen: the ringing stops " +
                "after a few minutes, and nothing is captured until you open Remnant " +
                "yourself. Turning notifications on under Notifications in Remnant's app " +
                "settings gets the full alarm back.",
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
        // Selecting a Cloud voice is the consent to use it, so with none selected there is no
        // network call at all and the alarm reads the greeting with the on-device voice.
        val voice = VoiceOption.selectedOrNull(prefs.selectedVoiceId) ?: return

        val apiKey = ApiKeys.GOOGLE_CLOUD_TTS
        if (apiKey.isEmpty()) return

        val name = prefs.userName.ifEmpty { "there" }
        val cacheKey = "${voice.id}|$name"

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
