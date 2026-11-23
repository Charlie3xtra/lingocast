"""Dynamic run_workflow script for parallel, quota-efficient TSV enrichment of LingoCast chunks."""

from pathlib import Path
import re

VALID_CATS = {"idiom", "collocation", "ielts", "vocab", "grammar"}
BAD_MARKERS = ("等待大模型精译", "本地词库预标注", "【听力翻译】")


def _clean_lines(raw_text: str) -> list[str]:
    text = raw_text.lstrip("\ufeff").strip()
    # Handle JSON-double-encoded string or literal \n / \t escapes from tool args
    if text.startswith('"') and text.endswith('"') and len(text.splitlines()) <= 3:
        import json as _json
        try:
            decoded = _json.loads(text)
            if isinstance(decoded, str):
                text = decoded
        except Exception:
            pass
    if len(text.splitlines()) <= 3 and "\\n" in text:
        text = text.replace("\\r\\n", "\n").replace("\\n", "\n").replace("\\t", "\t")

    out: list[str] = []
    for ln in text.splitlines():
        s = ln.strip()
        if not s or s.startswith("```") or s.startswith("#"):
            continue
        out.append(s)
    return out


def _has_cjk(text: str) -> bool:
    return any("\u4e00" <= c <= "\u9fff" for c in text)


def _parse_input(in_path: Path) -> tuple[str, dict[int, str], bool]:
    raw = in_path.read_text(encoding="utf-8")
    lines = raw.splitlines()
    header = lines[0] if lines and lines[0].startswith("#") else ""
    items: dict[int, str] = {}
    for ln in _clean_lines(raw):
        if "\t" not in ln:
            continue
        k, v = ln.split("\t", 1)
        if k.strip().isdigit():
            items[int(k.strip())] = v.strip()
    is_first = "_c01." in in_path.name
    return header, items, is_first


def _validate_tsv_text(tsv_text: str, expected_items: dict[int, str], is_first: bool) -> tuple[bool, str, str]:
    lines = _clean_lines(tsv_text)
    normalized_lines: list[str] = []
    translations: dict[int, str] = {}
    has_sep = False
    has_meta = False
    note_count = 0
    in_notes = False

    for ln in lines:
        if ln == "---NOTES---" or ln.startswith("---NOTES"):
            has_sep = True
            in_notes = True
            normalized_lines.append("---NOTES---")
            continue
        if not in_notes:
            if "\t" in ln:
                parts = ln.split("\t")
                k = parts[0].strip()
                if k.isdigit() and len(parts) >= 2:
                    val = parts[-1].strip()
                    if len(parts) > 2:
                        cjk_cols = [p.strip() for p in parts[1:] if p.strip() and _has_cjk(p)]
                        if cjk_cols:
                            val = cjk_cols[-1]
                    translations[int(k)] = val
                    ln_norm = f"{k}\t{val}"
                else:
                    ln_norm = ln
            else:
                ln_norm = ln
        else:
            ln_norm = ln
            parts = [p.strip() for p in ln.split("\t")]
            if parts and parts[0].upper() == "META" and len(parts) >= 6:
                has_meta = True
            elif parts and parts[0].upper() == "NOTE" and len(parts) >= 7 and parts[1].isdigit():
                note_count += 1
        normalized_lines.append(ln_norm)

    missing = [sid for sid in expected_items if not translations.get(sid, "").strip()]
    if missing:
        return False, f"Missing {len(missing)} sentence IDs (first few: {missing[:6]})", ""

    for sid, val in translations.items():
        if any(m in val for m in BAD_MARKERS):
            return False, f"Placeholder text detected at ID {sid}", ""

    cjk_count = sum(1 for sid in expected_items if _has_cjk(translations.get(sid, "")))
    cjk_ratio = cjk_count / max(1, len(expected_items))
    if cjk_ratio < 0.70:
        return False, f"Low Chinese translation ratio ({cjk_ratio:.0%}, need >=70%)", ""

    if not has_sep:
        return False, "Missing ---NOTES--- separator", ""
    if is_first and not has_meta:
        return False, "Chunk 1 missing valid 6-column META line after ---NOTES---", ""
    if note_count < 1:
        return False, "No valid 7-column NOTE lines found after ---NOTES---", ""

    normalized = "\n".join(normalized_lines) + "\n"
    return True, f"ok ({len(translations)} lines, {note_count} notes, {cjk_ratio:.0%} CJK)", normalized


