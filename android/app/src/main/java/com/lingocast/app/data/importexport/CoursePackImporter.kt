package com.lingocast.app.data.importexport

import android.content.Context
import android.content.res.AssetManager
import android.net.Uri
import android.provider.DocumentsContract
import com.lingocast.app.data.db.EpisodeEntity
import com.lingocast.app.data.db.LingoDatabase
import com.lingocast.app.data.db.SeriesEntity
import com.lingocast.app.data.db.SubtitleIndexEntity
import com.lingocast.app.data.model.CatalogJson
import com.lingocast.app.data.model.CatalogSeriesSummary
import com.lingocast.app.data.model.EpisodeBundleJson
import com.lingocast.app.data.model.EpisodeSummaryJson
import com.lingocast.app.data.model.SeriesManifestJson
import com.lingocast.app.data.model.SubscriptionFeedJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

data class SyncProgress(
    val currentTitle: String = "",
    val completedCount: Int = 0,
    val totalCount: Int = 0,
    val isRunning: Boolean = false,
    val message: String = ""
)

data class RemoteCatalogState(
    val baseUrl: String,
    val feedId: String = "",
    val feedTitle: String = "",
    val feedDescription: String = "",
    val updatedAt: String = "",
    val authToken: String? = null,
    val catalog: CatalogJson,
    val manifests: Map<String, SeriesManifestJson>,
    val installedKeys: Set<String>,
    val installedVideoKeys: Set<String> = emptySet()
)

object SubscriptionUrlNormalizer {
    private val FEED_SUFFIX_REGEX = Regex("/(api/v1/feed\\.json|feed\\.json|subscribe\\.json|catalog\\.json)$", RegexOption.IGNORE_CASE)
    private val SCHEME_REGEX = Regex("^https?://", RegexOption.IGNORE_CASE)

    fun normalizeBaseUrl(raw: String): String {
        var cleaned = raw.trim().trimEnd('/')
        if (cleaned.isEmpty()) return ""
        if (!SCHEME_REGEX.containsMatchIn(cleaned)) {
            val hostPart = cleaned.substringBefore('/').substringBefore(':')
            val isLocalOrIp = isLocalOrPrivateHost(hostPart)
            val explicitPort = cleaned.substringBefore('/').substringAfter(':', "").toIntOrNull()
            val useHttp = isLocalOrIp || (explicitPort != null && explicitPort != 443)
            cleaned = (if (useHttp) "http://" else "https://") + cleaned
        }
        cleaned = cleaned.replace(FEED_SUFFIX_REGEX, "").trimEnd('/')
        return cleaned
    }

    private fun isLocalOrPrivateHost(host: String): Boolean {
        val lower = host.lowercase()
        if (lower == "localhost" || lower.endsWith(".local") || lower.endsWith(".lan")) return true
        val parts = lower.split('.')
        if (parts.size == 4 && parts.all { p -> p.toIntOrNull() in 0..255 }) {
            val a = parts[0].toInt()
            val b = parts[1].toInt()
            return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31)
        }
        return false
    }
}

