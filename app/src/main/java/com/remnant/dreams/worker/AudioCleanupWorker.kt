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
 * One file sitting in the audio directory, reduced to what the orphan decision needs.
 */
internal data class AudioFileSnapshot(
    val path: String,
    val lastModifiedMillis: Long
)

/**
 * Decides which files in the audio directory are orphans: on disk with no dream row pointing
 * at them, and old enough that no live capture can still own them.
 *
 * Force-stopping the app mid-capture leaves exactly that. MediaRecorder never gets stopped, so
 * the m4a has no moov atom and will not play, and the audio path is only written to a row on
 * save, so nothing references the file. Both retention passes walk rows, which makes a file
 * with no row invisible to them -- it would sit there taking up space forever.
 *
 * The decision is kept clear of Android and java.io so it can be tested on the JVM; the worker
 * does the listing and the deleting.
 */
internal object AudioOrphanSweep {

    /**
     * How old a file has to be before the sweep will touch it.
     *
     * This is the whole safety story. A capture in progress has its file on disk and no row
     * pointing at it yet, so by reference alone it is indistinguishable from an orphan, and
     * deleting it would destroy the user's dream while they are still speaking it. Capture is
     * hard-capped at 10 minutes (CapturePolicy.MAX_CAPTURE_MS), so a day is a margin of
     * over a hundred times the longest recording that can exist. That size also absorbs the
     * smaller races -- a save whose row write is still queued, a compression pass part-way
     * through -- and any plausible disagreement between a file's last-modified stamp and the
     * worker's clock after a time sync. Waiting costs nothing: this worker runs daily, and the
     * file it is waiting on is dead weight nobody can play.
     */
    const val MIN_ORPHAN_AGE_MS = 24L * 60 * 60 * 1000

    /**
     * Recordings, and the compression worker's backups of recordings, are the only things the
     * sweep may remove.
     *
     * AudioCompressionWorker parks the original as `<name>.m4a.bak` while it swaps a compressed
     * copy into place, and until that swap completes or a later pass restores it, the backup is
     * the only surviving copy of the recording. No row ever points at the `.bak` name, so a
     * backup is judged by the recording it shadows: while any row references `<name>.m4a` the
     * backup is protected (the swap and restore passes still need it), but once nothing
     * references the base recording -- the entry was deleted, or retention cleared its audio --
     * the backup is unreachable by every other code path and would otherwise sit on disk
     * forever, which is exactly what the privacy policy promises does not happen.
     */
    private const val AUDIO_EXTENSION = ".m4a"

    /** Suffix AudioCompressionWorker appends when parking an original. */
    private const val BACKUP_SUFFIX = ".bak"

    /**
     * The subset of [files] safe to delete: not referenced by any of [referencedPaths], and last
     * written at least [MIN_ORPHAN_AGE_MS] before [nowMillis].
     */
    fun selectOrphans(
        files: List<AudioFileSnapshot>,
        referencedPaths: Collection<String>,
        nowMillis: Long
    ): List<AudioFileSnapshot> {
        val paths = referencedPaths.toHashSet()

        // A row and a directory listing ought to produce identical absolute paths, but they are
        // written at different moments by different code, and filesDir has been seen reporting
        // both /data/user/0/<pkg> and /data/data/<pkg> for the same directory. Counting a bare
        // name match as "referenced" too makes the comparison survive that. Every recording
        // lives in this one directory under a name stamped to the second, so the worst a false
        // match can do is leave an orphan another day, where a false miss deletes a recording
        // the user still has.
        val names = paths.mapTo(HashSet(paths.size)) { fileNameOf(it) }

        return files.filter { file ->
            val name = fileNameOf(file.path)
            // A backup stands or falls with the recording it shadows: strip the suffix and
            // judge the base name, so `<name>.m4a.bak` is protected exactly while some row
            // still references `<name>.m4a`.
            val isBackup = name.endsWith(BACKUP_SUFFIX, ignoreCase = true)
            val basePath = if (isBackup) file.path.dropLast(BACKUP_SUFFIX.length) else file.path
            val baseName = if (isBackup) name.dropLast(BACKUP_SUFFIX.length) else name
            baseName.endsWith(AUDIO_EXTENSION, ignoreCase = true) &&
                    basePath !in paths &&
                    baseName !in names &&
                    nowMillis - file.lastModifiedMillis >= MIN_ORPHAN_AGE_MS
        }
    }

