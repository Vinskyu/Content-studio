#!/usr/bin/env python3
"""
ContentStudio Kotlin & Chaquopy In-Process Bridge (engine/bridge.py)
Sprint H: Pintu masuk tunggal untuk Android Native APK (Kotlin + Chaquopy + FFmpeg nativeLibraryDir)
          Semua fungsi input & output menggunakan format JSON string bersih agar 100% bebas konversi tipe rumit JNI.
"""

import sys
import os

# Mencegah direktori engine/ membayangi modul library standar Python
_script_dir = os.path.dirname(os.path.abspath(__file__))
if sys.path and sys.path[0] == _script_dir:
    sys.path.pop(0)
_root_dir = os.path.dirname(_script_dir)
if _root_dir not in sys.path:
    sys.path.append(_root_dir)

import json
from typing import Dict, Any, List, Optional, Callable

from engine.runtime import (
    get_root_dir,
    get_ffmpeg_bin,
    get_ffprobe_bin,
    get_output_base_dir,
    get_workspaces_dir,
    get_skills_dir,
    get_assets_dir,
    get_fonts_dir,
    resolve_path,
    load_runtime_env
)

def _wrap_callback(cb: Optional[Callable]) -> Optional[Callable[[Dict[str, Any]], None]]:
    """Membungkus callback Kotlin agar dapat menerima dict maupun string JSON tanpa crash."""
    if not cb or not callable(cb):
        return None

    def _safe_call(event_dict: Dict[str, Any]):
        try:
            cb(event_dict)
        except TypeError:
            try:
                cb(json.dumps(event_dict, ensure_ascii=False))
            except Exception:
                pass
        except Exception:
            pass

    return _safe_call

# ==============================================================================
# 1. INISIALISASI RUNTIME & BYOK DARI APK (init_env)
# ==============================================================================
def init_env(
    root_dir: str = "",
    out_dir: str = "",
    ffmpeg_bin: str = "",
    ffprobe_bin: str = "",
    env_json: str = "{}"
) -> str:
    """
    Mengatur konfigurasi environment runtime dan API keys (BYOK) secara in-process.
    Semua parameter opsional; jika kosong akan menggunakan default runtime Android.
    """
    try:
        if root_dir and os.path.isdir(root_dir):
            os.environ["CONTENTSTUDIO_ROOT"] = os.path.abspath(root_dir)
        if out_dir:
            os.environ["CONTENTSTUDIO_OUT_DIR"] = os.path.abspath(out_dir)
        if ffmpeg_bin:
            os.environ["FFMPEG_BIN"] = ffmpeg_bin
        if ffprobe_bin:
            os.environ["FFPROBE_BIN"] = ffprobe_bin

        keys_set = []
        if env_json:
            parsed_env = {}
            if isinstance(env_json, str):
                try:
                    parsed_env = json.loads(env_json)
                except Exception:
                    pass
            elif isinstance(env_json, dict):
                parsed_env = env_json

            if isinstance(parsed_env, dict):
                for k, v in parsed_env.items():
                    if v is not None:
                        os.environ[str(k)] = str(v)
                        keys_set.append(str(k))

        # Muat file .env fallback
        load_runtime_env()

        res = {
            "status": "ok",
            "root_dir": get_root_dir(),
            "out_dir": get_output_base_dir(),
            "ffmpeg_bin": get_ffmpeg_bin(),
            "ffprobe_bin": get_ffprobe_bin(),
            "keys_configured": keys_set
        }
        return json.dumps(res, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)}, ensure_ascii=False)

# ==============================================================================
# 2. DAFTAR WORKSPACE & SKILL DNA (list_workspaces)
# ==============================================================================
def list_workspaces() -> str:
    """Mengembalikan daftar seluruh workspace dan status Skill DNA sutradaranya dalam format JSON string."""
    try:
        from engine.director import get_workspaces_list
        workspaces = get_workspaces_list()
        return json.dumps(workspaces, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)}, ensure_ascii=False)

