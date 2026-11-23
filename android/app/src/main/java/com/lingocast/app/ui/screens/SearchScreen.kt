package com.lingocast.app.ui.screens

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lingocast.app.data.db.SubtitleIndexEntity
import com.lingocast.app.data.repo.CourseRepository
import com.lingocast.app.ui.common.TimestampText
import com.lingocast.app.ui.common.expressivePressScale
import com.lingocast.app.ui.theme.LingoMotion
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(
    repository: CourseRepository,
    onJumpToTimestamp: (episodeKey: String, startMs: Long) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var liveResults by androidx.compose.runtime.remember { mutableStateOf<List<SubtitleIndexEntity>>(emptyList()) }

    LaunchedEffect(query) {
        if (query.trim().length < 2) {
            liveResults = emptyList()
            return@LaunchedEffect
        }
        delay(180)
        liveResults = repository.searchSubtitles(query)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("全库语境检索", style = MaterialTheme.typography.headlineMedium)

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Rounded.Clear, contentDescription = "清空")
                    }
                }
            },
            placeholder = { Text("输入英文单词、短语或中文释义，直接跳听原声...") },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth()
        )

        if (query.trim().length < 2) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("按语境找地道表达", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "例如搜索 \"road trip\"、\"protest\"、\"fascination\" 或任意中文词义，可直达对应视频时间轴片段精听。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else if (liveResults.isEmpty()) {
            Text(
                text = "未找到匹配 \"$query\" 的字幕或词汇记录。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                text = "找到 ${liveResults.size} 处原声语境（点击直接定位播放）",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(liveResults, key = { it.id }) { hit ->
                    val interactionSource = remember { MutableInteractionSource() }
                    Surface(
                        onClick = { onJumpToTimestamp(hit.episodeKey, hit.startMs) },
                        interactionSource = interactionSource,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem(
                                fadeInSpec = LingoMotion.effectsDefault(),
                                placementSpec = LingoMotion.spatialDefault()
                            )
                            .expressivePressScale(interactionSource)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = hit.episodeTitle,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                TimestampText(hit.startMs)
                            }
                            Text(hit.textEn, style = MaterialTheme.typography.bodyLarge)
                            if (hit.textZh.isNotBlank()) {
                                Text(
                                    text = hit.textZh,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