    /** Last path segment, taking either separator so the decision behaves the same off-device. */
    internal fun fileNameOf(path: String): String =
        path.substringAfterLast('/').substringAfterLast('\\')
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
 *
 * A third pass then sweeps the audio directory itself. Retention only ever looks at rows, so
 * a file that never got a row -- the app force-stopped mid-capture, a failed delete leaving
 * the file behind after its entry went -- is invisible to both settings and leaks. See
 * [AudioOrphanSweep] for what makes that safe to do while a capture may be running.
 */
class AudioCleanupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "AudioCleanupWorker"

        /** Mirrors the directory DreamCaptureService records into. */
        private const val AUDIO_DIR_NAME = "audio"
    }

    override suspend fun doWork(): Result {
        val prefs = PrefsManager(applicationContext)
        val dao = DreamDatabase.getInstance(applicationContext).dreamDao()
        val now = System.currentTimeMillis()

        cleanUpExpiredAudio(dao, prefs.audioRetentionDays, now)
        deleteExpiredEntries(dao, prefs.transcriptRetentionDays, now)

        // Last, so anything the retention passes have just unhooked from its row is already
        // visible to the sweep. Guarded because an unreadable directory is a poor reason to
        // fail a run whose retention work has already gone through.
        try {
            sweepOrphanedAudio(dao, now)
        } catch (e: Exception) {
            Log.e(TAG, "Orphan sweep failed", e)
        }

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
            // Once the row stops referencing this path, a compression backup of it would be
            // unreachable by the restore pass -- it must not outlive the recording.
            File("$audioPath.bak").delete()

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
            val path = entry.audioPath ?: continue
            // The row is about to go, taking the only reference to a compression backup with it.
            File("$path.bak").delete()
            val file = File(path)
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

    /**
     * Removes audio files that no dream row references, once they are old enough to be safe.
     *
     * This deliberately ignores both retention settings. "Keep forever" is the user asking us
     * to hold on to their dreams; an orphan is not a dream -- there is no entry, nothing in
     * the journal points at it, and nothing in the app can play it.
     */
    private suspend fun sweepOrphanedAudio(dao: DreamDao, now: Long) {
        val audioDir = File(applicationContext.filesDir, AUDIO_DIR_NAME)

        // listFiles() is null when the directory does not exist yet or cannot be read. Neither
        // is a problem -- there is simply nothing to sweep.
        val onDisk = audioDir.listFiles()
        if (onDisk == null || onDisk.isEmpty()) {
            Log.d(TAG, "No audio directory to sweep")
            return
        }

        val snapshots = onDisk
            .filter { it.isFile }
            .map { AudioFileSnapshot(it.absolutePath, it.lastModified()) }

        val orphans = AudioOrphanSweep.selectOrphans(snapshots, dao.getAllAudioPaths(), now)
        if (orphans.isEmpty()) {
            Log.d(TAG, "No orphaned audio files to sweep")
            return
        }

        var deleted = 0
        var freedBytes = 0L
        for (orphan in orphans) {
            val file = File(orphan.path)
            val size = file.length()
            if (file.delete()) {
                deleted++
                freedBytes += size
            } else {
                Log.w(TAG, "Couldn't delete orphaned audio file: ${file.name}")
            }
        }

        Log.d(TAG, "Swept $deleted orphaned audio files, freeing $freedBytes bytes")
    }
}
