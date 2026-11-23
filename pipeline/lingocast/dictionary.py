"""Offline ECDICT dictionary builder and high-speed SQLite lookup."""

from __future__ import annotations

import csv
import re
import sqlite3
from dataclasses import dataclass
from pathlib import Path

from simplemma import lemmatize

from .config import ECDICT_CSV, ECDICT_DB

EXAM_TAGS = ("zk", "gk", "cet4", "cet6", "ky", "toefl", "ielts", "gre")

TAG_DISPLAY = {
    "zk": "中考",
    "gk": "高考",
    "cet4": "CET-4",
    "cet6": "CET-6",
    "ky": "考研",
    "toefl": "TOEFL",
    "ielts": "IELTS",
    "gre": "GRE",
}

STOP_WORDS = frozenset(
    {
        "a", "an", "the", "and", "or", "but", "if", "so", "yet", "for", "nor",
        "in", "on", "at", "to", "of", "by", "as", "is", "am", "are", "was", "were",
        "be", "been", "being", "have", "has", "had", "do", "does", "did", "will",
        "would", "shall", "should", "can", "could", "may", "might", "must",
        "i", "you", "he", "she", "it", "we", "they", "me", "him", "her", "us",
        "them", "my", "your", "his", "its", "our", "their", "mine", "yours",
        "this", "that", "these", "those", "who", "whom", "whose", "which", "what",
        "where", "when", "why", "how", "there", "here", "then", "than", "too",
        "very", "just", "only", "also", "even", "now", "not", "no", "yes", "well",
        "oh", "okay", "ok", "yeah", "um", "uh", "like", "know", "think", "get",
        "got", "go", "going", "went", "gone", "come", "came", "see", "saw", "say",
        "said", "make", "made", "take", "took", "give", "gave", "want", "need",
        "one", "two", "three", "first", "more", "most", "some", "any", "all",
        "each", "every", "much", "many", "lot", "lots", "really", "actually",
        "thing", "things", "something", "anything", "everything", "nothing",
        "someone", "anyone", "everyone", "people", "time", "day", "way", "right",
        "good", "great", "little", "big", "new", "old", "back", "up", "down",
        "out", "off", "over", "under", "into", "from", "with", "about", "after",
        "before", "because", "while", "until", "still", "already", "never",
    }
)


@dataclass(slots=True)
class DictEntry:
    word: str
    phonetic: str
    pos: str
    translation: str
    definition: str
    collins: int
    oxford: int
    tags: list[str]
    bnc: int
    frq: int
    cefr_level: str


def strip_word(word: str) -> str:
    return re.sub(r"[^a-zA-Z0-9]", "", word).lower()


def _parse_int(val: str | None, default: int = 0) -> int:
    if not val:
        return default
    try:
        return int(float(val))
    except ValueError:
        return default


def estimate_cefr(tags: list[str], bnc: int, frq: int, collins: int, oxford: int) -> str:
    tag_set = set(tags)
    rank = min([r for r in (bnc, frq) if r > 0], default=0)
    if "zk" in tag_set:
        return "A2"
    if "gk" in tag_set:
        return "A2" if (rank > 0 and rank <= 1200) or collins >= 5 else "B1"
    if "cet4" in tag_set:
        return "B1" if (rank == 0 or rank <= 3500) else "B2"
    if "cet6" in tag_set or "ky" in tag_set or "ielts" in tag_set:
        return "C1" if rank > 6500 else "B2"
    if "toefl" in tag_set:
        return "C1"
    if "gre" in tag_set:
        return "C2"
    if oxford == 1 or collins >= 4:
        return "A2" if (rank > 0 and rank <= 1500) else "B1"
    if rank == 0:
        return "B2"
    if rank <= 1500:
        return "A2"
    if rank <= 3500:
        return "B1"
    if rank <= 7500:
        return "B2"
    if rank <= 18000:
        return "C1"
    return "C2"