class CoursePackImporter(
    private val context: Context,
    private val db: LingoDatabase,
    private val json: Json
) {
    private val internalPacksDir: File
        get() = File(context.filesDir, "packs").apply { mkdirs() }

    suspend fun ensureStarterPackLoaded() = withContext(Dispatchers.IO) {
        if (db.episodeDao().getAllKeys().isNotEmpty()) {
            return@withContext
        }
        val marker = File(internalPacksDir, ".starter_v1.done")
        val hasStarter = runCatching {
            context.assets.list("starter_pack")?.contains("catalog.json") == true
        }.getOrDefault(false)
        if (!hasStarter) return@withContext
        if (marker.exists()) {
            return@withContext
        }
        copyAssetDir("starter_pack", internalPacksDir)
        importPacksRoot(internalPacksDir)
        marker.writeText("ok")
    }

    suspend fun scanAdbPacksDir(): Int = withContext(Dispatchers.IO) {
        val extPacks = context.getExternalFilesDir("packs") ?: return@withContext 0
        if (!extPacks.exists()) {
            extPacks.mkdirs()
            return@withContext 0
        }
        val count = importPacksRoot(extPacks)
        android.util.Log.i("LingoCastImporter", "ADB scan finished: $count episodes in ${extPacks.absolutePath}")
        count
    }

    suspend fun importLcpZipUri(uri: Uri): Int = withContext(Dispatchers.IO) {
        val destRoot = internalPacksDir
        context.contentResolver.openInputStream(uri)?.use { rawIn ->
            ZipInputStream(BufferedInputStream(rawIn)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val safeRel = entry.name.replace("..", "").trimStart('/', '\\')
                    if (safeRel.isNotEmpty()) {
                        val target = File(destRoot, safeRel)
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { out -> zis.copyTo(out, 32 * 1024) }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } ?: error("无法打开所选课程包文件")
        importPacksRoot(destRoot)
    }

    suspend fun importFolderTreeUri(treeUri: Uri): Int = withContext(Dispatchers.IO) {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val destRoot = internalPacksDir
        copyDocumentTreeRecursive(treeUri, docId, destRoot)
        importPacksRoot(destRoot)
    }

    private fun copyDocumentTreeRecursive(treeUri: Uri, parentDocId: String, targetDir: File) {
        targetDir.mkdirs()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val childId = cursor.getString(0)
                val name = cursor.getString(1) ?: continue
                val mime = cursor.getString(2) ?: ""
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    copyDocumentTreeRecursive(treeUri, childId, File(targetDir, name))
                } else {
                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                    val outFile = File(targetDir, name)
                    context.contentResolver.openInputStream(fileUri)?.use { input ->
                        outFile.outputStream().use { output -> input.copyTo(output, 32 * 1024) }
                    }
                }
            }
        }
    }

    suspend fun importPacksRoot(packsRoot: File): Int = withContext(Dispatchers.IO) {
        if (!packsRoot.isDirectory) return@withContext 0
        var importedCount = 0
        val catalogFile = File(packsRoot, "catalog.json")
        if (catalogFile.isFile) {
            val catalog = json.decodeFromString<CatalogJson>(catalogFile.readText())
            for (series in catalog.series) {
                val manifestFile = File(packsRoot, series.manifestPath)
                if (manifestFile.isFile) {
                    importedCount += importSeriesManifest(manifestFile.parentFile!!, manifestFile)
                }
            }
            return@withContext importedCount
        }
        // Fallback: direct manifest.json or series subfolders
        val rootManifest = File(packsRoot, "manifest.json")
        if (rootManifest.isFile) {
            importedCount += importSeriesManifest(packsRoot, rootManifest)
        } else {
            packsRoot.listFiles()?.filter { it.isDirectory }?.forEach { sub ->
                val mf = File(sub, "manifest.json")
                if (mf.isFile) {
                    importedCount += importSeriesManifest(sub, mf)
                }
            }
        }
        importedCount
    }

    private suspend fun importSeriesManifest(seriesDir: File, manifestFile: File): Int {
        val manifest = json.decodeFromString<SeriesManifestJson>(manifestFile.readText())
        val coverAbs = File(seriesDir, manifest.coverPath).let { if (it.isFile) it.absolutePath else "" }
        db.seriesDao().upsert(
            SeriesEntity(
                seriesId = manifest.seriesId,
                title = manifest.title,
                titleZh = manifest.titleZh,
                description = manifest.description,
                accent = manifest.accent,
                cefrRange = manifest.cefrRange,
                coverPath = coverAbs,
                episodeCount = manifest.episodeCount,
                totalDurationMs = manifest.totalDurationMs,
                updatedAt = System.currentTimeMillis()
            )
        )

        var count = 0
        for (ep in manifest.episodes) {
            val epJsonFile = File(seriesDir, ep.episodeJsonFile)
            val audioFile = File(seriesDir, ep.audioFile)
            if (!epJsonFile.isFile || !audioFile.isFile) continue
            val videoFile = ep.videoFile?.let { File(seriesDir, it) }?.takeIf { it.isFile }
            val coverFile = File(seriesDir, ep.coverFile).takeIf { it.isFile }
            upsertEpisodeFromLocalFiles(
                seriesId = manifest.seriesId,
                epSummary = ep,
                epJsonFile = epJsonFile,
                audioFile = audioFile,
                videoFile = videoFile,
                coverFile = coverFile
            )
            count += 1
        }
        return count
    }

    private suspend fun upsertEpisodeFromLocalFiles(
        seriesId: String,
        epSummary: EpisodeSummaryJson,
        epJsonFile: File,
        audioFile: File,
        videoFile: File?,
        coverFile: File?
    ) {
        val epKey = "$seriesId/${epSummary.episodeId}"
        val existing = db.episodeDao().getByKey(epKey)
        if (existing != null &&
            existing.episodeJsonPath == epJsonFile.absolutePath &&
            existing.videoPath == videoFile?.absolutePath &&
            existing.sentenceCount == epSummary.sentenceCount &&
            existing.vocabCount == epSummary.vocabCount
        ) {
            return
        }
        db.episodeDao().upsert(
            EpisodeEntity(
                episodeKey = epKey,
                seriesId = seriesId,
                episodeId = epSummary.episodeId,
                indexNum = epSummary.index,
                title = epSummary.title,
                titleZh = epSummary.titleZh,
                cefrLevel = epSummary.cefrLevel,
                topicTagsCsv = epSummary.topicTags.joinToString(","),
                summaryZh = epSummary.summaryZh,
                durationMs = epSummary.durationMs,
                hasVideo = videoFile != null,
                videoPath = videoFile?.absolutePath,
                audioPath = audioFile.absolutePath,
                coverPath = coverFile?.absolutePath ?: "",
                episodeJsonPath = epJsonFile.absolutePath,
                sentenceCount = epSummary.sentenceCount,
                vocabCount = epSummary.vocabCount,
                noteCount = epSummary.noteCount,
                lastPositionMs = existing?.lastPositionMs ?: 0L,
                completed = existing?.completed ?: false,
                studySeconds = existing?.studySeconds ?: 0L,
                lastPlayedAt = existing?.lastPlayedAt ?: 0L
            )
        )

        // Populate search index
        val bundle = json.decodeFromString<EpisodeBundleJson>(epJsonFile.readText())
        val notesBySentence = bundle.timelineNotes.groupBy { it.sentenceId }
        val indexRows = bundle.sentences.map { s ->
            val noteText = notesBySentence[s.id].orEmpty()
                .joinToString(" | ") { "${it.phrase} ${it.meaningZh}" }
            SubtitleIndexEntity(
                episodeKey = epKey,
                episodeTitle = epSummary.titleZh.ifBlank { epSummary.title },
                seriesId = seriesId,
                sentenceId = s.id,
                startMs = s.startMs,
                endMs = s.endMs,
                textEn = s.textEn,
                textZh = s.textZh,
                notesText = noteText
            )
        }
        db.subtitleIndexDao().replaceEpisodeIndex(epKey, indexRows)
    }

    private class HttpStatusException(val code: Int, message: String) : IllegalStateException(message)

    suspend fun fetchRemoteCatalog(rawHostOrUrl: String, authToken: String? = null): RemoteCatalogState =
        fetchSubscriptionFeed(rawHostOrUrl, authToken)

    suspend fun fetchSubscriptionFeed(rawUrl: String, authToken: String? = null): RemoteCatalogState = withContext(Dispatchers.IO) {
        val baseUrl = SubscriptionUrlNormalizer.normalizeBaseUrl(rawUrl)
        if (baseUrl.isBlank()) error("请输入有效的订阅地址")
        val cleanToken = authToken?.trim()?.takeIf { it.isNotEmpty() }

        var feedTitle = ""
        var feedDescription = ""
        var feedId = ""
        var updatedAt = ""
        var catalog: CatalogJson? = null

        val feedCandidates = listOf("$baseUrl/api/v1/feed.json", "$baseUrl/feed.json")
        for (feedUrl in feedCandidates) {
            try {
                val feedText = httpGetText(feedUrl, cleanToken)
                val parsed = json.decodeFromString<SubscriptionFeedJson>(feedText)
                feedId = parsed.feedId
                feedTitle = parsed.title
                feedDescription = parsed.description
                updatedAt = parsed.updatedAt
                catalog = CatalogJson(
                    schemaVersion = parsed.protocolVersion,
                    generatedAt = parsed.updatedAt,
                    totalEpisodes = parsed.totalEpisodes,
                    totalDurationMs = parsed.totalDurationMs,
                    series = parsed.series
                )
                break
            } catch (e: HttpStatusException) {
                if (e.code == 401 || e.code == 403) {
                    error("该订阅源需要有效访问口令 (HTTP ${e.code})")
                }
            }
        }

        if (catalog == null) {
            val catalogText = try {
                httpGetText("$baseUrl/catalog.json", cleanToken)
            } catch (e: HttpStatusException) {
                if (e.code == 401 || e.code == 403) {
                    error("该订阅源需要有效访问口令 (HTTP ${e.code})")
                }
                throw e
            }
            catalog = json.decodeFromString<CatalogJson>(catalogText)
            updatedAt = catalog.generatedAt
        }

        val manifests = LinkedHashMap<String, SeriesManifestJson>()
        for (series in catalog.series) {
            val manifestText = httpGetText("$baseUrl/${series.manifestPath.trimStart('/')}", cleanToken)
            manifests[series.seriesId] = json.decodeFromString(manifestText)
        }
        val installed = db.episodeDao().getAllKeys().toSet()
        val installedVideos = db.episodeDao().getKeysWithVideo().toSet()
        RemoteCatalogState(
            baseUrl = baseUrl,
            feedId = feedId,
            feedTitle = feedTitle,
            feedDescription = feedDescription,
            updatedAt = updatedAt,
            authToken = cleanToken,
            catalog = catalog,
            manifests = manifests,
            installedKeys = installed,
            installedVideoKeys = installedVideos
        )
    }

    suspend fun syncEpisodesFromServer(
        remote: RemoteCatalogState,
        selectedEpisodes: List<Pair<CatalogSeriesSummary, EpisodeSummaryJson>>,
        includeVideo: Boolean,
        onProgress: (SyncProgress) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val destPacks = internalPacksDir
        var synced = 0
        val total = selectedEpisodes.size

        // Group by series to save manifests cleanly
        val grouped = selectedEpisodes.groupBy { it.first.seriesId }
        for ((seriesId, pairs) in grouped) {
            val seriesMeta = pairs.first().first
            val remoteManifest = remote.manifests[seriesId] ?: continue
            val seriesDir = File(destPacks, seriesId).apply { mkdirs() }

            val coverAbs = File(seriesDir, remoteManifest.coverPath).absolutePath
            db.seriesDao().upsert(
                SeriesEntity(
                    seriesId = seriesId,
                    title = seriesMeta.title,
                    titleZh = seriesMeta.titleZh,
                    description = seriesMeta.description,
                    accent = seriesMeta.accent,
                    cefrRange = seriesMeta.cefrRange,
                    coverPath = coverAbs,
                    episodeCount = remoteManifest.episodeCount,
                    totalDurationMs = remoteManifest.totalDurationMs,
                    updatedAt = System.currentTimeMillis()
                )
            )

            for ((_, ep) in pairs) {
                onProgress(
                    SyncProgress(
                        currentTitle = "${ep.index}. ${ep.titleZh.ifBlank { ep.title }}",
                        completedCount = synced,
                        totalCount = total,
                        isRunning = true,
                        message = "正在下载字幕与词汇包..."
                    )
                )

                val epJsonDest = File(seriesDir, ep.episodeJsonFile)
                val audioDest = File(seriesDir, ep.audioFile)
                val coverDest = File(seriesDir, ep.coverFile)

                downloadFile(
                    urlStr = "${remote.baseUrl}/$seriesId/${ep.episodeJsonFile}",
                    dest = epJsonDest,
                    authToken = remote.authToken
                )
                downloadFile(
                    urlStr = "${remote.baseUrl}/$seriesId/${ep.audioFile}",
                    dest = audioDest,
                    authToken = remote.authToken,
                    expectedSizeBytes = ep.audioSizeBytes
                )
                runCatching {
                    downloadFile(
                        urlStr = "${remote.baseUrl}/$seriesId/${ep.coverFile}",
                        dest = coverDest,
                        authToken = remote.authToken
                    )
                }

                var videoDest: File? = null
                if (includeVideo && ep.hasVideo && !ep.videoFile.isNullOrBlank()) {
                    onProgress(
                        SyncProgress(
                            currentTitle = "${ep.index}. ${ep.titleZh.ifBlank { ep.title }}",
                            completedCount = synced,
                            totalCount = total,
                            isRunning = true,
                            message = "正在同步高清视频流..."
                        )
                    )
                    val targetVid = File(seriesDir, ep.videoFile)
                    downloadFile(
                        urlStr = "${remote.baseUrl}/$seriesId/${ep.videoFile}",
                        dest = targetVid,
                        authToken = remote.authToken,
                        expectedSizeBytes = ep.videoSizeBytes
                    )
                    if (targetVid.isFile) {
                        videoDest = targetVid
                    }
                }

                upsertEpisodeFromLocalFiles(
                    seriesId = seriesId,
                    epSummary = ep,
                    epJsonFile = epJsonDest,
                    audioFile = audioDest,
                    videoFile = videoDest,
                    coverFile = coverDest.takeIf { it.isFile }
                )
                synced += 1
            }

            // Refresh series manifest on disk
            File(seriesDir, "manifest.json").writeText(json.encodeToString(SeriesManifestJson.serializer(), remoteManifest))
        }

        onProgress(
            SyncProgress(
                currentTitle = "同步完成",
                completedCount = synced,
                totalCount = total,
                isRunning = false,
                message = "已成功导入 $synced 集课程"
            )
        )
        synced
    }

    private fun copyAssetDir(assetPath: String, destDir: File) {
        val children = context.assets.list(assetPath) ?: return
        if (children.isEmpty()) {
            destDir.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                destDir.outputStream().use { output -> input.copyTo(output, 32 * 1024) }
            }
            return
        }
        destDir.mkdirs()
        for (child in children) {
            copyAssetDir("$assetPath/$child", File(destDir, child))
        }
    }

    private fun httpGetText(urlStr: String, authToken: String? = null): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 25000
            instanceFollowRedirects = true
            if (!authToken.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $authToken")
            }
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw HttpStatusException(code, "HTTP $code 请求失败: $urlStr")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadFile(
        urlStr: String,
        dest: File,
        authToken: String? = null,
        expectedSizeBytes: Long = 0L
    ) {
        dest.parentFile?.mkdirs()
        if (expectedSizeBytes > 0L && dest.isFile && dest.length() == expectedSizeBytes) {
            return
        }
        val tmp = File(dest.parentFile, "${dest.name}.part")
        var retriedFresh = false
        while (true) {
            val existingPartLen = if (tmp.isFile) tmp.length() else 0L
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12000
                readTimeout = 60000
                instanceFollowRedirects = true
                if (!authToken.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer $authToken")
                }
                if (existingPartLen > 0L) {
                    setRequestProperty("Range", "bytes=$existingPartLen-")
                }
            }
            try {
                val code = conn.responseCode
                if (code == 416 && existingPartLen > 0L && !retriedFresh) {
                    tmp.delete()
                    retriedFresh = true
                    continue
                }
                if (code !in 200..299 && code != HttpURLConnection.HTTP_PARTIAL) {
                    throw HttpStatusException(code, "HTTP $code 下载失败: $urlStr")
                }
                val isPartialAppend = (code == HttpURLConnection.HTTP_PARTIAL && existingPartLen > 0L)
                val expectedLen = conn.contentLengthLong
                if (!isPartialAppend && dest.isFile && expectedLen > 0 && dest.length() == expectedLen) {
                    return
                }
                conn.inputStream.use { input ->
                    FileOutputStream(tmp, isPartialAppend).use { output ->
                        input.copyTo(output, 64 * 1024)
                    }
                }
                if (dest.exists()) dest.delete()
                tmp.renameTo(dest)
                return
            } finally {
                conn.disconnect()
            }
        }
    }
}
