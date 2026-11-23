"""Media probing, lossless audio extraction, waveform generation, and visual OCR inspection."""

from __future__ import annotations

import json
import re
import struct
import subprocess
from pathlib import Path
from typing import Any

from .config import TMP_DIR, get_ffmpeg_exe

VIDEO_EXTS = {".mp4", ".mkv", ".mov", ".webm", ".m4v", ".avi"}
AUDIO_EXTS = {".m4a", ".mp3", ".aac", ".wav", ".ogg", ".flac"}

CEFR_MAP = {
    "beginner": "A1",
    "elementary": "A2",
    "pre-intermediate": "A2",
    "pre intermediate": "A2",
    "intermediate": "B1",
    "upper-intermediate": "B2",
    "upper intermediate": "B2",
    "advanced": "C1",
    "proficiency": "C2",
}


def natural_sort_key(path: Path) -> list[Any]:
    return [int(tok) if tok.isdigit() else tok.lower() for tok in re.split(r"(\d+)", path.name)]


def parse_ffmpeg_info(video_path: Path, ffmpeg_exe: str) -> dict[str, Any]:
    """Probe duration, dimensions, codecs, and embedded subtitle streams via ffmpeg -i."""
    proc = subprocess.run(
        [ffmpeg_exe, "-hide_banner", "-i", str(video_path)],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    stderr = proc.stderr
    duration_ms = 0
    m_dur = re.search(r"Duration:\s*(\d+):(\d+):(\d+)\.(\d+)", stderr)
    if m_dur:
        h, m, s, frac = m_dur.groups()
        frac_ms = int((frac + "000")[:3])
        duration_ms = ((int(h) * 3600) + (int(m) * 60) + int(s)) * 1000 + frac_ms

    width, height = 0, 0
    has_video = False
    audio_codec = ""
    embedded_subs: list[str] = []

    for line in stderr.splitlines():
        if "Stream #" in line:
            if "Video:" in line and "attached pic" not in line.lower():
                has_video = True
                m_dim = re.search(r",\s*(\d{2,5})x(\d{2,5})(?:\s|,|\[)", line)
                if m_dim:
                    width, height = int(m_dim.group(1)), int(m_dim.group(2))
            elif "Audio:" in line:
                m_aud = re.search(r"Audio:\s*([a-zA-Z0-9_]+)", line)
                if m_aud:
                    audio_codec = m_aud.group(1).lower()
            elif "Subtitle:" in line:
                embedded_subs.append(line.strip())

    return {
        "durationMs": duration_ms,
        "hasVideo": has_video,
        "width": width,
        "height": height,
        "audioCodec": audio_codec,
        "embeddedSubs": embedded_subs,
    }


def extract_audio_m4a(media_path: Path, out_audio: Path, audio_codec: str, ffmpeg_exe: str) -> None:
    """Extract lossless copy if AAC, otherwise transcode cleanly to 96k AAC m4a."""
    if out_audio.is_file() and out_audio.stat().st_size > 1024:
        return
    out_audio.parent.mkdir(parents=True, exist_ok=True)
    tmp_out = out_audio.with_suffix(".tmp.m4a")
    cmd = [ffmpeg_exe, "-y", "-v", "error", "-i", str(media_path), "-vn"]
    if audio_codec == "aac":
        cmd += ["-c:a", "copy"]
    else:
        cmd += ["-c:a", "aac", "-b:a", "96k"]
    cmd += ["-movflags", "+faststart", str(tmp_out)]
    subprocess.run(cmd, check=True)
    tmp_out.replace(out_audio)


def extract_cover_frame(media_path: Path, out_cover: Path, duration_ms: int, ffmpeg_exe: str) -> bool:
    """Extract clean frame at ~15s (or 10% duration) scaled to max 640px width."""
    if out_cover.is_file() and out_cover.stat().st_size > 1024:
        return True
    out_cover.parent.mkdir(parents=True, exist_ok=True)
    seek_sec = min(15.0, max(1.0, (duration_ms / 1000.0) * 0.1))
    tmp_jpg = out_cover.with_suffix(".tmp.jpg")
    proc = subprocess.run(
        [
            ffmpeg_exe,
            "-y",
            "-v",
            "error",
            "-ss",
            f"{seek_sec:.2f}",
            "-i",
            str(media_path),
            "-frames:v",
            "1",
            "-vf",
            "scale=640:-2",
            "-q:v",
            "3",
            str(tmp_jpg),
        ],
        capture_output=True,
    )
    if proc.returncode == 0 and tmp_jpg.is_file() and tmp_jpg.stat().st_size > 512:
        tmp_jpg.replace(out_cover)
        return True
    if tmp_jpg.exists():
        tmp_jpg.unlink()
    return False


def compute_waveform_peaks(audio_path: Path, ffmpeg_exe: str, num_bars: int = 240) -> list[int]:
    """Stream 8kHz mono PCM from ffmpeg and downsample to normalized [0..100] peak bars."""
    proc = subprocess.run(
        [
            ffmpeg_exe,
            "-v",
            "error",
            "-i",
            str(audio_path),
            "-ac",
            "1",
            "-ar",
            "4000",
            "-f",
            "s16le",
            "-",
        ],
        capture_output=True,
    )
    raw = proc.stdout
    total_samples = len(raw) // 2
    if total_samples < num_bars:
        return [20] * num_bars

    # Fast slicing peak extraction
    samples = struct.unpack(f"<{total_samples}h", raw[: total_samples * 2])
    bucket = max(1, total_samples // num_bars)
    peaks: list[int] = []
    for i in range(num_bars):
        start = i * bucket
        end = min(total_samples, start + bucket)
        chunk = samples[start:end:max(1, (end - start) // 40)]
        peak = max((abs(s) for s in chunk), default=0)
        peaks.append(peak)

    max_peak = max(peaks) or 1
    return [max(6, min(100, int((p / max_peak) * 100))) for p in peaks]


class OcrInspector:
    """Lazy RapidOCR inspector for title slides and burned-in subtitle detection."""

    def __init__(self) -> None:
        from rapidocr_onnxruntime import RapidOCR

        self._ocr = RapidOCR()

    def _read_text(self, image_path: Path) -> list[str]:
        result, _ = self._ocr(str(image_path))
        if not result:
            return []
        return [item[1].strip() for item in result if item and len(item) >= 2 and item[1].strip()]

    def parse_title_lines(self, title_lines: list[str]) -> dict[str, str]:
        joined = " | ".join(title_lines)
        lower_joined = joined.lower()
        lower_compact = re.sub(r"\s+", "", lower_joined)

        series_hint = ""
        cefr_hint = ""
        title_hint = ""
        ep_num_hint = ""

        if "talkpodcast" in lower_compact or ("episode" in lower_compact and "listenus" in lower_compact):
            series_hint = "talk-podcast"
            for line in title_lines:
                ll = line.lower().strip()
                if ll in CEFR_MAP:
                    cefr_hint = CEFR_MAP[ll]
                m_ep = re.search(r"episode\s*/?\s*([0-9a-zA-Z]+)", ll)
                if m_ep:
                    ep_num_hint = m_ep.group(1)

            noise = {
                "talk podcast", "talkpodcast", "listen us", "listenus", "easily",
                "quickly", "english", "english podcast", "englishpodcast",
                "stay update", "stayupdate", "subscribe", "learning podcast",
            }
            candidate_words: list[str] = []
            for line in title_lines:
                ll = line.lower().strip()
                ll_compact = re.sub(r"\s+", "", ll)
                if (
                    "wanttolea" in ll_compact
                    or "rightplace" in ll_compact
                    or "ourcommunity" in ll_compact
                    or "subscribeus" in ll_compact
                    or "videopodcast" in ll_compact
                    or any("\u4e00" <= c <= "\u9fff" for c in line)
                ):
                    break
                if (
                    ll in noise
                    or ll_compact in noise
                    or ll in CEFR_MAP
                    or ll.startswith("episode")
                    or len(ll) <= 1
                ):
                    continue
                if re.match(r"^(that's|and|it's|rightwe|about|iknow|i'mdoing|i'mreally)", ll_compact):
                    break
                candidate_words.append(line.strip())
            if candidate_words:
                title_hint = " ".join(candidate_words[:3])
        elif "luke" in lower_compact and "english" in lower_compact:
            series_hint = "lukes-english-podcast"

        return {
            "seriesHint": series_hint,
            "cefrHint": cefr_hint,
            "episodeNumHint": ep_num_hint,
            "titleHint": title_hint,
        }

    def inspect_episode(self, media_path: Path, cover_path: Path, duration_ms: int, ffmpeg_exe: str) -> dict[str, Any]:
        """Inspect title slide (cover) + mid-video bottom region for burned subtitles."""
        title_lines = self._read_text(cover_path) if cover_path.is_file() else []
        joined = " | ".join(title_lines)
        parsed = self.parse_title_lines(title_lines)
        series_hint = parsed["seriesHint"]
        cefr_hint = parsed["cefrHint"]
        ep_num_hint = parsed["episodeNumHint"]
        title_hint = parsed["titleHint"]

        # Check mid-video bottom 22% for burned-in subtitles
        has_burned_subs = False
        mid_sec = min(240.0, max(30.0, (duration_ms / 1000.0) * 0.35))
        tmp_mid = TMP_DIR / f"ocr_mid_{media_path.stem[:18]}.jpg"
        try:
            proc = subprocess.run(
                [
                    ffmpeg_exe,
                    "-y",
                    "-v",
                    "error",
                    "-ss",
                    f"{mid_sec:.2f}",
                    "-i",
                    str(media_path),
                    "-frames:v",
                    "1",
                    "-vf",
                    "crop=iw:ih*0.24:0:ih*0.76",
                    "-q:v",
                    "4",
                    str(tmp_mid),
                ],
                capture_output=True,
            )
            if proc.returncode == 0 and tmp_mid.is_file():
                sub_lines = self._read_text(tmp_mid)
                alpha_chars = sum(c.isalpha() for ln in sub_lines for c in ln)
                has_burned_subs = alpha_chars >= 10
        finally:
            if tmp_mid.exists():
                tmp_mid.unlink()

        return {
            "seriesHint": series_hint,
            "cefrHint": cefr_hint,
            "episodeNumHint": ep_num_hint,
            "titleHint": title_hint,
            "ocrTitleRaw": joined[:300],
            "hasBurnedSubs": has_burned_subs,
        }


def classify_episode(idx: int, path: Path, ocr_info: dict[str, Any]) -> tuple[str, str]:
    """Assign seriesId and clean fallback English title from OCR + filename patterns."""
    name = path.stem
    series_hint = ocr_info.get("seriesHint") or ""
    if series_hint:
        series_id = series_hint
    elif re.search(r"p\d+\s+\d+\.\d+-E", name) or re.search(r"p([0-3]\d|40)\s", name):
        series_id = "talk-podcast"
    elif re.search(r"p(4[1-9]|5\d|6\d)\s+\d+\.\s+", name):
        series_id = "lukes-english-podcast"
    else:
        # Derive clean slug from parent folder name
        parent = re.sub(r"[^a-zA-Z0-9]+", "-", path.parent.name.lower()).strip("-")
        series_id = parent or "custom-collection"

    title_hint = (ocr_info.get("titleHint") or "").strip()
    if title_hint:
        return series_id, title_hint
    if series_id == "talk-podcast":
        return series_id, f"Talk Podcast Episode {idx:02d}"
    if series_id == "lukes-english-podcast":
        return series_id, f"Luke's English Podcast #{idx - 40 if idx > 40 else idx:02d}"
    clean_name = re.sub(r"^[\d\s\-_]+", "", name).strip() or f"Episode {idx:02d}"
    return series_id, clean_name[:80]


def probe_directory(
    input_dir: Path,
    workspace_dir: Path,
    series_override: str | None = None,
    enable_ocr: bool = True,
) -> dict[str, Any]:
    """Scan all media files, extract audio/cover/waveform, inspect OCR, and save probe_manifest.json."""
    ffmpeg_exe = get_ffmpeg_exe()
    files = sorted(
        [
            p
            for p in input_dir.iterdir()
            if p.is_file() and p.suffix.lower() in (VIDEO_EXTS | AUDIO_EXTS)
        ],
        key=natural_sort_key,
    )
    if not files:
        raise FileNotFoundError(f"No video/audio files found in {input_dir}")

    media_root = workspace_dir / "media"
    media_root.mkdir(parents=True, exist_ok=True)
    manifest_path = workspace_dir / "probe_manifest.json"

    existing_by_name: dict[str, dict[str, Any]] = {}
    if manifest_path.is_file():
        old = json.loads(manifest_path.read_text(encoding="utf-8"))
        for ep in old.get("episodes", []):
            existing_by_name[ep["sourceFilename"]] = ep

    ocr = OcrInspector() if enable_ocr else None
    episodes: list[dict[str, Any]] = []

    for idx, media_path in enumerate(files, start=1):
        ep_id = f"ep_{idx:03d}"
        ep_media_dir = media_root / ep_id
        ep_media_dir.mkdir(parents=True, exist_ok=True)

        cached = existing_by_name.get(media_path.name)
        audio_out = ep_media_dir / "audio.m4a"
        cover_out = ep_media_dir / "cover.jpg"
        waveform_out = ep_media_dir / "waveform.json"

        if (
            cached
            and audio_out.is_file()
            and waveform_out.is_file()
            and (cover_out.is_file() or not cached.get("hasVideo"))
        ):
            episodes.append(cached)
            continue

        info = parse_ffmpeg_info(media_path, ffmpeg_exe)
        extract_audio_m4a(media_path, audio_out, info["audioCodec"], ffmpeg_exe)
        has_cover = (
            extract_cover_frame(media_path, cover_out, info["durationMs"], ffmpeg_exe)
            if info["hasVideo"]
            else False
        )
        peaks = compute_waveform_peaks(audio_out, ffmpeg_exe, num_bars=240)
        waveform_out.write_text(json.dumps(peaks), encoding="utf-8")

        ocr_info = (
            ocr.inspect_episode(media_path, cover_out, info["durationMs"], ffmpeg_exe)
            if (ocr is not None and info["hasVideo"] and has_cover)
            else {
                "seriesHint": "",
                "cefrHint": "",
                "episodeNumHint": "",
                "titleHint": "",
                "ocrTitleRaw": "",
                "hasBurnedSubs": False,
            }
        )
        series_id, inferred_title = classify_episode(idx, media_path, ocr_info)
        if series_override:
            series_id = series_override

        ep_record = {
            "episodeId": ep_id,
            "index": idx,
            "seriesId": series_id,
            "sourcePath": str(media_path),
            "sourceFilename": media_path.name,
            "title": inferred_title,
            "cefrHint": ocr_info.get("cefrHint") or ("B2" if series_id == "lukes-english-podcast" else "B1"),
            "durationMs": info["durationMs"],
            "hasVideo": info["hasVideo"],
            "width": info["width"],
            "height": info["height"],
            "audioCodec": info["audioCodec"],
            "embeddedSubs": info["embeddedSubs"],
            "audioPath": str(audio_out),
            "coverPath": str(cover_out) if has_cover else "",
            "waveformPath": str(waveform_out),
            "ocr": ocr_info,
        }
        episodes.append(ep_record)
        print(
            f"[probe {idx:02d}/{len(files)}] {ep_id} | {series_id} | "
            f"{info['durationMs']//60000}m{(info['durationMs']//1000)%60:02d}s | "
            f"{inferred_title} ({ep_record['cefrHint']})"
        )

    manifest = {
        "inputDir": str(input_dir),
        "workspaceDir": str(workspace_dir),
        "episodeCount": len(episodes),
        "totalDurationMs": sum(e["durationMs"] for e in episodes),
        "episodes": episodes,
    }
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    return manifest
