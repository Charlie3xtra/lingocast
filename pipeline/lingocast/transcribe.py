"""Local NVIDIA RTX 4070 CUDA transcription with word-level timestamps and smart segmentation."""

from __future__ import annotations

import json
import re
import subprocess
import time
from pathlib import Path
from typing import Any

import numpy as np

from .config import get_ffmpeg_exe, register_cuda_dlls, setup_isolated_env
from .subtitle_sources import build_whisper_initial_prompt, find_local_subtitle_sidecar


def decode_audio_ffmpeg(audio_path: Path, sample_rate: int = 16000) -> np.ndarray:
    """Decode audio via FFmpeg to float32 mono 16kHz PCM array (avoids PyAV 19 metadata_errors bug)."""
    ffmpeg_exe = get_ffmpeg_exe()
    proc = subprocess.run(
        [
            ffmpeg_exe,
            "-v",
            "error",
            "-i",
            str(audio_path),
            "-f",
            "f32le",
            "-ac",
            "1",
            "-ar",
            str(sample_rate),
            "-",
        ],
        capture_output=True,
        check=True,
    )
    return np.frombuffer(proc.stdout, dtype=np.float32).copy()


def resegment_words_to_sentences(
    word_tokens: list[dict[str, Any]],
    max_words: int = 22,
    max_duration_ms: int = 12000,
    pause_split_ms: int = 900,
) -> list[dict[str, Any]]:
    """Group word-level tokens into readable, naturally punctuated bilingual subtitle sentences."""
    if not word_tokens:
        return []

    sentences: list[dict[str, Any]] = []
    buf: list[dict[str, Any]] = []

    def flush() -> None:
        if not buf:
            return
        text_en = " ".join(tok["w"] for tok in buf)
        text_en = re.sub(r"\s+([,.;:!?])", r"\1", text_en).strip()
        if not text_en:
            buf.clear()
            return
        start_ms = int(buf[0]["s"])
        end_ms = max(start_ms + 80, int(buf[-1]["e"]))
        sentences.append(
            {
                "id": len(sentences),
                "startMs": start_ms,
                "endMs": end_ms,
                "textEn": text_en,
                "textZh": "",
                "speaker": None,
                "words": [
                    {"w": t["w"], "s": int(t["s"]), "e": max(int(t["s"]), int(t["e"]))}
                    for t in buf
                ],
            }
        )
        buf.clear()

    for tok in word_tokens:
        w = tok["w"].strip()
        if not w:
            continue
        s = max(0, int(tok["s"]))
        e = max(s, int(tok["e"]))
        clean_tok = {"w": w, "s": s, "e": e}

        if buf:
            gap_ms = s - int(buf[-1]["e"])
            elapsed_ms = e - int(buf[0]["s"])
            prev_word = buf[-1]["w"]
            # Split cleanly on acoustic silence gaps or if current buffer is already long at a clause break
            if gap_ms >= pause_split_ms:
                flush()
            elif len(buf) >= max_words or elapsed_ms >= max_duration_ms:
                if prev_word.endswith((",", ";", ":", "—", "-")):
                    flush()
                elif len(buf) >= max_words + 6 or elapsed_ms >= max_duration_ms + 3500:
                    flush()

        buf.append(clean_tok)
        if w.endswith((".", "?", "!")) and not re.match(r"^(Mr|Mrs|Ms|Dr|Prof|St|e\.g|i\.e)\.$", w, re.I):
            if len(buf) >= 3:
                flush()

    flush()
    return sentences


def load_whisper_pipeline(model_name: str = "large-v3-turbo", device: str = "cuda"):
    """Register CUDA DLLs on Windows and load faster-whisper + BatchedInferencePipeline."""
    setup_isolated_env()
    register_cuda_dlls()
    import ctranslate2
    from faster_whisper import BatchedInferencePipeline, WhisperModel

    actual_device = device
    compute_type = "float16"
    if device == "cuda" and ctranslate2.get_cuda_device_count() == 0:
        actual_device = "cpu"
        compute_type = "int8"

    try:
        model = WhisperModel(model_name, device=actual_device, compute_type=compute_type)
        used_model = model_name
    except Exception as exc:
        if model_name != "medium.en":
            print(f"[warn] Failed to load {model_name} ({exc}); falling back to medium.en")
            used_model = "medium.en"
            model = WhisperModel(used_model, device=actual_device, compute_type=compute_type)
        else:
            raise

    batched = BatchedInferencePipeline(model=model)
    return batched, used_model, actual_device


