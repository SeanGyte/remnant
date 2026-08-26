package com.remnant.dreams.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.remnant.dreams.data.DreamDao
import com.remnant.dreams.data.DreamDatabase
import com.remnant.dreams.data.PrefsManager
import com.remnant.dreams.data.TranscriptPlaceholders
import java.io.File
import java.util.Calendar

/**
 * The timestamp before which data has aged out of a [retentionDays]-day window.
 *
 * A retention of 0 means "keep forever" -- that is the caller's decision, not this
 * function's, so 0 simply yields [nowMillis] here.
 */
internal fun retentionCutoffMillis(nowMillis: Long, retentionDays: Int): Long {
    return Calendar.getInstance().apply {
        timeInMillis = nowMillis
        add(Calendar.DAY_OF_YEAR, -retentionDays)
    }.timeInMillis
}

/**
 * Daily worker that enforces the two retention settings, which are independent of
 * each other and both run on every pass:
 *
 * - Audio retention: deletes audio files older than the setting and clears the
 *   entry's audio path. If the transcript was the "tap to play the recording"
 *   placeholder, it is rewritten so it no longer points at a recording that is gone.
 * - Transcript retention: deletes entries older than the setting outright, removing
 *   any audio files they still own first so nothing is orphaned on disk.
 *
 * Either setting can be 0, meaning "keep forever".
 */
class AudioCleanupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "AudioCleanupWorker"
    }

    override suspend fun doWork(): Result {
        val prefs = PrefsManager(applicationContext)
        val dao = DreamDatabase.getInstance(applicationContext).dreamDao()
        val now = System.currentTimeMillis()

        cleanUpExpiredAudio(dao, prefs.audioRetentionDays, now)
        deleteExpiredEntries(dao, prefs.transcriptRetentionDays, now)

        return Result.success()
    }

    private suspend fun cleanUpExpiredAudio(dao: DreamDao, retentionDays: Int, now: Long) {
        if (retentionDays == 0) {
            Log.d(TAG, "Audio retention set to forever, no audio cleanup")
            return
        }

        val cutoff = retentionCutoffMillis(now, retentionDays)
        val entries = dao.getEntriesWithAudioOlderThan(cutoff)
        if (entries.isEmpty()) {
            Log.d(TAG, "No audio files older than $retentionDays days to clean up")
            return
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
            if (entry.transcription.trim() == TranscriptPlaceholders.NO_TRANSCRIPT_WITH_AUDIO) {
                dao.clearAudioAndUpdateTranscription(
                    entry.id,
                    TranscriptPlaceholders.NO_TRANSCRIPT_AUDIO_DELETED
                )
            } else {
                dao.clearAudioPath(entry.id)
            }

            deleted++
        }

        Log.d(TAG, "Cleaned up $deleted audio files")
    }

    private suspend fun deleteExpiredEntries(dao: DreamDao, retentionDays: Int, now: Long) {
        if (retentionDays == 0) {
            Log.d(TAG, "Transcript retention set to forever, no entries deleted")
            return
        }

        val cutoff = retentionCutoffMillis(now, retentionDays)

        // These rows are about to go, so their audio files have to go with them or
        // they sit on disk with nothing referencing them.
        var audioDeleted = 0
        for (entry in dao.getEntriesWithAudioOlderThan(cutoff)) {
            val file = File(entry.audioPath ?: continue)
            if (!file.exists()) continue
            if (file.delete()) {
                audioDeleted++
            } else {
                Log.w(TAG, "Couldn't delete audio for expiring entry ${entry.id}")
            }
        }

        val before = dao.getTotalCount()
        dao.deleteOlderThan(cutoff)
        val removed = before - dao.getTotalCount()

        Log.d(
            TAG,
            "Transcript retention $retentionDays days: deleted $removed entries " +
                    "and $audioDeleted audio files"
        )
    }
}
