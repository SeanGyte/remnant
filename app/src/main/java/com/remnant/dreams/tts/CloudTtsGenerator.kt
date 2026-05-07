package com.remnant.dreams.tts

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class CloudTtsGenerator(private val context: Context) {

    /**
     * Generate and cache a TTS prompt to a specific file slot.
     * [promptType] determines the filename (e.g. PROMPT_DREAM, PROMPT_WAKE_CHECK).
     */
    suspend fun generatePrompt(
        text: String,
        voice: VoiceOption,
        apiKey: String,
        promptType: String = PROMPT_DREAM
    ): File? = withContext(Dispatchers.IO) {
        try {
            val url = URL("$ENDPOINT?key=$apiKey")
            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 15_000
                readTimeout = 15_000
                doOutput = true
            }

            val body = JSONObject().apply {
                put("input", JSONObject().put("text", text))
                put("voice", JSONObject().apply {
                    put("languageCode", voice.languageCode)
                    put("name", voice.id)
                })
                put("audioConfig", JSONObject().apply {
                    put("audioEncoding", "MP3")
                    put("speakingRate", 0.95)
                    put("pitch", 0.0)
                })
            }

            connection.outputStream.use { it.write(body.toString().toByteArray()) }

            if (connection.responseCode != 200) {
                Log.e(TAG, "Cloud TTS failed: ${connection.responseCode}")
                return@withContext null
            }

            val response = connection.inputStream.bufferedReader().readText()
            val audioContent = JSONObject(response).getString("audioContent")
            val audioBytes = Base64.decode(audioContent, Base64.DEFAULT)

            val promptFile = getPromptFile(promptType)
            promptFile.writeBytes(audioBytes)

            Log.d(TAG, "Prompt audio cached ($promptType): ${promptFile.absolutePath} (${audioBytes.size} bytes)")
            promptFile
        } catch (e: Exception) {
            Log.e(TAG, "Cloud TTS error: ${e.message}")
            null
        }
    }

    fun getCachedPrompt(promptType: String = PROMPT_DREAM): File? {
        val file = getPromptFile(promptType)
        return if (file.exists() && file.length() > 0) file else null
    }

    fun clearCache() {
        getPromptFile(PROMPT_DREAM).delete()
        getPromptFile(PROMPT_WAKE_CHECK).delete()
    }

    private fun getPromptFile(promptType: String): File {
        val dir = File(context.filesDir, "tts").apply { mkdirs() }
        return File(dir, "$promptType.mp3")
    }

    companion object {
        private const val TAG = "CloudTTS"
        private const val ENDPOINT = "https://texttospeech.googleapis.com/v1/text:synthesize"

        /** The dream capture prompt: "Good morning {name}. What did you dream about last night?" */
        const val PROMPT_DREAM = "prompt_dream"

        /** The companion mode wake check: "Are you awake, {name}?" */
        const val PROMPT_WAKE_CHECK = "prompt_wakecheck"
    }
}
