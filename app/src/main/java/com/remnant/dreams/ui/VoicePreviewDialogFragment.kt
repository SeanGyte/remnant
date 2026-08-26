package com.remnant.dreams.ui

import android.app.Dialog
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.remnant.dreams.R
import com.remnant.dreams.data.PrefsManager
import com.remnant.dreams.tts.ApiKeys
import com.remnant.dreams.tts.CloudTtsGenerator
import com.remnant.dreams.tts.VoiceOption
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/** Row identity in the picker list. */
private fun VoiceOption?.rowId(): String = this?.id ?: VoiceOption.DEVICE_VOICE_ID

/**
 * Full-screen dialog for previewing and changing the morning voice. A Cloud voice is
 * assigned on first run, so this is where the user hears the alternatives and, if they
 * would rather nothing left the device, switches to the phone's own voice. The disclosure
 * sits above the list, where the Cloud voices are.
 * Previews are cached separately from the actual prompt audio.
 */
class VoicePreviewDialogFragment : DialogFragment() {

    /** Called when the user confirms a selection. Null means the phone's own voice. */
    var onVoiceSelected: ((VoiceOption?) -> Unit)? = null

    private lateinit var prefs: PrefsManager
    private var selectedVoiceId: String = ""
    private var mediaPlayer: MediaPlayer? = null
    private var currentGenerateJob: Job? = null
    private var playingVoiceId: String? = null
    private var adapter: VoiceAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.Theme_Remnant_Dialog)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState).apply {
            window?.setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.dialog_voice_preview, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefs = PrefsManager(requireContext())
        // Every install has a voice by now (assigned on first run), but an empty id would
        // mean no Cloud voice, which is the phone's own voice row.
        selectedVoiceId = prefs.selectedVoiceId.ifEmpty { VoiceOption.DEVICE_VOICE_ID }

        val recycler = view.findViewById<RecyclerView>(R.id.recyclerVoices)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnCancel)
        val btnConfirm = view.findViewById<MaterialButton>(R.id.btnConfirm)

        adapter = VoiceAdapter(
            // The null row is the phone's own voice -- the way out of the Cloud voices
            // the app assigns and offers.
            voices = listOf<VoiceOption?>(null) + VoiceOption.ALL,
            selectedId = selectedVoiceId,
            onPreviewClick = ::onPreviewClicked,
            onRowClick = ::onRowClicked
        )

        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        btnCancel.setOnClickListener { dismiss() }

        btnConfirm.setOnClickListener {
            onVoiceSelected?.invoke(VoiceOption.selectedOrNull(selectedVoiceId))
            dismiss()
        }
    }

    private fun onRowClicked(voice: VoiceOption?) {
        selectedVoiceId = voice.rowId()
        adapter?.updateSelectedId(selectedVoiceId)
    }

    private fun onPreviewClicked(voice: VoiceOption, position: Int) {
        // If already playing this voice, stop it
        if (playingVoiceId == voice.id && mediaPlayer?.isPlaying == true) {
            stopPlayback()
            adapter?.setRowState(position, RowState.IDLE)
            return
        }

        // Stop any current playback/generation
        stopPlayback()
        currentGenerateJob?.cancel()
        // Reset the previously playing row if different
        adapter?.clearAllStates()

        // Also select this row
        onRowClicked(voice)

        // Check cache first
        val cachedFile = getPreviewFile(voice.id)
        if (cachedFile.exists() && cachedFile.length() > 0) {
            playPreview(cachedFile, voice.id, position)
            return
        }

        // Generate preview
        val apiKey = ApiKeys.GOOGLE_CLOUD_TTS
        if (apiKey.isEmpty()) {
            Toast.makeText(requireContext(), "API key not configured", Toast.LENGTH_SHORT).show()
            return
        }

        adapter?.setRowState(position, RowState.LOADING)

        val name = prefs.userName.ifEmpty { "there" }
        val previewText = "Good morning $name. What did you dream about last night?"
        val voiceOption = voice

        currentGenerateJob = lifecycleScope.launch {
            val ttsGen = CloudTtsGenerator(requireContext())
            val promptType = "preview_${voiceOption.id}"
            val result = ttsGen.generatePrompt(previewText, voiceOption, apiKey, promptType)

            if (result != null) {
                // Move to our preview cache directory
                val previewFile = getPreviewFile(voiceOption.id)
                previewFile.parentFile?.mkdirs()
                result.copyTo(previewFile, overwrite = true)
                result.delete() // clean up the tts/ directory copy

                if (isAdded) {
                    playPreview(previewFile, voiceOption.id, position)
                }
            } else {
                if (isAdded) {
                    adapter?.setRowState(position, RowState.IDLE)
                    Toast.makeText(requireContext(), "Preview failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun playPreview(file: File, voiceId: String, position: Int) {
        try {
            stopPlayback()
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener {
                    playingVoiceId = null
                    if (isAdded) {
                        adapter?.setRowState(position, RowState.IDLE)
                    }
                }
                start()
            }
            playingVoiceId = voiceId
            adapter?.setRowState(position, RowState.PLAYING)
        } catch (e: Exception) {
            Log.e(TAG, "Playback error: ${e.message}")
            adapter?.setRowState(position, RowState.IDLE)
        }
    }

    private fun stopPlayback() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
        playingVoiceId = null
    }

    private fun getPreviewFile(voiceId: String): File {
        val dir = File(requireContext().filesDir, "tts/previews").apply { mkdirs() }
        return File(dir, "$voiceId.mp3")
    }

    override fun onDestroyView() {
        currentGenerateJob?.cancel()
        stopPlayback()
        adapter = null
        super.onDestroyView()
    }

    // ── Row state ────────────────────────────────────────────────────────

    enum class RowState { IDLE, LOADING, PLAYING }

    // ── Adapter ──────────────────────────────────────────────────────────

    private class VoiceAdapter(
        /** A null entry is the phone's own voice: nothing to preview, nothing sent. */
        private val voices: List<VoiceOption?>,
        private var selectedId: String,
        private val onPreviewClick: (VoiceOption, Int) -> Unit,
        private val onRowClick: (VoiceOption?) -> Unit
    ) : RecyclerView.Adapter<VoiceAdapter.VH>() {

        private val rowStates = MutableList(voices.size) { RowState.IDLE }

        fun updateSelectedId(id: String) {
            val oldIndex = voices.indexOfFirst { it.rowId() == selectedId }
            selectedId = id
            val newIndex = voices.indexOfFirst { it.rowId() == id }
            if (oldIndex >= 0) notifyItemChanged(oldIndex, "selection")
            if (newIndex >= 0) notifyItemChanged(newIndex, "selection")
        }

        fun setRowState(position: Int, state: RowState) {
            if (position in rowStates.indices) {
                rowStates[position] = state
                notifyItemChanged(position, "state")
            }
        }

        fun clearAllStates() {
            for (i in rowStates.indices) {
                if (rowStates[i] != RowState.IDLE) {
                    rowStates[i] = RowState.IDLE
                    notifyItemChanged(i, "state")
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_voice_preview, parent, false)
            return VH(v)
        }

        override fun getItemCount() = voices.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(voices[position], position)
        }

        override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
            if (payloads.isEmpty()) {
                onBindViewHolder(holder, position)
            } else {
                // Partial bind for selection/state changes
                holder.updateState(voices[position], position)
            }
        }

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val dotSelected: View = itemView.findViewById(R.id.dotSelected)
            private val textName: TextView = itemView.findViewById(R.id.textVoiceName)
            private val textDetail: TextView = itemView.findViewById(R.id.textVoiceDetail)
            private val btnPreview: MaterialButton = itemView.findViewById(R.id.btnPreview)
            private val voiceRow: View = itemView.findViewById(R.id.voiceRow)

            fun bind(voice: VoiceOption?, position: Int) {
                val context = itemView.context
                textName.text = voice?.friendlyName ?: context.getString(R.string.voice_device_name)
                textDetail.text = if (voice == null) {
                    context.getString(R.string.voice_device_detail)
                } else {
                    "${voice.description} \u00B7 ${voice.gender}"
                }
                updateState(voice, position)

                btnPreview.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (voice != null && pos != RecyclerView.NO_POSITION) onPreviewClick(voice, pos)
                }
                voiceRow.setOnClickListener {
                    onRowClick(voice)
                    updateSelectedId(voice.rowId())
                }
            }

            fun updateState(voice: VoiceOption?, position: Int) {
                val isSelected = voice.rowId() == selectedId
                dotSelected.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE

                // Nothing to fetch for the phone's own voice, so it has no preview button.
                if (voice == null) {
                    btnPreview.visibility = View.GONE
                    return
                }
                btnPreview.visibility = View.VISIBLE

                when (rowStates[position]) {
                    RowState.IDLE -> {
                        btnPreview.text = "Preview"
                        btnPreview.isEnabled = true
                    }
                    RowState.LOADING -> {
                        btnPreview.text = "Loading..."
                        btnPreview.isEnabled = false
                    }
                    RowState.PLAYING -> {
                        btnPreview.text = "Stop"
                        btnPreview.isEnabled = true
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "VoicePreview"
        const val FRAGMENT_TAG = "voice_preview_dialog"
    }
}
