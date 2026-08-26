package com.remnant.dreams.ui

import android.app.AlarmManager
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.remnant.dreams.R
import com.remnant.dreams.alarm.AlarmScheduler
import com.remnant.dreams.billing.BillingManager
import com.remnant.dreams.billing.ProGate
import com.remnant.dreams.data.DreamDatabase
import com.remnant.dreams.data.DreamEntry
import com.remnant.dreams.data.DreamSearch
import com.remnant.dreams.data.ExportFormatter
import com.remnant.dreams.data.PrefsManager
import com.remnant.dreams.databinding.ActivityJournalBinding
import com.remnant.dreams.tts.VoiceOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class JournalActivity : AppCompatActivity() {

    private lateinit var binding: ActivityJournalBinding
    private lateinit var prefs: PrefsManager
    private lateinit var adapter: DiaryAdapter
    private val database by lazy { DreamDatabase.getInstance(this) }

    /** Full journal, newest first, kept in sync by observeDreams(). */
    private var allDreams: List<DreamEntry> = emptyList()

    /** Active search query (Pro feature). Empty = no filter. */
    private var searchQuery: String = ""

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) writeExport(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EdgeToEdgeUtil.enable(this)
        binding = ActivityJournalBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EdgeToEdgeUtil.applySystemBarInsets(binding.root)

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
                allDreams = dreams
                renderDreams()
            }
        }
    }

    /** Applies the active search filter (if any) and renders the diary list. */
    private fun renderDreams() {
        val searching = searchQuery.isNotBlank()
        val visible = DreamSearch.filter(allDreams, searchQuery)
        adapter.submitList(DiaryAdapter.groupByMonth(visible))
        binding.emptyState.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerDreams.visibility = if (visible.isEmpty()) View.GONE else View.VISIBLE
        if (searching) {
            binding.textEmptyTitle.text = "No matches"
            binding.textEmptySubtitle.text = "No dreams mention \"${searchQuery.trim()}\"."
        } else {
            binding.textEmptyTitle.setText(R.string.journal_empty_title)
            binding.textEmptySubtitle.setText(R.string.journal_empty_subtitle)
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
        // Defensive re-arm: if the alarm chain ever broke (missed broadcast, force stop),
        // opening the app puts it back. No-ops when the alarm is disabled.
        AlarmScheduler.schedule(this)
        updateStats()
        updateModeStatus()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_journal, menu)
        setupSearch(menu.findItem(R.id.action_search))
        return true
    }

    /**
     * Search across entries -- Pro feature. Free users get the upgrade dialog instead
     * of the search box expanding.
     */
    private fun setupSearch(searchItem: MenuItem) {
        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                if (!BillingManager.isProUnlocked(this@JournalActivity)) {
                    ProGate.showUpgradeDialog(this@JournalActivity, "Search")
                    return false
                }
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                searchQuery = ""
                renderDreams()
                return true
            }
        })

        val searchView = searchItem.actionView as SearchView
        searchView.queryHint = "Search your dreams..."
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                searchView.clearFocus()
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                searchQuery = newText.orEmpty()
                renderDreams()
                return true
            }
        })
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_export -> {
                ProGate.requirePro(this, "Export") { startExport() }
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /** Export journal -- Pro feature. Plain text via the system file picker (SAF). */
    private fun startExport() {
        if (allDreams.isEmpty()) {
            Toast.makeText(this, "Nothing to export yet -- capture a dream first.", Toast.LENGTH_SHORT).show()
            return
        }
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        exportLauncher.launch("remnant-journal-$date.txt")
    }

    private fun writeExport(uri: android.net.Uri) {
        val dreams = allDreams
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(ExportFormatter.format(dreams).toByteArray(Charsets.UTF_8))
                        true
                    } ?: false
                } catch (e: Exception) {
                    false
                }
            }
            val msg = if (ok) {
                "Exported ${dreams.size} dream${if (dreams.size != 1) "s" else ""}."
            } else {
                "Export failed -- couldn't write to that location."
            }
            Toast.makeText(this@JournalActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
