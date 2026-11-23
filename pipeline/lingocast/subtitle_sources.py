"""4-tier cost-smart subtitle discovery: sidecar/embedded -> platform -> OCR hint -> local GPU ASR."""

from __future__ import annotations

import re
import subprocess
from pathlib import Path
from typing import Any

from .config import get_ffmpeg_exe


def _parse_timestamp_ms(ts: str) -> int:
    ts = ts.strip().replace(",", ".")
    parts = ts.split(":")
    if len(parts) == 3:
        h, m, s = parts
    elif len(parts) == 2:
        h = "0"
        m, s = parts
    else:
        return 0
    sec_float = float(s)
    return int(round((int(h) * 3600 + int(m) * 60 + sec_float) * 1000))


def parse_srt_or_vtt(sub_path: Path) -> list[dict[str, Any]]:
    """Parse standard .srt or .vtt subtitle files into sentence dicts with interpolated word timings."""
    text = sub_path.read_text(encoding="utf-8", errors="replace").lstrip("\ufeff")
    text = re.sub(r"^WEBVTT.*?\n\n", "", text, flags=re.DOTALL)
    blocks = re.split(r"\r?\n\s*\r?\n", text.strip())
    sentences: list[dict[str, Any]] = []

    for block in blocks:
        lines = [ln.strip() for ln in block.splitlines() if ln.strip()]
        if not lines:
            continue
        ts_idx = next((i for i, ln in enumerate(lines) if "-->" in ln), -1)
        if ts_idx < 0:
            continue
        ts_line = lines[ts_idx]
        m = re.match(r"([\d:.,]+)\s*-->\s*([\d:.,]+)", ts_line)
        if not m:
            continue
        start_ms = _parse_timestamp_ms(m.group(1))
        end_ms = max(start_ms + 200, _parse_timestamp_ms(m.group(2)))
        body = " ".join(lines[ts_idx + 1 :])
        body = re.sub(r"<[^>]+>", "", body).strip()
        if not body:
            continue

        raw_words = body.split()
        span = max(1, end_ms - start_ms)
        total_chars = sum(len(w) for w in raw_words) or 1
        words: list[dict[str, Any]] = []
        cursor = start_ms
        for w in raw_words:
            dur = max(40, int(round(span * (len(w) / total_chars))))
            w_end = min(end_ms, cursor + dur)
            words.append({"w": w, "s": cursor, "e": max(cursor, w_end)})
            cursor = w_end

        sentences.append(
            {
                "id": len(sentences),
                "startMs": start_ms,
                "endMs": end_ms,
                "textEn": body,
                "textZh": "",
                "speaker": None,
                "words": words,
            }
        )
    return sentences


def find_local_subtitle_sidecar(media_path: Path, ep_meta: dict[str, Any], workspace_dir: Path) -> tuple[list[dict[str, Any]] | None, dict[str, Any]]:
    """Tier 0: check sidecar .srt/.vtt files or embedded MP4 subtitle stream."""
    for ext in (".en.srt", ".eng.srt", ".srt", ".en.vtt", ".vtt"):
        cand = media_path.with_suffix(ext)
        if cand.is_file():
            sents = parse_srt_or_vtt(cand)
            if sents:
                return sents, {
                    "tier": 0,
                    "engine": f"sidecar:{cand.suffix}",
                    "hasBurnedSubs": bool(ep_meta.get("ocr", {}).get("hasBurnedSubs")),
                    "ocrTitleRaw": ep_meta.get("ocr", {}).get("ocrTitleRaw"),
                    "referenceUsed": cand.name,
                }

    embedded = ep_meta.get("embeddedSubs") or []
    if embedded:
        ffmpeg_exe = get_ffmpeg_exe()
        tmp_srt = workspace_dir / "media" / ep_meta["episodeId"] / "embedded.srt"
        proc = subprocess.run(
            [ffmpeg_exe, "-y", "-v", "error", "-i", str(media_path), "-map", "0:s:0", str(tmp_srt)],
            capture_output=True,
        )
        if proc.returncode == 0 and tmp_srt.is_file() and tmp_srt.stat().st_size > 20:
            sents = parse_srt_or_vtt(tmp_srt)
            if sents:
                return sents, {
                    "tier": 0,
                    "engine": "embedded_stream",
                    "hasBurnedSubs": bool(ep_meta.get("ocr", {}).get("hasBurnedSubs")),
                    "ocrTitleRaw": ep_meta.get("ocr", {}).get("ocrTitleRaw"),
                    "referenceUsed": "mp4:stream:s:0",
                }
    return None, {}


def build_whisper_initial_prompt(ep_meta: dict[str, Any]) -> str:
    """Tier 2->3 bridge: supply OCR/series title hints so Whisper nails proper nouns & punctuation."""
    series = ep_meta.get("seriesId", "")
    title = ep_meta.get("title", "")
    cefr = ep_meta.get("cefrHint", "")
    if series == "talk-podcast":
        return (
            f"Welcome to Talk Podcast ({cefr}). Today's topic is {title}. "
            "Natural English conversation podcast with clear punctuation."
        )
    if series == "lukes-english-podcast":
        return (
            "Hello listeners, welcome to Luke's English Podcast with Luke Thompson. "
            "British English podcast episode with natural storytelling and clear punctuation."
        )
    return f"English podcast episode titled {title}. Clear punctuation and natural dialogue."
