package com.lingocast.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lingocast.app.AppContainer
import com.lingocast.app.data.db.SavedWordEntity
import com.lingocast.app.data.srs.ReviewGrade
import com.lingocast.app.ui.common.CefrBadge
import com.lingocast.app.ui.theme.LingoMotion
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocabNotebookScreen(
    allWords: List<SavedWordEntity>,
    dueWords: List<SavedWordEntity>,
    container: AppContainer,
    onJumpToEpisode: (episodeKey: String, posMs: Long) -> Unit
) {
    var mode by remember { mutableIntStateOf(0) } // 0=all, 1=flashcards, 2=mastered
    val scope = rememberCoroutineScope()

    val masteredWords = remember(allWords) { allWords.filter { it.masteryLevel >= 3 } }
    val segments = listOf(
        "生词本 (${allWords.size})",
        "闪卡复习 (${dueWords.size})",
        "已掌握 (${masteredWords.size})"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("生词与记忆巩固", style = MaterialTheme.typography.headlineMedium)

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            segments.forEachIndexed { idx, label ->
                SegmentedButton(
                    selected = mode == idx,
                    onClick = { mode = idx },
                    shape = SegmentedButtonDefaults.itemShape(index = idx, count = segments.size)
                ) {
                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        AnimatedContent(
            targetState = mode,
            transitionSpec = {
                val forward = targetState > initialState
                (slideInHorizontally(LingoMotion.spatialDefault()) { w -> if (forward) w / 4 else -w / 4 } +
                    fadeIn(LingoMotion.effectsDefault()))
                    .togetherWith(
                        slideOutHorizontally(LingoMotion.spatialFast()) { w -> if (forward) -w / 4 else w / 4 } +
                            fadeOut(LingoMotion.effectsDefault())
                    )
            },
            label = "vocab_mode",
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) { currentMode ->
            when (currentMode) {
                1 -> FlashcardReviewDeck(
                    dueWords = dueWords,
                    onSpeak = { container.ttsSpeaker.speak(it) },
                    onPlayClip = { w ->
                        container.playerController.playVocabContextClip(w.sourceEpisodeKey, w.clipStartMs, w.clipEndMs)
                    },
                    onGrade = { w, grade ->
                        scope.launch { container.repository.gradeSavedWord(w, grade) }
                    }
                )
                else -> {
                    val displayList = if (currentMode == 2) masteredWords else allWords
                    if (displayList.isEmpty()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.School,
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = if (currentMode == 2) "还没有达到熟练掌握的单词" else "生词本还是空的",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "在精听播放器中点击字幕里任意单词，即可即时查词并收藏（会自动绑定原声片段）。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(displayList, key = { it.id }) { item ->
                                Box(
                                    modifier = Modifier.animateItem(
                                        fadeInSpec = LingoMotion.effectsDefault(),
                                        placementSpec = LingoMotion.spatialDefault()
                                    )
                                ) {
                                    SavedWordCard(
                                        item = item,
                                        onSpeak = { container.ttsSpeaker.speak(item.word) },
                                        onPlayClip = {
                                            container.playerController.playVocabContextClip(
                                                item.sourceEpisodeKey,
                                                item.clipStartMs,
                                                item.clipEndMs
                                            )
                                        },
                                        onOpenInEpisode = {
                                            onJumpToEpisode(item.sourceEpisodeKey, item.clipStartMs)
                                        },
                                        onDelete = {
                                            scope.launch { container.repository.deleteSavedWord(item.id) }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedWordCard(
    item: SavedWordEntity,
    onSpeak: () -> Unit,
    onPlayClip: () -> Unit,
    onOpenInEpisode: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        onClick = onOpenInEpisode,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(item.word, style = MaterialTheme.typography.titleLarge)
                    CefrBadge(item.cefrLevel)
                    if (item.phonetic.isNotBlank()) {
                        Text("/${item.phonetic}/", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Row {
                    IconButton(onClick = onSpeak) {
                        Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = "发音")
                    }
                    IconButton(onClick = onPlayClip) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = "回听原声")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = "删除")
                    }
                }
            }
            Text(
                text = (item.contextMeaningZh ?: item.meaningZh).lineSequence().firstOrNull().orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(item.sourceSentenceEn, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    Text("出自：${item.sourceEpisodeTitle}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun FlashcardReviewDeck(
    dueWords: List<SavedWordEntity>,
    onSpeak: (String) -> Unit,
    onPlayClip: (SavedWordEntity) -> Unit,
    onGrade: (SavedWordEntity, ReviewGrade) -> Unit
) {
    val current = dueWords.firstOrNull()
    if (current == null) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("今日待复习闪卡已全部完成！", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(
                    "SM-2 间隔记忆已为你规划好下次复习时间。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                )
            }
        }
        return
    }

    var flipped by remember(current.id) { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (flipped) 180f else 0f,
        animationSpec = LingoMotion.spatialSlow(),
        label = "flashcard_flip"
    )

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Surface(
            onClick = { flipped = !flipped },
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 4.dp,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .graphicsLayer {
                    rotationY = rotation
                    cameraDistance = 14f * density
                }
        ) {
            if (rotation <= 90f) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CefrBadge(current.cefrLevel)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = current.word,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold
                    )
                    if (current.phonetic.isNotBlank()) {
                        Text(
                            text = "/${current.phonetic}/",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilledTonalButton(onClick = { onSpeak(current.word) }) {
                            Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("单词发音")
                        }
                        FilledTonalButton(onClick = { onPlayClip(current) }) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("回听原声语境")
                        }
                    }
                    Spacer(Modifier.height(22.dp))
                    Text(
                        text = "点击卡片翻转查看释义与原句",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { rotationY = 180f }
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(current.word, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = current.contextMeaningZh ?: current.meaningZh,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(current.sourceSentenceEn, style = MaterialTheme.typography.bodyMedium)
                            if (current.sourceSentenceZh.isNotBlank()) {
                                Text(
                                    current.sourceSentenceZh,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(onClick = { onPlayClip(current) }) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("回听原声语境")
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ReviewGrade.entries.forEach { grade ->
                val colors = when (grade) {
                    ReviewGrade.AGAIN -> ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                    ReviewGrade.HARD -> ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    ReviewGrade.GOOD -> ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    ReviewGrade.EASY -> ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                Button(
                    onClick = { onGrade(current, grade) },
                    colors = colors,
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(grade.labelZh, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
        }
    }
}