# ==============================================================================
# 3. DAFTAR ASET FISIK (list_assets)
# ==============================================================================
def list_assets(asset_type: str = "", tag: str = "") -> str:
    """
    Membaca katalog assets/library.json.
    Jika library.json belum ada, otomatis melakukan pemindaian awal (auto-scan).
    Mendukung filter berdasarkan asset_type ('sticker', 'background', 'sfx') dan tag.
    """
    try:
        from engine.assets import load_library, scan_assets_library
        assets_dir = get_assets_dir()
        lib_file = os.path.join(assets_dir, "library.json")

        if not os.path.exists(lib_file):
            scan_assets_library()

        lib_data = load_library()
        assets = lib_data.get("assets", [])

        if asset_type:
            at_clean = asset_type.lower().strip()
            assets = [a for a in assets if a.get("type", "").lower() == at_clean]

        if tag:
            tag_clean = tag.lower().strip()
            assets = [a for a in assets if tag_clean in [t.lower() for t in a.get("tags", [])]]

        res = {
            "status": "ok",
            "total": len(assets),
            "assets": assets
        }
        return json.dumps(res, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)}, ensure_ascii=False)

# ==============================================================================
# 4. PINDAI ULANG ASET (scan_assets)
# ==============================================================================
def scan_assets() -> str:
    """Memindai ulang folder assets/ dan memperbarui assets/library.json secara in-process."""
    try:
        from engine.assets import scan_assets_library
        res = scan_assets_library()
        return json.dumps(res, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)}, ensure_ascii=False)

# ==============================================================================
# 5. CEK STATUS ROUTER & KUOTA AI (check_router_status)
# ==============================================================================
def check_router_status() -> str:
    """Mengembalikan status kesiapan provider AI (Groq, Gemini, OpenRouter) dan kuota secara in-process."""
    try:
        from engine.router import get_all_providers_status
        status_info = get_all_providers_status()
        return json.dumps(status_info, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)}, ensure_ascii=False)

# ==============================================================================
# 6. SUTRADARA AI: PENYUSUNAN PLAN (create_plan)
# ==============================================================================
def create_plan(req_json: str, event_callback: Optional[Callable] = None) -> str:
    """
    Menjalankan sutradara autonomous director secara in-process.
    Menerima req_json berupa JSON string dengan spesifikasi:
      {
        "workspace": "...",
        "project": "...",
        "topic": "...",
        "media": ["/path/1", ...],
        "vo": "/path/vo.mp3" (opsional),
        "bgm": "/path/bgm.mp3" (opsional),
        "duration": 45.0 (opsional)
      }
    Mengalirkan event progress ke event_callback: {"stage", "pct", "msg"}.
    Mengembalikan JSON string hasil plan.json & contract.json.
    """
    try:
        if isinstance(req_json, str):
            req_dict = json.loads(req_json)
        elif isinstance(req_json, dict):
            req_dict = req_json
        else:
            raise ValueError("req_json harus berupa JSON string atau dictionary")

        cb = _wrap_callback(event_callback)
        from engine.director import create_plan_pipeline
        result = create_plan_pipeline(req_dict, on_event=cb, json_events=False)
        return json.dumps(result, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)}, ensure_ascii=False)

# ==============================================================================
# 7. RENDER PROYEK VIDEO 1080P (render_project)
# ==============================================================================
def render_project(plan_path: str, event_callback: Optional[Callable] = None) -> str:
    """
    Mengeksekusi rendering video Full HD 1080p secara in-process.
    Mengalirkan event progress ke event_callback: {"stage", "pct", "msg"}.
    Mengembalikan JSON string berisi path video hasil akhir, resolusi, durasi, dan ukuran byte.
    """
    try:
        p_path = resolve_path(plan_path)
        if not os.path.exists(p_path):
            raise FileNotFoundError(f"File plan tidak ditemukan: {p_path}")

        cb = _wrap_callback(event_callback)

        def _progress_adapter(stage: str, pct: int, msg: str):
            if cb:
                cb({
                    "stage": stage,
                    "pct": max(0, min(100, int(pct))),
                    "msg": msg
                })

        from engine.build import render_pipeline, probe_file
        final_video = render_pipeline(
            plan_source=p_path,
            is_preview=False,
            on_progress=_progress_adapter
        )

        meta = probe_file(final_video)
        w = meta.get("width") or 1080
        h = meta.get("height") or 1920
        dur = meta.get("duration") or 0.0
        sz = os.path.getsize(final_video) if os.path.exists(final_video) else 0

        res = {
            "status": "ok",
            "output_video": final_video,
            "resolution": f"{w}x{h}",
            "width": w,
            "height": h,
            "duration": dur,
            "size_bytes": sz
        }
        return json.dumps(res, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)}, ensure_ascii=False)

