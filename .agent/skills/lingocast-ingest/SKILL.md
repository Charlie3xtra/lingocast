---
name: lingocast-ingest
description: Ingest new English videos, folders, or URLs into LingoCast Android course packs (.lcp) with word-level timestamps, bilingual subtitles, and timeline-synced IELTS vocabulary notes using minimal tokens.
---

# LingoCast Video-to-Course-Pack Skill

Use this skill whenever the user asks to add new English podcasts, videos, playlists, or URLs into the **LingoCast** Android app (`D:\ccproject\lingocast`).

## Rules & Environment
1. Always read `D:\ccproject\lingocast\AGENT_PLAYBOOK.md` and follow its TSV format strictly.
2. Keep all caches and temporary directories on `D:`:
   ```powershell
   $env:HF_HOME = 'D:\ccproject\lingocast\.toolchain\hf-cache'
   $env:TEMP = 'D:\ccproject\lingocast\.toolchain\tmp'
   $env:TMP = 'D:\ccproject\lingocast\.toolchain\tmp'
   $py = 'D:\ccproject\lingocast\.toolchain\venv\Scripts\python.exe'
   ```
3. Never copy noisy machine-translated burned-in Chinese captions directly from video frames. Use OCR only for title/CEFR hints (`probe` handles this automatically) and generate clean natural Chinese translations via the `.in.tsv` -> `.out.tsv` protocol.

## Execution Steps
1. Run media probing, GPU word-level transcription (or Tier 0/1 subtitle extraction), offline ECDICT lexicon tagging, and chunk export:
   - Local folder:
     `& $py -m lingocast.cli probe -i "<INPUT_DIR>" -w "<WORKSPACE>" --series "<SERIES_ID>"`
     `& $py -m lingocast.cli transcribe -w "<WORKSPACE>"`
     `& $py -m lingocast.cli lexicon -w "<WORKSPACE>"`
     `& $py -m lingocast.cli prepare-chunks -w "<WORKSPACE>"`
   - Web URL:
     `& $py -m lingocast.cli ingest -s "<URL>" --series "<SERIES_ID>"`
2. Translate and annotate each `chunks/ep_XXX_cYY.in.tsv` into `chunks/ep_XXX_cYY.out.tsv`:
   - **Recommended**: Define a zero-tool translator subagent (`EnableWriteTools=False`, `EnableMCPTools=False`, `EnableSubagentTools=False`) and execute `D:\ccproject\lingocast\pipeline\scripts\enrich_workflow.py` via `run_workflow` (runs `flash_lite` per chunk with automatic validation & `flash` retry on failure).
   - Or translate chunk-by-chunk directly when `run_workflow` is unavailable. Ensure 1-to-1 sentence ID parity, >=70% CJK lines, a `---NOTES---` block, `META` on chunk 1, and 10–14 high-value `NOTE` rows per chunk.
3. Validate, merge, pack, and validate schemas:
   ```powershell
   & $py -m lingocast.cli validate-chunks -w "<WORKSPACE>"
   & $py -m lingocast.cli merge-enriched -w "<WORKSPACE>"
   & $py -m lingocast.cli pack -w "<WORKSPACE>" --create-lcp
   & $py -m lingocast.cli validate
   ```
4. Provide the user with `lingocast serve --port 8765` (for wireless App sync) or the generated `.lcp` file under `D:\ccproject\lingocast\data\packs\`.