def should_keep_entry(word: str, tag: str, oxford: int, collins: int, bnc: int, frq: int, translation: str, phonetic: str = "") -> bool:
    if not word or not translation or len(word) > 48:
        return False
    if " " in word and not tag and collins == 0 and oxford == 0:
        return False
    if any(ord(c) > 127 for c in word):
        return False
    if tag.strip():
        return True
    if oxford == 1 or collins >= 1:
        return True
    if 0 < bnc <= 60000 or 0 < frq <= 60000:
        return True
    if phonetic and " " not in word and (word.endswith(("ly", "ness", "ment", "tion", "able", "ful", "less", "ous")) or len(word) <= 12):
        return True
    return False


def build_compact_sqlite(
    csv_path: Path = ECDICT_CSV,
    db_path: Path = ECDICT_DB,
) -> dict[str, int]:
    """Convert raw ecdict.csv into a fast, compact SQLite database for PC + Android."""
    if not csv_path.is_file():
        raise FileNotFoundError(f"ECDICT CSV not found at {csv_path}")

    db_path.parent.mkdir(parents=True, exist_ok=True)
    tmp_db = db_path.with_suffix(".db.tmp")
    if tmp_db.exists():
        tmp_db.unlink()

    conn = sqlite3.connect(str(tmp_db))
    cur = conn.cursor()
    cur.execute("PRAGMA journal_mode=OFF;")
    cur.execute("PRAGMA synchronous=OFF;")
    cur.execute("PRAGMA page_size=4096;")

    cur.execute(
        """
        CREATE TABLE words (
            word TEXT PRIMARY KEY,
            sw TEXT NOT NULL,
            phonetic TEXT NOT NULL DEFAULT '',
            pos TEXT NOT NULL DEFAULT '',
            translation TEXT NOT NULL DEFAULT '',
            definition TEXT NOT NULL DEFAULT '',
            collins INTEGER NOT NULL DEFAULT 0,
            oxford INTEGER NOT NULL DEFAULT 0,
            tag TEXT NOT NULL DEFAULT '',
            bnc INTEGER NOT NULL DEFAULT 0,
            frq INTEGER NOT NULL DEFAULT 0
        )
        """
    )
    cur.execute(
        """
        CREATE TABLE lemmas (
            form TEXT PRIMARY KEY,
            lemma TEXT NOT NULL
        )
        """
    )

    kept = 0
    lemma_rows: dict[str, str] = {}
    batch: list[tuple] = []

    with csv_path.open("r", encoding="utf-8", errors="replace", newline="") as fh:
        reader = csv.DictReader(fh)
        for row in reader:
            word = (row.get("word") or "").strip()
            translation = (row.get("translation") or "").strip()
            tag = (row.get("tag") or "").strip()
            oxford = _parse_int(row.get("oxford"))
            collins = _parse_int(row.get("collins"))
            bnc = _parse_int(row.get("bnc"))
            frq = _parse_int(row.get("frq"))
            phonetic = (row.get("phonetic") or "").strip()
            if not should_keep_entry(word, tag, oxford, collins, bnc, frq, translation, phonetic):
                continue

            sw = strip_word(word)
            if not sw:
                continue
            pos = (row.get("pos") or "").strip()
            definition = (row.get("definition") or "").strip()
            # Keep definitions concise to minimize mobile DB size
            if len(definition) > 320:
                definition = definition[:317] + "..."
            batch.append((word, sw, phonetic, pos, translation, definition, collins, oxford, tag, bnc, frq))
            kept += 1

            exchange = (row.get("exchange") or "").strip()
            if exchange and (tag or oxford or collins >= 1 or (0 < bnc <= 20000)):
                base_lower = word.lower()
                for part in exchange.split("/"):
                    if ":" not in part:
                        continue
                    kind, target = part.split(":", 1)
                    target_clean = target.strip().lower()
                    if not target_clean or target_clean == base_lower:
                        continue
                    if kind == "0":
                        # Current word is inflected form of target lemma
                        lemma_rows.setdefault(base_lower, target_clean)
                    elif kind in ("p", "d", "i", "3", "r", "t", "s"):
                        lemma_rows.setdefault(target_clean, base_lower)

            if len(batch) >= 5000:
                cur.executemany(
                    "INSERT OR IGNORE INTO words VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    batch,
                )
                batch.clear()

    if batch:
        cur.executemany(
            "INSERT OR IGNORE INTO words VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            batch,
        )

    cur.executemany(
        "INSERT OR IGNORE INTO lemmas VALUES (?,?)",
        lemma_rows.items(),
    )
    cur.execute("CREATE INDEX idx_words_sw ON words(sw);")
    cur.execute("CREATE INDEX idx_words_lower ON words(LOWER(word));")
    conn.commit()
    cur.execute("VACUUM;")
    conn.commit()
    conn.close()

    tmp_db.replace(db_path)
    return {
        "words": kept,
        "lemmas": len(lemma_rows),
        "size_bytes": db_path.stat().st_size,
    }


