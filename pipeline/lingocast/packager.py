"""Course pack bundler, JSON schema validator, .lcp archiver, and starter pack builder."""

from __future__ import annotations

import json
import shutil
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from jsonschema import validate as jsonschema_validate

from .config import PACKS_ROOT, SCHEMA_DIR

SERIES_DEFAULTS: dict[str, dict[str, str]] = {
    "talk-podcast": {
        "title": "Talk Podcast: Graded English Dialogues",
        "titleZh": "Talk Podcast · 分级英语对话精听",
        "description": "40集日常高频情景对话播客，从 Elementary 到 Upper-Intermediate 循序渐进，发音清晰、地道表达密集，适合雅思听力与口语日常磨耳朵。",
        "accent": "General / International",
        "cefrRange": "A2–B2",
    },
    "lukes-english-podcast": {
        "title": "Luke's English Podcast: Real British English",
        "titleZh": "Luke's English Podcast · 英式播客深度精读",
        "description": "16集高口碑英式英语原声播客与经典文学有声导读（含《世界大战》原著精读），语料自然地道、词汇丰富，适合雅思进阶精听与英音训练。",
        "accent": "British (RP)",
        "cefrRange": "B2–C2",
    },
}


def _copy_if_needed(src: Path, dst: Path) -> None:
    if not src.is_file():
        return
    if dst.is_file() and dst.stat().st_size == src.stat().st_size:
        return
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, dst)


