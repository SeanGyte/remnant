package com.remnant.dreams.ui

import android.animation.ValueAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.remnant.dreams.data.DreamEntry
import com.remnant.dreams.databinding.ItemDiaryEntryBinding
import com.remnant.dreams.databinding.ItemMonthHeaderBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Accordion-style diary adapter. Displays month headers and collapsible dream entries.
 * Each item in the list is either a [DiaryItem.MonthHeader] or a [DiaryItem.Entry].
 */
class DiaryAdapter(
    private val onEditClick: (DreamEntry) -> Unit,
    private val onPlayClick: (DreamEntry) -> Unit,
    private val onTryAgainClick: (DreamEntry) -> Unit
) : ListAdapter<DiaryItem, RecyclerView.ViewHolder>(DiaryDiffCallback()) {

    private val expandedIds = mutableSetOf<Long>()

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is DiaryItem.MonthHeader -> VIEW_TYPE_HEADER
            is DiaryItem.Entry -> VIEW_TYPE_ENTRY
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> {
                val binding = ItemMonthHeaderBinding.inflate(inflater, parent, false)
                MonthHeaderViewHolder(binding)
            }
            else -> {
                val binding = ItemDiaryEntryBinding.inflate(inflater, parent, false)
                EntryViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is DiaryItem.MonthHeader -> (holder as MonthHeaderViewHolder).bind(item)
            is DiaryItem.Entry -> (holder as EntryViewHolder).bind(item.dream)
        }
    }

    inner class MonthHeaderViewHolder(
        private val binding: ItemMonthHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(header: DiaryItem.MonthHeader) {
            binding.textMonthHeader.text = header.label
        }
    }

    inner class EntryViewHolder(
        private val binding: ItemDiaryEntryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val dayFormat = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())
        private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

        fun bind(entry: DreamEntry) {
            val date = Date(entry.timestamp)
            val isExpanded = expandedIds.contains(entry.id)

            // Header fields
            binding.textEntryDay.text = dayFormat.format(date)
            binding.textEntryTime.text = timeFormat.format(date)

            // Duration
            val minutes = entry.durationSeconds / 60
            val seconds = entry.durationSeconds % 60
            binding.textEntryDuration.text = if (minutes > 0) {
                "${minutes}m ${seconds}s"
            } else {
                "${seconds}s"
            }

            // Fragment indicator (red dot)
            binding.fragmentIndicator.visibility =
                if (entry.isFragment) View.VISIBLE else View.GONE

            // Chevron rotation (no animation on bind, just set state)
            binding.textChevron.rotation = if (isExpanded) 90f else 0f

            // Expanded body visibility
            binding.expandedBody.visibility = if (isExpanded) View.VISIBLE else View.GONE

            // Transcript
            binding.textTranscript.text = entry.transcription.ifEmpty {
                "No transcript available."
            }

            // Fragment note + Try again logic
            val ageMs = System.currentTimeMillis() - entry.timestamp
            val fortyEightHoursMs = 48L * 60 * 60 * 1000

            if (entry.isFragment) {
                if (ageMs <= fortyEightHoursMs) {
                    // Recent fragment -- show try again, no note
                    binding.textFragmentNote.visibility = View.GONE
                    binding.btnTryAgain.visibility = View.VISIBLE
                } else {
                    // Old fragment -- show note, no try again
                    binding.textFragmentNote.text = "This capture was incomplete"
                    binding.textFragmentNote.visibility = View.VISIBLE
                    binding.btnTryAgain.visibility = View.GONE
                }
            } else {
                binding.textFragmentNote.visibility = View.GONE
                binding.btnTryAgain.visibility = View.GONE
            }

            // Audio button logic
            val hasAudioPath = !entry.audioPath.isNullOrEmpty()
            val audioFileExists = hasAudioPath && File(entry.audioPath!!).exists()

            when {
                audioFileExists -> {
                    // Audio file is there -- show play button
                    binding.btnPlayAudio.text = "\u25B6 Play audio"
                    binding.btnPlayAudio.visibility = View.VISIBLE
                    binding.btnAudioExpired.visibility = View.GONE
                    binding.btnPlayAudio.setOnClickListener { onPlayClick(entry) }
                }
                hasAudioPath -> {
                    // Had audio but file is gone -- expired
                    binding.btnPlayAudio.visibility = View.GONE
                    binding.btnAudioExpired.visibility = View.VISIBLE
                }
                else -> {
                    // Never had audio
                    binding.btnPlayAudio.visibility = View.GONE
                    binding.btnAudioExpired.visibility = View.GONE
                }
            }

            // Edit button
            binding.btnEdit.setOnClickListener { onEditClick(entry) }

            // Try again button
            binding.btnTryAgain.setOnClickListener { onTryAgainClick(entry) }

            // Toggle expand/collapse on header tap
            binding.headerRow.setOnClickListener {
                val expanding = !expandedIds.contains(entry.id)
                if (expanding) {
                    expandedIds.add(entry.id)
                } else {
                    expandedIds.remove(entry.id)
                }

                // Animate chevron
                val targetRotation = if (expanding) 90f else 0f
                binding.textChevron.animate()
                    .rotation(targetRotation)
                    .setDuration(200)
                    .setInterpolator(DecelerateInterpolator())
                    .start()

                // Animate body expand/collapse
                if (expanding) {
                    binding.expandedBody.visibility = View.VISIBLE
                    binding.expandedBody.measure(
                        View.MeasureSpec.makeMeasureSpec(binding.root.width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                    )
                    val targetHeight = binding.expandedBody.measuredHeight
                    binding.expandedBody.layoutParams.height = 0
                    binding.expandedBody.requestLayout()

                    val anim = ValueAnimator.ofInt(0, targetHeight).apply {
                        duration = 250
                        interpolator = DecelerateInterpolator()
                        addUpdateListener { animator ->
                            val value = animator.animatedValue as Int
                            binding.expandedBody.layoutParams.height = value
                            binding.expandedBody.requestLayout()
                        }
                    }
                    anim.addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            binding.expandedBody.layoutParams.height =
                                ViewGroup.LayoutParams.WRAP_CONTENT
                        }
                    })
                    anim.start()
                } else {
                    val startHeight = binding.expandedBody.height
                    val anim = ValueAnimator.ofInt(startHeight, 0).apply {
                        duration = 200
                        interpolator = DecelerateInterpolator()
                        addUpdateListener { animator ->
                            val value = animator.animatedValue as Int
                            binding.expandedBody.layoutParams.height = value
                            binding.expandedBody.requestLayout()
                        }
                    }
                    anim.addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            binding.expandedBody.visibility = View.GONE
                            binding.expandedBody.layoutParams.height =
                                ViewGroup.LayoutParams.WRAP_CONTENT
                        }
                    })
                    anim.start()
                }
            }
        }
    }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_ENTRY = 1

        /**
         * Converts a flat list of DreamEntry objects into a grouped list with month headers.
         * Dreams are assumed to already be sorted newest-first.
         */
        fun groupByMonth(dreams: List<DreamEntry>): List<DiaryItem> {
            if (dreams.isEmpty()) return emptyList()

            val result = mutableListOf<DiaryItem>()
            val monthFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
            val cal = Calendar.getInstance()
            var currentMonth = ""

            for (dream in dreams) {
                cal.timeInMillis = dream.timestamp
                val monthLabel = monthFormat.format(cal.time)

                if (monthLabel != currentMonth) {
                    currentMonth = monthLabel
                    result.add(DiaryItem.MonthHeader(monthLabel))
                }

                result.add(DiaryItem.Entry(dream))
            }

            return result
        }
    }
}

/** Sealed class representing items in the diary list. */
sealed class DiaryItem {
    data class MonthHeader(val label: String) : DiaryItem()
    data class Entry(val dream: DreamEntry) : DiaryItem()
}

class DiaryDiffCallback : DiffUtil.ItemCallback<DiaryItem>() {
    override fun areItemsTheSame(oldItem: DiaryItem, newItem: DiaryItem): Boolean {
        return when {
            oldItem is DiaryItem.MonthHeader && newItem is DiaryItem.MonthHeader ->
                oldItem.label == newItem.label
            oldItem is DiaryItem.Entry && newItem is DiaryItem.Entry ->
                oldItem.dream.id == newItem.dream.id
            else -> false
        }
    }

    override fun areContentsTheSame(oldItem: DiaryItem, newItem: DiaryItem): Boolean {
        return oldItem == newItem
    }
}
