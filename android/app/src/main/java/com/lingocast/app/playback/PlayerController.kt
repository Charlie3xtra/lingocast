package com.lingocast.app.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.lingocast.app.data.db.EpisodeEntity
import com.lingocast.app.data.model.EpisodeBundleJson
import com.lingocast.app.data.model.SentenceJson
import com.lingocast.app.data.prefs.PreferencesStore
import com.lingocast.app.data.prefs.SubtitleDisplayMode
import com.lingocast.app.data.repo.CourseRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class PlaybackUiState(
    val episode: EpisodeEntity? = null,
    val bundle: EpisodeBundleJson? = null,
    val isLoading: Boolean = false,
    val isPlaying: Boolean = false,
    val isVideoMode: Boolean = true,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1.0f,
    val subtitleMode: SubtitleDisplayMode = SubtitleDisplayMode.BILINGUAL,
    val singleSentenceLoop: Boolean = false,
    val loopAMs: Long? = null,
    val loopBMs: Long? = null,
    val activeSentenceIndex: Int = -1,
    val activeWordIndex: Int = -1,
    val activeTimelineEntries: List<ActiveTimelineEntry> = emptyList(),
    val errorMessage: String? = null
)

class PlayerController(
    private val context: Context,
    private val repository: CourseRepository,
    private val preferences: PreferencesStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var exoPlayer: ExoPlayer? = null
    private var serviceStarted = false
    private var tickerJob: Job? = null
    private var openJob: Job? = null
    private var lastPersistedTickMs = 0L
    private var clipStopAtMs: Long? = null

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _uiState = MutableStateFlow(
        PlaybackUiState(subtitleMode = preferences.state.value.defaultSubMode)
    )
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

    fun getOrCreateExoPlayer(): ExoPlayer {
        exoPlayer?.let { return it }
        val player = ExoPlayerFactoryHelper.buildPlayer(context)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
                if (!isPlaying) {
                    persistProgress(deltaSeconds = 0L)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    val dur = player.duration.coerceAtLeast(_uiState.value.durationMs)
                    _uiState.value = _uiState.value.copy(durationMs = dur)
                }
            }
        })
        exoPlayer = player
        startTicker()
        return player
    }

    private fun ensureServiceStarted() {
        if (!serviceStarted) {
            runCatching {
                val intent = Intent(context, PlaybackService::class.java)
                ContextCompat.startForegroundService(context, intent)
                serviceStarted = true
            }
            // Connect MediaController asynchronously to keep service bound
            runCatching {
                val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
                MediaController.Builder(context, token).buildAsync()
            }
        }
    }

    fun openEpisode(
        episodeKey: String,
        startPositionMs: Long? = null,
        autoPlay: Boolean = true,
        deferMediaMs: Long = 280L
    ) {
        openJob?.cancel()
        openJob = scope.launch {
            val current = _uiState.value.episode
            if (current?.episodeKey == episodeKey && _uiState.value.bundle != null) {
                if (startPositionMs != null) {
                    _positionMs.value = startPositionMs
                    syncTimelineAt(startPositionMs, forcePositionState = true)
                }
                if (deferMediaMs > 0L) delay(deferMediaMs)
                if (startPositionMs != null) {
                    val dur = _uiState.value.durationMs.coerceAtLeast(1L)
                    getOrCreateExoPlayer().seekTo(startPositionMs.coerceIn(0L, dur))
                }
                if (autoPlay) play()
                return@launch
            }

            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val ep = withContext(Dispatchers.IO) { repository.getEpisode(episodeKey) }
            if (ep == null) {
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = "未找到课程剧集")
                return@launch
            }
            val bundle = runCatching { repository.loadEpisodeBundle(ep) }.getOrNull()
            if (bundle == null) {
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = "课程解析数据读取失败")
                return@launch
            }

            val useVideo = withContext(Dispatchers.IO) {
                ep.hasVideo && !ep.videoPath.isNullOrBlank() && File(ep.videoPath).isFile()
            }
            val resumeMs = startPositionMs ?: ep.lastPositionMs.takeIf { it > 0L && it < ep.durationMs - 4000L } ?: 0L
            val initialSentIdx = TimelineTracker.findActiveSentenceIndex(bundle.sentences, resumeMs)
            val initialWordIdx = TimelineTracker.findActiveWordIndex(bundle.sentences.getOrNull(initialSentIdx), resumeMs)
            val initialEntries = TimelineTracker.resolveActiveVocabEntries(bundle, initialSentIdx)

            _positionMs.value = resumeMs
            _uiState.value = _uiState.value.copy(
                episode = ep,
                bundle = bundle,
                isLoading = false,
                isVideoMode = useVideo,
                positionMs = resumeMs,
                durationMs = ep.durationMs,
                singleSentenceLoop = false,
                loopAMs = null,
                loopBMs = null,
                activeSentenceIndex = initialSentIdx,
                activeWordIndex = initialWordIdx,
                activeTimelineEntries = initialEntries
            )
            // Let page transition animate smoothly before ExoPlayer prepare/decoder startup
            if (deferMediaMs > 0L) delay(deferMediaMs)
            loadMediaIntoPlayer(ep, useVideo, resumeMs, autoPlay)
        }
    }

    fun playVocabContextClip(episodeKey: String, clipStartMs: Long, clipEndMs: Long) {
        scope.launch {
            val ep = repository.getEpisode(episodeKey) ?: return@launch
            val currentKey = _uiState.value.episode?.episodeKey
            if (currentKey != episodeKey) {
                openEpisode(episodeKey, startPositionMs = clipStartMs, autoPlay = true, deferMediaMs = 0L)
            } else {
                seekTo(clipStartMs)
                play()
            }
            clipStopAtMs = clipEndMs + 350L
        }
    }

    private suspend fun loadMediaIntoPlayer(ep: EpisodeEntity, useVideo: Boolean, posMs: Long, autoPlay: Boolean) {
        val (mediaFile, artworkUri) = withContext(Dispatchers.IO) {
            val file = if (useVideo && !ep.videoPath.isNullOrBlank() && File(ep.videoPath).isFile()) {
                File(ep.videoPath)
            } else {
                File(ep.audioPath)
            }
            val art = ep.coverPath.takeIf { it.isNotBlank() && File(it).isFile() }?.let { Uri.fromFile(File(it)) }
            file to art
        }
        ensureServiceStarted()
        val player = getOrCreateExoPlayer()
        val metadata = MediaMetadata.Builder()
            .setTitle(ep.titleZh.ifBlank { ep.title })
            .setSubtitle(ep.title)
            .setArtworkUri(artworkUri)
            .build()
        val item = MediaItem.Builder()
            .setUri(Uri.fromFile(mediaFile))
            .setMediaMetadata(metadata)
            .build()

        player.setMediaItem(item, posMs)
        player.playbackParameters = PlaybackParameters(_uiState.value.speed)
        player.prepare()
        if (autoPlay) {
            player.play()
        }
    }

    fun toggleVideoAudioMode() {
        scope.launch {
            val state = _uiState.value
            val ep = state.episode ?: return@launch
            val hasVideoFile = withContext(Dispatchers.IO) {
                ep.hasVideo && !ep.videoPath.isNullOrBlank() && File(ep.videoPath).isFile()
            }
            if (!hasVideoFile) return@launch
            val nextVideo = !state.isVideoMode
            val pos = exoPlayer?.currentPosition ?: _positionMs.value
            val wasPlaying = exoPlayer?.isPlaying == true
            _uiState.value = state.copy(isVideoMode = nextVideo)
            loadMediaIntoPlayer(ep, nextVideo, pos, wasPlaying)
        }
    }

    fun togglePlayPause() {
        val player = getOrCreateExoPlayer()
        clipStopAtMs = null
        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    fun play() {
        clipStopAtMs = null
        getOrCreateExoPlayer().play()
    }

    fun pause() {
        getOrCreateExoPlayer().pause()
    }

    fun seekTo(ms: Long) {
        val dur = _uiState.value.durationMs.coerceAtLeast(1L)
        val clamped = ms.coerceIn(0L, dur)
        getOrCreateExoPlayer().seekTo(clamped)
        _positionMs.value = clamped
        syncTimelineAt(clamped, forcePositionState = true)
    }

    fun seekRelative(deltaMs: Long) {
        val cur = exoPlayer?.currentPosition ?: _positionMs.value
        seekTo(cur + deltaMs)
    }

    fun stepSentence(delta: Int) {
        val sents = _uiState.value.bundle?.sentences ?: return
        if (sents.isEmpty()) return
        val curIdx = _uiState.value.activeSentenceIndex.coerceIn(0, sents.lastIndex)
        val nextIdx = (curIdx + delta).coerceIn(0, sents.lastIndex)
        seekTo(sents[nextIdx].startMs)
        if (!(_uiState.value.isPlaying)) {
            play()
        }
    }

    fun setSpeed(speed: Float) {
        getOrCreateExoPlayer().playbackParameters = PlaybackParameters(speed)
        _uiState.value = _uiState.value.copy(speed = speed)
    }

    fun setSubtitleMode(mode: SubtitleDisplayMode) {
        preferences.setDefaultSubMode(mode)
        _uiState.value = _uiState.value.copy(subtitleMode = mode)
    }

    fun toggleChineseTranslation() {
        val current = _uiState.value.subtitleMode
        val next = when (current) {
            SubtitleDisplayMode.BILINGUAL, SubtitleDisplayMode.CHINESE_ONLY -> SubtitleDisplayMode.ENGLISH_ONLY
            SubtitleDisplayMode.ENGLISH_ONLY, SubtitleDisplayMode.BLIND -> SubtitleDisplayMode.BILINGUAL
        }
        setSubtitleMode(next)
    }

    fun toggleSingleSentenceLoop() {
        val next = !_uiState.value.singleSentenceLoop
        _uiState.value = _uiState.value.copy(
            singleSentenceLoop = next,
            loopAMs = null,
            loopBMs = null
        )
    }

    fun toggleABLoopPoint() {
        val state = _uiState.value
        val cur = exoPlayer?.currentPosition ?: _positionMs.value
        when {
            state.loopAMs == null -> {
                _uiState.value = state.copy(loopAMs = cur, loopBMs = null, singleSentenceLoop = false)
            }
            state.loopBMs == null -> {
                val a = minOf(state.loopAMs, cur)
                val b = maxOf(state.loopAMs, cur)
                if (b - a >= 400L) {
                    _uiState.value = state.copy(loopAMs = a, loopBMs = b)
                    seekTo(a)
                } else {
                    _uiState.value = state.copy(loopAMs = null, loopBMs = null)
                }
            }
            else -> {
                _uiState.value = state.copy(loopAMs = null, loopBMs = null)
            }
        }
    }

    fun activeSentence(): SentenceJson? {
        val state = _uiState.value
        val sents = state.bundle?.sentences ?: return null
        return sents.getOrNull(state.activeSentenceIndex)
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                val player = exoPlayer
                if (player != null && _uiState.value.bundle != null) {
                    val pos = player.currentPosition.coerceAtLeast(0L)
                    handleLoopGuards(player, pos)
                    syncTimelineAt(pos)

                    if (player.isPlaying) {
                        val now = System.currentTimeMillis()
                        if (now - lastPersistedTickMs >= 5000L) {
                            lastPersistedTickMs = now
                            persistProgress(deltaSeconds = 5L)
                        }
                    }
                }
                delay(50L)
            }
        }
    }

    private fun handleLoopGuards(player: ExoPlayer, pos: Long) {
        val stopAt = clipStopAtMs
        if (stopAt != null && pos >= stopAt && player.isPlaying) {
            clipStopAtMs = null
            player.pause()
            return
        }

        val state = _uiState.value
        val loopA = state.loopAMs
        val loopB = state.loopBMs
        if (loopA != null && loopB != null && pos >= loopB && player.isPlaying) {
            player.seekTo(loopA)
            return
        }

        if (state.singleSentenceLoop && player.isPlaying) {
            val sents = state.bundle?.sentences ?: return
            val sent = sents.getOrNull(state.activeSentenceIndex) ?: return
            if (pos >= sent.endMs + 120L) {
                player.seekTo(sent.startMs)
            }
        }
    }

    private fun syncTimelineAt(pos: Long, forcePositionState: Boolean = false) {
        _positionMs.value = pos
        val state = _uiState.value
        val bundle = state.bundle ?: return
        val newSentIdx = if (state.singleSentenceLoop && state.activeSentenceIndex in bundle.sentences.indices) {
            // Keep locked on looping sentence unless user scrubbed outside
            val cur = bundle.sentences[state.activeSentenceIndex]
            if (pos in (cur.startMs - 400)..(cur.endMs + 400)) state.activeSentenceIndex
            else TimelineTracker.findActiveSentenceIndex(bundle.sentences, pos)
        } else {
            TimelineTracker.findActiveSentenceIndex(bundle.sentences, pos)
        }

        val sent = bundle.sentences.getOrNull(newSentIdx)
        val newWordIdx = TimelineTracker.findActiveWordIndex(sent, pos)
        val entries = if (newSentIdx != state.activeSentenceIndex) {
            TimelineTracker.resolveActiveVocabEntries(bundle, newSentIdx)
        } else {
            state.activeTimelineEntries
        }

        if (forcePositionState || newSentIdx != state.activeSentenceIndex || newWordIdx != state.activeWordIndex) {
            _uiState.value = state.copy(
                positionMs = pos,
                activeSentenceIndex = newSentIdx,
                activeWordIndex = newWordIdx,
                activeTimelineEntries = entries
            )
        }
    }

    private fun persistProgress(deltaSeconds: Long) {
        val state = _uiState.value
        val ep = state.episode ?: return
        val pos = exoPlayer?.currentPosition ?: _positionMs.value
        scope.launch {
            repository.updatePlaybackProgress(
                episodeKey = ep.episodeKey,
                positionMs = pos,
                durationMs = state.durationMs,
                deltaSeconds = deltaSeconds
            )
        }
    }

    fun release() {
        persistProgress(deltaSeconds = 0L)
        openJob?.cancel()
        tickerJob?.cancel()
        exoPlayer?.release()
        exoPlayer = null
    }
}
