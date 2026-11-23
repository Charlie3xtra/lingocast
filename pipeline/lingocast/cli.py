"""Typer CLI entry point for LingoCast video-to-course-pack workflow."""

from __future__ import annotations

import subprocess
from pathlib import Path
from typing import Optional

import typer

from .adb_push import push_packs_via_adb
from .agent_bridge import merge_enriched_workspace, prepare_chunks, validate_chunks_workspace
from .config import ECDICT_CSV, ECDICT_DB, PACKS_ROOT, ROOT_DIR, WORKSPACE_ROOT, setup_isolated_env
from .dictionary import LexiconLookup, build_compact_sqlite
from .enrich_api import enrich_chunks_via_api
from .lexicon import run_lexicon_workspace
from .packager import build_android_starter_pack, build_packs, validate_packs
from .probe import probe_directory
from .server import serve_packs
from .transcribe import transcribe_workspace

app = typer.Typer(
    name="lingocast",
    help="LingoCast: Extract bilingual subtitles, word timelines & IELTS vocabulary packs from English videos.",
    add_completion=False,
)


def _parse_ep_filter(episodes: str | None) -> set[str] | None:
    if not episodes:
        return None
    result: set[str] = set()
    for part in episodes.split(","):
        p = part.strip()
        if not p:
            continue
        if p.isdigit():
            result.add(f"ep_{int(p):03d}")
        else:
            result.add(p)
    return result or None


@app.command("build-dict")
def cmd_build_dict(
    csv_path: Path = typer.Option(ECDICT_CSV, help="Path to raw ecdict.csv"),
    db_path: Path = typer.Option(ECDICT_DB, help="Destination compact SQLite DB"),
) -> None:
    """Build compact offline ECDICT SQLite database."""
    stats = build_compact_sqlite(csv_path=csv_path, db_path=db_path)
    print(f"[build-dict] Built {db_path} | words={stats['words']} | lemmas={stats['lemmas']} | size={stats['size_bytes']/1024/1024:.2f} MB")


@app.command("lookup-test")
def cmd_lookup_test(words: list[str] = typer.Argument(...)) -> None:
    """Quick CLI test for offline ECDICT lookup & lemmatization."""
    lookup = LexiconLookup()
    try:
        for w in words:
            res = lookup.lookup(w)
            if res is None:
                print(f"{w}: NOT FOUND")
            else:
                print(f"{w} -> {res.word} /{res.phonetic}/ [{res.cefr_level}] {res.tags} :: {res.translation.splitlines()[0]}")
    finally:
        lookup.close()


@app.command("probe")
def cmd_probe(
    input_dir: Path = typer.Option(..., "--input", "-i", help="Directory containing source video/audio files"),
    workspace: Path = typer.Option(..., "--workspace", "-w", help="Workspace directory for intermediate files"),
    series: Optional[str] = typer.Option(None, "--series", help="Optional explicit seriesId override"),
    no_ocr: bool = typer.Option(False, "--no-ocr", help="Skip RapidOCR title/subtitle inspection"),
) -> None:
    """Probe media files, extract audio/covers/waveforms, and detect series/titles via OCR."""
    setup_isolated_env()
    manifest = probe_directory(input_dir, workspace, series_override=series, enable_ocr=not no_ocr)
    print(f"[probe] Done: {manifest['episodeCount']} episodes, {manifest['totalDurationMs']/3600000:.2f} hours.")


@app.command("transcribe")
def cmd_transcribe(
    workspace: Path = typer.Option(..., "--workspace", "-w", help="Workspace directory"),
    episodes: Optional[str] = typer.Option(None, "--episodes", help="Comma-separated episode IDs (e.g. ep_001,ep_002)"),
    model: str = typer.Option("large-v3-turbo", "--model", help="faster-whisper model name"),
    batch_size: int = typer.Option(16, "--batch-size", help="BatchedInferencePipeline batch size"),
    force: bool = typer.Option(False, "--force", help="Re-transcribe even if checkpoint exists"),
) -> None:
    """Run Tier-0 sidecar check or Tier-3 GPU faster-whisper word-level transcription."""
    transcribe_workspace(
        workspace_dir=workspace,
        episode_filter=_parse_ep_filter(episodes),
        model_name=model,
        batch_size=batch_size,
        force=force,
    )