# ==============================================================================
# 8. UNDUH MEDIA IN-PROCESS (fetch_media)
# ==============================================================================
def fetch_media(req_json: str, event_callback: Optional[Callable] = None) -> str:
    """
    Mengunduh potongan media video (default 1080p) atau audio secara in-process via dual-mode yt_dlp.
    req_json:
      {
        "url": "https://...",
        "out": "/path/to/dir",
        "audio_only": false (opsional),
        "max_height": 1080 (opsional, default 1080),
        "section": "00:00:10-00:00:20" (opsional),
        "name": "nama_file" (opsional)
      }
    """
    try:
        if isinstance(req_json, str):
            req_dict = json.loads(req_json)
        elif isinstance(req_json, dict):
            req_dict = req_json
        else:
            raise ValueError("req_json harus berupa JSON string atau dictionary")

        url = req_dict.get("url")
        if not url:
            raise ValueError("Field 'url' wajib disertakan")

        out_dir = req_dict.get("out") or get_output_base_dir()
        audio_only = bool(req_dict.get("audio_only", False))
        max_height = int(req_dict.get("max_height", 1080))
        name = req_dict.get("name")
        section = req_dict.get("section", "")

        cb = _wrap_callback(event_callback)

        from engine.fetch import download_media
        result = download_media(
            url=url,
            out_dir=out_dir,
            audio_only=audio_only,
            max_height=max_height,
            name_prefix=name,
            section=section,
            on_progress=cb
        )
        return json.dumps(result, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)}, ensure_ascii=False)

# ==============================================================================
# 9. RUN CONSOLE INTERAKTIF UNTUK APK (run_console)
# ==============================================================================
def run_console(
    input_text: str,
    attached_files_json: str = "[]",
    event_callback: Optional[Callable] = None
) -> str:
    """
    Pintu masuk interaktif untuk Command Console Native APK Kotlin / Chaquopy.
    Menerima input_text (perintah CLI diawali '/' atau instruksi bahasa bebas AI)
    dan daftar path file terlampir (JSON string array).
    Mengalirkan event progress ke event_callback jika ada proses build/render.
    Mengembalikan JSON string bersih hasil eksekusi operator.
    """
    try:
        attached_files = []
        if attached_files_json:
            if isinstance(attached_files_json, str):
                try:
                    parsed = json.loads(attached_files_json)
                    if isinstance(parsed, list):
                        attached_files = [str(x) for x in parsed]
                except Exception:
                    pass
            elif isinstance(attached_files_json, list):
                attached_files = [str(x) for x in attached_files_json]

        cb = _wrap_callback(event_callback)
        from engine.operator import execute_command
        result = execute_command(
            input_text=input_text,
            attached_files=attached_files,
            event_callback=cb
        )
        return json.dumps(result, ensure_ascii=False)
    except Exception as e:
        return json.dumps({
            "status": "error",
            "mode": "cli" if (input_text or "").startswith("/") else "agent",
            "active_workspace": "",
            "reply": f"[ERR] Kesalahan runtime operator: {e}",
            "logs": [f"[ERR] Operator error: {e}"],
            "steps": [],
            "action_cards": []
        }, ensure_ascii=False)

# ==============================================================================
# 10. GET SESSION STATE UNTUK HEADER BAR APK (get_session_state)
# ==============================================================================
def get_session_state() -> str:
    """
    Mengembalikan status sesi ringan untuk header bar APK:
    active_workspace, last_plan_path, last_output_video, router_status, dan storage.
    """
    try:
        from engine.operator import load_session, get_storage_info
        from engine.router import get_all_providers_status

        session = load_session()
        storage = get_storage_info()
        prov_info = get_all_providers_status()

        res = {
            "status": "ok",
            "active_workspace": session.get("active_workspace", "hok_insights"),
            "last_plan_path": session.get("last_plan_path"),
            "last_output_video": session.get("last_output_video"),
            "storage": storage,
            "providers": prov_info.get("providers", {})
        }
        return json.dumps(res, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)}, ensure_ascii=False)

