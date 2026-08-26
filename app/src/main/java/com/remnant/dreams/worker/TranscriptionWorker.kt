package com.remnant.dreams.worker

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.remnant.dreams.alarm.TranscriptAccumulator
import com.remnant.dreams.data.DreamDatabase
import com.remnant.dreams.data.TranscriptPlaceholders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlin.coroutines.resume

/**
 * Turns a finished recording into words.
 *
 * Recognition deliberately does not run during the capture. Sharing the microphone between
 * a recogniser and the recorder does not work -- the platform picks one and starves the
 * other, and the recogniser is the one that loses -- so the recorder gets the microphone to
 * itself and the recogniser gets the file afterwards, when nothing is competing for it.
 *
 * Everything here fails safe. The entry is already in the database with its audio and the
 * "couldn't catch the words" placeholder before this worker runs, so a device whose
 * recogniser will not read from a file, or will not run offline, simply leaves the user
 * exactly where they were: a playable recording and an honest note that the words are
 * missing. It never makes an entry worse.
 */
class TranscriptionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val dreamId = inputData.getLong(KEY_DREAM_ID, 0L)
        val audioPath = inputData.getString(KEY_AUDIO_PATH)

        if (dreamId == 0L || audioPath.isNullOrEmpty()) {
            Log.w(TAG, "Nothing to transcribe -- missing entry id or path")
            return Result.success()
        }

        // Feeding a recogniser from a file needs EXTRA_AUDIO_SOURCE, which arrived in
        // Android 12. Below that the placeholder stands; there is no safe way to get the
        // words without taking the microphone off the recorder again.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Log.i(TAG, "Recognition from a file needs API 31 -- leaving the placeholder")
            return Result.success()
        }

        val file = File(audioPath)
        if (!file.exists() || file.length() == 0L) {
            Log.w(TAG, "Recording is gone -- nothing to transcribe")
            return Result.success()
        }

        val transcript = try {
            withTimeoutOrNull(RECOGNITION_TIMEOUT_MS) { transcribe(file) }
        } catch (e: Exception) {
            Log.w(TAG, "Transcription failed: ${e.message}")
            null
        }

        if (transcript.isNullOrBlank()) {
            Log.i(TAG, "No words recovered -- the entry keeps its recording and placeholder")
            return Result.success()
        }

        return try {
            val dao = DreamDatabase.getInstance(applicationContext).dreamDao()
            val entry = dao.getDreamById(dreamId)
            if (entry == null) {
                Log.w(TAG, "Entry $dreamId went away before the words arrived")
                return Result.success()
            }

            // Only ever fill in a placeholder. If something else has already put real words
            // on this entry -- the user editing it, a re-run -- they win.
            if (entry.transcription != TranscriptPlaceholders.NO_TRANSCRIPT_WITH_AUDIO) {
                Log.i(TAG, "Entry $dreamId already has words -- leaving it alone")
                return Result.success()
            }

            dao.update(
                entry.copy(
                    transcription = transcript,
                    isFragment = transcript.length < FRAGMENT_MAX_CHARS
                )
            )
            // Never log the words themselves -- this is the user's private journal.
            Log.d(TAG, "Entry $dreamId transcribed: ${transcript.length} chars")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Could not save the transcript: ${e.message}")
            Result.retry()
        }
    }

    /**
     * Runs the recogniser against [file], piping the decoded audio to it.
     *
     * SpeechRecognizer has to be built and driven on a looper thread, hence Main; the
     * decode runs off it, filling the pipe as fast as the recogniser drains it.
     */
    private suspend fun transcribe(file: File): String? = withContext(Dispatchers.Main) {
        if (!SpeechRecognizer.isRecognitionAvailable(applicationContext)) {
            Log.w(TAG, "No recognition service on this device")
            return@withContext null
        }

        val audio = probeAudioFormat(file) ?: run {
            Log.w(TAG, "Could not read the recording's audio format")
            return@withContext null
        }

        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]

        val pump = CoroutineScope(Dispatchers.IO).launch {
            ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { out ->
                try {
                    decodePcmInto(file, out)
                } catch (e: Exception) {
                    Log.w(TAG, "Decode stopped early: ${e.message}")
                }
            }
        }

        try {
            recogniseFrom(readSide, audio)
        } finally {
            pump.cancel()
            try {
                readSide.close()
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun recogniseFrom(
        source: ParcelFileDescriptor,
        audio: AudioFormatInfo
    ): String? = suspendCancellableCoroutine { cont ->
        val transcript = TranscriptAccumulator()
        val recognizer = SpeechRecognizer.createSpeechRecognizer(applicationContext)

        fun finish(result: String?) {
            if (!cont.isActive) return
            try {
                recognizer.destroy()
            } catch (_: Exception) {
            }
            cont.resume(result)
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onPartialResults(partialResults: android.os.Bundle?) {
                transcript.onPartial(firstMatch(partialResults))
            }

            override fun onResults(results: android.os.Bundle?) {
                // A file ends where it ends, so this is the whole utterance. Anything the
                // partials had that never made it into a final result is still worth
                // keeping -- losing that is what left dreams reaching the database empty.
                transcript.onFinal(firstMatch(results))
                transcript.salvagePending()
                finish(transcript.text.ifBlank { null })
            }

            override fun onError(error: Int) {
                // The recogniser gave up, but the partials it managed are better than
                // nothing at all.
                transcript.salvagePending()
                val salvaged = transcript.text
                if (salvaged.isBlank()) {
                    Log.i(TAG, "Recogniser error $error (${errorName(error)}) with nothing to show")
                    finish(null)
                } else {
                    Log.i(TAG, "Recogniser error $error (${errorName(error)}); kept ${salvaged.length} chars")
                    finish(salvaged)
                }
            }

            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })

        cont.invokeOnCancellation {
            try {
                recognizer.cancel()
                recognizer.destroy()
            } catch (_: Exception) {
            }
        }

        val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Offline keeps the dream on the device, which is what the privacy policy
            // promises. If the language pack is missing the recogniser errors and the
            // placeholder stands -- it is not worth breaking that promise to avoid.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, audio.encoding)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, audio.sampleRate)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, audio.channelCount)
        }

        try {
            recognizer.startListening(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Recogniser would not start on a file: ${e.message}")
            finish(null)
        }
    }

    private fun firstMatch(bundle: android.os.Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    // ── Decoding ──────────────────────────────────────────────────────────

    private data class AudioFormatInfo(
        val sampleRate: Int,
        val channelCount: Int,
        val encoding: Int
    )

    /** Reads the recording's sample rate and channel count without decoding it. */
    private fun probeAudioFormat(file: File): AudioFormatInfo? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val track = audioTrackIndex(extractor) ?: return null
            val format = extractor.getTrackFormat(track)
            AudioFormatInfo(
                sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                encoding = android.media.AudioFormat.ENCODING_PCM_16BIT
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not probe the recording: ${e.message}")
            null
        } finally {
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun audioTrackIndex(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) return i
        }
        return null
    }

    /**
     * Decodes the recording to raw 16-bit PCM and writes it to [out].
     *
     * The recogniser wants PCM and the recording is AAC in an MPEG-4 container, so it has
     * to be run back through a decoder first. Closing [out] at the end is what tells the
     * recogniser the audio has finished -- without it, it waits for more that never comes.
     */
    private fun decodePcmInto(file: File, out: OutputStream) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            extractor.setDataSource(file.absolutePath)
            val track = audioTrackIndex(extractor) ?: return
            extractor.selectTrack(track)

            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return

            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }

            val info = MediaCodec.BufferInfo()
            var sawInputEnd = false
            var sawOutputEnd = false

            while (!sawOutputEnd) {
                if (!sawInputEnd) {
                    val inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer: ByteBuffer? = codec.getInputBuffer(inIndex)
                        val size = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEnd = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER,
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED,
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit

                    else -> {
                        if (outIndex >= 0) {
                            val buffer = codec.getOutputBuffer(outIndex)
                            if (buffer != null && info.size > 0) {
                                val chunk = ByteArray(info.size)
                                buffer.position(info.offset)
                                buffer.get(chunk, 0, info.size)
                                out.write(chunk)
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawOutputEnd = true
                        }
                    }
                }
            }
            out.flush()
        } finally {
            try {
                codec?.stop()
                codec?.release()
            } catch (_: Exception) {
            }
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun errorName(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
        SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
        SpeechRecognizer.ERROR_SERVER -> "SERVER"
        SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "LANGUAGE_NOT_SUPPORTED"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "LANGUAGE_UNAVAILABLE"
        else -> "UNKNOWN"
    }

    companion object {
        private const val TAG = "Transcription"

        private const val KEY_DREAM_ID = "dream_id"
        private const val KEY_AUDIO_PATH = "audio_path"

        /** Transcripts shorter than this are shown as fragments rather than full dreams. */
        const val FRAGMENT_MAX_CHARS = 50

        private const val DEQUEUE_TIMEOUT_US = 10_000L

        /**
         * Ceiling on one transcription. A ten-minute recording decodes far faster than real
         * time, so anything approaching this means the recogniser has stopped answering and
         * the entry is better off keeping its placeholder than holding a worker open.
         */
        private const val RECOGNITION_TIMEOUT_MS = 5 * 60_000L

        /** Queues the words for a freshly saved dream. */
        fun enqueue(context: Context, dreamId: Long, audioPath: String) {
            val request = OneTimeWorkRequestBuilder<TranscriptionWorker>()
                .setInputData(
                    Data.Builder()
                        .putLong(KEY_DREAM_ID, dreamId)
                        .putString(KEY_AUDIO_PATH, audioPath)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "transcribe_$dreamId",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
