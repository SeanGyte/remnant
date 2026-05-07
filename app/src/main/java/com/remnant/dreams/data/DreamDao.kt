package com.remnant.dreams.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DreamDao {

    @Query("SELECT * FROM dreams ORDER BY timestamp DESC")
    fun getAllDreams(): Flow<List<DreamEntry>>

    @Query("SELECT * FROM dreams WHERE timestamp >= :since ORDER BY timestamp DESC")
    fun getDreamsSince(since: Long): Flow<List<DreamEntry>>

    @Query("SELECT * FROM dreams WHERE id = :id")
    suspend fun getDreamById(id: Long): DreamEntry?

    @Query("SELECT COUNT(*) FROM dreams")
    suspend fun getTotalCount(): Int

    @Query("SELECT COUNT(*) FROM dreams WHERE timestamp >= :since")
    suspend fun getCountSince(since: Long): Int

    @Query("SELECT * FROM dreams WHERE timestamp >= :since ORDER BY durationSeconds DESC LIMIT 1")
    suspend fun getLongestDreamSince(since: Long): DreamEntry?

    @Query("SELECT MAX(durationSeconds) FROM dreams WHERE timestamp >= :since")
    suspend fun getMaxDurationSince(since: Long): Int?

    @Insert
    suspend fun insert(entry: DreamEntry): Long

    @Update
    suspend fun update(entry: DreamEntry)

    @Delete
    suspend fun delete(entry: DreamEntry)

    @Query("DELETE FROM dreams WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("SELECT * FROM dreams WHERE audioPath IS NOT NULL AND isCompressed = 0 AND timestamp < :olderThan")
    suspend fun getUncompressedAudioEntries(olderThan: Long): List<DreamEntry>

    @Query("SELECT * FROM dreams WHERE audioPath IS NOT NULL AND timestamp < :olderThan")
    suspend fun getEntriesWithAudioOlderThan(olderThan: Long): List<DreamEntry>

    @Query("UPDATE dreams SET audioPath = NULL, transcription = :newTranscription WHERE id = :id")
    suspend fun clearAudioAndUpdateTranscription(id: Long, newTranscription: String)

    @Query("UPDATE dreams SET audioPath = NULL WHERE id = :id")
    suspend fun clearAudioPath(id: Long)
}
