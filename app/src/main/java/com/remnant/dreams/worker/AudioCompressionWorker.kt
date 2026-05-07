package com.remnant.dreams.worker

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.remnant.dreams.data.DreamDatabase
import java.io.File
import java.nio.ByteBuffer
import java.util.Calendar

/**
 * Daily worker that re-encodes audio files older than 7 days from
 * 128kbps/44.1kHz AAC down to 32kbps/16kHz mono AAC to save storage.
 *
 * If re-encoding fails for any file, the original is kept untouched.
 */
class AudioCompressionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "AudioCompressionWorker"
        private const val COMPRESSION_AGE_DAYS = 7
        private const val TARGET_BITRATE = 32_000
        private const val TARGET_SAMPLE_RATE = 16_000
        private const val TARGET_CHANNEL_COUNT = 1
        private const val CODEC_TIMEOUT_US = 10_000L
    }

    override suspend fun doWork(): Result {
        val dao = DreamDatabase.getInstance(applicationContext).dreamDao()

        val cutoff = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -COMPRESSION_AGE_DAYS)
        }.timeInMillis

        val entries = dao.getUncompressedAudioEntries(cutoff)
        if (entries.isEmpty()) {
            Log.d(TAG, "No uncompressed audio files older than $COMPRESSION_AGE_DAYS days")
            return Result.success()
        }

        Log.d(TAG, "Found ${entries.size} audio files to compress")

        for (entry in entries) {
            val audioPath = entry.audioPath ?: continue
            val sourceFile = File(audioPath)
            if (!sourceFile.exists() || sourceFile.length() == 0L) {
                // File is gone or empty -- mark as compressed so we don't keep retrying
                dao.update(entry.copy(isCompressed = true))
                continue
            }

            val tempFile = File(sourceFile.parent, "${sourceFile.nameWithoutExtension}_compressed.m4a")

            try {
                val success = reencodeAac(sourceFile, tempFile)
                if (success && tempFile.exists() && tempFile.length() > 0) {
                    // Replace original with compressed version
                    if (sourceFile.delete()) {
                        tempFile.renameTo(sourceFile)
                        dao.update(entry.copy(isCompressed = true))
                        Log.d(TAG, "Compressed ${sourceFile.name}: ${sourceFile.length()} bytes")
                    } else {
                        // Couldn't delete original -- clean up temp and skip
                        tempFile.delete()
                        Log.w(TAG, "Couldn't delete original: ${sourceFile.name}")
                    }
                } else {
                    // Re-encode failed or produced empty file -- keep original
                    tempFile.delete()
                    Log.w(TAG, "Re-encode failed for ${sourceFile.name}, keeping original")
                }
            } catch (e: Exception) {
                // Any unexpected error -- keep original, clean up temp
                tempFile.delete()
                Log.e(TAG, "Error compressing ${sourceFile.name}", e)
            }
        }

        return Result.success()
    }

    /**
     * Re-encodes an AAC audio file to lower bitrate/sample rate using MediaCodec.
     * Returns true on success, false on failure.
     */
    private fun reencodeAac(source: File, dest: File): Boolean {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        try {
            // Set up extractor to read source file
            extractor = MediaExtractor().apply {
                setDataSource(source.absolutePath)
            }

            // Find the audio track
            val trackIndex = findAudioTrack(extractor) ?: return false
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)

            val inputMime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return false
            val inputSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val inputChannelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            // If already at or below target params, just mark as compressed
            if (inputSampleRate <= TARGET_SAMPLE_RATE && inputChannelCount <= TARGET_CHANNEL_COUNT) {
                val bitrate = inputFormat.getIntegerOrDefault(MediaFormat.KEY_BIT_RATE, Int.MAX_VALUE)
                if (bitrate <= TARGET_BITRATE) {
                    return true // Already small enough
                }
            }

            // Configure decoder
            decoder = MediaCodec.createDecoderByType(inputMime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            // Configure encoder
            val encoderFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                TARGET_SAMPLE_RATE,
                TARGET_CHANNEL_COUNT
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, TARGET_BITRATE)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            // Set up muxer
            muxer = MediaMuxer(dest.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxerTrackIndex = -1

            val decoderInputBuffers = decoder.inputBuffers
            var decoderOutputBuffers = decoder.outputBuffers
            var encoderInputBuffers = encoder.inputBuffers
            var encoderOutputBuffers = encoder.outputBuffers

            val decoderBufferInfo = MediaCodec.BufferInfo()
            val encoderBufferInfo = MediaCodec.BufferInfo()

            var inputDone = false
            var decoderDone = false
            var encoderDone = false

            // We need to resample if rates differ. For simplicity with MediaCodec,
            // the decoder outputs PCM at the source rate. The encoder expects PCM at
            // the target rate. Android's AAC encoder typically handles rate conversion
            // internally when configured with a different sample rate, but if the
            // device doesn't support it, we do a naive resample.
            val needsResample = inputSampleRate != TARGET_SAMPLE_RATE
            val needsDownmix = inputChannelCount > TARGET_CHANNEL_COUNT

            while (!encoderDone) {
                // Feed data into decoder
                if (!inputDone) {
                    val inputBufIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputBufIndex >= 0) {
                        val inputBuf = decoderInputBuffers[inputBufIndex]
                        val sampleSize = extractor.readSampleData(inputBuf, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inputBufIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(
                                inputBufIndex, 0, sampleSize,
                                extractor.sampleTime, 0
                            )
                            extractor.advance()
                        }
                    }
                }

                // Pull decoded PCM from decoder and feed into encoder
                if (!decoderDone) {
                    val decoderStatus = decoder.dequeueOutputBuffer(decoderBufferInfo, CODEC_TIMEOUT_US)
                    when {
                        decoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                            decoderOutputBuffers = decoder.outputBuffers
                        }
                        decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            // Decoder output format changed, continue
                        }
                        decoderStatus >= 0 -> {
                            val isEos = (decoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

                            if (decoderBufferInfo.size > 0) {
                                val decoderOutputBuf = decoderOutputBuffers[decoderStatus]
                                decoderOutputBuf.position(decoderBufferInfo.offset)
                                decoderOutputBuf.limit(decoderBufferInfo.offset + decoderBufferInfo.size)

                                // Process PCM: resample and/or downmix as needed
                                var pcmData = ByteArray(decoderBufferInfo.size)
                                decoderOutputBuf.get(pcmData)

                                if (needsDownmix && inputChannelCount == 2) {
                                    pcmData = downmixStereoToMono(pcmData)
                                }
                                if (needsResample) {
                                    pcmData = resample(pcmData, inputSampleRate, TARGET_SAMPLE_RATE)
                                }

                                // Feed processed PCM into encoder
                                feedEncoder(encoder, pcmData, decoderBufferInfo.presentationTimeUs, false)
                            }

                            decoder.releaseOutputBuffer(decoderStatus, false)

                            if (isEos) {
                                decoderDone = true
                                // Signal end of stream to encoder
                                feedEncoder(encoder, ByteArray(0), 0L, true)
                            }
                        }
                    }
                }

                // Pull compressed data from encoder and write to muxer
                val encoderStatus = encoder.dequeueOutputBuffer(encoderBufferInfo, CODEC_TIMEOUT_US)
                when {
                    encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                        encoderOutputBuffers = encoder.outputBuffers
                    }
                    encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxerStarted) {
                            // Shouldn't happen, but handle gracefully
                            Log.w(TAG, "Encoder output format changed after muxer started")
                        } else {
                            muxerTrackIndex = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    encoderStatus >= 0 -> {
                        val encoderOutputBuf = encoderOutputBuffers[encoderStatus]

                        if (encoderBufferInfo.size > 0 && muxerStarted) {
                            encoderOutputBuf.position(encoderBufferInfo.offset)
                            encoderOutputBuf.limit(encoderBufferInfo.offset + encoderBufferInfo.size)
                            muxer.writeSampleData(muxerTrackIndex, encoderOutputBuf, encoderBufferInfo)
                        }

                        val isEos = (encoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        encoder.releaseOutputBuffer(encoderStatus, false)

                        if (isEos) {
                            encoderDone = true
                        }
                    }
                }
            }

            return muxerStarted // Only true if we actually wrote data

        } catch (e: Exception) {
            Log.e(TAG, "Re-encode failed", e)
            return false
        } finally {
            try { extractor?.release() } catch (_: Exception) {}
            try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
            try { encoder?.stop(); encoder?.release() } catch (_: Exception) {}
            try { if (muxerStarted) muxer?.stop(); muxer?.release() } catch (_: Exception) {}
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return null
    }

    /**
     * Feed PCM bytes into the encoder. Blocks briefly waiting for an input buffer.
     */
    private fun feedEncoder(encoder: MediaCodec, data: ByteArray, presentationTimeUs: Long, eos: Boolean) {
        val flags = if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
        // Try a few times to get an input buffer
        for (attempt in 0 until 100) {
            val inputBufIndex = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
            if (inputBufIndex >= 0) {
                val inputBuf = encoder.getInputBuffer(inputBufIndex) ?: return
                inputBuf.clear()
                if (data.isNotEmpty()) {
                    val size = minOf(data.size, inputBuf.remaining())
                    inputBuf.put(data, 0, size)
                }
                encoder.queueInputBuffer(inputBufIndex, 0, data.size, presentationTimeUs, flags)
                return
            }
        }
        Log.w(TAG, "Couldn't get encoder input buffer after retries")
    }

    /**
     * Naive downmix from stereo 16-bit PCM to mono by averaging L+R channels.
     */
    private fun downmixStereoToMono(stereoData: ByteArray): ByteArray {
        val sampleCount = stereoData.size / 4 // 2 bytes per sample, 2 channels
        val mono = ByteArray(sampleCount * 2)
        for (i in 0 until sampleCount) {
            val leftOffset = i * 4
            val left = (stereoData[leftOffset].toInt() and 0xFF) or
                    (stereoData[leftOffset + 1].toInt() shl 8)
            val right = (stereoData[leftOffset + 2].toInt() and 0xFF) or
                    (stereoData[leftOffset + 3].toInt() shl 8)
            val mixed = ((left.toShort() + right.toShort()) / 2).toShort()
            val monoOffset = i * 2
            mono[monoOffset] = (mixed.toInt() and 0xFF).toByte()
            mono[monoOffset + 1] = (mixed.toInt() shr 8).toByte()
        }
        return mono
    }

    /**
     * Naive linear-interpolation resample for 16-bit mono PCM.
     * Good enough for voice recordings being compressed for archival.
     */
    private fun resample(data: ByteArray, fromRate: Int, toRate: Int): ByteArray {
        if (fromRate == toRate) return data

        val sampleCount = data.size / 2
        val ratio = fromRate.toDouble() / toRate.toDouble()
        val outputSampleCount = (sampleCount / ratio).toInt()
        val output = ByteArray(outputSampleCount * 2)

        for (i in 0 until outputSampleCount) {
            val srcPos = i * ratio
            val srcIndex = srcPos.toInt()
            val frac = srcPos - srcIndex

            val s0 = getSample16(data, srcIndex.coerceAtMost(sampleCount - 1))
            val s1 = getSample16(data, (srcIndex + 1).coerceAtMost(sampleCount - 1))
            val interpolated = (s0 + frac * (s1 - s0)).toInt().toShort()

            val offset = i * 2
            output[offset] = (interpolated.toInt() and 0xFF).toByte()
            output[offset + 1] = (interpolated.toInt() shr 8).toByte()
        }

        return output
    }

    private fun getSample16(data: ByteArray, index: Int): Double {
        val offset = index * 2
        if (offset + 1 >= data.size) return 0.0
        val value = (data[offset].toInt() and 0xFF) or (data[offset + 1].toInt() shl 8)
        return value.toShort().toDouble()
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, default: Int): Int {
        return try {
            getInteger(key)
        } catch (_: Exception) {
            default
        }
    }
}
