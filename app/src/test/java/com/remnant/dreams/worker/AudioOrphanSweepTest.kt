package com.remnant.dreams.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sweep deletes files off the user's phone with nothing but a timestamp and a set of paths
 * to go on, so the interesting cases are all the ones where it must NOT delete: a capture still
 * being recorded (file on disk, row not written until save), a recording the compression worker
 * has parked under a `.bak` name mid-swap, and a row whose path spells the same file a
 * different way. Getting any of those wrong destroys a dream, where getting an orphan wrong
 * costs a day of disk space, so every judgement call here leans towards keeping the file.
 */
class AudioOrphanSweepTest {

    /** 12 June 2026, 02:30:00 UTC -- an arbitrary fixed instant, matching the sibling test. */
    private val now = 1_781_231_400_000L

    private val audioDir = "/data/user/0/com.remnant.dreams/files/audio"

    private val minute = 60_000L

    private fun snapshot(name: String, ageMillis: Long, dir: String = audioDir) =
        AudioFileSnapshot("$dir/$name", now - ageMillis)

    private fun sweep(files: List<AudioFileSnapshot>, referenced: Collection<String>) =
        AudioOrphanSweep.selectOrphans(files, referenced, now).map { it.path }

    // --- the defect: unreferenced files must eventually go ---

    @Test
    fun `an old file no row points at is swept`() {
        val orphan = snapshot("dream_20260601_033000.m4a", 11 * 24 * 60 * minute)

        assertEquals(listOf(orphan.path), sweep(listOf(orphan), emptyList()))
    }

    @Test
    fun `only the unreferenced files are swept out of a mixed directory`() {
        val kept = snapshot("dream_20260601_033000.m4a", 11 * 24 * 60 * minute)
        val orphan = snapshot("dream_20260602_034500.m4a", 10 * 24 * 60 * minute)

        assertEquals(listOf(orphan.path), sweep(listOf(kept, orphan), listOf(kept.path)))
    }

    // --- a recording someone still owns is never touched ---

    @Test
    fun `a file a dream row still points at is never swept however old it gets`() {
        val kept = snapshot("dream_20240101_030000.m4a", 900L * 24 * 60 * minute)

        assertTrue(sweep(listOf(kept), listOf(kept.path)).isEmpty())
    }

    @Test
    fun `a row spelling the directory differently still protects its recording`() {
        // filesDir has been seen reporting both /data/user/0/<pkg> and /data/data/<pkg> for the
        // same directory, so a row written under one form must not read as an orphan when the
        // listing comes back under the other.
        val file = snapshot("dream_20260601_033000.m4a", 30L * 24 * 60 * minute)
        val rowPath = "/data/data/com.remnant.dreams/files/audio/dream_20260601_033000.m4a"

        assertTrue(sweep(listOf(file), listOf(rowPath)).isEmpty())
    }

    // --- the in-flight capture guard ---

    @Test
    fun `a capture still recording is left alone`() {
        // The dangerous case. Three minutes in, the file exists and the audio path is not
        // written to a row until the capture is saved, so nothing references it yet.
        val recording = snapshot("dream_20260612_022700.m4a", 3 * minute)

        assertTrue(sweep(listOf(recording), emptyList()).isEmpty())
    }

    @Test
    fun `a capture that has just run its full length is left alone`() {
        val recording = snapshot("dream_20260612_022000.m4a", 10 * minute)

        assertTrue(sweep(listOf(recording), emptyList()).isEmpty())
    }

    @Test
    fun `the age threshold clears the longest possible capture many times over`() {
        // DreamCaptureService caps a capture at 10 minutes. If that cap is ever raised, this
        // is what should force someone to look at the margin again.
        val maxCaptureMs = 10 * minute

        assertTrue(AudioOrphanSweep.MIN_ORPHAN_AGE_MS > 100 * maxCaptureMs)
    }

    @Test
    fun `a file one millisecond short of the threshold is kept`() {
        val file = snapshot("dream_20260611_030000.m4a", AudioOrphanSweep.MIN_ORPHAN_AGE_MS - 1)

        assertTrue(sweep(listOf(file), emptyList()).isEmpty())
    }

    @Test
    fun `a file landing exactly on the threshold is swept`() {
        val file = snapshot("dream_20260611_030000.m4a", AudioOrphanSweep.MIN_ORPHAN_AGE_MS)

        assertEquals(listOf(file.path), sweep(listOf(file), emptyList()))
    }

    @Test
    fun `a file stamped in the future is left alone`() {
        // A clock that has just been corrected backwards makes a brand new recording look like
        // it comes from tomorrow. Reading that as an ancient file would be the worst outcome,
        // so a negative age must never sweep.
        val file = snapshot("dream_20260612_040000.m4a", -2 * 24 * 60 * minute)

        assertTrue(sweep(listOf(file), emptyList()).isEmpty())
    }

    // --- things in the directory that are not ours to delete ---

    @Test
    fun `a compression backup is never swept`() {
        // AudioCompressionWorker moves the original to `<name>.m4a.bak` before renaming the
        // compressed copy into its place, and no row ever points at that name. Between the two
        // renames, and until a later pass restores it, the backup is the only copy there is.
        val backup = snapshot("dream_20260601_033000.m4a.bak", 40L * 24 * 60 * minute)

        assertTrue(sweep(listOf(backup), emptyList()).isEmpty())
    }

    @Test
    fun `a file that is not a recording at all is never swept`() {
        val stray = snapshot("notes.txt", 40L * 24 * 60 * minute)

        assertTrue(sweep(listOf(stray), emptyList()).isEmpty())
    }

    @Test
    fun `an abandoned compression temp file is swept once it is old`() {
        // A pass killed mid re-encode leaves `<name>_compressed.m4a` behind. It is only ever a
        // partial copy -- the finished one gets renamed onto the source path -- so once it is
        // past the threshold it is exactly the rubbish this sweep is for.
        val temp = snapshot("dream_20260601_033000_compressed.m4a", 40L * 24 * 60 * minute)

        assertEquals(listOf(temp.path), sweep(listOf(temp), emptyList()))
    }

    @Test
    fun `an upper case extension is still recognised as a recording`() {
        val orphan = snapshot("DREAM_20260601_033000.M4A", 40L * 24 * 60 * minute)

        assertEquals(listOf(orphan.path), sweep(listOf(orphan), emptyList()))
    }

    // --- degenerate inputs ---

    @Test
    fun `an empty directory sweeps nothing`() {
        assertTrue(sweep(emptyList(), listOf("$audioDir/dream_20260601_033000.m4a")).isEmpty())
    }

    @Test
    fun `a journal with no audio at all does not make live captures fair game`() {
        val recording = snapshot("dream_20260612_022700.m4a", 2 * minute)
        val orphan = snapshot("dream_20260101_033000.m4a", 160L * 24 * 60 * minute)

        assertEquals(listOf(orphan.path), sweep(listOf(recording, orphan), emptyList()))
    }

    // --- path handling ---

    @Test
    fun `the file name is the last segment under either separator`() {
        assertEquals("dream.m4a", AudioOrphanSweep.fileNameOf("/files/audio/dream.m4a"))
        assertEquals("dream.m4a", AudioOrphanSweep.fileNameOf("C:\\files\\audio\\dream.m4a"))
        assertEquals("dream.m4a", AudioOrphanSweep.fileNameOf("dream.m4a"))
    }
}