@app.command("lexicon")
def cmd_lexicon(
    workspace: Path = typer.Option(..., "--workspace", "-w", help="Workspace directory"),
    episodes: Optional[str] = typer.Option(None, "--episodes", help="Comma-separated episode IDs"),
    force: bool = typer.Option(False, "--force", help="Overwrite existing lexicon JSONs"),
) -> None:
    """Run deterministic offline ECDICT vocabulary & lemma analysis (0 LLM tokens)."""
    run_lexicon_workspace(workspace_dir=workspace, episode_filter=_parse_ep_filter(episodes), force=force)


@app.command("prepare-chunks")
def cmd_prepare_chunks(
    workspace: Path = typer.Option(..., "--workspace", "-w", help="Workspace directory"),
    episodes: Optional[str] = typer.Option(None, "--episodes", help="Comma-separated episode IDs"),
    sentences_per_chunk: int = typer.Option(180, "--chunk-size", help="Sentences per .in.tsv chunk"),
    force: bool = typer.Option(False, "--force", help="Overwrite existing .in.tsv files"),
) -> None:
    """Export token-efficient .in.tsv transcript chunks for AI agent or API enrichment."""
    prepare_chunks(
        workspace_dir=workspace,
        episode_filter=_parse_ep_filter(episodes),
        sentences_per_chunk=sentences_per_chunk,
        force=force,
    )


@app.command("validate-chunks")
def cmd_validate_chunks(
    workspace: Path = typer.Option(..., "--workspace", "-w", help="Workspace directory"),
    episodes: Optional[str] = typer.Option(None, "--episodes", help="Comma-separated episode IDs"),
) -> None:
    """Validate that .out.tsv files match .in.tsv sentence IDs 1-to-1 and follow TSV format."""
    report = validate_chunks_workspace(workspace_dir=workspace, episode_filter=_parse_ep_filter(episodes))
    if report["errorCount"] > 0:
        raise typer.Exit(code=1)


@app.command("enrich-api")
def cmd_enrich_api(
    workspace: Path = typer.Option(..., "--workspace", "-w", help="Workspace directory"),
    episodes: Optional[str] = typer.Option(None, "--episodes", help="Comma-separated episode IDs"),
    base_url: Optional[str] = typer.Option(None, "--base-url", help="OpenAI-compatible base URL"),
    api_key: Optional[str] = typer.Option(None, "--api-key", help="API key (or set LLM_API_KEY env var)"),
    model: Optional[str] = typer.Option(None, "--model", help="Model name (e.g. deepseek-chat, gpt-4o-mini)"),
    offline: bool = typer.Option(False, "--offline", help="Use zero-API local ECDICT fallback"),
    force: bool = typer.Option(False, "--force", help="Overwrite existing .out.tsv files"),
) -> None:
    """Enrich chunks via OpenAI-compatible API or offline dictionary fallback."""
    count = enrich_chunks_via_api(
        workspace_dir=workspace,
        episode_filter=_parse_ep_filter(episodes),
        base_url=base_url,
        api_key=api_key,
        model=model,
        offline=offline,
        force=force,
    )
    print(f"[enrich-api] Completed {count} chunks.")


@app.command("merge-enriched")
def cmd_merge_enriched(
    workspace: Path = typer.Option(..., "--workspace", "-w", help="Workspace directory"),
    episodes: Optional[str] = typer.Option(None, "--episodes", help="Comma-separated episode IDs"),
    allow_untranslated: bool = typer.Option(False, "--allow-untranslated", help="Allow merging without .out.tsv"),
) -> None:
    """Merge translations, timeline notes & ECDICT vocab into validated episode.json files."""
    merge_enriched_workspace(
        workspace_dir=workspace,
        episode_filter=_parse_ep_filter(episodes),
        allow_untranslated=allow_untranslated,
    )


@app.command("pack")
def cmd_pack(
    workspace: Path = typer.Option(..., "--workspace", "-w", help="Workspace directory"),
    output: Path = typer.Option(PACKS_ROOT, "--output", "-o", help="Destination packs root"),
    create_lcp: bool = typer.Option(False, "--create-lcp", help="Also create portable .lcp ZIP archives"),
    starter_dir: Optional[Path] = typer.Option(None, "--starter-dir", help="Optional Android assets/starter_pack output"),
) -> None:
    """Assemble series folders, manifests, catalog.json, and optional .lcp / starter_pack bundles."""
    build_packs(workspace_dir=workspace, packs_root=output, create_lcp=create_lcp)
    if starter_dir is not None:
        build_android_starter_pack(packs_root=output, dest_starter_dir=starter_dir)


