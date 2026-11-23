package com.lingocast.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lingocast.app.AppContainer
import com.lingocast.app.data.importexport.RemoteCatalogState
import com.lingocast.app.data.importexport.SyncProgress
import com.lingocast.app.data.model.CatalogSeriesSummary
import com.lingocast.app.data.model.EpisodeSummaryJson
import com.lingocast.app.data.prefs.SubtitleDisplayMode
import com.lingocast.app.data.prefs.TtsAccent
import com.lingocast.app.ui.theme.ThemeModePreference
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportCenterScreen(
    container: AppContainer
) {
    val prefs by container.preferences.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var hostInput by remember(prefs.lastSyncHost) { mutableStateOf(prefs.lastSyncHost) }
    var includeVideo by remember { mutableStateOf(true) }
    var remoteCatalog by remember { mutableStateOf<RemoteCatalogState?>(null) }
    val selectedKeys = remember { mutableStateMapOf<String, Boolean>() }
    var syncProgress by remember { mutableStateOf(SyncProgress()) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val openLcpLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                statusMessage = "正在导入 .lcp 课程包..."
                runCatching { container.importer.importLcpZipUri(uri) }
                    .onSuccess { count -> statusMessage = "成功从本地压缩包导入 $count 集课程！" }
                    .onFailure { err -> statusMessage = "导入失败：${err.message}" }
            }
        }
    }

    val openFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            scope.launch {
                statusMessage = "正在导入所选课程文件夹..."
                runCatching { container.importer.importFolderTreeUri(treeUri) }
                    .onSuccess { count -> statusMessage = "成功导入 $count 集课程！" }
                    .onFailure { err -> statusMessage = "导入失败：${err.message}" }
            }
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        item {
            Text("课程导入与个性化", style = MaterialTheme.typography.headlineMedium)
        }

        if (!statusMessage.isNullOrBlank()) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = statusMessage!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        }

        // Section 1: LAN Wi-Fi Sync
        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.CloudDownload, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("局域网 Wi-Fi 高速同步", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        text = "在电脑运行 `lingocast serve --port 8765` 后，输入终端显示的局域网地址即可同步全部 56 集或后续新增视频课程。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = hostInput,
                        onValueChange = { hostInput = it },
                        label = { Text("电脑服务地址 (如 192.168.31.100:8765)") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("同步高清视频画面（取消则仅传音频省空间）", style = MaterialTheme.typography.bodySmall)
                        Switch(checked = includeVideo, onCheckedChange = { includeVideo = it })
                    }
                    Button(
                        onClick = {
                            container.preferences.setLastSyncHost(hostInput)
                            scope.launch {
                                statusMessage = "正在连接 $hostInput..."
                                runCatching { container.importer.fetchRemoteCatalog(hostInput) }
                                    .onSuccess { remote ->
                                        remoteCatalog = remote
                                        selectedKeys.clear()
                                        remote.manifests.forEach { (sid, mf) ->
                                            mf.episodes.forEach { ep ->
                                                val key = "$sid/${ep.episodeId}"
                                                selectedKeys[key] = key !in remote.installedKeys
                                            }
                                        }
                                        statusMessage = "已连接：共 ${remote.catalog.totalEpisodes} 集可同步。"
                                    }
                                    .onFailure { err -> statusMessage = "连接失败：${err.message}" }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("获取电脑课程目录")
                    }

                    if (syncProgress.isRunning) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "${syncProgress.currentTitle} (${syncProgress.completedCount}/${syncProgress.totalCount})",
                                style = MaterialTheme.typography.labelMedium
                            )
                            LinearProgressIndicator(
                                progress = {
                                    if (syncProgress.totalCount > 0) syncProgress.completedCount.toFloat() / syncProgress.totalCount
                                    else 0f
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }

        // Remote series checklist when fetched
        remoteCatalog?.let { remote ->
            item {
                val allPairs = buildSelectedPairs(remote, selectedKeys)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("可同步剧集 (${allPairs.size} 已选)", style = MaterialTheme.typography.titleMedium)
                    Row {
                        TextButton(onClick = {
                            selectedKeys.keys.forEach { k -> selectedKeys[k] = k !in remote.installedKeys }
                        }) { Text("仅选未同步") }
                        Button(
                            enabled = allPairs.isNotEmpty() && !syncProgress.isRunning,
                            onClick = {
                                scope.launch {
                                    runCatching {
                                        container.importer.syncEpisodesFromServer(
                                            remote = remote,
                                            selectedEpisodes = allPairs,
                                            includeVideo = includeVideo,
                                            onProgress = { p -> syncProgress = p }
                                        )
                                    }.onSuccess { cnt ->
                                        statusMessage = "Wi-Fi 同步完成，共更新 $cnt 集！"
                                        remoteCatalog = container.importer.fetchRemoteCatalog(hostInput)
                                    }.onFailure { err ->
                                        syncProgress = SyncProgress()
                                        statusMessage = "同步中断：${err.message}"
                                    }
                                }
                            }
                        ) { Text("开始同步") }
                    }
                }
            }

            remote.catalog.series.forEach { series ->
                val mf = remote.manifests[series.seriesId]
                if (mf != null) {
                    item(key = "remote_hdr_${series.seriesId}") {
                        Text(
                            text = "${series.titleZh} (${mf.episodes.size} 集)",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    items(mf.episodes, key = { "${series.seriesId}/${it.episodeId}" }) { ep ->
                        val k = "${series.seriesId}/${ep.episodeId}"
                        val isInstalled = k in remote.installedKeys
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLowest,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = selectedKeys[k] == true,
                                    onCheckedChange = { checked -> selectedKeys[k] = checked }
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${ep.index}. ${ep.titleZh.ifBlank { ep.title }}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${ep.cefrLevel} · ${(ep.videoSizeBytes / 1024 / 1024)}MB 视频 / ${(ep.audioSizeBytes / 1024 / 1024)}MB 音频" +
                                            if (isInstalled) " · 已在手机中" else "",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Section 2: Local & ADB Imports
        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        Text("本地文件与 USB ADB 导入", style = MaterialTheme.typography.titleMedium)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        FilledTonalButton(
                            onClick = { openLcpLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text("导入 .lcp 包")
                        }
                        FilledTonalButton(
                            onClick = { openFolderLauncher.launch(null) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text("导入课程文件夹")
                        }
                    }
                    FilledTonalButton(
                        onClick = {
                            scope.launch {
                                val count = container.importer.scanAdbPacksDir()
                                statusMessage = if (count > 0) "已从 ADB 目录加载 $count 集课程！" else "ADB 目录下暂无新课程包（运行 lingocast push 推送）。"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Rounded.Usb, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("扫描 ADB 推送目录")
                    }
                }
            }
        }

        // Section 3: Material 3 Theme & Learning Settings
        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                        Text("Material 3 主题与学习偏好", style = MaterialTheme.typography.titleMedium)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Material You 动态取色", style = MaterialTheme.typography.bodyMedium)
                            Text("Android 12+ 随系统壁纸自适应配色", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = prefs.dynamicColor,
                            onCheckedChange = { container.preferences.setDynamicColor(it) }
                        )
                    }
                    Text("明暗模式", style = MaterialTheme.typography.labelLarge)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        listOf(
                            ThemeModePreference.SYSTEM to "跟随系统",
                            ThemeModePreference.LIGHT to "浅色",
                            ThemeModePreference.DARK to "深色"
                        ).forEachIndexed { idx, (mode, label) ->
                            SegmentedButton(
                                selected = prefs.themeMode == mode,
                                onClick = { container.preferences.setThemeMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(index = idx, count = 3)
                            ) { Text(label) }
                        }
                    }

                    HorizontalDivider()
                    Text("查词发音口音 (TTS)", style = MaterialTheme.typography.labelLarge)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        TtsAccent.entries.forEachIndexed { idx, acc ->
                            SegmentedButton(
                                selected = prefs.ttsAccent == acc,
                                onClick = { container.preferences.setTtsAccent(acc) },
                                shape = SegmentedButtonDefaults.itemShape(index = idx, count = TtsAccent.entries.size)
                            ) { Text(acc.labelZh) }
                        }
                    }

                    Text("默认字幕模式", style = MaterialTheme.typography.labelLarge)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SubtitleDisplayMode.entries.forEachIndexed { idx, m ->
                            SegmentedButton(
                                selected = prefs.defaultSubMode == m,
                                onClick = { container.preferences.setDefaultSubMode(m) },
                                shape = SegmentedButtonDefaults.itemShape(index = idx, count = SubtitleDisplayMode.entries.size)
                            ) { Text(m.labelZh, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
            }
        }

        // Section 4: Workflow Quick Guide
        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("后续导入其他视频工作流速查", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "1. 将新视频/链接交给电脑端 LingoCast 流水线或任意 AI Agent：\n" +
                            "   lingocast ingest -s <视频目录或URL> --series <专辑名>\n" +
                            "2. 启动同步服务并在此页一键拉取：\n" +
                            "   lingocast serve --port 8765",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun buildSelectedPairs(
    remote: RemoteCatalogState,
    selectedKeys: Map<String, Boolean>
): List<Pair<CatalogSeriesSummary, EpisodeSummaryJson>> {
    val result = ArrayList<Pair<CatalogSeriesSummary, EpisodeSummaryJson>>()
    for (s in remote.catalog.series) {
        val mf = remote.manifests[s.seriesId] ?: continue
        for (ep in mf.episodes) {
            if (selectedKeys["${s.seriesId}/${ep.episodeId}"] == true) {
                result.add(s to ep)
            }
        }
    }
    return result
}
