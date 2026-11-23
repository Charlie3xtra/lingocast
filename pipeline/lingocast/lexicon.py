"""Deterministic zero-LLM-token ECDICT vocabulary extraction and word-level linking."""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

from .dictionary import STOP_WORDS, DictEntry, LexiconLookup


def _is_notable_vocab(token_lower: str, lemma: str, entry: DictEntry) -> bool:
    if len(lemma) < 3 or len(token_lower) < 3:
        return False
    if lemma in STOP_WORDS or token_lower in STOP_WORDS:
        return False
    if re.search(r"\d", lemma):
        return False

    tag_set = set(entry.tags)
    high_exam = {"IELTS", "TOEFL", "CET-6", "考研", "GRE"}
    if tag_set & high_exam:
        return True
    if entry.cefr_level in ("B2", "C1", "C2"):
        return True
    if 1 <= entry.collins <= 3:
        return True
    if entry.bnc > 1800 and entry.cefr_level in ("B1", "B2", "C1", "C2"):
        return True
    return False


def analyze_episode_lexicon(
    transcript_data: dict[str, Any],
    lookup: LexiconLookup,
    max_vocab: int = 160,
) -> dict[str, Any]:
    """Attach lemma + vocabId to tokens and build deduplicated episode vocabList."""
    vocab_map: dict[str, dict[str, Any]] = {}
    sentences = transcript_data["sentences"]

    for sent in sentences:
        sid = sent["id"]
        for tok in sent["words"]:
            raw = tok["w"]
            clean = re.sub(r"^[^a-zA-Z'-]+|[^a-zA-Z'-]+$", "", raw).lower()
            if not clean or "'" in clean and clean.endswith(("'s", "'t", "'re", "'ve", "'ll", "'d", "'m")):
                base = clean.split("'")[0]
                if not base or base in STOP_WORDS:
                    continue
                clean = base

            entry = lookup.lookup(clean)
            if entry is None:
                continue
            lemma = entry.word.lower()
            tok["lemma"] = lemma

            if not _is_notable_vocab(clean, lemma, entry):
                continue

            vocab_id = f"v_{re.sub(r'[^a-z0-9]+', '_', lemma).strip('_')}"
            tok["vocabId"] = vocab_id

            item = vocab_map.get(vocab_id)
            if item is None:
                item = {
                    "id": vocab_id,
                    "word": entry.word,
                    "lemma": lemma,
                    "phonetic": entry.phonetic,
                    "pos": entry.pos,
                    "meaningZh": entry.translation,
                    "contextMeaningZh": None,
                    "cefrLevel": entry.cefr_level,
                    "tags": entry.tags,
                    "collins": entry.collins,
                    "oxford": entry.oxford,
                    "occurrences": [],
                    "_score": _score_vocab(entry),
                }
                vocab_map[vocab_id] = item

            # Deduplicate multiple occurrences inside the same sentence
            if not item["occurrences"] or item["occurrences"][-1]["sentenceId"] != sid:
                item["occurrences"].append(
                    {
                        "sentenceId": sid,
                        "startMs": tok["s"],
                        "endMs": tok["e"],
                    }
                )

    ranked = sorted(
        vocab_map.values(),
        key=lambda x: (x["_score"], len(x["occurrences"])),
        reverse=True,
    )[:max_vocab]
    kept_ids = {v["id"] for v in ranked}

    # Prune vocabId references not kept in top max_vocab
    for sent in sentences:
        for tok in sent["words"]:
            if tok.get("vocabId") and tok["vocabId"] not in kept_ids:
                tok.pop("vocabId", None)

    for item in ranked:
        item.pop("_score", None)

    return {
        "episodeId": transcript_data["episodeId"],
        "subtitleSource": transcript_data["subtitleSource"],
        "sentences": sentences,
        "vocabList": ranked,
    }


def _score_vocab(entry: DictEntry) -> int:
    score = 0
    tags = set(entry.tags)
    if "IELTS" in tags:
        score += 40
    if "TOEFL" in tags:
        score += 25
    if "CET-6" in tags or "考研" in tags:
        score += 20
    if "GRE" in tags:
        score += 15
    if entry.oxford == 1:
        score += 15
    if 1 <= entry.collins <= 3:
        score += 12
    cefr_bonus = {"A2": 2, "B1": 10, "B2": 24, "C1": 30, "C2": 22}.get(entry.cefr_level, 5)
    return score + cefr_bonus


def run_lexicon_workspace(
    workspace_dir: Path,
    episode_filter: set[str] | None = None,
    force: bool = False,
) -> list[Path]:
    """Run deterministic ECDICT vocabulary analysis over transcribed episodes."""
    tr_dir = workspace_dir / "transcripts"
    lex_dir = workspace_dir / "lexicon"
    lex_dir.mkdir(parents=True, exist_ok=True)

    lookup = LexiconLookup()
    outputs: list[Path] = []
    try:
        tr_files = sorted(tr_dir.glob("ep_*.json"))
        for tr_file in tr_files:
            ep_id = tr_file.stem
            if episode_filter and ep_id not in episode_filter:
                continue
            out_path = lex_dir / f"{ep_id}.json"
            if out_path.is_file() and not force:
                outputs.append(out_path)
                continue
            tr_data = json.loads(tr_file.read_text(encoding="utf-8"))
            enriched = analyze_episode_lexicon(tr_data, lookup)
            tmp = out_path.with_suffix(".tmp.json")
            tmp.write_text(json.dumps(enriched, ensure_ascii=False, indent=2), encoding="utf-8")
            tmp.replace(out_path)
            outputs.append(out_path)
    finally:
        lookup.close()

    print(f"[lexicon] Processed {len(outputs)} episodes into {lex_dir}")
    return outputs
