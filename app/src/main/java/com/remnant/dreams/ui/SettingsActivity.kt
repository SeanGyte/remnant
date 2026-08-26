package com.remnant.dreams.ui

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.android.billingclient.api.ProductDetails
import com.remnant.dreams.BuildConfig
import com.remnant.dreams.R
import com.remnant.dreams.alarm.AlarmScheduler
import com.remnant.dreams.billing.BillingManager
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
    private lateinit var billing: BillingManager
    private var proDetails: ProductDetails? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EdgeToEdgeUtil.enable(this)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EdgeToEdgeUtil.applySystemBarInsets(binding.root)

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
                // Only a selected Cloud voice needs regenerating -- the phone's own voice
                // reads the new name without anything being sent anywhere.
                if (changed) {
                    VoiceOption.selectedOrNull(prefs.selectedVoiceId)?.let { regeneratePrompt(it) }
                }
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

        binding.textVersion.text = "Remnant v${BuildConfig.VERSION_NAME}"

        setupProSection()
    }

    private fun setupProSection() {
        billing = BillingManager.getInstance(this)

        binding.btnGetPro.setOnClickListener {
            val details = proDetails
            if (details != null) {
                billing.launchPurchase(this, details)
            } else {
                Toast.makeText(this, "Google Play is unavailable right now. Try again shortly.", Toast.LENGTH_SHORT).show()
                loadProPrice()
            }
        }

        binding.btnRestorePro.setOnClickListener {
            Toast.makeText(this, "Checking your purchases...", Toast.LENGTH_SHORT).show()
            billing.refreshEntitlement { isPro ->
                runOnUiThread {
                    val msg = if (isPro) "Pro restored. Welcome back." else "No Pro purchase found on this Google account."
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }

        billing.purchaseOutcomeListener = { outcome ->
            runOnUiThread {
                val msg = when (outcome) {
                    BillingManager.PurchaseOutcome.SUCCESS -> "Pro unlocked. Thank you for supporting Remnant."
                    BillingManager.PurchaseOutcome.PENDING -> "Purchase pending -- Pro unlocks once payment completes."
                    BillingManager.PurchaseOutcome.CANCELLED -> null
                    BillingManager.PurchaseOutcome.ALREADY_OWNED -> "You already own Pro -- restoring it now."
                    BillingManager.PurchaseOutcome.ERROR -> "Purchase didn't go through. You haven't been charged."
                }
                msg?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
                updateProUi()
            }
        }

        // React to entitlement changes (purchase completes, restore succeeds).
        lifecycleScope.launch {
            billing.isProFlow.collect { updateProUi() }
        }

        updateProUi()
        loadProPrice()
    }

    private fun updateProUi() {
        when {
            prefs.isPro -> {
                binding.textProStatus.text = "Pro is unlocked. Thank you for supporting Remnant."
                binding.btnGetPro.visibility = View.GONE
                binding.btnRestorePro.visibility = View.GONE
            }
            BuildConfig.SIMULATE_PRO -> {
                binding.textProStatus.text = "Pro simulated (debug build). All Pro features are unlocked for testing."
                binding.btnGetPro.visibility = View.GONE
                binding.btnRestorePro.visibility = View.GONE
            }
            else -> {
                binding.textProStatus.text =
                    "Search across all your dreams and export your journal. " +
                        "One-time purchase -- no subscription, no ads, ever."
                binding.btnGetPro.visibility = View.VISIBLE
                binding.btnRestorePro.visibility = View.VISIBLE
            }
        }
    }

    private fun loadProPrice() {
        billing.queryProDetails { details ->
            runOnUiThread {
                proDetails = details
                val price = details?.oneTimePurchaseOfferDetails?.formattedPrice
                if (price != null) {
                    binding.btnGetPro.text = "Unlock Pro -- $price"
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        billing.purchaseOutcomeListener = null
    }

    /**
     * Opens the voice picker, which is where consent to Cloud text-to-speech is given and
     * taken back: selecting a Cloud voice is the consent, and choosing the phone's own
     * voice withdraws it, cached audio and all.
     */
    private fun showVoicePicker() {
        val dialog = VoicePreviewDialogFragment()
        dialog.onVoiceSelected = { voice ->
            prefs.selectedVoiceId = voice?.id ?: ""
            updateVoiceDisplay()
            if (voice != null) {
                regeneratePrompt(voice)
            } else {
                CloudTtsGenerator(this).clearCache()
                prefs.promptCacheKey = ""
            }
        }
        dialog.show(supportFragmentManager, VoicePreviewDialogFragment.FRAGMENT_TAG)
    }

    private fun updateVoiceDisplay() {
        val voice = VoiceOption.selectedOrNull(prefs.selectedVoiceId)
        binding.textCurrentVoice.text = if (voice == null) {
            getString(R.string.voice_device_name)
        } else {
            "${voice.friendlyName} -- ${voice.description}"
        }
    }

    private fun regeneratePrompt(voice: VoiceOption) {
        val apiKey = ApiKeys.GOOGLE_CLOUD_TTS
        if (apiKey.isEmpty()) {
            Toast.makeText(this, "API key not configured", Toast.LENGTH_SHORT).show()
            return
        }

        val name = prefs.userName.ifEmpty { "there" }

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
                prefs.promptCacheKey = "${voice.id}|$name"
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