@app.command("validate")
def cmd_validate(
    packs: Path = typer.Option(PACKS_ROOT, "--packs", "-p", help="Packs root containing catalog.json"),
) -> None:
    """Validate all manifests, episode.json files, and media assets inside a packs directory."""
    validate_packs(packs_root=packs)


@app.command("serve")
def cmd_serve(
    packs: Path = typer.Option(PACKS_ROOT, "--packs", "-p", help="Packs directory to serve over LAN Wi-Fi"),
    port: int = typer.Option(8765, "--port", help="HTTP port"),
) -> None:
    """Start LAN HTTP sync server for importing course packs directly from the Android app."""
    serve_packs(packs_root=packs, port=port)


@app.command("push")
def cmd_push(
    packs: Path = typer.Option(PACKS_ROOT, "--packs", "-p", help="Packs directory to push via ADB"),
    serial: Optional[str] = typer.Option(None, "--serial", "-s", help="Optional target ADB device serial"),
    audio_only: bool = typer.Option(False, "--audio-only", help="Push audio + metadata only (skip video.mp4)"),
) -> None:
    """Push course packs to a connected Android device over USB ADB."""
    push_packs_via_adb(packs_root=packs, device_serial=serial, audio_only=audio_only)


@app.command("ingest")
def cmd_ingest(
    source: str = typer.Option(..., "--source", "-s", help="Local folder path, single video path, or Bilibili/YouTube URL"),
    series: str = typer.Option("custom-series", "--series", help="Target seriesId slug"),
    workspace_name: Optional[str] = typer.Option(None, "--name", help="Workspace folder name"),
    output: Path = typer.Option(PACKS_ROOT, "--output", "-o", help="Destination packs root"),
    offline: bool = typer.Option(False, "--offline", help="Run offline fallback instead of pausing for agent/API enrichment"),
    create_lcp: bool = typer.Option(True, "--create-lcp", help="Bundle portable .lcp archive when finished"),
) -> None:
    """One-stop ingestion entrypoint for local videos/folders or URLs."""
    setup_isolated_env()
    ws_name = workspace_name or series
    ws_dir = WORKSPACE_ROOT / ws_name
    ws_dir.mkdir(parents=True, exist_ok=True)

    src_path = Path(source)
    if source.startswith(("http://", "https://")):
        dl_dir = ws_dir / "downloads"
        dl_dir.mkdir(parents=True, exist_ok=True)
        print(f"[ingest] Downloading URL via yt-dlp into {dl_dir}...")
        subprocess.run(
            [
                "yt-dlp",
                "--write-subs",
                "--write-auto-subs",
                "--sub-langs",
                "en.*",
                "--convert-subs",
                "srt",
                "-f",
                "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/b",
                "-o",
                str(dl_dir / "%(playlist_index|01)s - %(title).80s.%(ext)s"),
                source,
            ],
            check=True,
        )
        input_dir = dl_dir
    elif src_path.is_file():
        input_dir = ws_dir / "source_single"
        input_dir.mkdir(parents=True, exist_ok=True)
        target_link = input_dir / src_path.name
        if not target_link.exists():
            import shutil

            shutil.copy2(src_path, target_link)
    elif src_path.is_dir():
        input_dir = src_path
    else:
        raise FileNotFoundError(f"Source not found: {source}")

    probe_directory(input_dir, ws_dir, series_override=series, enable_ocr=True)
    transcribe_workspace(ws_dir)
    run_lexicon_workspace(ws_dir)
    prepare_chunks(ws_dir)

    if offline or os.environ.get("LLM_API_KEY") or os.environ.get("OPENAI_API_KEY"):
        enrich_chunks_via_api(ws_dir, offline=offline)
        merge_enriched_workspace(ws_dir)
        build_packs(ws_dir, packs_root=output, create_lcp=create_lcp)
        validate_packs(output)
    else:
        print(
            f"[ingest] Transcription & chunks ready at {ws_dir / 'chunks'}.\n"
            "  Next: have an AI agent write .out.tsv files (see AGENT_PLAYBOOK.md) OR run `lingocast enrich-api` / `--offline`,\n"
            f"  then run `lingocast merge-enriched -w {ws_dir} && lingocast pack -w {ws_dir} --create-lcp`."
        )


if __name__ == "__main__":
    app()
