package com.lingocast.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "series")
data class SeriesEntity(
    @PrimaryKey val seriesId: String,
    val title: String,
    val titleZh: String,
    val description: String,
    val accent: String,
    val cefrRange: String,
    val coverPath: String,
    val episodeCount: Int,
    val totalDurationMs: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "episodes",
    indices = [
        Index("seriesId"),
        Index("lastPlayedAt")
    ]
)
data class EpisodeEntity(
    @PrimaryKey val episodeKey: String, // "$seriesId/$episodeId"
    val seriesId: String,
    val episodeId: String,
    val indexNum: Int,
    val title: String,
    val titleZh: String,
    val cefrLevel: String,
    val topicTagsCsv: String,
    val summaryZh: String,
    val durationMs: Long,
    val hasVideo: Boolean,
    val videoPath: String?,
    val audioPath: String,
    val coverPath: String,
    val episodeJsonPath: String,
    val sentenceCount: Int,
    val vocabCount: Int,
    val noteCount: Int,
    val lastPositionMs: Long = 0L,
    val completed: Boolean = false,
    val studySeconds: Long = 0L,
    val lastPlayedAt: Long = 0L
)

@Entity(
    tableName = "saved_words",
    indices = [
        Index("lemma", unique = true),
        Index("nextReviewAt")
    ]
)
data class SavedWordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val word: String,
    val lemma: String,
    val phonetic: String,
    val pos: String,
    val meaningZh: String,
    val contextMeaningZh: String?,
    val tagsCsv: String,
    val cefrLevel: String,
    val sourceEpisodeKey: String,
    val sourceEpisodeTitle: String,
    val sourceSentenceId: Int,
    val clipStartMs: Long,
    val clipEndMs: Long,
    val sourceSentenceEn: String,
    val sourceSentenceZh: String,
    val intervalDays: Int = 0,
    val easeFactor: Float = 2.5f,
    val repetitions: Int = 0,
    val nextReviewAt: Long = 0L,
    val masteryLevel: Int = 0, // 0=new, 1=learning, 2=familiar, 3=mastered
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "subtitle_index",
    indices = [
        Index("episodeKey"),
        Index("sentenceId")
    ]
)
data class SubtitleIndexEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val episodeKey: String,
    val episodeTitle: String,
    val seriesId: String,
    val sentenceId: Int,
    val startMs: Long,
    val endMs: Long,
    val textEn: String,
    val textZh: String,
    val notesText: String
)
