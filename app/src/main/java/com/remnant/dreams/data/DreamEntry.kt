package com.remnant.dreams.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "dreams")
data class DreamEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val transcription: String = "",
    val audioPath: String? = null,
    val durationSeconds: Int = 0,
    val isFragment: Boolean = false,
    val isCompressed: Boolean = false
)
