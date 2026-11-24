#!/usr/bin/env python3
"""
ContentStudio Kotlin & Chaquopy In-Process Bridge (engine/bridge.py)
Sprint J: Bridge Persisten & Pengelola Workspace/Skill/MCP untuk Android Native APK.
Semua antarmuka masukan & keluaran menggunakan format JSON string bersih agar 100% Chaquopy-safe.
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

import re
import json
import shutil
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

def sync_persistent_storage(internal_root: str) -> None:
    """
    Jika /sdcard/ContentStudio writable, salin template bawaan (workspaces, assets, skills, fonts)
    hanya jika folder tersebut belum ada di /sdcard/ContentStudio agar editan user tidak pernah hilang.
    """
    sdcard_base = "/sdcard/ContentStudio"
    if not (os.path.exists("/sdcard") and os.access("/sdcard", os.W_OK)):
        return

    os.makedirs(sdcard_base, exist_ok=True)
    os.makedirs(os.path.join(sdcard_base, "out"), exist_ok=True)
    os.makedirs(os.path.join(sdcard_base, "inbox"), exist_ok=True)

    folders_to_sync = ["workspaces", "skills", "assets"]
    for folder in folders_to_sync:
        src = os.path.join(internal_root, folder)
        dst = os.path.join(sdcard_base, folder)
        if os.path.exists(src) and not os.path.exists(dst):
            try:
                shutil.copytree(src, dst)
            except Exception:
                pass

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
    """Mengatur konfigurasi environment runtime dan API keys (BYOK) secara in-process."""
    try:
        app_root = get_root_dir()
        if root_dir and os.path.isdir(root_dir):
            app_root = os.path.abspath(root_dir)
            os.environ["CONTENTSTUDIO_ROOT"] = app_root

        # Cek dan sinkronkan storage persisten di /sdcard/ContentStudio
        sync_persistent_storage(app_root)

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
                try: parsed_env = json.loads(env_json)
                except Exception: pass
            elif isinstance(env_json, dict):
                parsed_env = env_json

            if isinstance(parsed_env, dict):
                for k, v in parsed_env.items():
                    if v is not None:
                        val_str = str(v).strip()
                        os.environ[str(k)] = val_str
                        if val_str:
                            keys_set.append(str(k))

        # Muat file .env fallback
        load_runtime_env()

        # Inisialisasi default skills & asset scan jika diperlukan
        try:
            from engine.skills import ensure_default_skills
            ensure_default_skills()
        except Exception:
            pass

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
# 2. PENGELOLA WORKSPACE / AKUN
# ==============================================================================

def list_workspaces() -> str:
    """Mengembalikan daftar seluruh workspace dan status Skill DNA sutradaranya dalam format JSON terstruktur."""
    try:
        ws_dir = get_workspaces_dir()
        if not os.path.exists(ws_dir):
            os.makedirs(ws_dir, exist_ok=True)

        results = []
        ws_entries = sorted([d for d in os.listdir(ws_dir) if not d.startswith(".") and os.path.isdir(os.path.join(ws_dir, d))])

        for ws in ws_entries:
            ws_path = os.path.join(ws_dir, ws)
            # Cek skill di skills/ atau .agents/skills/
            s_name = f"{ws}-director"
            skill_dir = os.path.join(get_skills_dir(), s_name)
            skill_file = os.path.join(skill_dir, "SKILL.md")
            skill_ready = os.path.exists(skill_file)

            niche_title = "-"
            niche_file = os.path.join(ws_path, "niche.md")
            if os.path.exists(niche_file):
                try:
                    with open(niche_file, "r", encoding="utf-8") as f:
                        for line in f:
                            line = line.strip()
                            if line and not line.startswith("#"):
                                niche_title = line[:60]
                                break
                            elif line.startswith("#"):
                                niche_title = line.lstrip("#").strip()[:60]
                                break
                except Exception:
                    pass

            results.append({
                "workspace": ws,
                "status": "ready" if skill_ready else "missing",
                "skill_path": os.path.relpath(skill_file, get_root_dir()) if skill_ready else "",
                "niche": niche_title
            })

        return json.dumps({"status": "ok", "total": len(results), "workspaces": results}, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e), "workspaces": []}, ensure_ascii=False)

def switch_workspace(name: str) -> str:
    """Mengganti workspace aktif pada session secara senyap (tanpa spam chat)."""
    try:
        clean_name = re.sub(r"[^a-zA-Z0-9_-]+", "_", name.strip().lower())
        ws_dir = os.path.join(get_workspaces_dir(), clean_name)
        if not os.path.exists(ws_dir):
            return json.dumps({"status": "error", "message": f"Workspace '{clean_name}' tidak ditemukan."}, ensure_ascii=False)

        from engine.operator import load_session, save_session
        session = load_session()
        session["active_workspace"] = clean_name
        save_session(session)

        return json.dumps({"status": "ok", "active_workspace": clean_name}, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)}, ensure_ascii=False)

def create_workspace(name: str, niche: str, style: str = "", laws: str = "", skill: str = "") -> str:
    """Membuat akun/workspace baru beserta file konstitusinya."""
    try:
        clean_name = re.sub(r"[^a-zA-Z0-9_-]+", "_", name.strip().lower())
        if not clean_name:
            return json.dumps({"status": "error", "message": "Nama workspace tidak valid."}, ensure_ascii=False)

        ws_dir = os.path.join(get_workspaces_dir(), clean_name)
        os.makedirs(os.path.join(ws_dir, "projects"), exist_ok=True)
        os.makedirs(os.path.join(ws_dir, "references"), exist_ok=True)
        os.makedirs(os.path.join(ws_dir, "inbox"), exist_ok=True)

        niche_text = niche.strip() if niche else f"# NICHE: {clean_name}\nTopik konten otomatis."
        style_text = style.strip() if style else "# STYLE GUIDE\n- Rasio: 9:16 Full HD 1080p (1080x1920)\n- Framing: blur_fill."
        laws_text = laws.strip() if laws else "# LAWS & RULES\n1. Wajib resolusi 1080p.\n2. Bebas black bars."

        with open(os.path.join(ws_dir, "niche.md"), "w", encoding="utf-8") as f:
            f.write(niche_text + "\n")
        with open(os.path.join(ws_dir, "style.md"), "w", encoding="utf-8") as f:
            f.write(style_text + "\n")
        with open(os.path.join(ws_dir, "laws.md"), "w", encoding="utf-8") as f:
            f.write(laws_text + "\n")

        if skill:
            s_folder = os.path.join(get_skills_dir(), f"{clean_name}-director")
            os.makedirs(s_folder, exist_ok=True)
            with open(os.path.join(s_folder, "SKILL.md"), "w", encoding="utf-8") as f:
                f.write(skill.strip() + "\n")

        # Setel sebagai workspace aktif
        from engine.operator import load_session, save_session
        session = load_session()
        session["active_workspace"] = clean_name
        save_session(session)

        return json.dumps({"status": "ok", "workspace": clean_name, "message": f"Workspace '{clean_name}' berhasil dibuat."}, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)}, ensure_ascii=False)

def delete_workspace(name: str) -> str:
    """Menghapus akun/workspace."""
    try:
        clean_name = name.strip().lower()
        ws_dir = os.path.join(get_workspaces_dir(), clean_name)
        if not os.path.exists(ws_dir):
            return json.dumps({"status": "error", "message": f"Workspace '{clean_name}' tidak ditemukan."}, ensure_ascii=False)

        shutil.rmtree(ws_dir)

        # Hapus skill director terkait jika ada
        s_folder = os.path.join(get_skills_dir(), f"{clean_name}-director")
        if os.path.exists(s_folder):
            try: shutil.rmtree(s_folder)
            except Exception: pass

        # Ganti active_workspace ke workspace lain jika yang dihapus adalah yang aktif
        from engine.operator import load_session, save_session
        session = load_session()
        if session.get("active_workspace") == clean_name:
            avail = [d for d in os.listdir(get_workspaces_dir()) if os.path.isdir(os.path.join(get_workspaces_dir(), d)) and not d.startswith(".")]
            session["active_workspace"] = avail[0] if avail else "hok_insights"
            save_session(session)

        return json.dumps({"status": "ok", "message": f"Workspace '{clean_name}' berhasil dihapus."}, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)}, ensure_ascii=False)

def read_workspace_file(workspace: str, rel_path: str) -> str:
    """Membaca isi file konstitusi workspace (niche.md, style.md, laws.md, SKILL.md)."""
    try:
        clean_ws = workspace.strip().lower()
        ws_dir = os.path.join(get_workspaces_dir(), clean_ws)

        if rel_path.upper() == "SKILL.MD":
            s_file = os.path.join(get_skills_dir(), f"{clean_ws}-director", "SKILL.md")
            if not os.path.exists(s_file):
                s_file = os.path.join(ws_dir, "SKILL.md")
            target_p = s_file
        else:
            target_p = os.path.join(ws_dir, rel_path)

        if not os.path.exists(target_p):
            return json.dumps({"status": "ok", "content": "", "found": False}, ensure_ascii=False)

        with open(target_p, "r", encoding="utf-8") as f:
            content = f.read()

        return json.dumps({"status": "ok", "content": content, "found": True}, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)}, ensure_ascii=False)

def save_workspace_file(workspace: str, rel_path: str, content: str) -> str:
    """Menyimpan pembaruan file konstitusi workspace."""
    try:
        clean_ws = workspace.strip().lower()
        ws_dir = os.path.join(get_workspaces_dir(), clean_ws)
        os.makedirs(ws_dir, exist_ok=True)

        if rel_path.upper() == "SKILL.MD":
            s_dir = os.path.join(get_skills_dir(), f"{clean_ws}-director")
            os.makedirs(s_dir, exist_ok=True)
            target_p = os.path.join(s_dir, "SKILL.md")
        else:
            target_p = os.path.join(ws_dir, rel_path)

        with open(target_p, "w", encoding="utf-8") as f:
            f.write(content.strip() + "\n")

        return json.dumps({"status": "ok", "message": f"File '{rel_path}' berhasil disimpan."}, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)}, ensure_ascii=False)

# ==============================================================================
# 3. PENGELOLA SKILL & MCP
# ==============================================================================

def list_skills() -> str:
    """Mengembalikan daftar skill terpasang."""
    try:
        from engine.skills import list_skills as _list_skills
        skills = _list_skills()
        return json.dumps({"status": "ok", "total": len(skills), "skills": skills}, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e), "skills": []}, ensure_ascii=False)

def install_skill(source: str) -> str:
    """Memasang skill baru dari URL atau ZIP."""
    try:
        from engine.skills import install_skill as _install_skill
        res = _install_skill(source)
        return json.dumps(res, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)}, ensure_ascii=False)

def delete_skill(name: str) -> str:
    """Menghapus skill."""
    try:
        from engine.skills import delete_skill as _delete_skill
        res = _delete_skill(name)
        return json.dumps(res, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)}, ensure_ascii=False)

def list_mcp_servers() -> str:
    """Mengembalikan daftar server Remote MCP."""
    try:
        from engine.mcp_client import list_mcp_servers as _list_mcp
        res = _list_mcp()
        return json.dumps(res, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e), "servers": []}, ensure_ascii=False)

def save_mcp_server(name: str, url: str, headers_json: str = "{}", enabled: bool = True) -> str:
    """Menyimpan atau memperbarui server Remote MCP."""
    try:
        from engine.mcp_client import save_mcp_server as _save_mcp
        headers = {}
        if headers_json:
            try:
                headers = json.loads(headers_json) if isinstance(headers_json, str) else headers_json
            except Exception:
                headers = {}
        res = _save_mcp(name=name, url=url, headers=headers, enabled=enabled)
        return json.dumps(res, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)}, ensure_ascii=False)

def delete_mcp_server(name: str) -> str:
    """Menghapus server Remote MCP."""
    try:
        from engine.mcp_client import delete_mcp_server as _del_mcp
        res = _del_mcp(name)
        return json.dumps(res, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)}, ensure_ascii=False)

# ==============================================================================
# 4. PENGELOLA ASET FISIK
# ==============================================================================

def list_assets(asset_type: str = "", tag: str = "") -> str:
    """Membaca katalog assets/library.json dengan filter kategori & tag."""
    try:
        from engine.assets import load_library, scan_assets_library
        lib_data = load_library()
        assets = lib_data.get("assets", [])
        if not assets:
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
        return json.dumps({"status": "error", "message": str(e), "assets": []}, ensure_ascii=False)

def scan_assets() -> str:
    """Memindai ulang folder assets/."""
    try:
        from engine.assets import scan_assets_library
        res = scan_assets_library()
        return json.dumps(res, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)}, ensure_ascii=False)

# ==============================================================================
# 5. RENDER PROYEK VIDEO 1080P (render_project)
# ==============================================================================

def render_project(plan_path: str, event_callback: Optional[Callable] = None) -> str:
    """Mengeksekusi rendering video Full HD 1080p secara in-process."""
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
# 6. RUN CONSOLE INTERAKTIF (run_console)
# ==============================================================================

def run_console(
    input_text: str,
    attached_files_json: str = "[]",
    event_callback: Optional[Callable] = None
) -> str:
    """Pintu masuk eksekusi perintah / chat agent dari Android Kotlin."""
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
# 7. STATUS SESI & BADGE API (get_session_state)
# ==============================================================================

def get_session_state() -> str:
    """Mengembalikan status sesi untuk header bar APK termasuk status live API keys."""
    try:
        from engine.operator import load_session, get_storage_info
        from engine.router import get_all_providers_status, load_env, parse_keys_for_provider

        session = load_session()
        storage = get_storage_info()
        prov_info = get_all_providers_status()
        env = load_env()

        active_prov = "NONE"
        has_key = False
        for p in ["groq", "gemini", "openrouter"]:
            k_list = parse_keys_for_provider(p, env)
            if k_list:
                has_key = True
                active_prov = p.upper()
                break

        res = {
            "status": "ok",
            "active_workspace": session.get("active_workspace", "hok_insights"),
            "last_plan_path": session.get("last_plan_path"),
            "last_output_video": session.get("last_output_video"),
            "storage": storage,
            "has_api_key": has_key,
            "active_provider": active_prov,
            "providers": prov_info.get("providers", {})
        }
        return json.dumps(res, ensure_ascii=False)
    except Exception as e:
        return json.dumps({
            "status": "error",
            "message": str(e),
            "has_api_key": False,
            "active_provider": "NONE"
        }, ensure_ascii=False)