chunks_dir = Path(args["chunks_dir"])
episodes_filter = set(args.get("episodes") or [])
concurrency = int(args.get("concurrency") or 10)
primary_model = args.get("model") or "flash_lite"

all_inputs = sorted(chunks_dir.glob("ep_*_c*.in.tsv"))
pending: list[Path] = []
skipped = 0

for in_path in all_inputs:
    ep_id = in_path.name.split("_c")[0]
    if episodes_filter and ep_id not in episodes_filter:
        continue
    out_path = Path(str(in_path).replace(".in.tsv", ".out.tsv"))
    _, exp_items, is_first = _parse_input(in_path)
    if out_path.is_file():
        ok, _, _ = _validate_tsv_text(out_path.read_text(encoding="utf-8", errors="replace"), exp_items, is_first)
        if ok:
            skipped += 1
            continue
    pending.append(in_path)

log(f"Found {len(pending)} pending chunks ({skipped} already valid) in {chunks_dir}")


async def process_one(in_path: Path) -> tuple[str, bool, str]:
    header, exp_items, is_first = _parse_input(in_path)
    raw_in = in_path.read_text(encoding="utf-8")
    out_path = Path(str(in_path).replace(".in.tsv", ".out.tsv"))
    note_target = "3-6" if len(exp_items) < 45 else "10-14"
    meta_req = (
        "Include ONE 6-column META line right after ---NOTES--- (`titleZh` MUST be natural Simplified Chinese, e.g. 公路旅行：自驾游, NOT English)."
        if is_first
        else "Do NOT emit a META line (this is not chunk 1)."
    )

    base_prompt = (
        f"Chunk file: {in_path.name} ({len(exp_items)} sentences).\n"
        f"{meta_req} Emit {note_target} NOTE lines.\n"
        "Return ONLY raw TSV via `finish` tool.\n\n"
        f"{raw_in}"
    )

    # Attempt 1: primary_model (default flash_lite)
    last_err = ""
    for attempt_model in ([primary_model, "flash"] if primary_model != "flash" else ["flash"]):
        try:
            prompt = base_prompt if not last_err else (
                f"RETRY (previous output error: {last_err}). Fix strictly and return complete raw TSV.\n\n{base_prompt}"
            )
            result = await agent(
                prompt=prompt,
                schema={"type": "string"},
                role=f"Translate {in_path.name} ({attempt_model})",
                type_name="tsv_translator",
                model=attempt_model,
            )
            ok, msg, normalized = _validate_tsv_text(str(result), exp_items, is_first)
            if ok:
                tmp = out_path.with_suffix(".tmp.tsv")
                tmp.write_text(normalized, encoding="utf-8")
                tmp.replace(out_path)
                return in_path.name, True, f"{attempt_model}: {msg}"
            last_err = msg
        except Exception as exc:
            last_err = str(exc)

    return in_path.name, False, last_err


done_list: list[str] = []
failed_list: list[tuple[str, str]] = []

for i in range(0, len(pending), concurrency):
    wave = pending[i : i + concurrency]
    phase(f"Wave {i // concurrency + 1} ({len(wave)} chunks)")
    results = await parallel(wave, process_one)
    for fname, ok, info in results:
        if ok:
            done_list.append(fname)
            log(f"OK {fname}: {info}")
        else:
            failed_list.append((fname, info))
            log(f"FAILED {fname}: {info}")

print(f"SUMMARY: done={len(done_list)} skipped={skipped} failed={len(failed_list)}")
if failed_list:
    for fn, err in failed_list:
        print(f"  FAIL {fn}: {err}")
