package com.remnant.dreams.ui

import android.media.MediaPlayer
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.remnant.dreams.R
import com.remnant.dreams.data.DreamDatabase
import com.remnant.dreams.data.DreamEntry
import com.remnant.dreams.databinding.ActivityDreamDetailBinding
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DreamDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDreamDetailBinding
    private val database by lazy { DreamDatabase.getInstance(this) }
    private var dream: DreamEntry? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isPlaying = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDreamDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Dream"

        val dreamId = intent.getLongExtra(EXTRA_DREAM_ID, -1)
        if (dreamId == -1L) {
            finish()
            return
        }

        loadDream(dreamId)

        binding.btnSave.setOnClickListener { saveDream() }
        binding.btnDelete.setOnClickListener { confirmDelete() }
        binding.btnPlayAudio.setOnClickListener { toggleAudioPlayback() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                checkUnsavedChanges()
            }
        })
    }

    private fun loadDream(id: Long) {
        lifecycleScope.launch {
            dream = database.dreamDao().getDreamById(id)
            dream?.let { displayDream(it) } ?: finish()
        }
    }

    private fun displayDream(entry: DreamEntry) {
        val dateFormat = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault())
        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        val date = Date(entry.timestamp)

        binding.textDate.text = dateFormat.format(date)
        binding.textTime.text = timeFormat.format(date)
        binding.editTranscription.setText(entry.transcription)

        val minutes = entry.durationSeconds / 60
        val seconds = entry.durationSeconds % 60
        binding.textDuration.text = if (minutes > 0) {
            "Duration: ${minutes}m ${seconds}s"
        } else {
            "Duration: ${seconds}s"
        }

        if (entry.isFragment) {
            binding.textFragmentNote.visibility = View.VISIBLE
            binding.textFragmentNote.text = "Dream fragment -- even fragments help build recall."
        } else {
            binding.textFragmentNote.visibility = View.GONE
        }

        if (entry.audioPath != null && File(entry.audioPath).exists()) {
            binding.btnPlayAudio.visibility = View.VISIBLE
        } else {
            binding.btnPlayAudio.visibility = View.GONE
        }
    }

    private fun saveDream() {
        val current = dream ?: return
        val newText = binding.editTranscription.text.toString().trim()
        if (newText == current.transcription) {
            finish()
            return
        }

        lifecycleScope.launch {
            database.dreamDao().update(current.copy(transcription = newText))
            Toast.makeText(this@DreamDetailActivity, "Saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Delete dream?")
            .setMessage("This can't be undone.")
            .setPositiveButton("Delete") { _, _ -> deleteDream() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteDream() {
        val current = dream ?: return
        lifecycleScope.launch {
            // Delete audio file
            current.audioPath?.let { File(it).delete() }
            database.dreamDao().delete(current)
            finish()
        }
    }

    private fun toggleAudioPlayback() {
        if (isPlaying) {
            stopPlayback()
        } else {
            startPlayback()
        }
    }

    private fun startPlayback() {
        val path = dream?.audioPath ?: return
        if (!File(path).exists()) {
            Toast.makeText(this, "Audio file not found", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(path)
                prepare()
                start()
                setOnCompletionListener { stopPlayback() }
            }
            isPlaying = true
            binding.btnPlayAudio.text = "Stop"
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't play audio", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopPlayback() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
        isPlaying = false
        binding.btnPlayAudio.text = "Play Audio"
    }

    override fun onSupportNavigateUp(): Boolean {
        checkUnsavedChanges()
        return true
    }

    private fun checkUnsavedChanges() {
        val current = dream ?: run { finish(); return }
        val newText = binding.editTranscription.text.toString().trim()
        if (newText != current.transcription) {
            AlertDialog.Builder(this)
                .setTitle("Unsaved changes")
                .setMessage("Save your edits?")
                .setPositiveButton("Save") { _, _ -> saveDream() }
                .setNegativeButton("Discard") { _, _ -> finish() }
                .setNeutralButton("Cancel", null)
                .show()
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        stopPlayback()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_DREAM_ID = "dream_id"
    }
}
