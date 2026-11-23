"""Unit tests for LingoCast pipeline modules."""

from __future__ import annotations

import json
import sqlite3
from pathlib import Path

import pytest

from lingocast.agent_bridge import _locate_phrase_span_ms, merge_enriched_workspace, parse_output_chunk, prepare_chunks
from lingocast.dictionary import build_compact_sqlite, estimate_cefr
from lingocast.lexicon import analyze_episode_lexicon
from lingocast.dictionary import LexiconLookup
from lingocast.packager import build_packs, validate_packs
from lingocast.subtitle_sources import parse_srt_or_vtt
from lingocast.transcribe import resegment_words_to_sentences


def test_resegment_words_to_sentences():
    words = [
        {"w": "Hello", "s": 0, "e": 300},
        {"w": "everyone,", "s": 310, "e": 650},
        {"w": "welcome", "s": 660, "e": 950},
        {"w": "back.", "s": 960, "e": 1200},
        {"w": "Today", "s": 2400, "e": 2700},
        {"w": "we", "s": 2710, "e": 2850},
        {"w": "discuss", "s": 2860, "e": 3200},
        {"w": "road", "s": 3210, "e": 3500},
        {"w": "trips!", "s": 3510, "e": 3900},
    ]
    sents = resegment_words_to_sentences(words)
    assert len(sents) == 2
    assert sents[0]["textEn"] == "Hello everyone, welcome back."
    assert sents[0]["startMs"] == 0
    assert sents[0]["endMs"] == 1200
    assert sents[1]["textEn"] == "Today we discuss road trips!"
    assert sents[1]["id"] == 1


def test_parse_srt():
    srt_content = (
        "1\n00:00:01,000 --> 00:00:03,500\nWelcome to the podcast.\n\n"
        "2\n00:00:04,000 --> 00:00:06,200\nLet's get started right away!\n"
    )
    import tempfile
    with tempfile.TemporaryDirectory() as td:
        p = Path(td) / "sample.srt"
        p.write_text(srt_content, encoding="utf-8")
        sents = parse_srt_or_vtt(p)
        assert len(sents) == 2
        assert sents[0]["startMs"] == 1000
        assert sents[0]["endMs"] == 3500
        assert len(sents[1]["words"]) == 5


def test_dictionary_and_lexicon_and_packager(tmp_path: Path):
    csv_path = tmp_path / "mini_ecdict.csv"
    csv_path.write_text(
        "word,phonetic,definition,translation,pos,collins,oxford,tag,bnc,frq,exchange,detail,audio\n"
        "paralyse,'pærəlaɪz,cause to be unable to move,vt. 使瘫痪；使麻痹,v,2,1,ielts toefl gre,4500,5000,d:paralysed/p:paralysed/i:paralysing,,\n"
        "protest,'prəʊtest,express objection,n. 抗议；v. 反对,n/v,4,1,gk cet4 cet6 ky ielts,1900,2100,p:protested/i:protesting/s:protests,,\n"
        "curiosity,ˌkjʊəriˈɒsəti,desire to know,n. 好奇心,n,3,1,gk cet4 cet6 ielts,3200,3100,s:curiosities,,\n",
        encoding="utf-8",
    )
    db_path = tmp_path / "mini.db"
    stats = build_compact_sqlite(csv_path, db_path)
    assert stats["words"] == 3
    assert stats["lemmas"] >= 5

    lookup = LexiconLookup(db_path)
    try:
        res = lookup.lookup("paralysed")
        assert res is not None
        assert res.word == "paralyse"
        assert "IELTS" in res.tags
    finally:
        lookup.close()

    # Test locate phrase span
    sent = {
        "id": 0,
        "startMs": 1000,
        "endMs": 4000,
        "textEn": "A kind of fascination paralysed my actions.",
        "words": [
            {"w": "A", "s": 1000, "e": 1100},
            {"w": "kind", "s": 1110, "e": 1350},
            {"w": "of", "s": 1360, "e": 1450},
            {"w": "fascination", "s": 1460, "e": 2100},
            {"w": "paralysed", "s": 2110, "e": 2800},
            {"w": "my", "s": 2810, "e": 3000},
            {"w": "actions.", "s": 3010, "e": 3800},
        ],
    }
    s_ms, e_ms = _locate_phrase_span_ms("kind of fascination", sent)
    assert s_ms == 1110 and e_ms == 2100

    # Test parse_output_chunk (including multi-tab LLM echo cleanup)
    out_tsv = tmp_path / "ep_001_c01.out.tsv"
    out_tsv.write_text(
        "```tsv\n"
        "0\tA kind of fascination paralysed my actions.\t一种莫名的着迷使我无法动弹。\n"
        "---NOTES---\n"
        "META\tThe Heat-Ray\t热射线\tB2\t科幻,经典阅读\t讲述火星人热射线的震撼章节。\n"
        "NOTE\t0\tparalysed\tielts\tC1\t使呆若木鸡；使瘫痪\t比 frozen 更具文学画面感。\n"
        "```\n",
        encoding="utf-8",
    )
    parsed = parse_output_chunk(out_tsv, [0])
    assert parsed["valid"] is True
    assert parsed["translations"][0] == "一种莫名的着迷使我无法动弹。"
    assert parsed["meta"]["titleZh"] == "热射线"
    assert len(parsed["notes"]) == 1
    assert parsed["notes"][0]["category"] == "ielts"


def test_estimate_cefr():
    assert estimate_cefr(["ielts"], 7000, 7000, 2, 0) == "C1"
    assert estimate_cefr(["cet4"], 2500, 2500, 3, 1) == "B1"
