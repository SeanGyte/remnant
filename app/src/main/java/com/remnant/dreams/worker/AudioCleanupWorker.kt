package com.remnant.dreams.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.remnant.dreams.data.DreamDatabase
import com.remnant.dreams.data.PrefsManager
import java.io.File
import java.util.Calendar

/**
 * Daily worker that deletes audio files older than the user's retention setting,
 * and updates the database entries accordingly.
 *
 * If the transcript was a fallback "couldn't catch the words" message, it gets
 * updated to something that doesn't reference a recording that no longer exists.
 */
class AudioCleanupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "AudioCleanupWorker"

        // The fallback transcript that references "tap to play the recording"
        private const val FALLBACK_TRANSCRIPT =
            "Couldn't catch the words \u2014 tap to play the recording."

        // Replacement when the audio file has been cleaned up
        private const val CLEANED_TRANSCRIPT =
            "Dream captured but transcript unavailable."
    }

    override suspend fun doWork(): Result {
        val prefs = PrefsManager(applicationContext)
        val retentionDays = prefs.audioRetentionDays

        // 0 = keep forever
        if (retentionDays == 0) {
            Log.d(TAG, "Audio retention set to forever, nothing to do")
            return Result.success()
        }

        val dao = DreamDatabase.getInstance(applicationContext).dreamDao()

        val cutoff = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -retentionDays)
        }.timeInMillis

        val entries = dao.getEntriesWithAudioOlderThan(cutoff)
        if (entries.isEmpty()) {
            Log.d(TAG, "No audio files older than $retentionDays days to clean up")
            return Result.success()
        }

        Log.d(TAG, "Cleaning up ${entries.size} audio files older than $retentionDays days")

        var deleted = 0
        for (entry in entries) {
            val audioPath = entry.audioPath ?: continue

            // Delete the file from disk
            val file = File(audioPath)
            if (file.exists()) {
                if (!file.delete()) {
                    Log.w(TAG, "Couldn't delete audio file: $audioPath")
                    continue
                }
            }

            // Update the database
            if (entry.transcription.trim() == FALLBACK_TRANSCRIPT) {
                dao.clearAudioAndUpdateTranscription(entry.id, CLEANED_TRANSCRIPT)
            } else {
                dao.clearAudioPath(entry.id)
            }

            deleted++
        }

        Log.d(TAG, "Cleaned up $deleted audio files")
        return Result.success()
    }
}
