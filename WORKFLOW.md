# LingoCast 可复用视频听读导入与分析工作流 (`WORKFLOW.md`)

LingoCast 提供一套标准化的自动化工作流，可将**任意英文视频目录、单个视频、B站/YouTube 链接或本地音频**，批量转化为结构化、带词级时间轴、双语意译、词汇高亮与雅思精讲的安卓端课程包（Course Pack / `.lcp`）。

---

## 一、四级成本最优字幕获取策略 (4-Tier Subtitle Strategy)

工作流会自动按成本最低、精度最高的顺序逐级判定：

| 层级 | 来源 | 适用场景 | 成本与耗时 |
|---|---|---|---|
| **Tier 0** | 本地同名字幕 `.srt`/`.vtt` 或视频内嵌字幕流 (`0:s:0`) | 自带外挂/封装字幕的剧集 | **零 GPU / 零 LLM 转录消耗**，秒级解析 |
| **Tier 1** | `yt-dlp` 拉取平台官方/自动字幕 (`--write-subs --write-auto-subs`) | 直接输入 B站/YouTube URL | 仅网络下载字幕，免本地 ASR |
| **Tier 2** | **RapidOCR 画面标题/硬字幕辅助** | 播客片头题卡（如 *Talk Podcast* 每集开头的集数、CEFR 级别、主题名） | 极低开销（单帧毫秒级），将专有名词作为 `initial_prompt` 注入 ASR，大幅提升专名识别率；同时主动规避低质量机翻硬字幕污染 |
| **Tier 3** | **本地 GPU `faster-whisper` (`large-v3-turbo` + VAD)** | 无可用文本字幕轨道的视频（如 `BV1rq4CeDE1h`） | 本地 RTX 4070 `float16` 高速转录，直接生成**词级起止毫秒时间戳 (`w, s, e`)**，并按句末标点与语义停顿智能断句 |

---

## 二、流水线五阶段 CLI 全览

> 所有命令均应在隔离环境中运行（确保 Python 虚拟环境与缓存位于 `D:\ccproject\lingocast\.toolchain`）：

```powershell
$py = 'D:\ccproject\lingocast\.toolchain\venv\Scripts\python.exe'
```

### Stage 0：环境与离线词典（首次执行）
```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File D:\ccproject\lingocast\pipeline\scripts\bootstrap_env.ps1
& $py -m lingocast.cli build-dict
```
- 从 ECDICT（77 万+词条）精简构建 `data\dict\ecdict_compact.db`（包含 19 万+高频词与词形还原 `lemmas` 表，含音标、考纲标签、牛津/柯林斯星级及中文释义）。

### Stage 1：媒体探测与预处理 (`probe`)
```powershell
& $py -m lingocast.cli probe `
  --input "你的视频目录" `
  --workspace "D:\ccproject\lingocast\data\workspace\<任务名>" `
  [--series "自定义专辑ID"]
```
产出：
- `media/ep_XXX/audio.m4a`（无损抽取音轨，支持手机端息屏纯听）
- `media/ep_XXX/cover.jpg`（自动截取封面帧）
- `media/ep_XXX/waveform.json`（240 点归一化音频波形峰值，用于播放器声波进度条）
- `probe_manifest.json`（含时长、分辨率、OCR 识别的专辑名/集名/CEFR 提示）

### Stage 2：词级时间轴提取 (`transcribe`)
```powershell
& $py -m lingocast.cli transcribe `
  --workspace "D:\ccproject\lingocast\data\workspace\<任务名>" `
  --model large-v3-turbo --batch-size 16
```
- 支持**单集断点续传**，已完成的 `transcripts/ep_XXX.json` 自动跳过。
- 每句保留完整 `words: [{ w, s, e }]` 词级毫秒时间轴，用于手机端卡拉 OK 逐词跟踪与精准点词查词。

### Stage 3：零 Token 确定性词库预分析 (`lexicon`)
```powershell
& $py -m lingocast.cli lexicon --workspace "D:\ccproject\lingocast\data\workspace\<任务名>"
```
- 零外部 API / 零模型 token 消耗。
- 自动对全文单词做词形还原（lemma），匹配 `IELTS / TOEFL / CET-6 / 考研 / GRE / 牛津 3000 / 柯林斯星级` 重点词汇，生成每集的 `vocabList` 与精确 `occurrences` 时间戳。

### Stage 4：紧凑 TSV 协议双语精译与语境精讲 (`prepare-chunks` → Agent/API → `merge-enriched`)
1. 切分低 Token 紧凑文本块（每块约 180 句）：
   ```powershell
   & $py -m lingocast.cli prepare-chunks --workspace "D:\ccproject\lingocast\data\workspace\<任务名>"
   ```
2. 选择任意一种增强路径生成 `chunks/ep_XXX_cYY.out.tsv`：
   - **路径 A（AI Agent 自身模型 — 推荐）**：按 `AGENT_PLAYBOOK.md` 规范，由 Agent 直接读取 `.in.tsv` 并输出 `.out.tsv`。
   - **路径 B（OpenAI / DeepSeek / Gemini 兼容 API）**：
     ```powershell
     & $py -m lingocast.cli enrich-api --workspace "..." --base-url "https://api.deepseek.com/v1" --model "deepseek-chat"
     ```
   - **路径 C（完全离线零成本占位）**：
     ```powershell
     & $py -m lingocast.cli enrich-api --workspace "..." --offline
     ```
3. 校验并合并为标准化课程数据：
   ```powershell
   & $py -m lingocast.cli validate-chunks --workspace "D:\ccproject\lingocast\data\workspace\<任务名>"
   & $py -m lingocast.cli merge-enriched --workspace "D:\ccproject\lingocast\data\workspace\<任务名>"
   ```

### Stage 5：打包、Schema 校验与同步到安卓端 (`pack` / `validate` / `serve` / `push`)
```powershell
& $py -m lingocast.cli pack --workspace "D:\ccproject\lingocast\data\workspace\<任务名>" --create-lcp
& $py -m lingocast.cli validate
```
- 同步至手机（三选一）：
  1. **局域网 Wi-Fi 同步（推荐）**：`& $py -m lingocast.cli serve --port 8765` → 在手机 App「导入」页输入终端提示的局域网地址拉取；
  2. **USB ADB 推送**：`& $py -m lingocast.cli push` → 手机 App 点击「扫描 ADB 推送目录」；
  3. **本地文件导入**：将 `data\packs\<seriesId>.lcp` 拷贝到手机，App 内「导入 .lcp 包」。

---

## 三、一步导入快捷指令 (`ingest`)

对新下载的一组视频或 B站/YouTube 链接，也可以直接运行一键入口：
```powershell
& $py -m lingocast.cli ingest --source "视频目录或URL" --series "my-new-course"
```
当未配置外部 API Key 时，会在 Stage 4 自动切分好紧凑 `.in.tsv` 并提示由 AI Agent 接手精译。
