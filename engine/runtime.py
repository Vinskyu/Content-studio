#!/usr/bin/env python3
"""
ContentStudio Centralized Runtime & Android-Safe Environment (engine/runtime.py)
Sprint H: Sumber kebenaran tunggal untuk resolusi path, binary toolchain,
          dan konfigurasi environment APK Chaquopy / CLI.
"""

import os
import sys
import tempfile
from typing import Dict, Any, Optional

def get_root_dir() -> str:
    """Membaca CONTENTSTUDIO_ROOT dari environment atau fallback ke parent engine/."""
    env_root = os.environ.get("CONTENTSTUDIO_ROOT")
    if env_root and os.path.isdir(env_root):
        return os.path.abspath(env_root)
    # Fallback ke direktori induk dari folder engine/
    current_dir = os.path.dirname(os.path.abspath(__file__))
    return os.path.abspath(os.path.dirname(current_dir))

def get_ffmpeg_bin() -> str:
    """Membaca binary ffmpeg dari FFMPEG_BIN atau default 'ffmpeg'."""
    return os.environ.get("FFMPEG_BIN", "ffmpeg")

def get_ffprobe_bin() -> str:
    """Membaca binary ffprobe dari FFPROBE_BIN atau default 'ffprobe'."""
    return os.environ.get("FFPROBE_BIN", "ffprobe")

def get_output_base_dir() -> str:
    """Membaca base output direktori dari CONTENTSTUDIO_OUT_DIR atau fallback Android-safe."""
    env_out = os.environ.get("CONTENTSTUDIO_OUT_DIR")
    if env_out:
        return os.path.abspath(env_out)
    if os.path.exists("/sdcard"):
        return "/sdcard/ContentStudio/out"
    return os.path.join(get_root_dir(), "out")

def get_inbox_dir() -> str:
    """Membaca direktori inbox ContentStudio."""
    env_inbox = os.environ.get("CONTENTSTUDIO_INBOX_DIR")
    if env_inbox:
        return os.path.abspath(env_inbox)
    if os.path.exists("/sdcard"):
        return "/sdcard/ContentStudio/inbox"
    return os.path.join(get_root_dir(), "inbox")

def get_fonts_dir() -> str:
    """Membaca direktori fonts ContentStudio."""
    return os.path.join(get_root_dir(), "assets", "fonts")

def get_assets_dir() -> str:
    """Membaca direktori assets ContentStudio."""
    return os.path.join(get_root_dir(), "assets")

def get_workspaces_dir() -> str:
    """Membaca direktori workspaces ContentStudio."""
    return os.path.join(get_root_dir(), "workspaces")

def get_skills_dir() -> str:
    """Membaca direktori skills ContentStudio."""
    return os.path.join(get_root_dir(), ".agents", "skills")

def get_cache_dir() -> str:
    """Membaca direktori cache ContentStudio."""
    return os.path.join(get_root_dir(), ".cache")

def resolve_path(path: str) -> str:
    """Mengatasi path Linux virtual /tmp/ yang dialihkan ke $TMPDIR pada Android."""
    if not path:
        return path
    if path.startswith("/tmp/") and not os.path.exists("/tmp"):
        rel = os.path.relpath(path, "/tmp")
        resolved = os.path.join(tempfile.gettempdir(), rel)
        os.makedirs(os.path.dirname(resolved), exist_ok=True)
        return resolved
    return os.path.abspath(path)

def load_runtime_env() -> Dict[str, str]:
    """Membaca environment variables dengan fallback ke .env di root ContentStudio."""
    env_vars = dict(os.environ)
    env_file = os.path.join(get_root_dir(), ".env")
    if os.path.exists(env_file):
        try:
            with open(env_file, "r", encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if not line or line.startswith("#") or "=" not in line:
                        continue
                    k, v = line.split("=", 1)
                    k = k.strip()
                    v = v.strip().strip("'\"")
                    if k and k not in env_vars:
                        env_vars[k] = v
                    elif k and not env_vars.get(k):
                        env_vars[k] = v
        except Exception:
            pass
    return env_vars
