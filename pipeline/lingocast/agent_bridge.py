"""Token-efficient TSV chunk protocol for AI-agent and API enrichment of episodes."""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

from jsonschema import validate as jsonschema_validate

from .config import SCHEMA_DIR
from .dictionary import LexiconLookup

VALID_CATEGORIES = {"idiom", "collocation", "ielts", "vocab", "grammar"}
CATEGORY_ALIAS = {
    "phrase": "collocation",
    "slang": "idiom",
    "expression": "ielts",
    "word": "vocab",
    "vocabulary": "vocab",
    "syntax": "grammar",
    "structure": "grammar",
}


def prepare_chunks(
    workspace_dir: Path,
    episode_filter: set[str] | None = None,
    sentences_per_chunk: int = 180,
    force: bool = False,
) -> dict[str, Any]:
    """Export compact .in.tsv files (~180 sentences per chunk) for low-token translation & analysis."""
    manifest = json.loads((workspace_dir / "probe_manifest.json").read_text(encoding="utf-8"))
    lex_dir = workspace_dir / "lexicon"
    chunks_dir = workspace_dir / "chunks"
    chunks_dir.mkdir(parents=True, exist_ok=True)

    summary_items: list[dict[str, Any]] = []
    total_chunks = 0

    for ep in manifest["episodes"]:
        ep_id = ep["episodeId"]
        if episode_filter and ep_id not in episode_filter:
            continue
        lex_path = lex_dir / f"{ep_id}.json"
        if not lex_path.is_file():
            continue

        lex_data = json.loads(lex_path.read_text(encoding="utf-8"))
        sentences = lex_data["sentences"]
        if not sentences:
            continue

        num_chunks = max(1, (len(sentences) + sentences_per_chunk - 1) // sentences_per_chunk)
        ocr_hint = (ep.get("ocr", {}).get("ocrTitleRaw") or "").replace("\n", " ").strip()
        ep_chunk_files: list[str] = []

        for ci in range(num_chunks):
            chunk_tag = f"c{ci + 1:02d}"
            in_path = chunks_dir / f"{ep_id}_{chunk_tag}.in.tsv"
            ep_chunk_files.append(in_path.name)
            total_chunks += 1
            if in_path.is_file() and not force:
                continue

            slice_sents = sentences[ci * sentences_per_chunk : (ci + 1) * sentences_per_chunk]
            header = (
                f"# EPISODE: {ep_id} | SERIES: {ep['seriesId']} | "
                f"TITLE_HINT: {ep['title']} | CEFR_HINT: {ep['cefrHint']} | "
                f"CHUNK: {ci + 1}/{num_chunks} | OCR: {ocr_hint[:140]}"
            )
            lines = [header]
            for s in slice_sents:
                clean_text = s["textEn"].replace("\t", " ").replace("\r", " ").replace("\n", " ").strip()
                lines.append(f"{s['id']}\t{clean_text}")
            in_path.write_text("\n".join(lines) + "\n", encoding="utf-8")

        summary_items.append(
            {
                "episodeId": ep_id,
                "seriesId": ep["seriesId"],
                "sentenceCount": len(sentences),
                "chunkCount": num_chunks,
                "chunks": ep_chunk_files,
            }
        )

    index_path = chunks_dir / "chunks_index.json"
    index_path.write_text(
        json.dumps({"totalChunks": total_chunks, "episodes": summary_items}, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print(f"[prepare-chunks] Prepared {total_chunks} chunks across {len(summary_items)} episodes in {chunks_dir}")
    return {"totalChunks": total_chunks, "episodes": summary_items}


def _clean_tsv_lines(raw_text: str) -> list[str]:
    lines: list[str] = []
    for line in raw_text.lstrip("\ufeff").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("```") or stripped.startswith("#"):
            continue
        lines.append(stripped)
    return lines


def _parse_input_lines(in_path: Path) -> dict[int, str]:
    items: dict[int, str] = {}
    for line in _clean_tsv_lines(in_path.read_text(encoding="utf-8")):
        if "\t" not in line:
            continue
        id_part, text_en = line.split("\t", 1)
        if id_part.strip().isdigit():
            items[int(id_part.strip())] = text_en.strip()
    return items


def _has_cjk(text: str) -> bool:
    return any("\u4e00" <= ch <= "\u9fff" for ch in text)


def parse_output_chunk(out_path: Path, expected_items: list[int] | dict[int, str]) -> dict[str, Any]:
    """Parse and validate a single .out.tsv file against expected sentence IDs and Chinese translation quality."""
    if isinstance(expected_items, dict):
        expected_ids = list(expected_items.keys())
        source_by_id = expected_items
    else:
        expected_ids = list(expected_items)
        source_by_id = {}

    raw = out_path.read_text(encoding="utf-8", errors="replace")
    lines = _clean_tsv_lines(raw)

    translations: dict[int, str] = {}
    meta: dict[str, Any] | None = None
    notes: list[dict[str, Any]] = []
    in_notes_section = False

    for line in lines:
        if line == "---NOTES---" or line.startswith("---NOTES"):
            in_notes_section = True
            continue

        if not in_notes_section:
            parts = line.split("\t")
            if len(parts) >= 2 and parts[0].strip().isdigit():
                sid = int(parts[0].strip())
                # Handle occasional <id>\t<En>\t<Zh> or <id>\t<En>\t<id>\t<Zh> echoes from LLMs
                candidate = parts[-1].strip()
                if len(parts) > 2:
                    cjk_cols = [p.strip() for p in parts[1:] if p.strip() and _has_cjk(p)]
                    if cjk_cols:
                        candidate = cjk_cols[-1]
                translations[sid] = candidate
            continue

        parts = [p.strip() for p in line.split("\t")]
        if not parts:
            continue
        tag = parts[0].upper()
        if tag == "META" and len(parts) >= 6:
            meta = {
                "title": parts[1],
                "titleZh": parts[2],
                "cefrLevel": parts[3].upper() if parts[3] else "B1",
                "topicTags": [t.strip() for t in re.split(r"[,，/|]", parts[4]) if t.strip()],
                "summaryZh": parts[5],
            }
        elif tag == "NOTE" and len(parts) >= 7:
            sid_str = parts[1]
            if not sid_str.isdigit():
                continue
            raw_cat = parts[3].lower()
            cat = raw_cat if raw_cat in VALID_CATEGORIES else CATEGORY_ALIAS.get(raw_cat, "ielts")
            notes.append(
                {
                    "sentenceId": int(sid_str),
                    "phrase": parts[2],
                    "category": cat,
                    "cefrLevel": parts[4].upper() if parts[4] else "B2",
                    "meaningZh": parts[5],
                    "usageTipZh": parts[6],
                }
            )

    missing_ids = [sid for sid in expected_ids if sid not in translations or not translations[sid].strip()]
    placeholder_hits = [
        sid
        for sid, val in translations.items()
        if any(bad in val for bad in ("等待大模型精译", "本地词库预标注", "【听力翻译】"))
    ]
    non_cjk_bad: list[int] = []
    for sid, val in translations.items():
        src = source_by_id.get(sid, "")
        if not _has_cjk(val) and (not src or len(src) >= 12):
            non_cjk_bad.append(sid)

    cjk_ratio = 1.0
    if translations:
        cjk_ratio = sum(1 for v in translations.values() if _has_cjk(v)) / len(translations)

    valid = (
        len(missing_ids) == 0
        and len(placeholder_hits) == 0
        and cjk_ratio >= 0.70
    )
    return {
        "valid": valid,
        "missingIds": missing_ids,
        "placeholderHits": placeholder_hits,
        "cjkRatio": cjk_ratio,
        "translatedCount": len(translations),
        "expectedCount": len(expected_ids),
        "translations": translations,
        "meta": meta,
        "notes": notes,
    }


def validate_chunks_workspace(
    workspace_dir: Path,
    episode_filter: set[str] | None = None,
) -> dict[str, Any]:
    """Validate that all .in.tsv chunks have matching valid .out.tsv files."""
    chunks_dir = workspace_dir / "chunks"
    in_files = sorted(chunks_dir.glob("ep_*_c*.in.tsv"))
    errors: list[str] = []
    valid_count = 0
    total_notes = 0

    for in_path in in_files:
        ep_id = in_path.name.split("_c")[0]
        if episode_filter and ep_id not in episode_filter:
            continue
        expected_items = _parse_input_lines(in_path)
        out_path = Path(str(in_path).replace(".in.tsv", ".out.tsv"))
        if not out_path.is_file():
            errors.append(f"MISSING {out_path.name}")
            continue
        parsed = parse_output_chunk(out_path, expected_items)
        if not parsed["valid"]:
            if parsed["placeholderHits"]:
                errors.append(f"PLACEHOLDER {out_path.name}: {len(parsed['placeholderHits'])} placeholder lines")
            elif parsed["cjkRatio"] < 0.70:
                errors.append(f"LOW_CJK {out_path.name}: only {parsed['cjkRatio']*100:.0f}% lines have Chinese")
            else:
                sample = parsed["missingIds"][:6]
                errors.append(
                    f"INCOMPLETE {out_path.name}: {parsed['translatedCount']}/{parsed['expectedCount']} "
                    f"(missing IDs e.g. {sample})"
                )
            continue
        valid_count += 1
        total_notes += len(parsed["notes"])

    report = {
        "validChunks": valid_count,
        "errorCount": len(errors),
        "totalNotes": total_notes,
        "errors": errors,
    }
    if errors:
        print(f"[validate-chunks] FAILED ({len(errors)} issues, {valid_count} valid):")
        for err in errors[:30]:
            print("  -", err)
    else:
        print(f"[validate-chunks] OK: {valid_count} chunks valid, {total_notes} timeline notes.")
    return report


def _locate_phrase_span_ms(phrase: str, sentence: dict[str, Any]) -> tuple[int, int]:
    """Align a note phrase onto word-level timestamps inside the target sentence."""
    words = sentence.get("words") or []
    if not words:
        return int(sentence["startMs"]), int(sentence["endMs"])

    phrase_tokens = [re.sub(r"[^a-z0-9]", "", t.lower()) for t in phrase.split() if re.sub(r"[^a-z0-9]", "", t.lower())]
    if not phrase_tokens:
        return int(sentence["startMs"]), int(sentence["endMs"])

    sent_tokens = [re.sub(r"[^a-z0-9]", "", w["w"].lower()) for w in words]
    n = len(phrase_tokens)
    for i in range(max(0, len(sent_tokens) - n + 1)):
        if sent_tokens[i : i + n] == phrase_tokens:
            return int(words[i]["s"]), int(words[i + n - 1]["e"])

    # Fallback: match single main token
    for tok in phrase_tokens:
        if len(tok) >= 4 and tok in sent_tokens:
            idx = sent_tokens.index(tok)
            return int(words[idx]["s"]), int(words[idx]["e"])

    return int(sentence["startMs"]), int(sentence["endMs"])


def merge_enriched_workspace(
    workspace_dir: Path,
    episode_filter: set[str] | None = None,
    allow_untranslated: bool = False,
) -> list[Path]:
    """Merge probe metadata + lexicon + agent .out.tsv into schema-validated episode.json files."""
    manifest = json.loads((workspace_dir / "probe_manifest.json").read_text(encoding="utf-8"))
    lex_dir = workspace_dir / "lexicon"
    chunks_dir = workspace_dir / "chunks"
    ep_out_dir = workspace_dir / "episodes"
    ep_out_dir.mkdir(parents=True, exist_ok=True)
    ep_schema = json.loads((SCHEMA_DIR / "episode.schema.json").read_text(encoding="utf-8"))

    lookup = LexiconLookup()
    written: list[Path] = []
    try:
        for ep in manifest["episodes"]:
            ep_id = ep["episodeId"]
            if episode_filter and ep_id not in episode_filter:
                continue

            lex_path = lex_dir / f"{ep_id}.json"
            if not lex_path.is_file():
                continue
            lex_data = json.loads(lex_path.read_text(encoding="utf-8"))
            sentences: list[dict[str, Any]] = lex_data["sentences"]
            sent_by_id = {s["id"]: s for s in sentences}

            in_files = sorted(chunks_dir.glob(f"{ep_id}_c*.in.tsv"))
            merged_translations: dict[int, str] = {}
            merged_meta: dict[str, Any] | None = None
            raw_notes: list[dict[str, Any]] = []

            for in_path in in_files:
                out_path = Path(str(in_path).replace(".in.tsv", ".out.tsv"))
                if not out_path.is_file():
                    if allow_untranslated:
                        continue
                    raise FileNotFoundError(f"Missing enriched output chunk: {out_path}")
                parsed = parse_output_chunk(out_path, _parse_input_lines(in_path))
                if not parsed["valid"] and not allow_untranslated:
                    raise ValueError(f"Incomplete enriched output chunk {out_path}: missing {parsed['missingIds'][:5]}")
                merged_translations.update(parsed["translations"])
                if merged_meta is None and parsed["meta"]:
                    merged_meta = parsed["meta"]
                raw_notes.extend(parsed["notes"])

            for s in sentences:
                s["textZh"] = merged_translations.get(s["id"], s.get("textZh", ""))

            timeline_notes: list[dict[str, Any]] = []
            seen_notes: set[tuple[int, str]] = set()
            for idx_note, note in enumerate(raw_notes):
                sid = note["sentenceId"]
                sent = sent_by_id.get(sid)
                if sent is None:
                    continue
                key = (sid, note["phrase"].lower())
                if key in seen_notes:
                    continue
                seen_notes.add(key)

                s_ms, e_ms = _locate_phrase_span_ms(note["phrase"], sent)
                dict_entry = lookup.lookup(note["phrase"]) if " " not in note["phrase"] else None
                timeline_notes.append(
                    {
                        "id": f"n_{ep_id}_{len(timeline_notes):03d}",
                        "sentenceId": sid,
                        "startMs": s_ms,
                        "endMs": max(s_ms, e_ms),
                        "phrase": note["phrase"],
                        "category": note["category"],
                        "cefrLevel": note["cefrLevel"],
                        "phonetic": dict_entry.phonetic if dict_entry and dict_entry.phonetic else None,
                        "meaningZh": note["meaningZh"],
                        "usageTipZh": note["usageTipZh"],
                    }
                )

            timeline_notes.sort(key=lambda x: (x["sentenceId"], x["startMs"]))

            # Enrich vocabList contextMeaningZh from matching timeline notes when available
            note_by_phrase = {n["phrase"].lower(): n["meaningZh"] for n in timeline_notes}
            for v in lex_data["vocabList"]:
                ctx = note_by_phrase.get(v["word"].lower()) or note_by_phrase.get(v["lemma"].lower())
                if ctx:
                    v["contextMeaningZh"] = ctx

            peaks_path = Path(ep["waveformPath"])
            waveform_peaks = json.loads(peaks_path.read_text(encoding="utf-8")) if peaks_path.is_file() else [25] * 240

            meta = merged_meta or {}
            title_en = (meta.get("title") or ep["title"]).strip()
            title_zh = (meta.get("titleZh") or title_en).strip()
            cefr = (meta.get("cefrLevel") or ep.get("cefrHint") or "B1").strip()
            if cefr not in ("A1", "A2", "B1", "B2", "C1", "C2"):
                cefr = ep.get("cefrHint") or "B1"
            tags = meta.get("topicTags") or (["雅思对话", "日常情景"] if ep["seriesId"] == "talk-podcast" else ["英式播客", "精听进阶"])
            summary_zh = (meta.get("summaryZh") or f"{title_zh}（{title_en}）双语精听精读课程。").strip()

            episode_bundle = {
                "schemaVersion": 1,
                "episodeId": ep_id,
                "seriesId": ep["seriesId"],
                "index": ep["index"],
                "sourceFilename": ep["sourceFilename"],
                "title": title_en,
                "titleZh": title_zh,
                "cefrLevel": cefr,
                "topicTags": tags[:5],
                "summaryZh": summary_zh,
                "durationMs": max(1, int(ep["durationMs"])),
                "media": {
                    "videoFile": "video.mp4" if ep["hasVideo"] else None,
                    "audioFile": "audio.m4a",
                    "coverFile": "cover.jpg",
                    "hasVideo": bool(ep["hasVideo"]),
                    "width": int(ep.get("width") or 0),
                    "height": int(ep.get("height") or 0),
                    "waveformPeaks": waveform_peaks,
                },
                "subtitleSource": lex_data["subtitleSource"],
                "sentences": sentences,
                "timelineNotes": timeline_notes,
                "vocabList": lex_data["vocabList"],
            }
            jsonschema_validate(instance=episode_bundle, schema=ep_schema)
            dest = ep_out_dir / f"{ep_id}.json"
            tmp = dest.with_suffix(".tmp.json")
            tmp.write_text(json.dumps(episode_bundle, ensure_ascii=False, indent=2), encoding="utf-8")
            tmp.replace(dest)
            written.append(dest)
    finally:
        lookup.close()

    print(f"[merge-enriched] Merged & validated {len(written)} episode JSONs in {ep_out_dir}")
    return written
