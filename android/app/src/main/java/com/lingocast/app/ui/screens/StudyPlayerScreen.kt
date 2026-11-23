package com.lingocast.app.ui.screens

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.lingocast.app.AppContainer
import com.lingocast.app.R
import com.lingocast.app.data.model.DictionaryResult
import com.lingocast.app.data.model.SentenceJson
import com.lingocast.app.data.prefs.SubtitleDisplayMode
import com.lingocast.app.playback.ActiveTimelineEntry
import com.lingocast.app.ui.common.CefrBadge
import com.lingocast.app.ui.common.SlimTimelineBar
import com.lingocast.app.ui.common.TimestampText
import com.lingocast.app.ui.common.WordLookupSheet
import com.lingocast.app.ui.theme.LingoMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private data class LookupDialogState(
    val token: String,
    val result: DictionaryResult?,
    val isSaved: Boolean,
    val sentence: SentenceJson?
)

@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun StudyPlayerScreen(
    episodeKey: String,
    startPositionMs: Long?,
    widthSizeClass: WindowWidthSizeClass,
    container: AppContainer,
    onBack: () -> Unit
) {
    val controller = container.playerController
    val playbackState by controller.uiState.collectAsStateWithLifecycle()
    val shadowState by container.shadowRecorder.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    LaunchedEffect(episodeKey, startPositionMs) {
        controller.openEpisode(episodeKey, startPositionMs = startPositionMs, autoPlay = true)
    }

    var lookupState by remember { mutableStateOf<LookupDialogState?>(null) }
    var noteDialogEntry by remember { mutableStateOf<ActiveTimelineEntry?>(null) }
    var showShadowing by remember { mutableStateOf(false) }
    var showDictation by remember { mutableStateOf(false) }
    var showFullscreenVideo by remember { mutableStateOf(false) }
    var showSubMenu by remember { mutableStateOf(false) }
    var resourcesSheetInitialTab by remember { mutableStateOf<Int?>(null) }

    val listState = rememberLazyListState()
    var autoFollow by remember { mutableStateOf(true) }

    // Only pause auto-follow when the user explicitly drags the subtitle list
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                autoFollow = false
            }
        }
    }

    LaunchedEffect(playbackState.activeSentenceIndex, autoFollow, playbackState.bundle) {
        val idx = playbackState.activeSentenceIndex
        if (autoFollow && idx >= 0 && playbackState.bundle != null) {
            val vh = listState.layoutInfo.viewportSize.height
            val offsetPx = if (vh > 0) -(vh * 0.16f).toInt() else -72
            listState.animateScrollToItem(index = idx, scrollOffset = offsetPx)
        }
    }

    fun triggerWordLookup(rawWord: String, sentence: SentenceJson?, contextHint: String? = null) {
        controller.pause()
        scope.launch {
            val res = container.repository.lookupWord(rawWord, contextHint)
            val saved = if (res != null) container.repository.isWordSaved(res.word, res.word) else false
            lookupState = LookupDialogState(
                token = rawWord,
                result = res,
                isSaved = saved,
                sentence = sentence
            )
            if (res != null) {
                container.ttsSpeaker.speak(res.word)
            }
        }
    }

    val speeds = remember { listOf(0.6f, 0.75f, 0.85f, 1.0f, 1.15f, 1.25f, 1.5f, 2.0f) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = playbackState.episode?.titleZh ?: "加载课程...",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        playbackState.episode?.let { ep ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                CefrBadge(ep.cefrLevel)
                                Text(
                                    text = ep.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (playbackState.bundle != null && widthSizeClass == WindowWidthSizeClass.Compact) {
                        IconButton(onClick = { resourcesSheetInitialTab = 0 }) {
                            Icon(Icons.AutoMirrored.Rounded.MenuBook, contentDescription = "词汇与精讲")
                        }
                    }
                    if (playbackState.episode?.hasVideo == true) {
                        IconButton(onClick = { controller.toggleVideoAudioMode() }) {
                            Icon(
                                imageVector = if (playbackState.isVideoMode) Icons.Rounded.Headphones else Icons.Rounded.Videocam,
                                contentDescription = if (playbackState.isVideoMode) "切为纯音频" else "切为视频画面"
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { showSubMenu = true }) {
                            Icon(Icons.Rounded.Subtitles, contentDescription = "字幕模式")
                        }
                        DropdownMenu(expanded = showSubMenu, onDismissRequest = { showSubMenu = false }) {
                            SubtitleDisplayMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(mode.labelZh) },
                                    onClick = {
                                        controller.setSubtitleMode(mode)
                                        showSubMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        if (playbackState.isLoading || playbackState.bundle == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                if (playbackState.errorMessage != null) {
                    Text(playbackState.errorMessage!!, color = MaterialTheme.colorScheme.error)
                } else {
                    CircularProgressIndicator()
                }
            }
            return@Scaffold
        }

        val bundle = playbackState.bundle!!
        val episode = playbackState.episode!!
        val isTwoPane = widthSizeClass != WindowWidthSizeClass.Compact

        val mediaAndControls: @Composable (Modifier) -> Unit = { mod ->
            Column(
                modifier = mod,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AnimatedContent(
                    targetState = playbackState.isVideoMode && episode.hasVideo && !showFullscreenVideo,
                    transitionSpec = {
                        (expandVertically(LingoMotion.spatialDefault()) + fadeIn(LingoMotion.effectsDefault())) togetherWith
                            (shrinkVertically(LingoMotion.spatialDefault()) + fadeOut(LingoMotion.effectsDefault()))
                    },
                    label = "video_audio_switch"
                ) { showVideo ->
                    if (showVideo) {
                        VideoPlayerCard(
                            controller = controller,
                            onDoubleTapSeek = { controller.seekRelative(it) },
                            onFullscreen = { showFullscreenVideo = true }
                        )
                    } else {
                        AudioOnlyHeroCard(
                            coverPath = episode.coverPath,
                            title = episode.titleZh.ifBlank { episode.title },
                            subtitleMode = playbackState.subtitleMode,
                            canSwitchToVideo = episode.hasVideo,
                            onSwitchVideo = { controller.toggleVideoAudioMode() }
                        )
                    }
                }

                // Slim Timeline Bar + Compact Timestamps
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    SlimTimelineBar(
                        positionMs = playbackState.positionMs,
                        durationMs = playbackState.durationMs,
                        loopAMs = playbackState.loopAMs,
                        loopBMs = playbackState.loopBMs,
                        onSeek = { ms ->
                            autoFollow = true
                            controller.seekTo(ms)
                        }
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TimestampText(playbackState.positionMs)
                        Text(
                            text = if (playbackState.activeSentenceIndex >= 0) {
                                "句 ${playbackState.activeSentenceIndex + 1} / ${bundle.sentences.size}"
                            } else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TimestampText(playbackState.durationMs)
                    }
                }

                // Compact 1-line timeline vocab bar on phones; full card strip on wide 2-pane tablets
                if (isTwoPane) {
                    TimelineVocabStrip(
                        sentenceIndex = playbackState.activeSentenceIndex,
                        entries = playbackState.activeTimelineEntries,
                        onEntryClick = { entry ->
                            if (" " in entry.title.trim()) {
                                controller.pause()
                                noteDialogEntry = entry
                            } else {
                                triggerWordLookup(entry.title, controller.activeSentence(), entry.meaningZh)
                            }
                        }
                    )
                } else {
                    CompactTimelineVocabBar(
                        sentenceIndex = playbackState.activeSentenceIndex,
                        entries = playbackState.activeTimelineEntries,
                        onEntryClick = { entry ->
                            if (" " in entry.title.trim()) {
                                controller.pause()
                                noteDialogEntry = entry
                            } else {
                                triggerWordLookup(entry.title, controller.activeSentence(), entry.meaningZh)
                            }
                        }
                    )
                }
            }
        }

        val subtitleListComposable: @Composable (Modifier) -> Unit = { mod ->
            SyncedSubtitleList(
                sentences = bundle.sentences,
                activeSentenceIndex = playbackState.activeSentenceIndex,
                activeWordIndex = playbackState.activeWordIndex,
                subtitleMode = playbackState.subtitleMode,
                listState = listState,
                autoFollow = autoFollow,
                onResumeFollow = {
                    autoFollow = true
                    val idx = playbackState.activeSentenceIndex
                    if (idx >= 0) {
                        val vh = listState.layoutInfo.viewportSize.height
                        val offsetPx = if (vh > 0) -(vh * 0.16f).toInt() else -72
                        scope.launch { listState.animateScrollToItem(index = idx, scrollOffset = offsetPx) }
                    }
                },
                onSentenceClick = { sent ->
                    autoFollow = true
                    controller.seekTo(sent.startMs)
                    if (!playbackState.isPlaying) controller.play()
                },
                onWordClick = { word, sent ->
                    val matchedNote = bundle.timelineNotes.firstOrNull {
                        it.sentenceId == sent.id && it.phrase.contains(word.trim(',', '.', '?', '!'), ignoreCase = true)
                    }
                    triggerWordLookup(word, sent, matchedNote?.meaningZh)
                },
                modifier = mod
            )
        }

        val subtitleAndStudyPane: @Composable (Modifier) -> Unit = { mod ->
            var selectedTab by remember { mutableIntStateOf(0) }
            Column(modifier = mod) {
                PrimaryTabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("同步字幕") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("词汇 (${bundle.vocabList.size})") }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text("精讲 (${bundle.timelineNotes.size})") }
                    )
                }
                when (selectedTab) {
                    0 -> subtitleListComposable(Modifier.weight(1f))
                    1 -> EpisodeVocabTabContent(
                        vocabList = bundle.vocabList,
                        onSpeak = { container.ttsSpeaker.speak(it) },
                        onJumpToSentence = { ms ->
                            selectedTab = 0
                            autoFollow = true
                            controller.seekTo(ms)
                            controller.play()
                        },
                        onLookupVocab = { v ->
                            val sent = v.occurrences.firstOrNull()?.let { occ ->
                                bundle.sentences.getOrNull(occ.sentenceId)
                            }
                            triggerWordLookup(v.word, sent, v.contextMeaningZh ?: v.meaningZh)
                        }
                    )
                    else -> EpisodeNotesTabContent(
                        notes = bundle.timelineNotes,
                        onJumpToMs = { ms ->
                            selectedTab = 0
                            autoFollow = true
                            controller.seekTo(ms)
                            controller.play()
                        }
                    )
                }
            }
        }

        val dock: @Composable () -> Unit = {
            ExpressivePlaybackDock(
                isPlaying = playbackState.isPlaying,
                singleSentenceLoop = playbackState.singleSentenceLoop,
                hasABLoop = playbackState.loopAMs != null && playbackState.loopBMs != null,
                loopPendingA = playbackState.loopAMs != null && playbackState.loopBMs == null,
                speed = playbackState.speed,
                vocabCount = bundle.vocabList.size,
                noteCount = bundle.timelineNotes.size,
                onPrevSentence = {
                    autoFollow = true
                    controller.stepSentence(-1)
                },
                onTogglePlay = { controller.togglePlayPause() },
                onNextSentence = {
                    autoFollow = true
                    controller.stepSentence(1)
                },
                onToggleSingleLoop = { controller.toggleSingleSentenceLoop() },
                onToggleABLoop = { controller.toggleABLoopPoint() },
                onCycleSpeed = {
                    val idx = speeds.indexOfFirst { kotlin.math.abs(it - playbackState.speed) < 0.03f }
                    val next = speeds[(idx + 1) % speeds.size]
                    controller.setSpeed(next)
                },
                onOpenShadowing = {
                    controller.pause()
                    showShadowing = true
                },
                onOpenDictation = {
                    controller.pause()
                    showDictation = true
                },
                onOpenResources = if (isTwoPane) null else { tab -> resourcesSheetInitialTab = tab }
            )
        }

        if (isTwoPane) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(0.45f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    mediaAndControls(Modifier.weight(1f))
                    dock()
                }
                subtitleAndStudyPane(
                    Modifier
                        .weight(0.55f)
                        .fillMaxHeight()
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                mediaAndControls(Modifier)
                subtitleListComposable(Modifier.weight(1f))
                Box(modifier = Modifier.padding(bottom = 6.dp)) {
                    dock()
                }
            }
        }
    }

    resourcesSheetInitialTab?.let { initTab ->
        val bundle = playbackState.bundle
        if (bundle != null) {
            StudyResourcesSheet(
                initialTab = initTab,
                vocabList = bundle.vocabList,
                notes = bundle.timelineNotes,
                onSpeak = { container.ttsSpeaker.speak(it) },
                onJumpToMs = { ms ->
                    autoFollow = true
                    controller.seekTo(ms)
                    controller.play()
                },
                onLookupVocab = { v ->
                    val sent = v.occurrences.firstOrNull()?.let { occ ->
                        bundle.sentences.getOrNull(occ.sentenceId)
                    }
                    triggerWordLookup(v.word, sent, v.contextMeaningZh ?: v.meaningZh)
                },
                onDismiss = { resourcesSheetInitialTab = null }
            )
        }
    }

    // Fullscreen Video Dialog
    if (showFullscreenVideo) {
        Dialog(
            onDismissRequest = { showFullscreenVideo = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .statusBarsPadding()
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = true
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            player = controller.getOrCreateExoPlayer()
                        }
                    },
                    update = { view ->
                        val expected = controller.getOrCreateExoPlayer()
                        if (view.player !== expected) {
                            view.player = expected
                        }
                    },
                    onRelease = { view -> view.player = null },
                    modifier = Modifier.fillMaxSize()
                )
                IconButton(
                    onClick = { showFullscreenVideo = false },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                ) {
                    Icon(Icons.Rounded.FullscreenExit, contentDescription = "退出全屏", tint = Color.White)
                }
            }
        }
    }

    // Word Lookup ModalBottomSheet
    lookupState?.let { state ->
        WordLookupSheet(
            token = state.token,
            result = state.result,
            isSaved = state.isSaved,
            sentenceEn = state.sentence?.textEn,
            onSpeak = { container.ttsSpeaker.speak(it) },
            onToggleSave = {
                val res = state.result ?: return@WordLookupSheet
                val ep = playbackState.episode ?: return@WordLookupSheet
                val sent = state.sentence ?: controller.activeSentence() ?: return@WordLookupSheet
                scope.launch {
                    if (state.isSaved) {
                        container.repository.removeSavedWordByText(res.word, res.word)
                        lookupState = state.copy(isSaved = false)
                    } else {
                        container.repository.saveWord(res, ep, sent)
                        lookupState = state.copy(isSaved = true)
                    }
                }
            },
            onDismiss = { lookupState = null }
        )
    }

    // Multi-word Idiom / Grammar Tip Dialog
    noteDialogEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { noteDialogEntry = null },
            title = { Text(entry.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CefrBadge(entry.cefrLevel)
                    Text(entry.meaningZh, style = MaterialTheme.typography.titleMedium)
                    Text(entry.usageTipZh, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(onClick = { container.ttsSpeaker.speak(entry.title) }) {
                    Text("朗读短语")
                }
            },
            dismissButton = {
                TextButton(onClick = { noteDialogEntry = null }) {
                    Text("关闭")
                }
            }
        )
    }

    // Shadowing Sheet
    if (showShadowing) {
        val activeSent = controller.activeSentence()
        if (activeSent != null) {
            ShadowingSheet(
                sentence = activeSent,
                shadowState = shadowState,
                onPlayNative = {
                    controller.seekTo(activeSent.startMs)
                    controller.play()
                },
                onStartRecord = { container.shadowRecorder.startRecording() },
                onStopRecord = { container.shadowRecorder.stopRecording() },
                onPlayMine = { container.shadowRecorder.playRecording() },
                onDismiss = {
                    container.shadowRecorder.stopRecording()
                    container.shadowRecorder.stopPlayback()
                    showShadowing = false
                }
            )
        }
    }

    // Dictation Sheet
    if (showDictation) {
        val activeSent = controller.activeSentence()
        if (activeSent != null) {
            DictationSheet(
                sentence = activeSent,
                onReplaySentence = {
                    controller.seekTo(activeSent.startMs)
                    controller.play()
                },
                onDismiss = { showDictation = false }
            )
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoPlayerCard(
    controller: com.lingocast.app.playback.PlayerController,
    onDoubleTapSeek: (Long) -> Unit,
    onFullscreen: () -> Unit
) {
    var seekHint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(seekHint) {
        if (seekHint != null) {
            delay(750)
            seekHint = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(22.dp))
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.view_lingo_player, null, false) as PlayerView).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    player = controller.getOrCreateExoPlayer()
                }
            },
            update = { view ->
                val expected = controller.getOrCreateExoPlayer()
                if (view.player !== expected) {
                    view.player = expected
                }
            },
            onRelease = { view -> view.player = null },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { controller.togglePlayPause() },
                        onDoubleTap = { offset ->
                            if (offset.x < size.width / 2f) {
                                onDoubleTapSeek(-5000L)
                                seekHint = "-5 秒"
                            } else {
                                onDoubleTapSeek(5000L)
                                seekHint = "+5 秒"
                            }
                        }
                    )
                }
        )

        AnimatedVisibility(
            visible = seekHint != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.65f),
                shape = CircleShape
            ) {
                Text(
                    text = seekHint.orEmpty(),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        IconButton(
            onClick = onFullscreen,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
                .size(34.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
        ) {
            Icon(Icons.Rounded.Fullscreen, contentDescription = "全屏", tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun AudioOnlyHeroCard(
    coverPath: String,
    title: String,
    subtitleMode: SubtitleDisplayMode,
    canSwitchToVideo: Boolean,
    onSwitchVideo: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(width = 78.dp, height = 54.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (coverPath.isNotBlank() && File(coverPath).isFile()) {
                    AsyncImage(
                        model = File(coverPath),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(Icons.Rounded.Headphones, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("纯音频精听模式 · ${subtitleMode.labelZh}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (canSwitchToVideo) {
                Surface(
                    onClick = onSwitchVideo,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = CircleShape
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Rounded.Videocam, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("切视频", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
