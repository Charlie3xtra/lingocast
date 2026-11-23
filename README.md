# LingoCast — 英语原声视频/播客精听精读 Android App 与全自动课程制作工作流

**LingoCast** 是一套完整的本地化英语听读学习系统，包含：
1. **Material 3 Expressive 原生 Android 应用**（Kotlin + Jetpack Compose + Media3 ExoPlayer + Room + ECDICT 离线词库）；
2. **可复用 Python + AI Agent 视频导入与分析工作流**（RTX 4070 GPU 词级时间轴提取 + OCR 题卡识别 + 低 Token TSV 双语精译/雅思精讲 + Wi-Fi / ADB / `.lcp` 一键同步）。

---

## 一、已处理的 56 集雅思/英语播客课程资产（`BV1rq4CeDE1h`）

已从全部 56 个源视频（总时长 **26.72 小时**，保留原画视频 + 无损抽取 AAC 音频 + 240 点音频波形 + 封面）完整提取并校验通过：
- **专辑 1：`talk-podcast`（*Talk Podcast · 分级英语对话精听*）** — **40 集**（A2–B2，通过封面题卡 OCR 精准恢复了真实集名与 CEFR 等级，如 *Road Trip*、*Protest!*、*Planning For The Worst*、*Driving Sales* 等）；
- **专辑 2：`lukes-english-podcast`（*Luke's English Podcast · 英式播客深度精读*）** — **16 集**（B2–C2，纯正英式播客与文学朗读，已从主播开场白恢复真实标题，如 *The Birthday Party*、*A Christmas Carol*、*Sir Gawain and the Green Knight*、*The War of the Worlds Chapter V: The Heat-Ray* 等）。
- **全库统计**：**23,298** 句中英双语字幕、**220,146** 个逐词毫秒时间戳、**2,067** 条随时间轴弹出的习语/搭配/雅思高分精讲笔记、**8,284** 条分级词库索引条目，并内置 **193,928** 词条的离线 ECDICT 词典。

课程数据成品默认生成于本地 `data/packs/`（已由 `.gitignore` 忽略，可通过下方 Python 工作流自行生成或同步）。

---

## 二、Android 端安装与功能亮点

- **安装包下载**：前往 [GitHub Releases](https://github.com/Charlie3xtra/lingocast/releases) 下载 `LingoCast-debug.apk`（Android 8.0+ / API 26+；构建 APK 时可内置离线 ECDICT 词库与精选体验包，仓库源码本身已忽略大型离线词库、课程媒体包与构建产物）。
- **核心学习功能**：
  1. **音视频无缝切换 + 细轨进度条（`SlimTimelineBar`）**：支持基于 `TextureView` 的 16:9 嵌入式原画视频播放（双击 ±5 秒、全屏）与一键切换极简纯音频卡片，并支持后台/锁屏/线控播放。
  2. **词级卡拉 OK 双语字幕**：播放时逐词实时高亮跟踪并自动居中滚动（仅手动拖拽字幕列表时暂停跟随）；支持「中英双语 / 仅英文 / 仅中文 / 盲听模式（点击揭晓）」。
  3. **随时间轴动态变化的实时词栏与学习抽屉**：竖屏播放器采用单行紧致 `CompactTimelineVocabBar` 随时间轴切换当前句词汇提示，并将全集「词汇 / 精讲」标签页收纳进底部工作表 `StudyResourcesSheet`，最大化字幕阅读视口（平板/折叠屏横屏保留双栏直出与 `TimelineVocabStrip`）。
  4. **零延迟即点即查 + 原声语境生词本**：点击字幕中任意英文单词自动暂停并弹出释义卡片（音标、TTS 英/美发音、考纲标签、牛津/柯林斯星级、本句语境释义），一键收藏后自动关联原视频片段，在 **SM-2 记忆闪卡** 中可直接「回听原声语境」。
  5. **精听训练工具箱**：`0.6x–2.0x` 无变调变速、单句循环、A-B 区间复读、跟读录音对比、挖空拼写听写、全库字幕/词汇检索直达播放。
  6. **严格 Material 3 Expressive 规范**：Material You 动态取色、明暗主题切换、高圆角容器、物理弹簧动效、共享元素转场、Android 17 预测性返回（Predictive Back）手势缩放适配，以及基于 `WindowSizeClass` 的手机单栏/平板折叠屏双栏自适应布局。

---

## 三、如何把全套 56 集（或后续新视频）同步到手机

### 方式 A：局域网 Wi-Fi 无线同步（最推荐）
1. 确保手机与电脑处于同一 Wi-Fi 下，在电脑运行：
   ```powershell
   D:\ccproject\lingocast\.toolchain\venv\Scripts\python.exe -m lingocast.cli serve --port 8765
   ```
2. 终端会打印出电脑局域网地址（例如 `http://192.168.31.xxx:8765`）。
3. 打开手机端 **LingoCast → 导入** 页，输入该地址点击 **获取电脑课程目录**，可勾选任意集数及是否同步高清视频画面（取消勾选视频可仅传轻量音频以节省手机空间），点击 **开始同步** 即可。

### 方式 B：USB ADB 高速推送
手机连接电脑并开启 USB 调试后运行：
```powershell
D:\ccproject\lingocast\.toolchain\venv\Scripts\python.exe -m lingocast.cli push
# 若仅需推送音频+字幕+词汇包（跳过 MP4 视频），可加 --audio-only
```
然后在手机端 **LingoCast → 导入 → 扫描 ADB 推送目录** 即可秒速加载。

### 方式 C：`.lcp` 离线包或文件夹导入
将生成好的 `.lcp` 包或专辑目录拷贝至手机，在 App **导入** 页选择 **导入 .lcp 包** 或 **导入课程文件夹**。

---

## 四、后续导入其他新视频的完整工作流

无论是你自己使用，还是把任务交给其他 AI Agent（如 Antigravity / Claude Code / Codex / Cursor / WorkBuddy），均已提供完整标准化入口：
- **流程总览与 CLI 手册**：[WORKFLOW.md](WORKFLOW.md)
- **AI Agent 标准化作业剧本**：[AGENT_PLAYBOOK.md](AGENT_PLAYBOOK.md)
- **Agent Skill 定义**：[SKILL.md](.agent/skills/lingocast-ingest/SKILL.md)
- **低 Token 并行精译工作流脚本**：[enrich_workflow.py](pipeline/scripts/enrich_workflow.py)

一键启动新视频处理：
```powershell
$py = 'D:\ccproject\lingocast\.toolchain\venv\Scripts\python.exe'
& $py -m lingocast.cli ingest --source "你的新视频目录或B站/YouTube链接" --series "new-series-id"
```

---

## 五、项目目录与环境隔离说明

所有依赖、Android SDK、Gradle 缓存、Python 虚拟环境及模型缓存均严格隔离在本地 `.toolchain/` 中，不占用系统盘空间（仓库已默认忽略 `.toolchain/`、`data/`、`dist/` 与大型二进制资产）：
- [android/](android/) — 原生 Kotlin / Jetpack Compose Android 工程
- [pipeline/](pipeline/) — Python 处理管线、JSON Schema、Unit Tests 与构建脚本
- `data/packs/`（gitignored）— 本地生成的成品课程包目录
- `dist/LingoCast-debug.apk`（gitignored）— 本地编译或从 [GitHub Releases](https://github.com/Charlie3xtra/lingocast/releases) 获取的可安装 APK
