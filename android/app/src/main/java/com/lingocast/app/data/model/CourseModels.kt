package com.lingocast.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class CatalogJson(
    val schemaVersion: Int = 1,
    val generatedAt: String = "",
    val totalEpisodes: Int = 0,
    val totalDurationMs: Long = 0L,
    val series: List<CatalogSeriesSummary> = emptyList()
)

@Serializable
data class CatalogSeriesSummary(
    val seriesId: String,
    val title: String,
    val titleZh: String,
    val description: String = "",
    val accent: String = "",
    val cefrRange: String = "",
    val coverPath: String = "",
    val manifestPath: String = "",
    val episodeCount: Int = 0,
    val totalDurationMs: Long = 0L,
    val audioTotalBytes: Long = 0L,
    val videoTotalBytes: Long = 0L,
    val lcpFile: String? = null
)

@Serializable
data class SubscriptionFeedJson(
    val protocol: String = "lingocast-feed",
    val protocolVersion: Int = 1,
    val feedId: String = "",
    val title: String = "",
    val description: String = "",
    val updatedAt: String = "",
    val requiresAuth: Boolean = false,
    val totalSeries: Int = 0,
    val totalEpisodes: Int = 0,
    val totalDurationMs: Long = 0L,
    val totalAudioBytes: Long = 0L,
    val totalVideoBytes: Long = 0L,
    val series: List<CatalogSeriesSummary> = emptyList()
)

@Serializable
data class SavedSubscription(
    val id: String,
    val name: String,
    val url: String,
    val token: String = "",
    val lastSyncedAt: Long = 0L,
    val lastEpisodeCount: Int = 0,
    val lastSeriesCount: Int = 0
)

@Serializable
data class SeriesManifestJson(
    val schemaVersion: Int = 1,
    val seriesId: String,
    val title: String,
    val titleZh: String,
    val description: String = "",
    val accent: String = "",
    val cefrRange: String = "",
    val coverPath: String = "",
    val episodeCount: Int = 0,
    val totalDurationMs: Long = 0L,
    val updatedAt: String = "",
    val episodes: List<EpisodeSummaryJson> = emptyList()
)

@Serializable
data class EpisodeSummaryJson(
    val episodeId: String,
    val index: Int,
    val title: String,
    val titleZh: String,
    val cefrLevel: String = "B1",
    val topicTags: List<String> = emptyList(),
    val summaryZh: String = "",
    val durationMs: Long = 0L,
    val hasVideo: Boolean = false,
    val videoSizeBytes: Long = 0L,
    val audioSizeBytes: Long = 0L,
    val sentenceCount: Int = 0,
    val vocabCount: Int = 0,
    val noteCount: Int = 0,
    val coverFile: String = "",
    val episodeJsonFile: String = "",
    val videoFile: String? = null,
    val audioFile: String = ""
)

@Serializable
data class EpisodeBundleJson(
    val schemaVersion: Int = 1,
    val episodeId: String,
    val seriesId: String,
    val index: Int,
    val sourceFilename: String = "",
    val title: String,
    val titleZh: String,
    val cefrLevel: String = "B1",
    val topicTags: List<String> = emptyList(),
    val summaryZh: String = "",
    val durationMs: Long = 0L,
    val media: MediaInfoJson,
    val subtitleSource: SubtitleSourceJson = SubtitleSourceJson(),
    val sentences: List<SentenceJson> = emptyList(),
    val timelineNotes: List<TimelineNoteJson> = emptyList(),
    val vocabList: List<VocabItemJson> = emptyList()
)

@Serializable
data class MediaInfoJson(
    val videoFile: String? = null,
    val audioFile: String = "audio.m4a",
    val coverFile: String = "cover.jpg",
    val hasVideo: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
    val waveformPeaks: List<Int> = emptyList()
)

@Serializable
data class SubtitleSourceJson(
    val tier: Int = 3,
    val engine: String = "",
    val hasBurnedSubs: Boolean = false,
    val ocrTitleRaw: String? = null,
    val referenceUsed: String? = null
)

@Serializable
data class SentenceJson(
    val id: Int,
    val startMs: Long,
    val endMs: Long,
    val textEn: String,
    val textZh: String = "",
    val speaker: String? = null,
    val words: List<WordTimingJson> = emptyList()
)

@Serializable
data class WordTimingJson(
    val w: String,
    val s: Long,
    val e: Long,
    val lemma: String? = null,
    val vocabId: String? = null
)

@Serializable
data class TimelineNoteJson(
    val id: String,
    val sentenceId: Int,
    val startMs: Long,
    val endMs: Long,
    val phrase: String,
    val category: String = "ielts",
    val cefrLevel: String = "B2",
    val phonetic: String? = null,
    val meaningZh: String = "",
    val usageTipZh: String = ""
)

@Serializable
data class VocabItemJson(
    val id: String,
    val word: String,
    val lemma: String,
    val phonetic: String = "",
    val pos: String = "",
    val meaningZh: String = "",
    val contextMeaningZh: String? = null,
    val cefrLevel: String = "B2",
    val tags: List<String> = emptyList(),
    val collins: Int = 0,
    val oxford: Int = 0,
    val occurrences: List<VocabOccurrenceJson> = emptyList()
)

@Serializable
data class VocabOccurrenceJson(
    val sentenceId: Int,
    val startMs: Long,
    val endMs: Long
)

data class DictionaryResult(
    val word: String,
    val phonetic: String,
    val pos: String,
    val meaningZh: String,
    val definitionEn: String,
    val tags: List<String>,
    val cefrLevel: String,
    val collins: Int,
    val oxford: Int,
    val contextMeaningZh: String? = null
)
