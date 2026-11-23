package com.lingocast.app.data.dict

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.lingocast.app.data.model.DictionaryResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Offline <2ms SQLite dictionary powered by ECDICT compact DB bundled in APK assets.
 */
class OfflineDictionary(private val context: Context) {

    @Volatile
    private var db: SQLiteDatabase? = null
    private val lock = Any()

    private fun ensureOpen(): SQLiteDatabase? {
        db?.let { if (it.isOpen) return it }
        synchronized(lock) {
            db?.let { if (it.isOpen) return it }
            val dest = File(context.filesDir, "dict/ecdict_compact.db")
            if (!dest.exists() || dest.length() < 1024L) {
                dest.parentFile?.mkdirs()
                val tmp = File(dest.parentFile, "ecdict_compact.db.tmp")
                val copied = runCatching {
                    context.assets.open("ecdict_compact.db").use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output, 32 * 1024) }
                    }
                    tmp.renameTo(dest)
                }.getOrDefault(false)
                if (!copied && tmp.exists()) {
                    tmp.delete()
                }
            }
            if (!dest.exists()) return null
            val opened = SQLiteDatabase.openDatabase(
                dest.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            )
            db = opened
            return opened
        }
    }

    suspend fun lookup(rawToken: String, contextMeaningZh: String? = null): DictionaryResult? =
        withContext(Dispatchers.IO) {
            val clean = cleanToken(rawToken)
            if (clean.length < 2) return@withContext null
            val database = ensureOpen() ?: return@withContext null

            val candidates = buildCandidateForms(database, clean)
            for (cand in candidates) {
                val res = queryWord(database, cand, contextMeaningZh)
                if (res != null) return@withContext res
            }
            null
        }

    private fun buildCandidateForms(database: SQLiteDatabase, clean: String): List<String> {
        val list = LinkedHashSet<String>()
        database.rawQuery("SELECT lemma FROM lemmas WHERE form = ? LIMIT 1", arrayOf(clean)).use { c ->
            if (c.moveToFirst()) {
                list.add(c.getString(0))
            }
        }
        list.add(clean)
        if (clean.endsWith("'s") && clean.length > 3) {
            list.add(clean.dropLast(2))
        }
        if (clean.endsWith("ies") && clean.length > 4) {
            list.add(clean.dropLast(3) + "y")
        }
        if (clean.endsWith("es") && clean.length > 3) {
            list.add(clean.dropLast(2))
            list.add(clean.dropLast(1))
        }
        if (clean.endsWith("s") && clean.length > 3) {
            list.add(clean.dropLast(1))
        }
        if (clean.endsWith("ied") && clean.length > 4) {
            list.add(clean.dropLast(3) + "y")
        }
        if (clean.endsWith("ed") && clean.length > 3) {
            list.add(clean.dropLast(2))
            list.add(clean.dropLast(1))
        }
        if (clean.endsWith("ing") && clean.length > 4) {
            val stem = clean.dropLast(3)
            list.add(stem)
            list.add(stem + "e")
            if (stem.length >= 2 && stem.last() == stem[stem.length - 2]) {
                list.add(stem.dropLast(1))
            }
        }
        return list.toList()
    }

    private fun queryWord(
        database: SQLiteDatabase,
        word: String,
        contextMeaningZh: String?
    ): DictionaryResult? {
        database.rawQuery(
            """
            SELECT word, phonetic, pos, translation, definition, collins, oxford, tag, bnc, frq
            FROM words WHERE LOWER(word) = ? LIMIT 1
            """.trimIndent(),
            arrayOf(word.lowercase(Locale.US))
        ).use { c ->
            if (!c.moveToFirst()) return null
            val rawTags = c.getString(7).split(' ').filter { it.isNotBlank() }
            val bnc = c.getInt(8)
            val frq = c.getInt(9)
            val collins = c.getInt(5)
            val oxford = c.getInt(6)
            return DictionaryResult(
                word = c.getString(0),
                phonetic = c.getString(1),
                pos = c.getString(2),
                meaningZh = c.getString(3).replace("\\n", "\n"),
                definitionEn = c.getString(4).replace("\\n", "\n"),
                tags = rawTags.map { tagDisplay(it) },
                cefrLevel = estimateCefr(rawTags, bnc, frq, collins, oxford),
                collins = collins,
                oxford = oxford,
                contextMeaningZh = contextMeaningZh
            )
        }
    }

    companion object {
        fun cleanToken(raw: String): String =
            raw.trim()
                .replace(Regex("^[^a-zA-Z'-]+|[^a-zA-Z'-]+$"), "")
                .lowercase(Locale.US)

        fun tagDisplay(tag: String): String = when (tag.lowercase(Locale.US)) {
            "zk" -> "中考"
            "gk" -> "高考"
            "cet4" -> "CET-4"
            "cet6" -> "CET-6"
            "ky" -> "考研"
            "toefl" -> "TOEFL"
            "ielts" -> "IELTS"
            "gre" -> "GRE"
            else -> tag.uppercase(Locale.US)
        }

        fun estimateCefr(tags: List<String>, bnc: Int, frq: Int, collins: Int, oxford: Int): String {
            val set = tags.map { it.lowercase(Locale.US) }.toSet()
            val rank = listOf(bnc, frq).filter { it > 0 }.minOrNull() ?: 0
            return when {
                "zk" in set -> "A2"
                "gk" in set -> if ((rank in 1..1200) || collins >= 5) "A2" else "B1"
                "cet4" in set -> if (rank == 0 || rank <= 3500) "B1" else "B2"
                "cet6" in set || "ky" in set || "ielts" in set -> if (rank > 6500) "C1" else "B2"
                "toefl" in set -> "C1"
                "gre" in set -> "C2"
                oxford == 1 || collins >= 4 -> if (rank in 1..1500) "A2" else "B1"
                rank == 0 -> "B2"
                rank <= 1500 -> "A2"
                rank <= 3500 -> "B1"
                rank <= 7500 -> "B2"
                rank <= 18000 -> "C1"
                else -> "C2"
            }
        }
    }
}
