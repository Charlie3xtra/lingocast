package com.lingocast.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SeriesDao {
    @Query("SELECT * FROM series ORDER BY seriesId ASC")
    fun observeAll(): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE seriesId = :seriesId LIMIT 1")
    fun observeById(seriesId: String): Flow<SeriesEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<SeriesEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: SeriesEntity)
}

@Dao
interface EpisodeDao {
    @Query("SELECT * FROM episodes ORDER BY indexNum ASC")
    fun observeAll(): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE seriesId = :seriesId ORDER BY indexNum ASC")
    fun observeBySeries(seriesId: String): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE lastPlayedAt > 0 ORDER BY lastPlayedAt DESC LIMIT 1")
    fun observeLastPlayed(): Flow<EpisodeEntity?>

    @Query("SELECT * FROM episodes WHERE episodeKey = :key LIMIT 1")
    suspend fun getByKey(key: String): EpisodeEntity?

    @Query("SELECT * FROM episodes WHERE episodeKey = :key LIMIT 1")
    fun observeByKey(key: String): Flow<EpisodeEntity?>

    @Query("SELECT episodeKey FROM episodes")
    suspend fun getAllKeys(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: EpisodeEntity)

    @Query("UPDATE episodes SET lastPositionMs = :posMs, lastPlayedAt = :playedAt, completed = :completed, studySeconds = studySeconds + :deltaSec WHERE episodeKey = :key")
    suspend fun updateProgress(
        key: String,
        posMs: Long,
        playedAt: Long,
        completed: Boolean,
        deltaSec: Long
    )
}

@Dao
interface SavedWordDao {
    @Query("SELECT * FROM saved_words ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<SavedWordEntity>>

    @Query("SELECT * FROM saved_words WHERE nextReviewAt <= :nowMs ORDER BY nextReviewAt ASC")
    fun observeDueWords(nowMs: Long): Flow<List<SavedWordEntity>>

    @Query("SELECT * FROM saved_words WHERE LOWER(lemma) = LOWER(:lemma) OR LOWER(word) = LOWER(:word) LIMIT 1")
    suspend fun findByWordOrLemma(word: String, lemma: String): SavedWordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: SavedWordEntity): Long

    @Update
    suspend fun update(item: SavedWordEntity)

    @Query("DELETE FROM saved_words WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface SubtitleIndexDao {
    @Query(
        """
        SELECT * FROM subtitle_index
        WHERE textEn LIKE '%' || :query || '%'
           OR textZh LIKE '%' || :query || '%'
           OR notesText LIKE '%' || :query || '%'
        ORDER BY episodeKey ASC, sentenceId ASC
        LIMIT 80
        """
    )
    suspend fun search(query: String): List<SubtitleIndexEntity>

    @Query("DELETE FROM subtitle_index WHERE episodeKey = :episodeKey")
    suspend fun deleteForEpisode(episodeKey: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<SubtitleIndexEntity>)

    @Transaction
    suspend fun replaceEpisodeIndex(episodeKey: String, items: List<SubtitleIndexEntity>) {
        deleteForEpisode(episodeKey)
        if (items.isNotEmpty()) {
            insertAll(items)
        }
    }
}