def transcribe_workspace(
    workspace_dir: Path,
    episode_filter: set[str] | None = None,
    model_name: str = "large-v3-turbo",
    batch_size: int = 16,
    force: bool = False,
) -> list[Path]:
    """Transcribe all (or filtered) probed episodes with word-level timestamps and resume checkpointing."""
    manifest = json.loads((workspace_dir / "probe_manifest.json").read_text(encoding="utf-8"))
    out_dir = workspace_dir / "transcripts"
    out_dir.mkdir(parents=True, exist_ok=True)

    episodes = manifest["episodes"]
    if episode_filter:
        episodes = [e for e in episodes if e["episodeId"] in episode_filter]

    pending: list[dict[str, Any]] = []
    outputs: list[Path] = []
    for ep in episodes:
        out_path = out_dir / f"{ep['episodeId']}.json"
        if out_path.is_file() and not force:
            outputs.append(out_path)
        else:
            pending.append(ep)

    if not pending:
        print(f"[transcribe] All {len(episodes)} selected episodes already transcribed.")
        return outputs

    batched_pipeline = None
    used_model = model_name
    actual_device = "cuda"

    total_pending_audio_sec = sum(e["durationMs"] / 1000.0 for e in pending)
    done_audio_sec = 0.0
    wall_start = time.perf_counter()

    for idx, ep in enumerate(pending, start=1):
        ep_id = ep["episodeId"]
        out_path = out_dir / f"{ep_id}.json"
        source_path = Path(ep["sourcePath"])
        audio_path = Path(ep["audioPath"])

        # Tier 0: Check local sidecar or embedded subtitles first
        tier0_sents, tier0_meta = find_local_subtitle_sidecar(source_path, ep, workspace_dir)
        if tier0_sents:
            payload = {
                "episodeId": ep_id,
                "subtitleSource": tier0_meta,
                "sentences": tier0_sents,
            }
            tmp_path = out_path.with_suffix(".tmp.json")
            tmp_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
            tmp_path.replace(out_path)
            outputs.append(out_path)
            print(f"[transcribe {idx}/{len(pending)}] {ep_id} loaded from Tier-0 sidecar ({len(tier0_sents)} sents)")
            continue

        if batched_pipeline is None:
            print(f"[whisper] Initializing model={model_name} batch_size={batch_size}...")
            batched_pipeline, used_model, actual_device = load_whisper_pipeline(model_name=model_name)
            print(f"[whisper] Ready on device={actual_device} model={used_model}")

        t0 = time.perf_counter()
        prompt = build_whisper_initial_prompt(ep)
        audio_np = decode_audio_ffmpeg(audio_path)
        segments, _info = batched_pipeline.transcribe(
            audio_np,
            batch_size=batch_size,
            language="en",
            word_timestamps=True,
            vad_filter=True,
            vad_parameters={"min_silence_duration_ms": 450},
            initial_prompt=prompt,
        )

        raw_words: list[dict[str, Any]] = []
        for seg in segments:
            if getattr(seg, "words", None):
                for w in seg.words:
                    txt = (w.word or "").strip()
                    if not txt:
                        continue
                    raw_words.append(
                        {
                            "w": txt,
                            "s": int(round(float(w.start) * 1000)),
                            "e": int(round(float(w.end) * 1000)),
                        }
                    )
            else:
                txt = (seg.text or "").strip()
                if txt:
                    s_ms = int(round(float(seg.start) * 1000))
                    e_ms = int(round(float(seg.end) * 1000))
                    parts = txt.split()
                    step = max(40, (e_ms - s_ms) // max(1, len(parts)))
                    for pi, p in enumerate(parts):
                        ws = s_ms + pi * step
                        raw_words.append({"w": p, "s": ws, "e": min(e_ms, ws + step)})

        sentences = resegment_words_to_sentences(raw_words)
        payload = {
            "episodeId": ep_id,
            "subtitleSource": {
                "tier": 3,
                "engine": f"faster-whisper:{used_model}:{actual_device}",
                "hasBurnedSubs": bool(ep.get("ocr", {}).get("hasBurnedSubs")),
                "ocrTitleRaw": ep.get("ocr", {}).get("ocrTitleRaw"),
                "referenceUsed": prompt[:140],
            },
            "sentences": sentences,
        }
        tmp_path = out_path.with_suffix(".tmp.json")
        tmp_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        tmp_path.replace(out_path)
        outputs.append(out_path)

        elapsed = time.perf_counter() - t0
        audio_sec = max(1.0, ep["durationMs"] / 1000.0)
        done_audio_sec += audio_sec
        wall_elapsed = time.perf_counter() - wall_start
        overall_rtf = wall_elapsed / max(1.0, done_audio_sec)
        eta_min = ((total_pending_audio_sec - done_audio_sec) * overall_rtf) / 60.0
        print(
            f"[transcribe {idx:02d}/{len(pending)}] {ep_id} | {audio_sec/60:.1f}m audio in {elapsed:.1f}s "
            f"(speed {audio_sec/max(0.1,elapsed):.1f}x) | {len(sentences)} sents | ETA {eta_min:.1f}m",
            flush=True,
        )

    return outputs
