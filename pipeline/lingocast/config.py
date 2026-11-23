"""Path, environment, FFmpeg, and CUDA DLL configuration for LingoCast pipeline."""

from __future__ import annotations

import os
import shutil
import sys
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parents[2]
TOOLCHAIN_DIR = ROOT_DIR / ".toolchain"
VENV_DIR = TOOLCHAIN_DIR / "venv"
HF_CACHE_DIR = TOOLCHAIN_DIR / "hf-cache"
TMP_DIR = TOOLCHAIN_DIR / "tmp"

DATA_DIR = ROOT_DIR / "data"
DICT_DIR = DATA_DIR / "dict"
ECDICT_CSV = DICT_DIR / "ecdict.csv"
ECDICT_DB = DICT_DIR / "ecdict_compact.db"
WORKSPACE_ROOT = DATA_DIR / "workspace"
PACKS_ROOT = DATA_DIR / "packs"
SCHEMA_DIR = ROOT_DIR / "pipeline" / "schema"

WORKBUDDY_FFMPEG = Path(
    r"F:\Programfiles\WorkBuddy\resources\vendor\python\Lib\site-packages\imageio_ffmpeg\binaries\ffmpeg-win-x86_64-v9.0.2.exe"
)
ADB_TOOLCHAIN = TOOLCHAIN_DIR / "android-sdk" / "platform-tools" / "adb.exe"
ADB_EXE_DEFAULT = Path(
    r"F:\Programfiles\ADBgjx\AndroidWatch_ADB_ToolBox\adb\adb.exe"
)


def setup_isolated_env() -> None:
    """Keep HuggingFace caches and temp dirs strictly on D: drive."""
    HF_CACHE_DIR.mkdir(parents=True, exist_ok=True)
    TMP_DIR.mkdir(parents=True, exist_ok=True)
    os.environ.setdefault("HF_HOME", str(HF_CACHE_DIR))
    os.environ.setdefault("HUGGINGFACE_HUB_CACHE", str(HF_CACHE_DIR / "hub"))
    os.environ.setdefault("TEMP", str(TMP_DIR))
    os.environ.setdefault("TMP", str(TMP_DIR))


def register_cuda_dlls() -> list[Path]:
    """Register pip-installed NVIDIA cuBLAS/cuDNN DLL directories before importing ctranslate2."""
    added: list[Path] = []
    if sys.platform != "win32":
        return added
    site_packages = Path(sys.prefix) / "Lib" / "site-packages"
    candidates = [
        site_packages / "nvidia" / "cublas" / "bin",
        site_packages / "nvidia" / "cudnn" / "bin",
        site_packages / "nvidia" / "cuda_runtime" / "bin",
        site_packages / "ctranslate2",
    ]
    for path in candidates:
        if path.is_dir():
            os.add_dll_directory(str(path))
            os.environ["PATH"] = str(path) + os.pathsep + os.environ.get("PATH", "")
            added.append(path)
    return added


def get_ffmpeg_exe() -> str:
    """Resolve FFmpeg binary, preferring imageio-ffmpeg or WorkBuddy bundled binary."""
    env_ff = os.environ.get("FFMPEG_BINARY")
    if env_ff and Path(env_ff).is_file():
        return env_ff
    try:
        import imageio_ffmpeg

        exe = imageio_ffmpeg.get_ffmpeg_exe()
        if exe and Path(exe).is_file():
            return exe
    except Exception:
        pass
    if WORKBUDDY_FFMPEG.is_file():
        return str(WORKBUDDY_FFMPEG)
    which_ff = shutil.which("ffmpeg")
    if which_ff:
        return which_ff
    raise FileNotFoundError("FFmpeg executable not found. Install imageio-ffmpeg or set FFMPEG_BINARY.")


def get_adb_exe() -> str | None:
    """Resolve adb.exe if available."""
    env_adb = os.environ.get("ADB_EXE")
    if env_adb and Path(env_adb).is_file():
        return env_adb
    if ADB_TOOLCHAIN.is_file():
        return str(ADB_TOOLCHAIN)
    which_adb = shutil.which("adb")
    if which_adb:
        return which_adb
    if ADB_EXE_DEFAULT.is_file():
        return str(ADB_EXE_DEFAULT)
    return None
