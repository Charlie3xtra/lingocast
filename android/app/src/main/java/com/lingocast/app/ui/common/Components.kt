package com.lingocast.app.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lingocast.app.data.model.DictionaryResult
import com.lingocast.app.ui.theme.LingoMotion
import com.lingocast.app.ui.theme.TabularNumeralStyle

fun formatDurationMs(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return if (h > 0) {
        String.format("%d:%02d:%02d", h, m, s)
    } else {
        String.format("%02d:%02d", m, s)
    }
}

fun formatHoursMinutes(ms: Long): String {
    val totalMin = (ms / 60000L).coerceAtLeast(0L)
    val h = totalMin / 60L
    val m = totalMin % 60L
    return if (h > 0) "${h}小时${m}分" else "${m}分钟"
}

@Composable
fun CefrBadge(level: String, modifier: Modifier = Modifier) {
    val (bg, fg) = when (level.uppercase()) {
        "A1", "A2" -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        "B1" -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        "B2" -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        modifier = modifier,
        color = bg,
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = level.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun Modifier.expressivePressScale(interactionSource: InteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.975f else 1f,
        animationSpec = LingoMotion.expressiveBouncy(),
        label = "press_scale"
    )
    return this.scale(scale)
}

@Composable
fun SlimTimelineBar(
    positionMs: Long,
    durationMs: Long,
    loopAMs: Long? = null,
    loopBMs: Long? = null,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val safeDur = durationMs.coerceAtLeast(1L)
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val progress = if (isDragging) {
        dragFraction.coerceIn(0f, 1f)
    } else {
        (positionMs.toFloat() / safeDur.toFloat()).coerceIn(0f, 1f)
    }
    val thumbRadius by animateDpAsState(
        targetValue = if (isDragging) 8.dp else 5.5.dp,
        animationSpec = LingoMotion.expressiveBouncy(),
        label = "thumb_radius"
    )

    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val activeColor = MaterialTheme.colorScheme.primary
    val loopRangeColor = MaterialTheme.colorScheme.tertiary
    val thumbGlowColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(26.dp)
            .pointerInput(safeDur) {
                detectTapGestures { offset ->
                    val frac = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    onSeek((frac * safeDur).toLong())
                }
            }
            .pointerInput(safeDur) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        dragFraction = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        onSeek((dragFraction * safeDur).toLong())
                        isDragging = false
                    },
                    onDragCancel = {
                        isDragging = false
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        dragFraction = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    }
                )
            }
    ) {
        val trackHeight = 4.dp.toPx()
        val cy = size.height / 2f
        val pad = 6.dp.toPx()
        val usableW = (size.width - pad * 2f).coerceAtLeast(1f)

        drawLine(
            color = trackColor,
            start = Offset(pad, cy),
            end = Offset(pad + usableW, cy),
            strokeWidth = trackHeight,
            cap = StrokeCap.Round
        )

        val playX = pad + progress * usableW
        drawLine(
            color = activeColor,
            start = Offset(pad, cy),
            end = Offset(playX, cy),
            strokeWidth = trackHeight,
            cap = StrokeCap.Round
        )

        if (loopAMs != null && loopBMs != null && loopBMs > loopAMs) {
            val ax = pad + (loopAMs.toFloat() / safeDur).coerceIn(0f, 1f) * usableW
            val bx = pad + (loopBMs.toFloat() / safeDur).coerceIn(0f, 1f) * usableW
            drawLine(
                color = loopRangeColor,
                start = Offset(ax, cy),
                end = Offset(bx, cy),
                strokeWidth = trackHeight + 1.5.dp.toPx(),
                cap = StrokeCap.Round
            )
        } else if (loopAMs != null) {
            val ax = pad + (loopAMs.toFloat() / safeDur).coerceIn(0f, 1f) * usableW
            drawCircle(color = loopRangeColor, radius = 4.dp.toPx(), center = Offset(ax, cy))
        }

        if (isDragging) {
            drawCircle(
                color = thumbGlowColor,
                radius = thumbRadius.toPx() * 1.7f,
                center = Offset(playX, cy)
            )
        }
        drawCircle(
            color = activeColor,
            radius = thumbRadius.toPx(),
            center = Offset(playX, cy)
        )
    }
}

