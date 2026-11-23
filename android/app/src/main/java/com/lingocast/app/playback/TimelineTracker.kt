package com.lingocast.app.playback

import com.lingocast.app.data.model.EpisodeBundleJson
import com.lingocast.app.data.model.SentenceJson
import com.lingocast.app.data.model.TimelineNoteJson
import com.lingocast.app.data.model.VocabItemJson

data class ActiveTimelineEntry(
    val id: String,
    val title: String,
    val phonetic: String?,
    val category: String, // idiom | collocation | ielts | vocab | grammar
    val cefrLevel: String,
    val meaningZh: String,
    val usageTipZh: String,
    val isFromNote: Boolean
)

object TimelineTracker {

    /**
     * O(log N) binary search for the active sentence at [positionMs].
     * Returns the index of the sentence containing [positionMs], or the nearest preceding sentence
     * within a short gap, or -1 if [sentences] is empty.
     */
    fun findActiveSentenceIndex(sentences: List<SentenceJson>, positionMs: Long): Int {
        if (sentences.isEmpty()) return -1
        if (positionMs < sentences.first().startMs) return 0

        var lo = 0
        var hi = sentences.lastIndex
        var candidate = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val s = sentences[mid]
            if (positionMs >= s.startMs) {
                candidate = mid
                if (positionMs <= s.endMs) {
                    return mid
                }
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return candidate
    }

    /**
     * Finds the currently spoken word index inside [sentence] for karaoke highlighting.
     */
    fun findActiveWordIndex(sentence: SentenceJson?, positionMs: Long): Int {
        val words = sentence?.words ?: return -1
        if (words.isEmpty()) return -1
        if (positionMs < words.first().s) return -1
        if (positionMs > words.last().e + 250L) return -1

        var lo = 0
        var hi = words.lastIndex
        var best = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val w = words[mid]
            if (positionMs >= w.s) {
                best = mid
                if (positionMs <= w.e) return mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return best
    }

    /**
     * Returns curated LLM timelineNotes + matched ECDICT notable words for the active sentence.
     */
    fun resolveActiveVocabEntries(
        bundle: EpisodeBundleJson?,
        sentenceIndex: Int
    ): List<ActiveTimelineEntry> {
        if (bundle == null || sentenceIndex !in bundle.sentences.indices) return emptyList()
        val sentence = bundle.sentences[sentenceIndex]
        val sid = sentence.id

        val entries = ArrayList<ActiveTimelineEntry>()
        val seenKeys = HashSet<String>()

        // 1. Curated timeline notes for this sentence (and adjacent ±1 sentence for rich continuity)
        val notes = bundle.timelineNotes.filter { it.sentenceId == sid }
        for (note: TimelineNoteJson in notes) {
            val key = note.phrase.lowercase()
            if (seenKeys.add(key)) {
                entries.add(
                    ActiveTimelineEntry(
                        id = note.id,
                        title = note.phrase,
                        phonetic = note.phonetic,
                        category = note.category,
                        cefrLevel = note.cefrLevel,
                        meaningZh = note.meaningZh,
                        usageTipZh = note.usageTipZh,
                        isFromNote = true
                    )
                )
            }
        }

        // 2. Sentence word tokens linked to ECDICT vocabList
        val vocabById = bundle.vocabList.associateBy { it.id }
        for (tok in sentence.words) {
            val vid = tok.vocabId ?: continue
            val v: VocabItemJson = vocabById[vid] ?: continue
            val key = v.lemma.lowercase()
            if (seenKeys.add(key)) {
                val meaning = (v.contextMeaningZh ?: v.meaningZh).lineSequence().firstOrNull().orEmpty()
                val tagsLabel = if (v.tags.isNotEmpty()) v.tags.take(3).joinToString(" · ") else "核心词汇"
                entries.add(
                    ActiveTimelineEntry(
                        id = v.id,
                        title = v.word,
                        phonetic = v.phonetic.takeIf { it.isNotBlank() },
                        category = if ("IELTS" in v.tags) "ielts" else "vocab",
                        cefrLevel = v.cefrLevel,
                        meaningZh = meaning,
                        usageTipZh = "$tagsLabel | ${v.pos}".trim(' ', '|'),
                        isFromNote = false
                    )
                )
            }
        }

        return entries
    }
}
