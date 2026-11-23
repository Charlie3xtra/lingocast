package com.lingocast.app

import com.lingocast.app.data.db.SavedWordEntity
import com.lingocast.app.data.dict.OfflineDictionary
import com.lingocast.app.data.importexport.SubscriptionUrlNormalizer
import com.lingocast.app.data.model.EpisodeBundleJson
import com.lingocast.app.data.model.MediaInfoJson
import com.lingocast.app.data.model.SentenceJson
import com.lingocast.app.data.model.SubscriptionFeedJson
import com.lingocast.app.data.model.TimelineNoteJson
import com.lingocast.app.data.model.VocabItemJson
import com.lingocast.app.data.model.WordTimingJson
import com.lingocast.app.data.srs.ReviewGrade
import com.lingocast.app.data.srs.Sm2Scheduler
import com.lingocast.app.playback.TimelineTracker
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LingoCastUnitTests {

    @Test
    fun timelineTracker_binarySearch_findsSentenceAndWord() {
        val sents = listOf(
            SentenceJson(
                id = 0,
                startMs = 1000L,
                endMs = 3000L,
                textEn = "Good morning everyone.",
                textZh = "大家早上好。",
                words = listOf(
                    WordTimingJson("Good", 1000L, 1400L),
                    WordTimingJson("morning", 1450L, 2100L),
                    WordTimingJson("everyone.", 2150L, 2900L)
                )
            ),
            SentenceJson(
                id = 1,
                startMs = 3500L,
                endMs = 6000L,
                textEn = "Let's take a road trip.",
                textZh = "我们去自驾游吧。",
                words = listOf(
                    WordTimingJson("Let's", 3500L, 3800L),
                    WordTimingJson("take", 3850L, 4150L),
                    WordTimingJson("a", 4160L, 4250L),
                    WordTimingJson("road", 4300L, 4900L),
                    WordTimingJson("trip.", 4950L, 5800L)
                )
            )
        )
        assertEquals(0, TimelineTracker.findActiveSentenceIndex(sents, 1600L))
        assertEquals(1, TimelineTracker.findActiveSentenceIndex(sents, 4500L))
        assertEquals(1, TimelineTracker.findActiveWordIndex(sents[0], 1700L))
        assertEquals(3, TimelineTracker.findActiveWordIndex(sents[1], 4500L))
    }

    @Test
    fun timelineTracker_resolvesTimelineVocabNotes() {
        val bundle = EpisodeBundleJson(
            episodeId = "ep_001",
            seriesId = "talk-podcast",
            index = 1,
            title = "Road Trip",
            titleZh = "公路旅行",
            durationMs = 10000L,
            media = MediaInfoJson(),
            sentences = listOf(
                SentenceJson(
                    id = 0,
                    startMs = 0L,
                    endMs = 4000L,
                    textEn = "I remained paralysed with curiosity.",
                    words = listOf(WordTimingJson("paralysed", 1200L, 1900L, "paralyse", "v_paralyse"))
                )
            ),
            timelineNotes = listOf(
                TimelineNoteJson(
                    id = "n0",
                    sentenceId = 0,
                    startMs = 1200L,
                    endMs = 1900L,
                    phrase = "paralysed with curiosity",
                    category = "ielts",
                    cefrLevel = "C1",
                    meaningZh = "因好奇而呆住",
                    usageTipZh = "地道描写情绪定格"
                )
            ),
            vocabList = listOf(
                VocabItemJson(
                    id = "v_paralyse",
                    word = "paralyse",
                    lemma = "paralyse",
                    phonetic = "'pærəlaɪz",
                    meaningZh = "vt. 使瘫痪",
                    cefrLevel = "C1",
                    tags = listOf("IELTS")
                )
            )
        )
        val entries = TimelineTracker.resolveActiveVocabEntries(bundle, 0)
        assertEquals(2, entries.size)
        assertTrue(entries.first().isFromNote)
    }

    @Test
    fun sm2Scheduler_progressesAndResetsCorrectly() {
        val word = SavedWordEntity(
            word = "protest",
            lemma = "protest",
            phonetic = "prə'test",
            pos = "n/v",
            meaningZh = "抗议",
            contextMeaningZh = null,
            tagsCsv = "IELTS",
            cefrLevel = "B1",
            sourceEpisodeKey = "talk-podcast/ep_020",
            sourceEpisodeTitle = "Protest",
            sourceSentenceId = 5,
            clipStartMs = 10000L,
            clipEndMs = 13000L,
            sourceSentenceEn = "Citizens are protesting.",
            sourceSentenceZh = "市民正在抗议。"
        )
        val r1 = Sm2Scheduler.review(word, ReviewGrade.GOOD, nowMs = 1_000_000L)
        assertEquals(1, r1.repetitions)
        assertEquals(1, r1.intervalDays)

        val r2 = Sm2Scheduler.review(r1, ReviewGrade.GOOD, nowMs = 2_000_000L)
        assertEquals(2, r2.repetitions)
        assertEquals(6, r2.intervalDays)

        val forgot = Sm2Scheduler.review(r2, ReviewGrade.AGAIN, nowMs = 3_000_000L)
        assertEquals(0, forgot.repetitions)
        assertEquals(0, forgot.intervalDays)
    }

    @Test
    fun dictionary_cefrAndCleanToken() {
        assertEquals("paralysed", OfflineDictionary.cleanToken("  \"Paralysed,\" "))
        assertEquals("B1", OfflineDictionary.estimateCefr(listOf("cet4"), 2500, 2500, 3, 1))
        assertEquals("C1", OfflineDictionary.estimateCefr(listOf("ielts"), 7000, 7000, 2, 0))
    }

    @Test
    fun episodeBundleJson_parsesSamplePayload() {
        val raw = """
            {
              "schemaVersion": 1,
              "episodeId": "ep_001",
              "seriesId": "talk-podcast",
              "index": 1,
              "title": "Road Trip",
              "titleZh": "自驾游",
              "cefrLevel": "A2",
              "topicTags": ["旅行"],
              "summaryZh": "关于自驾游的英语对话。",
              "durationMs": 60000,
              "media": {
                "videoFile": "video.mp4",
                "audioFile": "audio.m4a",
                "coverFile": "cover.jpg",
                "hasVideo": true,
                "width": 852,
                "height": 480,
                "waveformPeaks": [10, 40, 80]
              },
              "subtitleSource": { "tier": 3, "engine": "faster-whisper", "hasBurnedSubs": true },
              "sentences": [
                { "id": 0, "startMs": 0, "endMs": 2000, "textEn": "Let's go.", "textZh": "出发吧。", "words": [] }
              ],
              "timelineNotes": [],
              "vocabList": []
            }
        """.trimIndent()
        val parsed = Json { ignoreUnknownKeys = true }.decodeFromString<EpisodeBundleJson>(raw)
        assertEquals("Road Trip", parsed.title)
        assertEquals(1, parsed.sentences.size)
    }

    @Test
    fun subscriptionUrlNormalizer_andFeedJson_workCorrectly() {
        assertEquals(
            "https://courses.example.com",
            SubscriptionUrlNormalizer.normalizeBaseUrl("https://courses.example.com/feed.json")
        )
        assertEquals(
            "https://courses.example.com",
            SubscriptionUrlNormalizer.normalizeBaseUrl("courses.example.com/api/v1/feed.json/")
        )
        assertEquals(
            "http://192.168.1.50:8765",
            SubscriptionUrlNormalizer.normalizeBaseUrl("192.168.1.50:8765/catalog.json")
        )
        assertEquals(
            "http://localhost:8765",
            SubscriptionUrlNormalizer.normalizeBaseUrl("localhost:8765")
        )

        val feedRaw = """
            {
              "protocol": "lingocast-feed",
              "protocolVersion": 1,
              "feedId": "lingocast-test",
              "title": "Demo English Feed",
              "totalSeries": 1,
              "totalEpisodes": 2,
              "totalAudioBytes": 12000000,
              "totalVideoBytes": 48000000,
              "series": [
                {
                  "seriesId": "sample-series",
                  "title": "Sample Series",
                  "titleZh": "示例专辑",
                  "manifestPath": "sample-series/manifest.json",
                  "episodeCount": 2,
                  "audioTotalBytes": 12000000,
                  "videoTotalBytes": 48000000
                }
              ]
            }
        """.trimIndent()
        val feed = Json { ignoreUnknownKeys = true }.decodeFromString<SubscriptionFeedJson>(feedRaw)
        assertEquals("lingocast-feed", feed.protocol)
        assertEquals(2, feed.totalEpisodes)
        assertEquals(48_000_000L, feed.series.first().videoTotalBytes)
    }
}