@Composable
fun WaveformSeekBar(
    peaks: List<Int>,
    positionMs: Long,
    durationMs: Long,
    loopAMs: Long? = null,
    loopBMs: Long? = null,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val bars = if (peaks.isNotEmpty()) peaks else List(80) { 30 + ((it * 17) % 55) }
    val safeDur = durationMs.coerceAtLeast(1L)
    val progress = (positionMs.toFloat() / safeDur.toFloat()).coerceIn(0f, 1f)

    val playedColor = MaterialTheme.colorScheme.primary
    val unplayedColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val loopRangeColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.28f)
    val loopPinColor = MaterialTheme.colorScheme.tertiary

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .pointerInput(safeDur) {
                detectTapGestures { offset ->
                    val frac = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    onSeek((frac * safeDur).toLong())
                }
            }
            .pointerInput(safeDur) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    val frac = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    onSeek((frac * safeDur).toLong())
                }
            }
    ) {
        val w = size.width
        val h = size.height
        if (loopAMs != null && loopBMs != null && loopBMs > loopAMs) {
            val ax = (loopAMs.toFloat() / safeDur) * w
            val bx = (loopBMs.toFloat() / safeDur) * w
            drawRect(
                color = loopRangeColor,
                topLeft = Offset(ax, 0f),
                size = Size((bx - ax).coerceAtLeast(4f), h)
            )
        } else if (loopAMs != null) {
            val ax = (loopAMs.toFloat() / safeDur) * w
            drawRect(color = loopPinColor, topLeft = Offset(ax, 0f), size = Size(3.dp.toPx(), h))
        }

        val count = bars.size
        val step = w / count.coerceAtLeast(1)
        val barWidth = (step * 0.58f).coerceIn(2f, 8f)
        val midY = h / 2f

        for (i in 0 until count) {
            val norm = (bars[i] / 100f).coerceIn(0.08f, 1f)
            val barH = (h * 0.84f) * norm
            val x = i * step + (step - barWidth) / 2f
            val barFrac = i.toFloat() / count.toFloat()
            drawRoundRect(
                color = if (barFrac <= progress) playedColor else unplayedColor,
                topLeft = Offset(x, midY - barH / 2f),
                size = Size(barWidth, barH),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }

        // Playhead indicator
        val playX = (progress * w).coerceIn(2f, w - 2f)
        drawRoundRect(
            color = playedColor,
            topLeft = Offset(playX - 1.5.dp.toPx(), 2.dp.toPx()),
            size = Size(3.dp.toPx(), h - 4.dp.toPx()),
            cornerRadius = CornerRadius(4f, 4f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WordLookupSheet(
    token: String,
    result: DictionaryResult?,
    isSaved: Boolean,
    sentenceEn: String?,
    onSpeak: (String) -> Unit,
    onToggleSave: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = result?.word ?: token,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (result != null && result.phonetic.isNotBlank()) {
                        Text(
                            text = "/${result.phonetic}/  ${result.pos}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                FilledTonalIconButton(onClick = { onSpeak(result?.word ?: token) }) {
                    Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = "发音")
                }
            }

            if (result != null) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CefrBadge(result.cefrLevel)
                    result.tags.take(4).forEach { tag ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = tag,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                    if (result.oxford == 1) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "牛津3000",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                    if (result.collins > 0) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.Star,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    text = "柯林斯 ${result.collins}星",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    }
                }

                if (!result.contextMeaningZh.isNullOrBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                text = "本句语境精讲",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = result.contextMeaningZh,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "离线词典释义 (ECDICT)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = result.meaningZh.ifBlank { "暂无中文释义" },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (result.definitionEn.isNotBlank()) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Text(
                                text = result.definitionEn,
                                style = MaterialTheme.typography.bodySmall,
                                fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "离线词库中未检索到 \"$token\"，可能是专有名词或口语连读拼写。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }

            if (!sentenceEn.isNullOrBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = "原声例句语境",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = sentenceEn,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            if (result != null) {
                val btnScale by animateFloatAsState(
                    targetValue = if (isSaved) 1.01f else 1.0f,
                    animationSpec = LingoMotion.expressiveBouncy(),
                    label = "save_btn_scale"
                )
                if (isSaved) {
                    FilledTonalButton(
                        onClick = onToggleSave,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .scale(btnScale),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Rounded.Bookmark, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("已收藏到生词本（点击移出）")
                    }
                } else {
                    Button(
                        onClick = onToggleSave,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .scale(btnScale),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Rounded.BookmarkBorder, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("收藏到生词本 · 关联本句原声")
                    }
                }
            }
        }
    }
}

@Composable
fun TimestampText(ms: Long, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text = formatDurationMs(ms),
        style = MaterialTheme.typography.labelMedium.merge(TabularNumeralStyle),
        color = color,
        modifier = modifier
    )
}
