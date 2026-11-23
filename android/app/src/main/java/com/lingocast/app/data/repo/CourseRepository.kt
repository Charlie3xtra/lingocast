package com.lingocast.app.data.repo

import com.lingocast.app.data.db.EpisodeEntity
import com.lingocast.app.data.db.LingoDatabase
import com.lingocast.app.data.db.SavedWordEntity
import com.lingocast.app.data.db.SeriesEntity
import com.lingocast.app.data.db.SubtitleIndexEntity
import com.lingocast.app.data.dict.OfflineDictionary
import com.lingocast.app.data.model.DictionaryResult
import com.lingocast.app.data.model.EpisodeBundleJson
import com.lingocast.app.data.model.SentenceJson
import com.lingocast.app.data.srs.ReviewGrade
import com.lingocast.app.data.srs.Sm2Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

class CourseRepository(
    private val db: LingoDatabase,
    private val json: Json,
    val dictionary: OfflineDictionary
) {
    private val bundleCache = object : LinkedHashMap<String, EpisodeBundleJson>(6, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, EpisodeBundleJson>?): Boolean = size > 4
    }

    fun observeSeries(): Flow<List<SeriesEntity>> = db.seriesDao().observeAll()
    fun observeSeriesById(seriesId: String): Flow<SeriesEntity?> = db.seriesDao().observeById(seriesId)
    fun observeAllEpisodes(): Flow<List<EpisodeEntity>> = db.episodeDao().observeAll()
    fun observeEpisodesBySeries(seriesId: String): Flow<List<EpisodeEntity>> = db.episodeDao().observeBySeries(seriesId)
    fun observeLastPlayedEpisode(): Flow<EpisodeEntity?> = db.episodeDao().observeLastPlayed()
    fun observeEpisode(episodeKey: String): Flow<EpisodeEntity?> = db.episodeDao().observeByKey(episodeKey)
    fun observeSavedWords(): Flow<List<SavedWordEntity>> = db.savedWordDao().observeAll()
    fun observeDueWords(): Flow<List<SavedWordEntity>> = db.savedWordDao().observeDueWords(System.currentTimeMillis())

    suspend fun getEpisode(episodeKey: String): EpisodeEntity? = db.episodeDao().getByKey(episodeKey)

    suspend fun loadEpisodeBundle(episode: EpisodeEntity): EpisodeBundleJson {
        synchronized(bundleCache) {
            bundleCache[episode.episodeKey]?.let { return it }
        }
        return withContext(Dispatchers.IO) {
            val text = File(episode.episodeJsonPath).readText()
            val parsed = json.decodeFromString<EpisodeBundleJson>(text)
            synchronized(bundleCache) {
                bundleCache[episode.episodeKey] = parsed
            }
            parsed
        }
    }

    suspend fun updatePlaybackProgress(
        episodeKey: String,
        positionMs: Long,
        durationMs: Long,
        deltaSeconds: Long
    ) = withContext(Dispatchers.IO) {
        val completed = durationMs > 0L && positionMs >= (durationMs * 0.94f).toLong()
        db.episodeDao().updateProgress(
            key = episodeKey,
            posMs = positionMs,
            playedAt = System.currentTimeMillis(),
            completed = completed,
            deltaSec = deltaSeconds
        )
    }

    suspend fun lookupWord(token: String, contextMeaningZh: String? = null): DictionaryResult? =
        dictionary.lookup(token, contextMeaningZh)

    suspend fun isWordSaved(word: String, lemma: String): Boolean =
        db.savedWordDao().findByWordOrLemma(word, lemma) != null

    suspend fun saveWord(
        dictResult: DictionaryResult,
        episode: EpisodeEntity,
        sentence: SentenceJson
    ): Long = withContext(Dispatchers.IO) {
        val entity = SavedWordEntity(
            word = dictResult.word,
            lemma = dictResult.word.lowercase(),
            phonetic = dictResult.phonetic,
            pos = dictResult.pos,
            meaningZh = dictResult.meaningZh,
            contextMeaningZh = dictResult.contextMeaningZh,
            tagsCsv = dictResult.tags.joinToString(","),
            cefrLevel = dictResult.cefrLevel,
            sourceEpisodeKey = episode.episodeKey,
            sourceEpisodeTitle = episode.titleZh.ifBlank { episode.title },
            sourceSentenceId = sentence.id,
            clipStartMs = sentence.startMs,
            clipEndMs = sentence.endMs,
            sourceSentenceEn = sentence.textEn,
            sourceSentenceZh = sentence.textZh,
            nextReviewAt = System.currentTimeMillis()
        )
        db.savedWordDao().upsert(entity)
    }

    suspend fun removeSavedWordByText(word: String, lemma: String) = withContext(Dispatchers.IO) {
        db.savedWordDao().findByWordOrLemma(word, lemma)?.let {
            db.savedWordDao().deleteById(it.id)
        }
    }

    suspend fun deleteSavedWord(id: Long) = withContext(Dispatchers.IO) {
        db.savedWordDao().deleteById(id)
    }

    suspend fun gradeSavedWord(word: SavedWordEntity, grade: ReviewGrade) = withContext(Dispatchers.IO) {
        db.savedWordDao().update(Sm2Scheduler.review(word, grade))
    }

    suspend fun searchSubtitles(query: String): List<SubtitleIndexEntity> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.length < 2) emptyList() else db.subtitleIndexDao().search(trimmed)
    }
}
