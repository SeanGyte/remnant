package com.remnant.dreams.ui

import android.app.AlarmManager
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.remnant.dreams.R
import com.remnant.dreams.data.DreamDatabase
import com.remnant.dreams.data.PrefsManager
import com.remnant.dreams.databinding.ActivityJournalBinding
import com.remnant.dreams.tts.VoiceOption
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

class JournalActivity : AppCompatActivity() {

    private lateinit var binding: ActivityJournalBinding
    private lateinit var prefs: PrefsManager
    private lateinit var adapter: DiaryAdapter
    private val database by lazy { DreamDatabase.getInstance(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityJournalBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PrefsManager(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "Remnant"

        setupRecyclerView()
        observeDreams()
        updateStats()
        updateModeStatus()
    }

    private fun setupRecyclerView() {
        adapter = DiaryAdapter(
            onEditClick = { dream ->
                val intent = Intent(this, DreamDetailActivity::class.java).apply {
                    putExtra(DreamDetailActivity.EXTRA_DREAM_ID, dream.id)
                }
                startActivity(intent)
            },
            onPlayClick = { dream ->
                val intent = Intent(this, DreamDetailActivity::class.java).apply {
                    putExtra(DreamDetailActivity.EXTRA_DREAM_ID, dream.id)
                }
                startActivity(intent)
            },
            onTryAgainClick = { dream ->
                val intent = Intent(this, DreamDetailActivity::class.java).apply {
                    putExtra(DreamDetailActivity.EXTRA_DREAM_ID, dream.id)
                }
                startActivity(intent)
            }
        )
        binding.recyclerDreams.layoutManager = LinearLayoutManager(this)
        binding.recyclerDreams.adapter = adapter
    }

    private fun observeDreams() {
        lifecycleScope.launch {
            database.dreamDao().getAllDreams().collectLatest { dreams ->
                val diaryItems = DiaryAdapter.groupByMonth(dreams)
                adapter.submitList(diaryItems)
                binding.emptyState.visibility = if (dreams.isEmpty()) View.VISIBLE else View.GONE
                binding.recyclerDreams.visibility = if (dreams.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun updateStats() {
        val streak = prefs.currentStreak
        val total = prefs.totalCaptures

        if (total > 0) {
            binding.textStats.visibility = View.VISIBLE
            val totalText = "$total dream${if (total != 1) "s" else ""} captured"
            val streakText = when {
                streak >= 2 -> "$streak day streak"
                streak == 1 -> "Day 1 -- you're building a habit"
                else -> ""
            }
            binding.textStats.text = if (streakText.isNotEmpty()) {
                "$totalText  |  $streakText"
            } else {
                totalText
            }
        } else {
            binding.textStats.visibility = View.VISIBLE
            binding.textStats.text = "Your first alarm is set. Sweet dreams."
        }
    }

    private fun updateModeStatus() {
        if (!prefs.alarmEnabled) {
            setModeStatus("Alarm is off.", R.color.text_secondary)
            return
        }

        val alarmHour = prefs.alarmHour
        val alarmMinute = prefs.alarmMinute
        val amPm = if (alarmHour < 12) "AM" else "PM"
        val displayHour = when {
            alarmHour == 0 -> 12
            alarmHour > 12 -> alarmHour - 12
            else -> alarmHour
        }
        val timeStr = String.format(Locale.US, "%d:%02d %s", displayHour, alarmMinute, amPm)

        // Calculate our alarm time for companion detection
        val ourAlarmCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, alarmHour)
            set(Calendar.MINUTE, alarmMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(Calendar.getInstance())) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        val ourAlarmTime = ourAlarmCal.timeInMillis

        // Check for nearby alarms
        val alarmManager = getSystemService(AlarmManager::class.java)
        val nextAlarm = alarmManager.nextAlarmClock

        if (nextAlarm != null) {
            val nextTime = nextAlarm.triggerTime

            // Is there any alarm from another app before ours?
            if (nextTime < ourAlarmTime && kotlin.math.abs(nextTime - ourAlarmTime) >= 1000) {
                // Companion mode -- figure out the other alarm's time for display
                val otherCal = Calendar.getInstance().apply { timeInMillis = nextTime }
                val otherHour = otherCal.get(Calendar.HOUR_OF_DAY)
                val otherMinute = otherCal.get(Calendar.MINUTE)
                val otherAmPm = if (otherHour < 12) "AM" else "PM"
                val otherDisplayHour = when {
                    otherHour == 0 -> 12
                    otherHour > 12 -> otherHour - 12
                    else -> otherHour
                }
                val otherTimeStr = String.format(Locale.US, "%d:%02d %s", otherDisplayHour, otherMinute, otherAmPm)

                val voiceId = prefs.selectedVoiceId.ifEmpty { VoiceOption.DEFAULT.id }
                val voiceName = VoiceOption.findById(voiceId).friendlyName

                setModeStatus(
                    "Companion mode -- alarm at $otherTimeStr detected. $voiceName will ask if you're awake at $timeStr. No alarm tone.",
                    R.color.accent
                )
                return
            }
        }

        // Standalone mode
        setModeStatus(
            "Standalone mode -- full alarm at $timeStr.",
            R.color.accent
        )
    }

    private fun setModeStatus(text: String, dotColorRes: Int) {
        binding.textModeStatus.text = text
        val dot = binding.modeIndicator.background
        if (dot is GradientDrawable) {
            dot.setColor(ContextCompat.getColor(this, dotColorRes))
        }
    }

    override fun onResume() {
        super.onResume()
        updateStats()
        updateModeStatus()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_journal, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
