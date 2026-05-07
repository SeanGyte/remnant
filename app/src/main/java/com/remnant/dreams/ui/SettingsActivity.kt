package com.remnant.dreams.ui

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.remnant.dreams.R
import com.remnant.dreams.alarm.AlarmScheduler
import com.remnant.dreams.data.PrefsManager
import com.remnant.dreams.databinding.ActivitySettingsBinding
import com.remnant.dreams.tts.ApiKeys
import com.remnant.dreams.tts.CloudTtsGenerator
import com.remnant.dreams.tts.VoiceOption
import kotlinx.coroutines.launch
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: PrefsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        prefs = PrefsManager(this)

        binding.editName.setText(prefs.userName)
        updateTimeDisplay()
        updateVoiceDisplay()
        updateAudioRetentionDisplay()
        updateTranscriptRetentionDisplay()
        binding.switchAlarm.isChecked = prefs.alarmEnabled

        binding.btnChangeTime.setOnClickListener {
            TimePickerDialog(this, { _, hour, minute ->
                prefs.alarmHour = hour
                prefs.alarmMinute = minute
                updateTimeDisplay()
                if (prefs.alarmEnabled) {
                    AlarmScheduler.schedule(this)
                    Toast.makeText(this, "Alarm updated", Toast.LENGTH_SHORT).show()
                }
            }, prefs.alarmHour, prefs.alarmMinute, false).show()
        }

        binding.switchAlarm.setOnCheckedChangeListener { _, isChecked ->
            prefs.alarmEnabled = isChecked
            if (isChecked) {
                AlarmScheduler.schedule(this)
                Toast.makeText(this, "Alarm enabled", Toast.LENGTH_SHORT).show()
            } else {
                AlarmScheduler.cancel(this)
                Toast.makeText(this, "Alarm disabled", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSaveName.setOnClickListener {
            val name = binding.editName.text.toString().trim()
            if (name.isNotEmpty()) {
                val changed = name != prefs.userName
                prefs.userName = name
                if (changed) regeneratePrompt()
                Toast.makeText(this, "Name updated", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnChangeVoice.setOnClickListener {
            showVoicePicker()
        }

        binding.btnChangeAudioRetention.setOnClickListener {
            showAudioRetentionPicker()
        }

        binding.btnChangeTranscriptRetention.setOnClickListener {
            showTranscriptRetentionPicker()
        }

    }

    private fun showVoicePicker() {
        val dialog = VoicePreviewDialogFragment()
        dialog.onVoiceSelected = { voice ->
            prefs.selectedVoiceId = voice.id
            updateVoiceDisplay()
            regeneratePrompt()
        }
        dialog.show(supportFragmentManager, VoicePreviewDialogFragment.FRAGMENT_TAG)
    }

    private fun updateVoiceDisplay() {
        val voiceId = prefs.selectedVoiceId.ifEmpty { VoiceOption.DEFAULT.id }
        val voice = VoiceOption.findById(voiceId)
        binding.textCurrentVoice.text = "${voice.friendlyName} -- ${voice.description}"
    }

    private fun regeneratePrompt() {
        val apiKey = ApiKeys.GOOGLE_CLOUD_TTS
        if (apiKey.isEmpty()) {
            Toast.makeText(this, "API key not configured", Toast.LENGTH_SHORT).show()
            return
        }

        val name = prefs.userName.ifEmpty { "there" }
        val voiceId = prefs.selectedVoiceId.ifEmpty { VoiceOption.DEFAULT.id }
        val voice = VoiceOption.findById(voiceId)

        Toast.makeText(this, "Generating voice...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val ttsGen = CloudTtsGenerator(this@SettingsActivity)

            // Cache dream prompt
            val dreamText = "Good morning $name. What did you dream about last night?"
            val dreamResult = ttsGen.generatePrompt(dreamText, voice, apiKey, CloudTtsGenerator.PROMPT_DREAM)

            // Cache wake check prompt (for companion mode)
            val wakeText = "Are you awake, $name?"
            ttsGen.generatePrompt(wakeText, voice, apiKey, CloudTtsGenerator.PROMPT_WAKE_CHECK)

            if (dreamResult != null) {
                prefs.promptCacheKey = "$voiceId|$name"
                Toast.makeText(this@SettingsActivity, "Voice updated", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@SettingsActivity, "Failed -- will use device voice", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showAudioRetentionPicker() {
        val options = listOf(7, 30, 90, 0)
        val labels = arrayOf("7 days", "30 days", "90 days", "Forever")
        val currentIndex = options.indexOf(prefs.audioRetentionDays).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Keep audio recordings for")
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                prefs.audioRetentionDays = options[which]
                updateAudioRetentionDisplay()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTranscriptRetentionPicker() {
        val options = listOf(90, 365, 0)
        val labels = arrayOf("90 days", "1 year", "Forever")
        val currentIndex = options.indexOf(prefs.transcriptRetentionDays).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Keep transcripts for")
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                prefs.transcriptRetentionDays = options[which]
                updateTranscriptRetentionDisplay()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateAudioRetentionDisplay() {
        binding.textAudioRetention.text = retentionLabel(prefs.audioRetentionDays)
    }

    private fun updateTranscriptRetentionDisplay() {
        binding.textTranscriptRetention.text = retentionLabel(prefs.transcriptRetentionDays)
    }

    private fun retentionLabel(days: Int): String = when (days) {
        0 -> "Forever"
        365 -> "1 year"
        else -> "$days days"
    }

    private fun updateTimeDisplay() {
        val hour = prefs.alarmHour
        val minute = prefs.alarmMinute
        val amPm = if (hour < 12) "AM" else "PM"
        val displayHour = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        binding.textCurrentTime.text = String.format(Locale.US, "%d:%02d %s", displayHour, minute, amPm)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_settings, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_save -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
