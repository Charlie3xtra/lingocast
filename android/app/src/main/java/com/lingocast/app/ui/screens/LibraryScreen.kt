package com.lingocast.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.lingocast.app.data.db.EpisodeEntity
import com.lingocast.app.data.db.SeriesEntity
import com.lingocast.app.ui.common.CefrBadge
import com.lingocast.app.ui.common.expressivePressScale
import com.lingocast.app.ui.common.formatDurationMs
import com.lingocast.app.ui.common.formatHoursMinutes
import com.lingocast.app.ui.theme.LingoMotion
import java.io.File

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun LibraryScreen(
    seriesList: List<SeriesEntity>,
    episodes: List<EpisodeEntity>,
    lastPlayed: EpisodeEntity?,
    savedWordCount: Int,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope,
    onOpenSeries: (String) -> Unit,
    onOpenEpisode: (String) -> Unit,
    onOpenImport: () -> Unit
) {
    var selectedFilter by rememberSaveable { mutableStateOf("全部") }
    val filters = listOf("全部", "A2", "B1", "B2", "C1", "未听完", "已完成")

    val filteredEpisodes = episodes.filter { ep ->
        when (selectedFilter) {
            "全部" -> true
            "未听完" -> !ep.completed
            "已完成" -> ep.completed
            else -> ep.cefrLevel.equals(selectedFilter, ignoreCase = true)
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 310.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "LingoCast 精听精读",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "已装载 ${episodes.size} 集课程 · 生词本 $savedWordCount 词",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    onClick = onOpenImport,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "导入课程",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }

        if (lastPlayed != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ResumeHeroCard(
                    episode = lastPlayed,
                    onClick = { onOpenEpisode(lastPlayed.episodeKey) }
                )
            }
        }

        if (seriesList.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "精听专辑",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            items(seriesList, key = { "series_${it.seriesId}" }) { series ->
                Box(
                    modifier = Modifier.animateItem(
                        fadeInSpec = LingoMotion.effectsDefault(),
                        placementSpec = LingoMotion.spatialDefault()
                    )
                ) {
                    SeriesCollectionCard(
                        series = series,
                        installedCount = episodes.count { it.seriesId == series.seriesId },
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        onClick = { onOpenSeries(series.seriesId) }
                    )
                }
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "单集列表",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    filters.forEach { label ->
                        FilterChip(
                            selected = selectedFilter == label,
                            onClick = { selectedFilter = label },
                            label = { Text(label) }
                        )
                    }
                }
            }
        }

        if (filteredEpisodes.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("暂无匹配筛选条件的课程", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "可前往「导入与同步」通过局域网 Wi-Fi 同步完整 56 集雅思播客。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(filteredEpisodes, key = { it.episodeKey }) { ep ->
                EpisodeRowCard(
                    episode = ep,
                    onClick = { onOpenEpisode(ep.episodeKey) },
                    modifier = Modifier.animateItem(
                        fadeInSpec = LingoMotion.effectsDefault(),
                        placementSpec = LingoMotion.spatialDefault()
                    )
                )
            }
        }
    }
}

@Composable
private fun ResumeHeroCard(
    episode: EpisodeEntity,
    onClick: () -> Unit
) {
    val progress = if (episode.durationMs > 0) {
        (episode.lastPositionMs.toFloat() / episode.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .expressivePressScale(interactionSource)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                if (episode.coverPath.isNotBlank() && File(episode.coverPath).isFile()) {
                    AsyncImage(
                        model = File(episode.coverPath),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        Icons.Rounded.Headphones,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "继续精听",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = episode.titleZh.ifBlank { episode.title },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${formatDurationMs(episode.lastPositionMs)} / ${formatDurationMs(episode.durationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.5f)
                )
            }
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(46.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = "继续播放",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SeriesCollectionCard(
    series: SeriesEntity,
    installedCount: Int,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    ElevatedCard(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier
            .fillMaxWidth()
            .expressivePressScale(interactionSource)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(148.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                with(sharedTransitionScope) {
                    if (series.coverPath.isNotBlank() && File(series.coverPath).isFile()) {
                        AsyncImage(
                            model = File(series.coverPath),
                            contentDescription = series.titleZh,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .sharedElement(
                                    rememberSharedContentState(key = "series_cover_${series.seriesId}"),
                                    animatedVisibilityScope = animatedVisibilityScope
                                )
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))
                            )
                        )
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = series.cefrRange,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(14.dp)
                ) {
                    Text(
                        text = series.titleZh,
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${series.accent} · 已同步 $installedCount/${series.episodeCount} 集 · ${formatHoursMinutes(series.totalDurationMs)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
            }
            Text(
                text = series.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
    }
}

@Composable
fun EpisodeRowCard(
    episode: EpisodeEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val progress by animateFloatAsState(
        targetValue = if (episode.durationMs > 0) (episode.lastPositionMs.toFloat() / episode.durationMs).coerceIn(0f, 1f) else 0f,
        animationSpec = LingoMotion.effectsDefault(),
        label = "ep_progress"
    )
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 1.dp,
        modifier = modifier
            .fillMaxWidth()
            .expressivePressScale(interactionSource)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 86.dp, height = 58.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center
                ) {
                    if (episode.coverPath.isNotBlank() && File(episode.coverPath).isFile()) {
                        AsyncImage(
                            model = File(episode.coverPath),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            Icons.Rounded.Headphones,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Surface(
                        color = Color.Black.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                    ) {
                        Text(
                            text = formatDurationMs(episode.durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CefrBadge(episode.cefrLevel)
                        Icon(
                            if (episode.hasVideo) Icons.Rounded.Videocam else Icons.Rounded.Headphones,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (episode.completed) {
                            Icon(
                                Icons.Rounded.CheckCircle,
                                contentDescription = "已精听",
                                modifier = Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.secondary
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "#${episode.indexNum}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = episode.titleZh.ifBlank { episode.title },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${episode.title} · ${episode.vocabCount}词 · ${episode.noteCount}笔记",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (progress > 0.01f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            }
        }
    }
}
