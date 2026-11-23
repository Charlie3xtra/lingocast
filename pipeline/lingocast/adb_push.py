"""USB ADB high-speed push helper for LingoCast course packs."""

from __future__ import annotations

import os
import subprocess
from pathlib import Path

from .config import get_adb_exe

TARGET_DEVICE_PACKS_DIR = "/sdcard/Android/data/com.lingocast.app/files/packs"


def push_packs_via_adb(packs_root: Path, device_serial: str | None = None, audio_only: bool = False) -> None:
    adb = get_adb_exe()
    if not adb:
        raise FileNotFoundError("adb.exe not found. Set ADB_EXE or add Android platform-tools to PATH.")
    if not packs_root.is_dir():
        raise FileNotFoundError(f"Packs directory not found: {packs_root}")

    base = [adb] + (["-s", device_serial] if device_serial else [])
    subprocess.run(base + ["shell", "mkdir", "-p", TARGET_DEVICE_PACKS_DIR], check=True)

    if not audio_only:
        print(f"[adb-push] Fast pushing full packs tree -> {TARGET_DEVICE_PACKS_DIR}")
        subprocess.run(base + ["push", f"{packs_root}{os.sep}.", TARGET_DEVICE_PACKS_DIR], check=True)
        subprocess.run(base + ["shell", "chmod", "-R", "775", TARGET_DEVICE_PACKS_DIR], check=True)
        print("[adb-push] Complete. Launch LingoCast (auto-imports) or tap 扫描 ADB 目录.")
        return

    catalog = packs_root / "catalog.json"
    if catalog.is_file():
        subprocess.run(base + ["push", str(catalog), f"{TARGET_DEVICE_PACKS_DIR}/catalog.json"], check=True)

    for series_dir in sorted(p for p in packs_root.iterdir() if p.is_dir()):
        dst_series = f"{TARGET_DEVICE_PACKS_DIR}/{series_dir.name}"
        subprocess.run(base + ["shell", "mkdir", "-p", dst_series], check=True)
        manifest = series_dir / "manifest.json"
        if manifest.is_file():
            subprocess.run(base + ["push", str(manifest), f"{dst_series}/manifest.json"], check=True)

        for ep_dir in sorted(p for p in series_dir.iterdir() if p.is_dir() and p.name.startswith("ep_")):
            dst_ep = f"{dst_series}/{ep_dir.name}"
            subprocess.run(base + ["shell", "mkdir", "-p", dst_ep], check=True)
            for fname in ("episode.json", "audio.m4a", "cover.jpg", "video.mp4"):
                if audio_only and fname == "video.mp4":
                    continue
                fpath = ep_dir / fname
                if fpath.is_file():
                    print(f"[adb-push] {series_dir.name}/{ep_dir.name}/{fname}")
                    subprocess.run(base + ["push", str(fpath), f"{dst_ep}/{fname}"], check=True)

    subprocess.run(base + ["shell", "chmod", "-R", "775", TARGET_DEVICE_PACKS_DIR], check=True)
    print(f"[adb-push] Complete. Open LingoCast -> Import -> 扫描 ADB 目录 on device.")