def build_packs(
    workspace_dir: Path,
    packs_root: Path = PACKS_ROOT,
    create_lcp: bool = False,
) -> dict[str, Any]:
    """Assemble series folders, episode media, manifest.json files, and catalog.json."""
    probe_manifest = json.loads((workspace_dir / "probe_manifest.json").read_text(encoding="utf-8"))
    probe_by_id = {e["episodeId"]: e for e in probe_manifest["episodes"]}

    ep_json_dir = workspace_dir / "episodes"
    ep_files = sorted(ep_json_dir.glob("ep_*.json"))
    if not ep_files:
        raise FileNotFoundError(f"No merged episode JSONs found in {ep_json_dir}")

    ep_schema = json.loads((SCHEMA_DIR / "episode.schema.json").read_text(encoding="utf-8"))
    manifest_schema = json.loads((SCHEMA_DIR / "manifest.schema.json").read_text(encoding="utf-8"))

    by_series: dict[str, list[tuple[dict[str, Any], dict[str, Any]]]] = {}
    for ep_path in ep_files:
        ep_bundle = json.loads(ep_path.read_text(encoding="utf-8"))
        jsonschema_validate(instance=ep_bundle, schema=ep_schema)
        probe_ep = probe_by_id[ep_bundle["episodeId"]]
        by_series.setdefault(ep_bundle["seriesId"], []).append((ep_bundle, probe_ep))

    packs_root.mkdir(parents=True, exist_ok=True)
    now_iso = datetime.now(timezone.utc).isoformat()
    catalog_series: list[dict[str, Any]] = []
    total_episodes = 0
    total_duration_ms = 0

    for series_id, items in by_series.items():
        items.sort(key=lambda pair: pair[0]["index"])
        series_dir = packs_root / series_id
        series_dir.mkdir(parents=True, exist_ok=True)

        meta_default = SERIES_DEFAULTS.get(
            series_id,
            {
                "title": series_id.replace("-", " ").title(),
                "titleZh": f"{series_id.replace('-', ' ').title()} · 英语听读课程",
                "description": "由 LingoCast 工作流导入并解析的双语英语听读课程。",
                "accent": "English",
                "cefrRange": "B1–C1",
            },
        )

        ep_summaries: list[dict[str, Any]] = []
        series_duration_ms = 0

        for ep_bundle, probe_ep in items:
            ep_id = ep_bundle["episodeId"]
            ep_dir = series_dir / ep_id
            ep_dir.mkdir(parents=True, exist_ok=True)

            # Copy episode.json, audio.m4a, cover.jpg, and original video.mp4
            ep_json_dest = ep_dir / "episode.json"
            ep_json_dest.write_text(json.dumps(ep_bundle, ensure_ascii=False, indent=2), encoding="utf-8")

            audio_src = Path(probe_ep["audioPath"])
            audio_dest = ep_dir / "audio.m4a"
            _copy_if_needed(audio_src, audio_dest)

            cover_src = Path(probe_ep["coverPath"]) if probe_ep.get("coverPath") else None
            cover_dest = ep_dir / "cover.jpg"
            if cover_src and cover_src.is_file():
                _copy_if_needed(cover_src, cover_dest)

            video_src = Path(probe_ep["sourcePath"])
            video_dest = ep_dir / "video.mp4"
            video_size = 0
            if ep_bundle["media"]["hasVideo"] and video_src.is_file():
                _copy_if_needed(video_src, video_dest)
                video_size = video_dest.stat().st_size if video_dest.is_file() else 0

            audio_size = audio_dest.stat().st_size if audio_dest.is_file() else 0
            series_duration_ms += ep_bundle["durationMs"]

            ep_summaries.append(
                {
                    "episodeId": ep_id,
                    "index": ep_bundle["index"],
                    "title": ep_bundle["title"],
                    "titleZh": ep_bundle["titleZh"],
                    "cefrLevel": ep_bundle["cefrLevel"],
                    "topicTags": ep_bundle["topicTags"],
                    "summaryZh": ep_bundle["summaryZh"],
                    "durationMs": ep_bundle["durationMs"],
                    "hasVideo": ep_bundle["media"]["hasVideo"],
                    "videoSizeBytes": video_size,
                    "audioSizeBytes": audio_size,
                    "sentenceCount": len(ep_bundle["sentences"]),
                    "vocabCount": len(ep_bundle["vocabList"]),
                    "noteCount": len(ep_bundle["timelineNotes"]),
                    "coverFile": f"{ep_id}/cover.jpg",
                    "episodeJsonFile": f"{ep_id}/episode.json",
                    "videoFile": f"{ep_id}/video.mp4" if ep_bundle["media"]["hasVideo"] else None,
                    "audioFile": f"{ep_id}/audio.m4a",
                }
            )

        manifest = {
            "schemaVersion": 1,
            "seriesId": series_id,
            "title": meta_default["title"],
            "titleZh": meta_default["titleZh"],
            "description": meta_default["description"],
            "accent": meta_default["accent"],
            "cefrRange": meta_default["cefrRange"],
            "coverPath": ep_summaries[0]["coverFile"],
            "episodeCount": len(ep_summaries),
            "totalDurationMs": series_duration_ms,
            "updatedAt": now_iso,
            "episodes": ep_summaries,
        }
        jsonschema_validate(instance=manifest, schema=manifest_schema)
        manifest_path = series_dir / "manifest.json"
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")

        lcp_name = None
        if create_lcp:
            lcp_path = packs_root / f"{series_id}.lcp"
            with zipfile.ZipFile(lcp_path, "w", compression=zipfile.ZIP_STORED) as zf:
                for fpath in sorted(series_dir.rglob("*")):
                    if fpath.is_file():
                        zf.write(fpath, arcname=f"{series_id}/{fpath.relative_to(series_dir).as_posix()}")
            lcp_name = lcp_path.name

        catalog_series.append(
            {
                "seriesId": series_id,
                "title": manifest["title"],
                "titleZh": manifest["titleZh"],
                "description": manifest["description"],
                "accent": manifest["accent"],
                "cefrRange": manifest["cefrRange"],
                "coverPath": f"{series_id}/{manifest['coverPath']}",
                "manifestPath": f"{series_id}/manifest.json",
                "episodeCount": manifest["episodeCount"],
                "totalDurationMs": manifest["totalDurationMs"],
                "lcpFile": lcp_name,
            }
        )
        total_episodes += manifest["episodeCount"]
        total_duration_ms += series_duration_ms

    catalog = {
        "schemaVersion": 1,
        "generatedAt": now_iso,
        "totalEpisodes": total_episodes,
        "totalDurationMs": total_duration_ms,
        "series": catalog_series,
    }
    (packs_root / "catalog.json").write_text(json.dumps(catalog, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"[pack] Built {total_episodes} episodes across {len(catalog_series)} series in {packs_root}")
    return catalog


def validate_packs(packs_root: Path = PACKS_ROOT) -> int:
    """Validate all manifests and episode.json files inside packs_root."""
    ep_schema = json.loads((SCHEMA_DIR / "episode.schema.json").read_text(encoding="utf-8"))
    manifest_schema = json.loads((SCHEMA_DIR / "manifest.schema.json").read_text(encoding="utf-8"))

    catalog_path = packs_root / "catalog.json"
    if not catalog_path.is_file():
        raise FileNotFoundError(f"catalog.json not found in {packs_root}")
    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))

    count = 0
    for s in catalog["series"]:
        manifest_path = packs_root / s["manifestPath"]
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        jsonschema_validate(instance=manifest, schema=manifest_schema)
        series_dir = manifest_path.parent
        for ep in manifest["episodes"]:
            ep_json = json.loads((series_dir / ep["episodeJsonFile"]).read_text(encoding="utf-8"))
            jsonschema_validate(instance=ep_json, schema=ep_schema)
            if not (series_dir / ep["audioFile"]).is_file():
                raise FileNotFoundError(f"Missing audio file: {series_dir / ep['audioFile']}")
            count += 1
    print(f"[validate] All {len(catalog['series'])} series manifests and {count} episode bundles passed schema & asset checks.")
    return count


