package com.lingocast.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.lingocast.app.data.model.SentenceJson
import com.lingocast.app.data.model.TimelineNoteJson
import com.lingocast.app.data.model.VocabItemJson
import com.lingocast.app.data.prefs.SubtitleDisplayMode
import com.lingocast.app.playback.ActiveTimelineEntry
import com.lingocast.app.playback.ShadowState
import com.lingocast.app.ui.common.CefrBadge
import com.lingocast.app.ui.common.TimestampText
import com.lingocast.app.ui.common.expressivePressScale
import com.lingocast.app.ui.theme.LingoMotion

/**
 * Compact 1-line timeline-synced vocabulary pill strip for portrait phones.
 * Takes 0dp when empty and smoothly animates open/closed with spring physics.
 */
@Composable
fun CompactTimelineVocabBar(
    sentenceIndex: Int,
    entries: List<ActiveTimelineEntry>,
    onEntryClick: (ActiveTimelineEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = entries.isNotEmpty(),
        enter = expandVertically(LingoMotion.spatialDefault()) + fadeIn(LingoMotion.effectsDefault()),
        exit = shrinkVertically(LingoMotion.spatialDefault()) + fadeOut(LingoMotion.effectsDefault()),
        modifier = modifier.fillMaxWidth()
    ) {
        AnimatedContent(
            targetState = sentenceIndex to entries,
            transitionSpec = {
                (slideInHorizontally(LingoMotion.spatialFast()) { it / 5 } + fadeIn(LingoMotion.effectsDefault())) togetherWith
                    (slideOutHorizontally(LingoMotion.spatialFast()) { -it / 6 } + fadeOut(LingoMotion.effectsDefault()))
            },
            contentKey = { it.first },
            label = "compact_timeline_vocab"
        ) { (_, items) ->
            LazyRow(
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(items, key = { it.id }) { item ->
                    val (bg, fg) = when (item.category) {
                        "idiom", "collocation" -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
                        "grammar" -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
                        "ielts" -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
                        else -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurface
                    }
                    val shortMeaning = item.meaningZh.lineSequence().firstOrNull().orEmpty()
                    Surface(
                        onClick = { onEntryClick(item) },
                        color = bg,
                        shape = CircleShape
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text(
                                text = item.cefrLevel,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = fg.copy(alpha = 0.8f)
                            )
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = fg,
                                maxLines = 1
                            )
                            if (shortMeaning.isNotBlank()) {
                                Text(
                                    text = "· $shortMeaning",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = fg.copy(alpha = 0.85f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 150.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Dynamic timeline-synced vocabulary & expression deck for spacious 2-pane tablet layouts.
 */
@Composable
fun TimelineVocabStrip(
    sentenceIndex: Int,
    entries: List<ActiveTimelineEntry>,
    onEntryClick: (ActiveTimelineEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        Icons.Rounded.AutoStories,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "此刻时间轴词汇 · 表达精讲",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (entries.isNotEmpty()) {
                    Text(
                        text = "${entries.size} 项",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AnimatedContent(
                targetState = sentenceIndex to entries,
                transitionSpec = {
                    (slideInVertically(LingoMotion.spatialFast()) { it / 3 } + fadeIn(LingoMotion.effectsDefault())) togetherWith
                        (slideOutVertically(LingoMotion.spatialFast()) { -it / 4 } + fadeOut(LingoMotion.effectsDefault()))
                },
                contentKey = { it.first },
                label = "timeline_vocab_strip"
            ) { (_, items) ->
                if (items.isEmpty()) {
                    Text(
                        text = "播放过程中，本句核心词汇与地道表达会随时间轴自动弹出。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                } else {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(items, key = { it.id }) { item ->
                            TimelineVocabCard(item = item, onClick = { onEntryClick(item) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineVocabCard(
    item: ActiveTimelineEntry,
    onClick: () -> Unit
) {
    val (container, onContainer, catLabel) = when (item.category) {
        "idiom", "collocation" -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            if (item.category == "idiom") "地道习语" else "固定搭配"
        )
        "grammar" -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            "句型语法"
        )
        "ielts" -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            "雅思高频"
        )
        else -> Triple(
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.onSurface,
            "核心词汇"
        )
    }

    Surface(
        onClick = onClick,
        color = container,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.widthIn(min = 172.dp, max = 265.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = catLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = onContainer.copy(alpha = 0.85f)
                )
                CefrBadge(item.cefrLevel)
                if (!item.phonetic.isNullOrBlank()) {
                    Text(
                        text = "/${item.phonetic}/",
                        style = MaterialTheme.typography.labelSmall,
                        color = onContainer.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = onContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.meaningZh,
                style = MaterialTheme.typography.bodySmall,
                color = onContainer.copy(alpha = 0.9f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun SyncedSubtitleList(
    sentences: List<SentenceJson>,
    activeSentenceIndex: Int,
    activeWordIndex: Int,
    subtitleMode: SubtitleDisplayMode,
    listState: LazyListState,
    autoFollow: Boolean,
    onResumeFollow: () -> Unit,
    onSentenceClick: (SentenceJson) -> Unit,
    onWordClick: (word: String, sentence: SentenceJson) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(sentences, key = { _, s -> s.id }) { idx, sentence ->
                SubtitleSentenceCard(
                    sentence = sentence,
                    isActive = idx == activeSentenceIndex,
                    activeWordIndex = if (idx == activeSentenceIndex) activeWordIndex else -1,
                    subtitleMode = subtitleMode,
                    onCardClick = { onSentenceClick(sentence) },
                    onWordClick = { word -> onWordClick(word, sentence) }
                )
            }
            item { Spacer(Modifier.height(64.dp)) }
        }

        AnimatedVisibility(
            visible = !autoFollow && activeSentenceIndex >= 0,
            enter = fadeIn(LingoMotion.effectsDefault()) + slideInVertically(LingoMotion.spatialFast()) { it / 2 },
            exit = fadeOut(LingoMotion.effectsDefault()),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp)
        ) {
            FilledTonalButton(
                onClick = onResumeFollow,
                shape = CircleShape
            ) {
                Icon(Icons.Rounded.GraphicEq, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("回到当前句")
            }
        }
    }
}

@Composable
private fun SubtitleSentenceCard(
    sentence: SentenceJson,
    isActive: Boolean,
    activeWordIndex: Int,
    subtitleMode: SubtitleDisplayMode,
    onCardClick: () -> Unit,
    onWordClick: (String) -> Unit
) {
    var blindRevealed by remember(sentence.id, subtitleMode) { mutableStateOf(false) }

    val cardBg by animateColorAsState(
        targetValue = if (isActive) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainerLowest
        },
        animationSpec = LingoMotion.effectsDefault(),
        label = "sent_bg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isActive) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
        } else {
            Color.Transparent
        },
        animationSpec = LingoMotion.effectsDefault(),
        label = "sent_border"
    )

    val accentHeight by animateDpAsState(
        targetValue = if (isActive) 28.dp else 0.dp,
        animationSpec = LingoMotion.expressiveBouncy(),
        label = "accent_bar"
    )
    val interactionSource = remember { MutableInteractionSource() }

    val primaryColor = MaterialTheme.colorScheme.primary
    val primaryBg = MaterialTheme.colorScheme.primaryContainer
    val vocabColor = MaterialTheme.colorScheme.secondary
    val baseTextColor = if (isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.84f)

    Surface(
        onClick = onCardClick,
        interactionSource = interactionSource,
        color = cardBg,
        shape = RoundedCornerShape(16.dp),
        border = if (isActive) BorderStroke(1.2.dp, borderColor) else null,
        tonalElevation = if (isActive) 3.dp else 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .expressivePressScale(interactionSource)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (accentHeight > 1.dp) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(accentHeight)
                        .clip(CircleShape)
                        .background(primaryColor)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TimestampText(
                        ms = sentence.startMs,
                        color = if (isActive) primaryColor else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (subtitleMode == SubtitleDisplayMode.BLIND) {
                        Surface(
                            onClick = { blindRevealed = !blindRevealed },
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = CircleShape
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Rounded.Visibility, contentDescription = null, modifier = Modifier.size(12.dp))
                                Text(
                                    text = if (blindRevealed) "隐藏原文" else "揭晓原文",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }

                val showEnglish = when (subtitleMode) {
                    SubtitleDisplayMode.BILINGUAL, SubtitleDisplayMode.ENGLISH_ONLY -> true
                    SubtitleDisplayMode.CHINESE_ONLY -> false
                    SubtitleDisplayMode.BLIND -> blindRevealed
                }
                val showChinese = when (subtitleMode) {
                    SubtitleDisplayMode.BILINGUAL, SubtitleDisplayMode.CHINESE_ONLY -> true
                    SubtitleDisplayMode.ENGLISH_ONLY -> false
                    SubtitleDisplayMode.BLIND -> blindRevealed
                }

                if (!showEnglish && !showChinese) {
                    Surface(
                        onClick = { blindRevealed = true },
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "盲听训练中 · 轻触播放本句 / 点击右上角揭晓文本",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                } else {
                    if (showEnglish) {
                        val annotatedEn = remember(sentence.id, sentence.words, activeWordIndex, isActive, baseTextColor) {
                            buildAnnotatedString {
                                if (sentence.words.isEmpty()) {
                                    append(sentence.textEn)
                                } else {
                                    sentence.words.forEachIndexed { wIdx, tok ->
                                        if (wIdx > 0) append(" ")
                                        val isCurrentWord = isActive && wIdx == activeWordIndex
                                        val isVocab = !tok.vocabId.isNullOrBlank()
                                        val spanStyle = when {
                                            isCurrentWord -> SpanStyle(
                                                color = primaryColor,
                                                background = primaryBg,
                                                fontWeight = FontWeight.Bold
                                            )
                                            isVocab -> SpanStyle(
                                                color = vocabColor,
                                                fontWeight = FontWeight.SemiBold,
                                                textDecoration = TextDecoration.Underline
                                            )
                                            else -> SpanStyle(color = baseTextColor)
                                        }
                                        withLink(
                                            LinkAnnotation.Clickable(
                                                tag = "tok_$wIdx",
                                                styles = TextLinkStyles(style = spanStyle),
                                                linkInteractionListener = { onWordClick(tok.w) }
                                            )
                                        ) {
                                            append(tok.w)
                                        }
                                    }
                                }
                            }
                        }
                        Text(
                            text = annotatedEn,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = if (isActive) 17.sp else 15.sp,
                                lineHeight = if (isActive) 25.sp else 22.sp
                            )
                        )
                    }

                    if (showChinese && sentence.textZh.isNotBlank()) {
                        Text(
                            text = sentence.textZh,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isActive) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.76f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ExpressivePlaybackDock(
    isPlaying: Boolean,
    singleSentenceLoop: Boolean,
    hasABLoop: Boolean,
    loopPendingA: Boolean,
    speed: Float,
    vocabCount: Int = 0,
    noteCount: Int = 0,
    onPrevSentence: () -> Unit,
    onTogglePlay: () -> Unit,
    onNextSentence: () -> Unit,
    onToggleSingleLoop: () -> Unit,
    onToggleABLoop: () -> Unit,
    onCycleSpeed: () -> Unit,
    onOpenShadowing: () -> Unit,
    onOpenDictation: () -> Unit,
    onOpenResources: ((initialTab: Int) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val playCorner by animateDpAsState(
        targetValue = if (isPlaying) 13.dp else 22.dp,
        animationSpec = LingoMotion.expressiveBouncy(),
        label = "play_shape"
    )
    val playInteraction = remember { MutableInteractionSource() }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(22.dp),
        tonalElevation = 5.dp,
        shadowElevation = 3.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                FilledIconToggleButton(
                    checked = singleSentenceLoop,
                    onCheckedChange = { onToggleSingleLoop() },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(Icons.Rounded.RepeatOne, contentDescription = "单句循环", modifier = Modifier.size(18.dp))
                }

                IconButton(onClick = onPrevSentence, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = "上一句", modifier = Modifier.size(26.dp))
                }

                Surface(
                    onClick = onTogglePlay,
                    interactionSource = playInteraction,
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(playCorner),
                    modifier = Modifier
                        .size(width = 64.dp, height = 42.dp)
                        .expressivePressScale(playInteraction)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (isPlaying) "暂停" else "播放",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                IconButton(onClick = onNextSentence, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "下一句", modifier = Modifier.size(26.dp))
                }

                FilledIconToggleButton(
                    checked = hasABLoop || loopPendingA,
                    onCheckedChange = { onToggleABLoop() },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(Icons.Rounded.Repeat, contentDescription = "A-B 复读", modifier = Modifier.size(18.dp))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                DockMiniAction(label = "${speed}x", onClick = onCycleSpeed)
                DockMiniAction(icon = Icons.Rounded.Mic, label = "跟读", onClick = onOpenShadowing)
                DockMiniAction(icon = Icons.Rounded.EditNote, label = "听写", onClick = onOpenDictation)
                if (onOpenResources != null) {
                    DockMiniAction(
                        icon = Icons.AutoMirrored.Rounded.MenuBook,
                        label = "词表($vocabCount)",
                        onClick = { onOpenResources(0) }
                    )
                    DockMiniAction(
                        label = "精讲($noteCount)",
                        onClick = { onOpenResources(1) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DockMiniAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyResourcesSheet(
    initialTab: Int,
    vocabList: List<VocabItemJson>,
    notes: List<TimelineNoteJson>,
    onSpeak: (String) -> Unit,
    onJumpToMs: (Long) -> Unit,
    onLookupVocab: (VocabItemJson) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedTab by remember(initialTab) { mutableIntStateOf(initialTab) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.84f)
                .navigationBarsPadding()
        ) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("本集词汇 (${vocabList.size})") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("精讲笔记 (${notes.size})") }
                )
            }
            when (selectedTab) {
                0 -> EpisodeVocabTabContent(
                    vocabList = vocabList,
                    onSpeak = onSpeak,
                    onJumpToSentence = { ms ->
                        onJumpToMs(ms)
                        onDismiss()
                    },
                    onLookupVocab = { v ->
                        onLookupVocab(v)
                    }
                )
                else -> EpisodeNotesTabContent(
                    notes = notes,
                    onJumpToMs = { ms ->
                        onJumpToMs(ms)
                        onDismiss()
                    }
                )
            }
        }
    }
}

@Composable
fun EpisodeVocabTabContent(
    vocabList: List<VocabItemJson>,
    onSpeak: (String) -> Unit,
    onJumpToSentence: (Long) -> Unit,
    onLookupVocab: (VocabItemJson) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(vocabList, key = { it.id }) { v ->
            Surface(
                onClick = { onLookupVocab(v) },
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(v.word, style = MaterialTheme.typography.titleMedium)
                            CefrBadge(v.cefrLevel)
                            if (v.phonetic.isNotBlank()) {
                                Text("/${v.phonetic}/", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Text(
                            text = (v.contextMeaningZh ?: v.meaningZh).lineSequence().firstOrNull().orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (v.occurrences.isNotEmpty()) {
                            Text(
                                text = "出现 ${v.occurrences.size} 次 · 点击查词 / 右侧跳听原声",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    IconButton(onClick = { onSpeak(v.word) }) {
                        Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = "发音")
                    }
                    if (v.occurrences.isNotEmpty()) {
                        FilledTonalIconButton(onClick = { onJumpToSentence(v.occurrences.first().startMs) }) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = "原声定位")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EpisodeNotesTabContent(
    notes: List<TimelineNoteJson>,
    onJumpToMs: (Long) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(notes, key = { it.id }) { note ->
            Surface(
                onClick = { onJumpToMs(note.startMs) },
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CefrBadge(note.cefrLevel)
                            Text(note.category.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        TimestampText(note.startMs)
                    }
                    Text(note.phrase, style = MaterialTheme.typography.titleMedium)
                    Text(note.meaningZh, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(note.usageTipZh, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShadowingSheet(
    sentence: SentenceJson,
    shadowState: ShadowState,
    onPlayNative: () -> Unit,
    onStartRecord: () -> Unit,
    onStopRecord: () -> Unit,
    onPlayMine: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasMicPermission = granted
        if (granted) onStartRecord()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp)
                .padding(bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("跟读对比训练", style = MaterialTheme.typography.headlineSmall)
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(sentence.textEn, style = MaterialTheme.typography.bodyLarge)
                    if (sentence.textZh.isNotBlank()) {
                        Text(sentence.textZh, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Live amplitude meter
            val ampScale by animateFloatAsState(
                targetValue = if (shadowState.isRecording) shadowState.amplitudeNormalized else 0.08f,
                animationSpec = LingoMotion.spatialFast(),
                label = "amp"
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                repeat(16) { i ->
                    val factor = ((i % 5) + 1) / 5f
                    val h = (10f + 30f * ampScale * factor).dp
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .width(6.dp)
                            .height(h)
                            .clip(CircleShape)
                            .background(
                                if (shadowState.isRecording) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                            )
                    )
                }
            }

            if (!shadowState.error.isNullOrBlank()) {
                Text(shadowState.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilledTonalButton(
                    onClick = onPlayNative,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("听原声")
                }

                Button(
                    onClick = {
                        if (shadowState.isRecording) {
                            onStopRecord()
                        } else if (hasMicPermission) {
                            onStartRecord()
                        } else {
                            permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    colors = if (shadowState.isRecording) {
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                    modifier = Modifier.weight(1.2f).height(48.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(if (shadowState.isRecording) Icons.Rounded.Stop else Icons.Rounded.Mic, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (shadowState.isRecording) "停止录音" else "开始跟读")
                }

                FilledTonalButton(
                    onClick = onPlayMine,
                    enabled = shadowState.hasRecording && !shadowState.isRecording,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("听我的")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DictationSheet(
    sentence: SentenceJson,
    onReplaySentence: () -> Unit,
    onDismiss: () -> Unit
) {
    val cleanWords = remember(sentence.id) {
        sentence.textEn.split(" ").filter { it.isNotBlank() }
    }
    val targetIdx = remember(sentence.id) {
        cleanWords.indices
            .filter { cleanWords[it].replace(Regex("[^a-zA-Z]"), "").length >= 5 }
            .maxByOrNull { cleanWords[it].length }
            ?: (cleanWords.size / 2).coerceIn(0, (cleanWords.size - 1).coerceAtLeast(0))
    }
    val targetRaw = cleanWords.getOrNull(targetIdx).orEmpty()
    val targetClean = targetRaw.replace(Regex("[^a-zA-Z'-]"), "")

    var answer by remember(sentence.id) { mutableStateOf("") }
    var checked by remember(sentence.id) { mutableStateOf(false) }
    val isCorrect = answer.trim().equals(targetClean, ignoreCase = true)

    LaunchedEffect(sentence.id) {
        onReplaySentence()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp)
                .padding(bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("精听挖空拼写", style = MaterialTheme.typography.headlineSmall)
                FilledTonalIconButton(onClick = onReplaySentence) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "重听此句")
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                FlowRow(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    cleanWords.forEachIndexed { idx, w ->
                        if (idx == targetIdx) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = if (checked && isCorrect) targetRaw else "_____ (${targetClean.length}字母)",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        } else {
                            Text(w, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }

            if (sentence.textZh.isNotBlank()) {
                Text("释义提示：${sentence.textZh}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            OutlinedTextField(
                value = answer,
                onValueChange = {
                    answer = it
                    checked = false
                },
                label = { Text("输入空缺单词") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )

            if (checked) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = if (isCorrect) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = if (isCorrect) "拼写完全正确！" else "参考答案：$targetClean",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (isCorrect) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
                    )
                }
            }

            Button(
                onClick = { checked = true },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("核对拼写")
            }
        }
    }
}