class LexiconLookup:
    """Fast read-only SQLite ECDICT dictionary lookup with lemmatization."""

    def __init__(self, db_path: Path = ECDICT_DB) -> None:
        if not db_path.is_file():
            raise FileNotFoundError(
                f"Compact dictionary DB not found at {db_path}. Run `lingocast build-dict` first."
            )
        self.db_path = db_path
        self.conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True, check_same_thread=False)
        self.conn.row_factory = sqlite3.Row
        self._cache: dict[str, DictEntry | None] = {}

    def close(self) -> None:
        self.conn.close()

    def resolve_lemma(self, token: str) -> str:
        clean = re.sub(r"^[^a-zA-Z]+|[^a-zA-Z]+$", "", token).lower()
        if not clean:
            return ""
        row = self.conn.execute(
            "SELECT lemma FROM lemmas WHERE form = ? LIMIT 1", (clean,)
        ).fetchone()
        if row:
            return str(row["lemma"])
        try:
            lem = lemmatize(clean, lang="en").lower()
            if lem:
                return lem
        except Exception:
            pass
        return clean

    def lookup(self, token: str) -> DictEntry | None:
        clean = re.sub(r"^[^a-zA-Z'-]+|[^a-zA-Z'-]+$", "", token).lower()
        if not clean or len(clean) < 2:
            return None
        if clean in self._cache:
            return self._cache[clean]

        candidates = []
        lemma = self.resolve_lemma(clean)
        if lemma and lemma != clean:
            candidates.append(lemma)
        candidates.append(clean)
        if clean.endswith("'s") and len(clean) > 3:
            candidates.append(clean[:-2])

        row = None
        for cand in candidates:
            r = self.conn.execute(
                "SELECT * FROM words WHERE LOWER(word) = ? LIMIT 1", (cand,)
            ).fetchone()
            if r is not None:
                if row is None or (not str(row["tag"]).strip() and str(r["tag"]).strip()):
                    row = r
                if str(row["tag"]).strip() and str(row["phonetic"]).strip():
                    break
        if row is None:
            sw = strip_word(lemma or clean)
            if sw:
                row = self.conn.execute(
                    "SELECT * FROM words WHERE sw = ? ORDER BY bnc ASC LIMIT 1", (sw,)
                ).fetchone()

        if row is None:
            self._cache[clean] = None
            return None

        raw_tags = [t for t in str(row["tag"]).split() if t]
        cefr = estimate_cefr(
            raw_tags,
            int(row["bnc"]),
            int(row["frq"]),
            int(row["collins"]),
            int(row["oxford"]),
        )
        entry = DictEntry(
            word=str(row["word"]),
            phonetic=str(row["phonetic"]),
            pos=str(row["pos"]),
            translation=str(row["translation"]).replace("\\n", "\n"),
            definition=str(row["definition"]).replace("\\n", "\n"),
            collins=int(row["collins"]),
            oxford=int(row["oxford"]),
            tags=[TAG_DISPLAY.get(t, t.upper()) for t in raw_tags],
            bnc=int(row["bnc"]),
            frq=int(row["frq"]),
            cefr_level=cefr,
        )
        self._cache[clean] = entry
        return entry