def build_android_starter_pack(
    packs_root: Path,
    dest_starter_dir: Path,
    picks: list[tuple[str, str, bool]] | None = None,
) -> None:
    """Build lightweight embedded starter_pack for Android APK assets so first launch is richly populated.

    picks: list of (series_id, episode_id, include_video)
    """
    if picks is None:
        picks = [
            ("talk-podcast", "ep_001", True),
            ("lukes-english-podcast", "ep_041", False),
        ]

    if dest_starter_dir.exists():
        shutil.rmtree(dest_starter_dir)
    dest_starter_dir.mkdir(parents=True, exist_ok=True)

    now_iso = datetime.now(timezone.utc).isoformat()
    catalog_series: list[dict[str, Any]] = []
    total_episodes = 0
    total_duration_ms = 0

    for series_id, ep_id, include_video in picks:
        src_series = packs_root / series_id
        src_ep = src_series / ep_id
        if not (src_ep / "episode.json").is_file():
            continue

        src_manifest = json.loads((src_series / "manifest.json").read_text(encoding="utf-8"))
        ep_bundle = json.loads((src_ep / "episode.json").read_text(encoding="utf-8"))

        dst_series = dest_starter_dir / series_id
        dst_ep = dst_series / ep_id
        dst_ep.mkdir(parents=True, exist_ok=True)

        shutil.copy2(src_ep / "audio.m4a", dst_ep / "audio.m4a")
        if (src_ep / "cover.jpg").is_file():
            shutil.copy2(src_ep / "cover.jpg", dst_ep / "cover.jpg")
        video_size = 0
        if include_video and (src_ep / "video.mp4").is_file():
            shutil.copy2(src_ep / "video.mp4", dst_ep / "video.mp4")
            video_size = (dst_ep / "video.mp4").stat().st_size
        else:
            ep_bundle["media"]["hasVideo"] = False
            ep_bundle["media"]["videoFile"] = None

        (dst_ep / "episode.json").write_text(json.dumps(ep_bundle, ensure_ascii=False, indent=2), encoding="utf-8")

        audio_size = (dst_ep / "audio.m4a").stat().st_size
        starter_ep_summary = {
            "episodeId": ep_id,
            "index": ep_bundle["index"],
            "title": ep_bundle["title"],
            "titleZh": ep_bundle["titleZh"],
            "cefrLevel": ep_bundle["cefrLevel"],
            "topicTags": ep_bundle["topicTags"],
            "summaryZh": ep_bundle["summaryZh"],
            "durationMs": ep_bundle["durationMs"],
            "hasVideo": ep_bundle["media"]["hasVideo"],
            "videoSizeBytes": video_size,
            "audioSizeBytes": audio_size,
            "sentenceCount": len(ep_bundle["sentences"]),
            "vocabCount": len(ep_bundle["vocabList"]),
            "noteCount": len(ep_bundle["timelineNotes"]),
            "coverFile": f"{ep_id}/cover.jpg",
            "episodeJsonFile": f"{ep_id}/episode.json",
            "videoFile": f"{ep_id}/video.mp4" if ep_bundle["media"]["hasVideo"] else None,
            "audioFile": f"{ep_id}/audio.m4a",
        }
        starter_manifest = {
            "schemaVersion": 1,
            "seriesId": series_id,
            "title": src_manifest["title"],
            "titleZh": src_manifest["titleZh"],
            "description": src_manifest["description"],
            "accent": src_manifest["accent"],
            "cefrRange": src_manifest["cefrRange"],
            "coverPath": starter_ep_summary["coverFile"],
            "episodeCount": 1,
            "totalDurationMs": ep_bundle["durationMs"],
            "updatedAt": now_iso,
            "episodes": [starter_ep_summary],
        }
        (dst_series / "manifest.json").write_text(
            json.dumps(starter_manifest, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        catalog_series.append(
            {
                "seriesId": series_id,
                "title": starter_manifest["title"],
                "titleZh": starter_manifest["titleZh"],
                "description": starter_manifest["description"],
                "accent": starter_manifest["accent"],
                "cefrRange": starter_manifest["cefrRange"],
                "coverPath": f"{series_id}/{starter_manifest['coverPath']}",
                "manifestPath": f"{series_id}/manifest.json",
                "episodeCount": 1,
                "totalDurationMs": ep_bundle["durationMs"],
                "lcpFile": None,
            }
        )
        total_episodes += 1
        total_duration_ms += ep_bundle["durationMs"]

    (dest_starter_dir / "catalog.json").write_text(
        json.dumps(
            {
                "schemaVersion": 1,
                "generatedAt": now_iso,
                "totalEpisodes": total_episodes,
                "totalDurationMs": total_duration_ms,
                "series": catalog_series,
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    print(f"[starter-pack] Built {total_episodes} starter episodes at {dest_starter_dir}")
