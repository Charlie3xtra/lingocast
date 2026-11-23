"""Optional OpenAI-compatible / DeepSeek / Gemini API enrichment + offline fallback."""

from __future__ import annotations

import json
import os
import re
from pathlib import Path

import httpx

from .dictionary import LexiconLookup

SYSTEM_PROMPT = """You are an expert IELTS & bilingual English podcast study editor.
Given numbered English podcast transcript lines (`id\\ttext`), output STRICTLY in TSV format:
1. First, translate EVERY SINGLE input line 1-to-1 in order:
   <id>\\t<自然、准确、贴合对话语境的简体中文意译>
   (NEVER merge or skip any sentence ID!)
2. Then emit a separator line:
   ---NOTES---
3. If CHUNK is 1/N, emit one META line:
   META\\t<clean English title>\\t<中文标题>\\t<CEFR A2|B1|B2|C1>\\t<2-4 comma-separated Chinese topic tags>\\t<1-2 sentence Chinese summary>
4. Emit 10 to 16 high-value NOTE lines for notable idioms, collocations, IELTS Band 7-9 phrases, or key grammar patterns from this chunk:
   NOTE\\t<sentence_id>\\t<exact English phrase from that sentence>\\t<idiom|collocation|ielts|vocab|grammar>\\t<B1|B2|C1|C2>\\t<简明中文释义>\\t<地道用法/雅思加分点拨>
Output ONLY raw TSV lines. Do NOT wrap in markdown code fences."""


def _offline_fallback_chunk(in_path: Path, lookup: LexiconLookup) -> str:
    """Zero-API fallback: produce dictionary-assisted study notes when no LLM API key is configured."""
    raw_lines = in_path.read_text(encoding="utf-8").splitlines()
    header = raw_lines[0] if raw_lines and raw_lines[0].startswith("#") else ""
    m_title = re.search(r"TITLE_HINT:\s*([^|]+)", header)
    m_cefr = re.search(r"CEFR_HINT:\s*([^|]+)", header)
    m_chunk = re.search(r"CHUNK:\s*(\d+)/(\d+)", header)
    title_hint = (m_title.group(1).strip() if m_title else in_path.stem)
    cefr_hint = (m_cefr.group(1).strip() if m_cefr else "B1")
    is_first = (m_chunk.group(1) == "1") if m_chunk else True

    out_lines: list[str] = []
    notes: list[str] = []

    for ln in raw_lines:
        if not ln.strip() or ln.startswith("#") or "\t" not in ln:
            continue
        sid, text_en = ln.split("\t", 1)
        # Keep English with gloss placeholders cleanly in offline fallback mode
        glosses: list[str] = []
        for tok in text_en.split():
            clean = re.sub(r"^[^a-zA-Z]+|[^a-zA-Z]+$", "", tok)
            if len(clean) < 4:
                continue
            entry = lookup.lookup(clean)
            if entry and entry.cefr_level in ("B2", "C1", "C2"):
                short_zh = entry.translation.split("\n")[0].strip()
                glosses.append(f"{entry.word}: {short_zh}")
                if len(notes) < 12:
                    notes.append(
                        f"NOTE\t{sid}\t{clean}\t{'ielts' if 'IELTS' in entry.tags else 'vocab'}\t"
                        f"{entry.cefr_level}\t{short_zh}\t建议结合原声例句掌握该词汇搭配。"
                    )
        hint_zh = "；".join(glosses[:3]) if glosses else "（本地词库预标注，等待大模型精译）"
        out_lines.append(f"{sid}\t{hint_zh}")

    out_lines.append("---NOTES---")
    if is_first:
        out_lines.append(f"META\t{title_hint}\t{title_hint}\t{cefr_hint}\t英语播客,精听\t{title_hint} 英语原声精听课程。")
    out_lines.extend(notes)
    return "\n".join(out_lines) + "\n"


def enrich_chunks_via_api(
    workspace_dir: Path,
    episode_filter: set[str] | None = None,
    base_url: str | None = None,
    api_key: str | None = None,
    model: str | None = None,
    offline: bool = False,
    force: bool = False,
) -> int:
    """Enrich pending .in.tsv chunks using an OpenAI-compatible API or offline dictionary fallback."""
    chunks_dir = workspace_dir / "chunks"
    in_files = sorted(chunks_dir.glob("ep_*_c*.in.tsv"))

    resolved_key = api_key or os.environ.get("LLM_API_KEY") or os.environ.get("OPENAI_API_KEY") or os.environ.get("DEEPSEEK_API_KEY")
    resolved_base = (base_url or os.environ.get("LLM_BASE_URL") or os.environ.get("OPENAI_BASE_URL") or "https://api.deepseek.com/v1").rstrip("/")
    resolved_model = model or os.environ.get("LLM_MODEL") or "deepseek-chat"

    if not offline and not resolved_key:
        raise RuntimeError(
            "No LLM API key found. Pass --api-key / set LLM_API_KEY in .env, use AI-agent mode, or pass --offline."
        )

    lookup = LexiconLookup() if offline else None
    processed = 0
    try:
        with httpx.Client(timeout=120.0) as client:
            for in_path in in_files:
                ep_id = in_path.name.split("_c")[0]
                if episode_filter and ep_id not in episode_filter:
                    continue
                out_path = Path(str(in_path).replace(".in.tsv", ".out.tsv"))
                if out_path.is_file() and not force:
                    continue

                if offline:
                    assert lookup is not None
                    result_tsv = _offline_fallback_chunk(in_path, lookup)
                else:
                    content = in_path.read_text(encoding="utf-8")
                    resp = client.post(
                        f"{resolved_base}/chat/completions",
                        headers={"Authorization": f"Bearer {resolved_key}", "Content-Type": "application/json"},
                        json={
                            "model": resolved_model,
                            "temperature": 0.2,
                            "messages": [
                                {"role": "system", "content": SYSTEM_PROMPT},
                                {"role": "user", "content": content},
                            ],
                        },
                    )
                    resp.raise_for_status()
                    data = resp.json()
                    result_tsv = data["choices"][0]["message"]["content"].strip() + "\n"

                tmp = out_path.with_suffix(".tmp.tsv")
                tmp.write_text(result_tsv, encoding="utf-8")
                tmp.replace(out_path)
                processed += 1
                print(f"[enrich-api] Wrote {out_path.name}")
    finally:
        if lookup is not None:
            lookup.close()

    return processed
