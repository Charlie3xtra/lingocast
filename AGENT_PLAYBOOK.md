# LingoCast AI Agent Playbook (`AGENT_PLAYBOOK.md`)

本手册面向**任何 AI 编程助手 / Agent**（Antigravity、Claude Code、Codex、Cursor、WorkBuddy 等）。当用户提供一个新视频文件、本地视频文件夹或视频链接时，请严格按以下低 Token、可断点续传的标准作业流程（SOP）将其转化为 LingoCast 安卓端课程包。

---

## 0. 环境约束（必须遵守）

- **项目根目录**：`D:\ccproject\lingocast`
- **Python 解释器**：`D:\ccproject\lingocast\.toolchain\venv\Scripts\python.exe`
- **磁盘约束**：**禁止将模型缓存或临时文件写入 C 盘**。执行命令前务必设置：
  ```powershell
  $env:HF_HOME = 'D:\ccproject\lingocast\.toolchain\hf-cache'
  $env:TEMP = 'D:\ccproject\lingocast\.toolchain\tmp'
  $env:TMP = 'D:\ccproject\lingocast\.toolchain\tmp'
  ```

---

## 1. 接入新视频/目录的标准步骤

### Step 1：获取来源并运行本地预处理 + GPU 转录 + 离线词库
根据用户提供的输入形态执行（假设新专辑 ID 为 `<series_slug>`，工作目录 `W = D:\ccproject\lingocast\data\workspace\<series_slug>`）：

- 若为**本地视频/音频目录**：
  ```powershell
  $py = 'D:\ccproject\lingocast\.toolchain\venv\Scripts\python.exe'
  & $py -m lingocast.cli probe --input "<SOURCE_DIR>" --workspace "<W>" --series "<series_slug>"
  & $py -m lingocast.cli transcribe --workspace "<W>"
  & $py -m lingocast.cli lexicon --workspace "<W>"
  & $py -m lingocast.cli prepare-chunks --workspace "<W>"
  ```
- 若为 **URL（B站 / YouTube / 播客链接）**：
  ```powershell
  & $py -m lingocast.cli ingest --source "<URL>" --series "<series_slug>"
  ```
  > `ingest` 会优先尝试 `yt-dlp --write-subs --write-auto-subs` 拉取原站英文字幕；若原站无字幕或只有机翻，会自动回退至本地 RTX 4070 `faster-whisper` 词级转录，随后完成 `lexicon` 与 `prepare-chunks`。

---

## 2. 使用 Agent 自身模型处理 `.in.tsv`（极省 Token 协议）

`prepare-chunks` 会在 `<W>\chunks\` 生成若干 `ep_XXX_cYY.in.tsv` 文件。不要向模型发送原始 JSON 时间戳，**只需读取简洁 TSV**：

### 输入文件格式 (`ep_XXX_cYY.in.tsv`)
```tsv
# EPISODE: ep_001 | SERIES: talk-podcast | TITLE_HINT: Road Trip | CEFR_HINT: A2 | CHUNK: 1/2 | OCR: ...
0	Welcome back to Talk Podcast!
1	Today we are going on a long road trip.
```

### 你必须生成的输出文件 (`ep_XXX_cYY.out.tsv`)
与输入同目录、将 `.in.tsv` 替换为 `.out.tsv`。**严格遵守以下格式**（不要包 Markdown 代码块围栏）：

1. **逐行一对一中文意译**（绝对不可合并、跳过或漏掉任何 `<id>`）：
   ```tsv
   0	欢迎回到 Talk Podcast！
   1	今天我们要来一场长途自驾游。
   ```
2. **分隔符**：
   ```tsv
   ---NOTES---
   ```
3. **元数据行（仅当 `CHUNK: 1/...` 时必须输出 1 行 `META`）**：
   ```tsv
   META	<简洁英文标题>	<自然中文标题>	<A1|A2|B1|B2|C1|C2>	<2-4个逗号分隔中文标签>	<1-2句课程简介>
   ```
4. **随时间轴绑定的精讲笔记（每块 10–18 条 `NOTE`，精选高价值习语、搭配、雅思高频词或长难句语法）**：
   ```tsv
   NOTE	<sentence_id>	<该句中真实出现的英文词组/短语>	<idiom|collocation|ielts|vocab|grammar>	<B1|B2|C1|C2>	<简明中文释义>	<地道表达解析或雅思提分点拨>
   ```
   示例：
   ```tsv
   NOTE	1	road trip	collocation	A2	自驾游；公路旅行	口语高频搭配，常与 go on a road trip / take a road trip 连用。
   ```

### 省 Token 实战策略
- **首选方案（Antigravity / 支持并行子工作流的 Agent）**：直接定义只读零工具翻译子 Agent（`tsv_translator`，禁用写入与命令行工具，仅通过 `finish` 返回原始 TSV），然后调用现成工作流脚本 [enrich_workflow.py](pipeline/scripts/enrich_workflow.py) 以 `flash_lite` 并发处理每块 `.in.tsv`（校验失败自动回退 `flash` 重试），可在 10 分钟内高质量跑完上百个切片且零脚本作弊风险：
  ```python
  run_workflow(
      ScriptPath=r"D:\ccproject\lingocast\pipeline\scripts\enrich_workflow.py",
      Args={"chunks_dir": r"<W>\chunks", "concurrency": 10}
  )
  ```
- **通用 Agent 方案**：若当前环境无 `run_workflow`，切勿把十几个切片同时塞给一个允许执行命令的 Agent；请按 **单集/单块** 读取 `.in.tsv` 并写入 `.out.tsv`，随后立即执行校验：
  ```powershell
  & $py -m lingocast.cli validate-chunks --workspace "<W>" --episodes ep_001,ep_002
  ```

---

## 3. 合并、校验、打包并提示用户同步

所有 `.out.tsv` 通过校验后，执行最终打包：
```powershell
& $py -m lingocast.cli validate-chunks --workspace "<W>"
& $py -m lingocast.cli merge-enriched --workspace "<W>"
& $py -m lingocast.cli pack --workspace "<W>" --create-lcp
& $py -m lingocast.cli validate
```

打包完成后，告知用户以下任一方式即可导入手机：
- **Wi-Fi 一键同步**：运行 `& $py -m lingocast.cli serve --port 8765`，在手机 App「导入」页填入显示的局域网地址同步增量剧集；
- **`.lcp` 离线包**：直接将 `D:\ccproject\lingocast\data\packs\<series_slug>.lcp` 发给手机并用 LingoCast「导入 .lcp 包」打开；
- **USB ADB**：手机开启 USB 调试后运行 `& $py -m lingocast.cli push`。
