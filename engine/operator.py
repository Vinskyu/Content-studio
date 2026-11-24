#!/usr/bin/env python3
"""
ContentStudio Autonomous CLI Agent & Interactive Operator (engine/operator.py)
Sprint I-2: Autonomous CLI Agent penuh 100% in-process (Android/Chaquopy-safe, 0 subprocess python3, Python 3.11+).
            Mendukung 2 Mode:
            1. Mode CLI Langsung (diawali '/', 0 Token AI, pemicu instan tombol UI)
            2. Mode Autonomous Agent (agentic tool-calling loop maks 8 langkah, read/write workspace & plan.json, auto-validation, step-streaming)
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
import time
import shutil
import argparse
from datetime import datetime
from typing import Dict, Any, List, Optional, Tuple, Callable

from engine.runtime import (
    get_root_dir,
    get_cache_dir,
    get_output_base_dir,
    get_workspaces_dir,
    get_skills_dir,
    get_inbox_dir,
    get_assets_dir,
    resolve_path,
    load_runtime_env
)

SESSION_FILE_NAME = "operator_session.json"

def get_session_file_path() -> str:
    """Mengembalikan path file state operator session."""
    cache_d = get_cache_dir()
    os.makedirs(cache_d, exist_ok=True)
    return os.path.join(cache_d, SESSION_FILE_NAME)

def load_session() -> Dict[str, Any]:
    """Membaca session state dari cache atau inisialisasi default."""
    sess_path = get_session_file_path()
    default_session = {
        "active_workspace": "hok_insights",
        "last_plan_path": None,
        "last_output_video": None,
        "history": []
    }
    if os.path.exists(sess_path):
        try:
            with open(sess_path, "r", encoding="utf-8") as f:
                data = json.load(f)
                if isinstance(data, dict):
                    for k, v in default_session.items():
                        if k not in data:
                            data[k] = v
                    return data
        except Exception:
            pass

    # Verifikasi active_workspace awal
    ws_dir = get_workspaces_dir()
    if os.path.exists(ws_dir):
        available = [d for d in os.listdir(ws_dir) if os.path.isdir(os.path.join(ws_dir, d)) and not d.startswith(".")]
        if available and default_session["active_workspace"] not in available:
            default_session["active_workspace"] = available[0]

    return default_session

def save_session(session: Dict[str, Any]) -> None:
    """Menyimpan session state secara atomic dengan membatasi history maks 10 entri."""
    sess_path = get_session_file_path()
    tmp_path = sess_path + ".tmp"
    try:
        if "history" in session and isinstance(session["history"], list):
            session["history"] = session["history"][-10:]

        with open(tmp_path, "w", encoding="utf-8") as f:
            json.dump(session, f, indent=2, ensure_ascii=False)
        os.replace(tmp_path, sess_path)
    except Exception as e:
        if os.path.exists(tmp_path):
            try:
                os.remove(tmp_path)
            except Exception:
                pass

def get_storage_info() -> Dict[str, Any]:
    """Membaca sisa penyimpanan disk (GB) pada ContentStudio root dan output base."""
    root_d = get_root_dir()
    out_d = get_output_base_dir()
    try:
        u_root = shutil.disk_usage(root_d)
        free_gb = round(u_root.free / (1024 ** 3), 2)
        total_gb = round(u_root.total / (1024 ** 3), 2)
        pct_used = round(((u_root.total - u_root.free) / u_root.total) * 100, 1) if u_root.total > 0 else 0.0
    except Exception:
        free_gb = 0.0
        total_gb = 0.0
        pct_used = 0.0

    return {
        "free_gb": free_gb,
        "total_gb": total_gb,
        "pct_used": pct_used,
        "root_dir": root_d,
        "out_dir": out_d
    }

def sanitize_safe_path(rel_path: str) -> Optional[str]:
    """
    Memastikan path berada dalam sandbox direktori ContentStudio.
    Mencegah directory traversal keluar dari root.
    Mendukung fallback otomatis jika user/LLM lupa menulis prefix 'workspaces/'.
    """
    if not rel_path:
        return None
    root = os.path.abspath(get_root_dir())
    clean_rel = rel_path.strip().lstrip("/")
    target = os.path.abspath(os.path.join(root, clean_rel))
    if target == root or target.startswith(root + os.sep):
        if os.path.exists(target):
            return target
        # Coba fallback ke folder workspaces/ jika belum diawali workspaces/
        if not clean_rel.startswith("workspaces/"):
            ws_target = os.path.abspath(os.path.join(root, "workspaces", clean_rel))
            if os.path.exists(ws_target):
                return ws_target
        return target
    return None

def emit_step_event(cb: Optional[Callable], tool: str, status: str, detail: str):
    """Mengirim event langkah kerja terstruktur ke callback Kotlin / UI."""
    if cb and callable(cb):
        try:
            cb({
                "type": "step",
                "tool": tool,
                "status": status,
                "detail": detail
            })
        except Exception:
            pass

def emit_progress_event(cb: Optional[Callable], stage: str, pct: int, msg: str):
    """Mengirim event progres ke callback Kotlin / UI."""
    if cb and callable(cb):
        try:
            cb({
                "type": "progress",
                "stage": stage,
                "pct": max(0, min(100, int(pct))),
                "msg": msg
            })
        except Exception:
            pass

# ==============================================================================
# 1. PARSER ARGUMEN PERINTAH /PLAN
# ==============================================================================
def parse_plan_args(arg_str: str, attached_files: Optional[List[str]] = None) -> Dict[str, Any]:
    """Parse argumen perintah /plan."""
    parts = arg_str.strip().split()
    topic_words = []
    duration = 45.0
    media_list = list(attached_files) if attached_files else []
    vo_file = None
    bgm_file = None

    idx = 0
    while idx < len(parts):
        token = parts[idx]
        if token == "--duration" and idx + 1 < len(parts):
            try:
                duration = float(parts[idx + 1])
            except ValueError:
                pass
            idx += 2
        elif token == "--media" and idx + 1 < len(parts):
            m_path = parts[idx + 1]
            if m_path not in media_list:
                media_list.append(m_path)
            idx += 2
        elif token == "--vo" and idx + 1 < len(parts):
            vo_file = parts[idx + 1]
            idx += 2
        elif token == "--bgm" and idx + 1 < len(parts):
            bgm_file = parts[idx + 1]
            idx += 2
        else:
            topic_words.append(token)
            idx += 1

    topic = " ".join(topic_words).strip() or "Konten Otomatis"
    return {
        "topic": topic,
        "duration": duration,
        "media": media_list,
        "vo": vo_file,
        "bgm": bgm_file
    }

# ==============================================================================
# 2. HANDLER MODE CLI LANGSUNG (DIAWALI '/')
# ==============================================================================
def handle_cli_command(
    cmd_line: str,
    attached_files: Optional[List[str]] = None,
    session: Optional[Dict[str, Any]] = None,
    event_callback: Optional[Callable] = None
) -> Dict[str, Any]:
    """Mengeksekusi perintah CLI instan (0 token AI)."""
    session = session if session is not None else load_session()
    raw = cmd_line.strip()
    cmd_name = raw.split()[0].lower()
    arg_str = raw[len(cmd_name):].strip()

    logs: List[str] = [f"[CLI] Perintah: {raw}"]
    action_cards: List[Dict[str, Any]] = []
    steps: List[Dict[str, str]] = []
    reply = ""

    # /help
    if cmd_name == "/help":
        reply = (
            "[CLI] PANDUAN PERINTAH OPERATOR:\n"
            "  /help                     - Tampilkan bantuan ini\n"
            "  /status                   - Cek status API provider, workspace, & sisa storage\n"
            "  /workspaces               - Daftar akun & status Skill DNA\n"
            "  /use <nama_workspace>     - Ganti workspace aktif\n"
            "  /assets [scan|list|search <q>] - Kelola aset visual & audio lokal\n"
            "  /fetch <url> [start-end]  - Unduh potongan klip 1080p in-process\n"
            "  /plan <topik> [opsi]      - Susun rencana plan.json & contract.json\n"
            "  /render [plan_path]       - Render video 1080p in-process\n"
            "  /clear                    - Bersihkan riwayat percakapan sesi"
        )
        logs.append("[OK] Bantuan ditampilkan.")

    # /status
    elif cmd_name == "/status":
        from engine.router import get_all_providers_status
        prov_info = get_all_providers_status()
        providers = prov_info.get("providers", {})
        storage = get_storage_info()

        prov_lines = []
        for p, st in providers.items():
            status_tag = st.get("status", "UNKNOWN")
            ready_k = st.get("ready", 0)
            total_k = st.get("total_keys", 0)
            prov_lines.append(f"  - {p.upper():<10}: {status_tag} ({ready_k}/{total_k} key siap)")

        reply = (
            "[STATUS] SISTEM & RUNTIME CONTENTSTUDIO:\n"
            f"  Workspace Aktif : {session.get('active_workspace', '-')}\n"
            f"  Last Plan       : {session.get('last_plan_path') or '-'}\n"
            f"  Last Output     : {session.get('last_output_video') or '-'}\n"
            f"  Sisa Storage    : {storage['free_gb']} GB bebas dari total {storage['total_gb']} GB ({storage['pct_used']}% terpakai)\n"
            "  Provider LLM Status:\n" + "\n".join(prov_lines)
        )
        logs.append("[OK] Status sistem diperbarui.")

    # /workspaces
    elif cmd_name == "/workspaces":
        from engine.director import get_workspaces_list
        ws_list = get_workspaces_list()
        lines = ["[STATUS] DAFTAR WORKSPACE TERSEDIA:"]
        for item in ws_list:
            w_name = item.get("workspace", "")
            w_st = item.get("status", "").upper()
            w_niche = item.get("niche", "-")
            active_marker = " [AKTIF]" if w_name == session.get("active_workspace") else ""
            lines.append(f"  * {w_name:<16} [{w_st}]{active_marker} - {w_niche}")
        reply = "\n".join(lines)
        logs.append(f"[OK] {len(ws_list)} workspace ditemukan.")

    # /use <workspace>
    elif cmd_name == "/use":
        target_ws = arg_str.strip()
        if not target_ws:
            reply = "[ERR] Tentukan nama workspace. Contoh: /use hok_insights"
            logs.append("[ERR] Argumen workspace kosong.")
            return {
                "status": "error",
                "mode": "cli",
                "active_workspace": session.get("active_workspace", ""),
                "reply": reply,
                "logs": logs,
                "steps": steps,
                "action_cards": action_cards
            }

        ws_full_path = os.path.join(get_workspaces_dir(), target_ws)
        if not os.path.exists(ws_full_path) or not os.path.isdir(ws_full_path):
            reply = f"[ERR] Workspace '{target_ws}' tidak ditemukan di {ws_full_path}. Ketik /workspaces untuk melihat daftar."
            logs.append(f"[ERR] Workspace '{target_ws}' tidak valid.")
        else:
            session["active_workspace"] = target_ws
            save_session(session)
            reply = f"[OK] Workspace aktif dialihkan ke: '{target_ws}'."
            logs.append(f"[OK] Active workspace set to {target_ws}.")

    # /assets [scan | list | search <q>]
    elif cmd_name == "/assets":
        sub = arg_str.strip().split()
        sub_act = sub[0].lower() if sub else "list"
        query = " ".join(sub[1:]).strip() if len(sub) > 1 else ""

        from engine.assets import scan_assets_library
        if sub_act == "scan":
            logs.append("[OP] Memindai folder assets lokal...")
            res = scan_assets_library()
            total = res.get("total_assets", 0)
            reply = f"[OK] Pemindaian selesai. Total {total} aset tercatat di assets/library.json."
            logs.append(f"[OK] Scan selesai: {total} aset.")
        elif sub_act == "search":
            if not query:
                reply = "[ERR] Tentukan kata kunci pencarian. Contoh: /assets search hook"
                logs.append("[ERR] Query search kosong.")
            else:
                from engine.bridge import list_assets
                all_assets_json = list_assets()
                all_data = json.loads(all_assets_json)
                items = all_data.get("assets", [])
                matched = [
                    it for it in items
                    if query.lower() in it.get("name", "").lower()
                    or query.lower() in str(it.get("tags", [])).lower()
                    or query.lower() in it.get("type", "").lower()
                ]
                if not matched:
                    reply = f"[INFO] Tidak ditemukan aset dengan kata kunci: '{query}'."
                else:
                    lines = [f"[STATUS] HASIL PENCARIAN ASET ('{query}', total {len(matched)}):"]
                    for m in matched[:15]:
                        lines.append(f"  - [{m.get('type', '-').upper():<10}] {m.get('name', '-'):<25} ({m.get('path', '-')})")
                    reply = "\n".join(lines)
                logs.append(f"[OK] Search selesai: {len(matched)} hasil.")
        else:  # list
            from engine.bridge import list_assets
            all_assets_json = list_assets()
            all_data = json.loads(all_assets_json)
            items = all_data.get("assets", [])
            lines = [f"[STATUS] KATALOG ASET LOKAL (Total: {len(items)}):"]
            for m in items[:15]:
                lines.append(f"  - [{m.get('type', '-').upper():<10}] {m.get('name', '-'):<25} ({m.get('path', '-')})")
            if len(items) > 15:
                lines.append(f"  ... dan {len(items) - 15} aset lainnya. Gunakan '/assets search <kata_kunci>' untuk filter.")
            reply = "\n".join(lines)
            logs.append(f"[OK] Katalog aset dimuat ({len(items)} item).")

    # /fetch <url> [start-end]
    elif cmd_name == "/fetch":
        parts = arg_str.strip().split()
        if not parts:
            reply = "[ERR] Tentukan URL yang ingin diunduh. Contoh: /fetch https://youtu.be/xxxx 00:00:10-00:00:20"
            logs.append("[ERR] Argumen URL kosong.")
        else:
            url = parts[0]
            section = parts[1] if len(parts) > 1 else ""
            active_ws = session.get("active_workspace", "hok_insights")
            out_dir = os.path.join(get_workspaces_dir(), active_ws, "inbox")
            os.makedirs(out_dir, exist_ok=True)

            logs.append(f"[OP] Mengunduh media in-process dari: {url}")
            from engine.fetch import download_media
            try:
                res = download_media(
                    url=url,
                    out_dir=out_dir,
                    max_height=1080,
                    section=section,
                    on_progress=event_callback
                )
                f_path = res.get("file_path", "")
                sz = res.get("size_bytes", 0)
                reply = (
                    f"[OK] Media berhasil diunduh (1080p):\n"
                    f"  File  : {f_path}\n"
                    f"  Ukuran: {round(sz / (1024 * 1024), 2)} MB\n"
                    f"Media siap digunakan dalam perencanaan (/plan)."
                )
                logs.append(f"[OK] Download berhasil: {f_path}")
            except Exception as e:
                reply = f"[ERR] Gagal mengunduh media: {e}"
                logs.append(f"[ERR] Download error: {e}")

    # /plan <topik> [opsi]
    elif cmd_name == "/plan":
        plan_params = parse_plan_args(arg_str, attached_files)
        active_ws = session.get("active_workspace", "hok_insights")
        proj_name = f"proj_{datetime.now().strftime('%Y%m%d_%H%M%S')}"

        logs.append(f"[OP] Menyusun rencana konten di workspace '{active_ws}'...")
        req_dict = {
            "workspace": active_ws,
            "project": proj_name,
            "topic": plan_params["topic"],
            "duration": plan_params["duration"],
            "media": plan_params["media"],
            "vo": plan_params["vo"],
            "bgm": plan_params["bgm"]
        }

        from engine.director import create_plan_pipeline
        try:
            res = create_plan_pipeline(req_dict, on_event=event_callback, json_events=False)
            if res.get("status") == "ok":
                plan_path = res.get("plan_path", "")
                contract = res.get("contract") or {}
                session["last_plan_path"] = plan_path
                save_session(session)

                c_title = contract.get("title", plan_params["topic"])
                c_hook = contract.get("hook", "-")
                c_dur = contract.get("duration", plan_params["duration"])
                c_shots = len(contract.get("scenes", []))

                reply = (
                    f"[PLAN] RENCANA KONTEN BERHASIL DISUSUN:\n"
                    f"  Judul     : {c_title}\n"
                    f"  Hook      : {c_hook}\n"
                    f"  Target Dur: {c_dur}s ({c_shots} adegan)\n"
                    f"  File Plan : {plan_path}\n\n"
                    f"Konfirmasi rendering dengan mengetik: /render {plan_path}"
                )
                action_cards.append({
                    "type": "contract",
                    "title": c_title,
                    "hook": c_hook,
                    "plan_path": plan_path,
                    "primary_cmd": f"/render {plan_path}"
                })
                logs.append(f"[OK] Plan dibuat: {plan_path}")
            else:
                reply = f"[ERR] Gagal menyusun rencana: {res.get('message', 'Unknown error')}"
                logs.append(f"[ERR] Plan pipeline error: {res.get('message')}")
        except Exception as e:
            reply = f"[ERR] Kesalahan runtime saat membuat rencana: {e}"
            logs.append(f"[ERR] Exception in plan pipeline: {e}")

    # /render [plan_path]
    elif cmd_name == "/render":
        p_path = arg_str.strip() or session.get("last_plan_path")
        if not p_path:
            reply = "[ERR] Tidak ada rencana untuk dirender. Tentukan path atau jalankan /plan terlebih dahulu."
            logs.append("[ERR] Plan path kosong.")
        else:
            p_path = resolve_path(p_path)
            if not os.path.exists(p_path):
                reply = f"[ERR] File plan.json tidak ditemukan: {p_path}"
                logs.append(f"[ERR] File not found: {p_path}")
            else:
                logs.append(f"[RENDER] Memulai rendering Full HD 1080p: {p_path}...")
                from engine.bridge import render_project
                try:
                    res_json = render_project(p_path, event_callback=event_callback)
                    res = json.loads(res_json)
                    if res.get("status") == "ok":
                        out_vid = res.get("output_video", "")
                        session["last_output_video"] = out_vid
                        save_session(session)

                        res_str = res.get("resolution", "1080x1920")
                        dur_sec = res.get("duration", 0.0)
                        sz_mb = round(res.get("size_bytes", 0) / (1024 * 1024), 2)

                        reply = (
                            f"[RENDER] PRODUKSI VIDEO 1080P SELESAI:\n"
                            f"  Output    : {out_vid}\n"
                            f"  Resolusi  : {res_str}\n"
                            f"  Durasi    : {dur_sec}s\n"
                            f"  Ukuran    : {sz_mb} MB"
                        )
                        action_cards.append({
                            "type": "video_ready",
                            "title": "Render 1080p Selesai",
                            "path": out_vid
                        })
                        logs.append(f"[OK] Render selesai: {out_vid}")
                    else:
                        reply = f"[ERR] Rendering gagal: {res.get('message', 'Unknown error')}"
                        logs.append(f"[ERR] Render error: {res.get('message')}")
                except Exception as e:
                    reply = f"[ERR] Kesalahan runtime saat render: {e}"
                    logs.append(f"[ERR] Exception in render: {e}")

    # /clear
    elif cmd_name == "/clear":
        session["history"] = []
        save_session(session)
        reply = "[OK] Riwayat percakapan sesi telah dibersihkan."
        logs.append("[OK] Sesi di-reset.")

    else:
        reply = f"[ERR] Perintah tidak dikenali: '{cmd_name}'. Ketik /help untuk daftar perintah yang tersedia."
        logs.append(f"[ERR] Unknown command: {cmd_name}")

    return {
        "status": "ok",
        "mode": "cli",
        "active_workspace": session.get("active_workspace", ""),
        "reply": reply,
        "logs": logs,
        "steps": steps,
        "action_cards": action_cards
    }

# ==============================================================================
# 3. HANDLER AUTONOMOUS AGENT (AGENTIC TOOL-CALLING LOOP MAKS 8 LANGKAH)
# ==============================================================================
AGENT_TOOLS_SCHEMA = """
Tersedia tools kerja otonom yang dapat kamu panggil:
1. read_file: {"rel_path": "<path_relatif>"} -> Membaca isi file di workspaces/, .agents/skills/, atau assets/
2. write_file: {"rel_path": "<path_relatif>", "content": "<teks_atau_json>"} -> Menulis/mengedit file di workspaces/ atau .agents/skills/ (untuk revisi plan.json, style.md, SKILL.md, dll.)
3. validate_plan: {"plan_path": "<path_ke_plan.json>"} -> Menguji validasi plan.json in-process sebelum dirender
4. create_workspace: {"name": "<nama>", "niche_md": "<teks>", "style_md": "<teks>", "laws_md": "<teks>", "skill_md": "<teks>"} -> Membuat struktur workspace baru dan Skill DNA
5. list_workspaces: {} -> Daftar workspace dan status Skill DNA
6. set_workspace: {"workspace": "<nama>"} -> Ubah workspace aktif
7. list_assets: {"asset_type": "<sticker|sfx|background|kosong>", "tag": "<kata_kunci|kosong>"} -> List aset library
8. scan_assets: {} -> Pindai ulang folder aset lokal
9. fetch_media: {"url": "<url>", "section": "<00:00:10-00:00:20|kosong>"} -> Unduh potongan media 1080p
10. inspect_media: {"file_path": "<path>"} -> Probe durasi, resolusi, aspek rasio media
11. create_plan: {"topic": "<topik>", "duration": <detik_float>, "media_paths": ["<path1>", ...]} -> Susun rencana & kontrak video
12. render_project: {"plan_path": "<path_ke_plan.json>"} -> Render Full HD 1080p video
"""

def clean_llm_json(raw: str) -> str:
    """Membersihkan format output JSON dari blok markdown ```json."""
    raw = raw.strip()
    if raw.startswith("```"):
        raw = re.sub(r"^```[a-zA-Z0-9_-]*\n", "", raw)
        raw = re.sub(r"\n```$", "", raw)
    return raw.strip()

def execute_agent_tool(tool_name: str, args: Dict[str, Any], session: Dict[str, Any],
                       event_callback: Optional[Callable] = None) -> Tuple[Any, Optional[Dict[str, Any]], str, str]:
    """
    Mengeksekusi tool internal in-process dengan sandboxing.
    Return: (tool_result, optional_action_card, log_msg, step_detail)
    """
    card = None

    # TOOL 1: read_file
    if tool_name == "read_file":
        rel_path = args.get("rel_path", "").strip()
        safe_path = sanitize_safe_path(rel_path)
        if not safe_path or not os.path.exists(safe_path):
            return {"status": "error", "message": f"File tidak ditemukan atau di luar sandbox: {rel_path}"}, None, f"[ERR] File tidak ada: {rel_path}", f"Gagal membaca {rel_path}"
        try:
            with open(safe_path, "r", encoding="utf-8") as f:
                content = f.read(100_000)  # Batasi 100KB
            return {"status": "ok", "path": rel_path, "content": content}, None, f"[OK] Membaca {rel_path} ({len(content)} karakter).", f"Membaca isi file {rel_path}"
        except Exception as e:
            return {"status": "error", "message": str(e)}, None, f"[ERR] Gagal membaca: {e}", f"Error membaca {rel_path}"

    # TOOL 2: write_file
    elif tool_name == "write_file":
        rel_path = args.get("rel_path", "").strip()
        content = args.get("content", "")
        safe_path = sanitize_safe_path(rel_path)
        if not safe_path:
            return {"status": "error", "message": f"Path tidak valid atau di luar sandbox: {rel_path}"}, None, f"[ERR] Path invalid: {rel_path}", f"Ditolak: di luar sandbox"
        try:
            os.makedirs(os.path.dirname(safe_path), exist_ok=True)
            with open(safe_path, "w", encoding="utf-8") as f:
                f.write(content)
            # Jika yang diedit adalah plan.json, perbarui session last_plan_path
            if safe_path.endswith("plan.json"):
                session["last_plan_path"] = safe_path
                save_session(session)
            return {"status": "ok", "path": rel_path, "bytes_written": len(content)}, None, f"[OK] Menulis file {rel_path} ({len(content)} byte).", f"Menulis ke file {rel_path}"
        except Exception as e:
            return {"status": "error", "message": str(e)}, None, f"[ERR] Gagal menulis: {e}", f"Error menulis {rel_path}"

    # TOOL 3: validate_plan
    elif tool_name == "validate_plan":
        p_path = resolve_path(args.get("plan_path") or session.get("last_plan_path", ""))
        from engine.build import validate_plan_file
        is_valid, err_msg = validate_plan_file(p_path)
        if is_valid:
            return {"status": "ok", "valid": True, "plan_path": p_path}, None, f"[OK] Validasi sukses: {p_path}.", f"Memvalidasi plan.json: LULUS"
        else:
            return {"status": "invalid", "valid": False, "plan_path": p_path, "error": err_msg}, None, f"[WARN] Validasi gagal: {err_msg[:80]}.", f"Memvalidasi plan.json: GAGAL ({err_msg[:60]})"

    # TOOL 4: create_workspace
    elif tool_name == "create_workspace":
        name = args.get("name", "").strip().lower().replace(" ", "_")
        if not name:
            return {"status": "error", "message": "Nama workspace wajib diisi."}, None, "[ERR] Nama workspace kosong.", "Gagal: nama kosong"

        ws_dir = os.path.join(get_workspaces_dir(), name)
        skill_dir = os.path.join(get_skills_dir(), f"{name}-director")
        os.makedirs(ws_dir, exist_ok=True)
        os.makedirs(os.path.join(ws_dir, "inbox"), exist_ok=True)
        os.makedirs(os.path.join(ws_dir, "projects"), exist_ok=True)
        os.makedirs(skill_dir, exist_ok=True)

        niche_md = args.get("niche_md") or f"# NICHE: {name.upper()}\nWorkspace konten mandiri untuk niche {name}."
        style_md = args.get("style_md") or "# STYLE & TONE\n- Resolusi: 1080p (9:16)\n- Pacing cepat dan hook kuat."
        laws_md = args.get("laws_md") or "# LAWS\n1. Seluruh media wajib beresolusi Full HD 1080x1920.\n2. Audio jernih."
        skill_md = args.get("skill_md") or f"# SKILL: {name}-director\nSutradara otomatis untuk workspace {name}."

        with open(os.path.join(ws_dir, "niche.md"), "w", encoding="utf-8") as f:
            f.write(niche_md)
        with open(os.path.join(ws_dir, "style.md"), "w", encoding="utf-8") as f:
            f.write(style_md)
        with open(os.path.join(ws_dir, "laws.md"), "w", encoding="utf-8") as f:
            f.write(laws_md)
        with open(os.path.join(skill_dir, "SKILL.md"), "w", encoding="utf-8") as f:
            f.write(skill_md)

        session["active_workspace"] = name
        save_session(session)
        return {"status": "ok", "workspace": name, "ws_dir": ws_dir}, None, f"[OK] Workspace '{name}' berhasil dibuat.", f"Membuat workspace '{name}' & Skill DNA"

    # TOOL 5: list_workspaces
    elif tool_name == "list_workspaces":
        from engine.director import get_workspaces_list
        res = get_workspaces_list()
        return res, None, f"[OK] Diperoleh {len(res)} workspace.", "Melihat daftar workspace"

    # TOOL 6: set_workspace
    elif tool_name == "set_workspace":
        target = args.get("workspace", "").strip()
        ws_path = os.path.join(get_workspaces_dir(), target)
        if os.path.exists(ws_path) and os.path.isdir(ws_path):
            session["active_workspace"] = target
            save_session(session)
            return {"status": "ok", "active_workspace": target}, None, f"[OK] Workspace aktif set ke {target}.", f"Mengganti workspace ke '{target}'"
        return {"status": "error", "message": f"Workspace '{target}' tidak ditemukan."}, None, f"[ERR] Workspace '{target}' tidak ditemukan.", f"Gagal ganti workspace"

    # TOOL 7: list_assets
    elif tool_name == "list_assets":
        from engine.bridge import list_assets
        res_str = list_assets(asset_type=args.get("asset_type", ""), tag=args.get("tag", ""))
        return json.loads(res_str), None, "[OK] Katalog aset dimuat.", "Melihat katalog aset"

    # TOOL 8: scan_assets
    elif tool_name == "scan_assets":
        from engine.assets import scan_assets_library
        res = scan_assets_library()
        return res, None, f"[OK] Scan aset selesai: {res.get('total_assets', 0)} item.", "Memindai folder aset"

    # TOOL 9: fetch_media
    elif tool_name == "fetch_media":
        url = args.get("url", "")
        sec = args.get("section", "")
        active_ws = session.get("active_workspace", "hok_insights")
        out_d = os.path.join(get_workspaces_dir(), active_ws, "inbox")
        from engine.fetch import download_media
        res = download_media(url=url, out_dir=out_d, max_height=1080, section=sec, on_progress=event_callback)
        return res, None, f"[OK] Download media selesai: {res.get('file_path')}.", f"Mengunduh klip dari {url[:30]}..."

    # TOOL 10: inspect_media
    elif tool_name == "inspect_media":
        f_path = resolve_path(args.get("file_path", ""))
        from engine.director import probe_media_file
        res = probe_media_file(f_path)
        return res, None, f"[OK] Inspeksi media selesai: {f_path}.", f"Menginspeksi media {os.path.basename(f_path)}"

    # TOOL 11: create_plan
    elif tool_name == "create_plan":
        topic = args.get("topic", "Konten Video")
        dur = float(args.get("duration", 45.0) or 45.0)
        m_paths = args.get("media_paths") or []
        active_ws = session.get("active_workspace", "hok_insights")
        proj_name = f"proj_{datetime.now().strftime('%Y%m%d_%H%M%S')}"

        req_dict = {
            "workspace": active_ws,
            "project": proj_name,
            "topic": topic,
            "duration": dur,
            "media": m_paths
        }
        from engine.director import create_plan_pipeline
        res = create_plan_pipeline(req_dict, on_event=event_callback, json_events=False)
        if res.get("status") == "ok":
            plan_path = res.get("plan_path", "")
            session["last_plan_path"] = plan_path
            save_session(session)
            contract = res.get("contract") or {}
            card = {
                "type": "contract",
                "title": contract.get("title", topic),
                "hook": contract.get("hook", "-"),
                "plan_path": plan_path,
                "primary_cmd": f"/render {plan_path}"
            }
            return res, card, f"[OK] Plan dibuat: {plan_path}.", f"Menyusun rencana video '{topic}'"
        return res, None, f"[ERR] Gagal membuat plan: {res.get('message')}.", "Gagal menyusun plan"

    # TOOL 12: render_project
    elif tool_name == "render_project":
        plan_path = resolve_path(args.get("plan_path") or session.get("last_plan_path", ""))
        from engine.bridge import render_project
        res_str = render_project(plan_path, event_callback=event_callback)
        res = json.loads(res_str)
        if res.get("status") == "ok":
            out_vid = res.get("output_video", "")
            session["last_output_video"] = out_vid
            save_session(session)
            card = {
                "type": "video_ready",
                "title": "Render 1080p Selesai",
                "path": out_vid
            }
            return res, card, f"[OK] Render selesai: {out_vid}.", f"Merender video 1080p"
        return res, None, f"[ERR] Gagal render: {res.get('message')}.", "Gagal merender video"

    else:
        return {"status": "error", "message": f"Tool '{tool_name}' tidak dikenal."}, None, f"[ERR] Tool '{tool_name}' tidak dikenal.", f"Tool '{tool_name}' tidak dikenal"

def handle_agent_instruction(
    user_input: str,
    attached_files: Optional[List[str]] = None,
    session: Optional[Dict[str, Any]] = None,
    event_callback: Optional[Callable] = None
) -> Dict[str, Any]:
    """
    Menangani instruksi bahasa alami bebas (Autonomous CLI Agent) dengan agentic tool-calling loop (maks 8 langkah).
    Mendukung modifikasi file, self-validation, guardrail konfirmasi, dan streaming steps.
    """
    session = session if session is not None else load_session()
    logs: List[str] = [f"[AGENT] Menerima instruksi: {user_input[:80]}..."]
    action_cards: List[Dict[str, Any]] = []
    steps: List[Dict[str, str]] = []

    input_lower = user_input.lower()
    auto_render_requested = any(k in input_lower for k in [
        "langsung render", "render sekarang", "auto render", "tanpa konfirmasi", "direct render"
    ])

    from engine.router import route_call

    history = session.get("history", [])
    active_ws = session.get("active_workspace", "hok_insights")
    last_plan = session.get("last_plan_path")

    history_snippet = ""
    if history:
        history_snippet = "Riwayat percakapan sebelumnya:\n"
        for turn in history[-4:]:
            r = turn.get("role", "user")
            c = turn.get("content", "")
            history_snippet += f"- {r.upper()}: {c}\n"

    system_prompt = f"""Kamu adalah Autonomous AI Agent Mandiri ContentStudio, asisten produksi video pendek otomatis profesional.
ATURAN KERAS:
1. DILARANG KERAS MENGGUNAKAN EMOJI DALAM BENTUK APA PUN. Gunakan tag ASCII bersih seperti [OP], [OK], [ERR], [PLAN], [RENDER].
2. Berikan jawaban yang padat, akurat, dan langsung ke inti.
3. Workspace aktif saat ini: '{active_ws}'.
4. File plan terakhir: '{last_plan or "None"}'.
5. File terlampir dari input: {json.dumps(attached_files or [])}.

{AGENT_TOOLS_SCHEMA}

KEMAMPUAN OTONOM:
- Kamu bisa membaca file (read_file), menulis/memperbaiki konfigurasi atau plan.json (write_file), dan mengujinya sendiri (validate_plan).
- Jika pengguna meminta membuat niche/akun baru, panggil 'create_workspace'.
- Jika pengguna meminta revisi hook/durasi/shot pada plan yang sudah ada, baca file dengan 'read_file', sesuaikan isinya dengan 'write_file', lalu validasi dengan 'validate_plan'.

GUARDRAIL PRODUKSI KONTEN:
- Jika pengguna meminta membuat atau merakit video baru dari nol, panggil tool 'create_plan'.
- SETELAH tool 'create_plan' berhasil, kamu WAJIB BERHENTI (jangan panggil tool render_project), KECUALI jika pengguna meminta langsung render (auto_render={auto_render_requested}). Pengguna harus mengonfirmasi rancangan sebelum render dimulai.

FORMAT RESPON:
Kamu HARUS menjawab HANYA dalam format JSON valid satu objek (tanpa blok markdown):
{{
  "thought": "Penalaran langkah kamu (maksimal 1 kalimat)",
  "tool_call": {{
    "name": "nama_tool_atau_null",
    "args": {{ ... }}
  }},
  "reply": "Pesan atau kesimpulan untuk pengguna"
}}
Jika tidak perlu memanggil tool lagi (jawaban final), isi "tool_call": null.
"""

    loop_count = 0
    max_loops = 8
    current_prompt = f"{system_prompt}\n{history_snippet}\nInstruksi Pengguna:\n{user_input}\n"
    final_reply = ""

    while loop_count < max_loops:
        loop_count += 1
        logs.append(f"[OP] AI memproses penalaran langkah #{loop_count}...")

        try:
            raw_response = route_call(task="json", prompt=current_prompt, pace=2.0, raise_on_error=True)
            clean_resp = clean_llm_json(raw_response)
            parsed = json.loads(clean_resp)
        except Exception as e:
            err_msg = f"[OP] Gagal menghubungi layanan AI router: {e}. Anda dapat menggunakan perintah CLI langsung (ketik /help)."
            logs.append(f"[ERR] LLM call failed: {e}")
            final_reply = err_msg
            break

        thought = parsed.get("thought", "")
        tool_call = parsed.get("tool_call")
        partial_reply = parsed.get("reply", "")

        if thought:
            logs.append(f"[OP] Penalaran: {thought}")

        if not tool_call or not isinstance(tool_call, dict) or not tool_call.get("name"):
            final_reply = partial_reply or thought or "[OK] Selesai."
            break

        tool_name = tool_call.get("name")
        tool_args = tool_call.get("args") or {}
        logs.append(f"[TOOL] Memanggil '{tool_name}'...")

        # Pancarkan event running ke UI
        emit_step_event(event_callback, tool_name, "running", f"Menjalankan {tool_name}...")

        tool_result, card, tool_log, step_detail = execute_agent_tool(tool_name, tool_args, session, event_callback)
        logs.append(tool_log)
        is_err = tool_result.get("status") == "error" if isinstance(tool_result, dict) else False
        step_st = "error" if is_err else "done"

        # Catat langkah kerja
        steps.append({
            "tool": tool_name,
            "status": step_st,
            "detail": step_detail
        })
        emit_step_event(event_callback, tool_name, step_st, step_detail)

        if card:
            action_cards.append(card)

        # Guardrail konfirmasi setelah create_plan
        if tool_name == "create_plan":
            c_dict = tool_result.get("contract") or {}
            c_title = c_dict.get("title", "Rencana Konten")
            c_hook = c_dict.get("hook", "-")
            p_path = tool_result.get("plan_path", "")

            if not auto_render_requested:
                final_reply = (
                    f"[PLAN] Rencana video '{c_title}' telah siap disusun:\n"
                    f"  Hook      : {c_hook}\n"
                    f"  File Plan : {p_path}\n\n"
                    f"Silakan tinjau rancangan di atas. Jika setuju, ketik: /render {p_path}"
                )
                break
            else:
                logs.append("[OP] Mode auto-render aktif: melanjutkan eksekusi render...")

        elif tool_name == "render_project":
            out_v = tool_result.get("output_video", "")
            final_reply = partial_reply or f"[RENDER] Video Full HD 1080p berhasil dirender di: {out_v}"
            break

        current_prompt += f"\n[Tool Output '{tool_name}']:\n{json.dumps(tool_result, ensure_ascii=False)}\nLanjutkan penalaran atau berikan jawaban akhir."

    # Simpan interaksi ke riwayat sesi
    if "history" not in session:
        session["history"] = []
    session["history"].append({"role": "user", "content": user_input})
    session["history"].append({"role": "assistant", "content": final_reply})
    save_session(session)

    return {
        "status": "ok",
        "mode": "agent",
        "active_workspace": session.get("active_workspace", ""),
        "reply": final_reply,
        "logs": logs,
        "steps": steps,
        "action_cards": action_cards
    }

# ==============================================================================
# 4. FUNGSI UTAMA EKSEKUSI (execute_command)
# ==============================================================================
def execute_command(
    input_text: str,
    attached_files: Optional[List[str]] = None,
    event_callback: Optional[Callable] = None
) -> Dict[str, Any]:
    """
    Titik masuk utama eksekusi untuk Kotlin Chaquopy dan terminal console.
    Mendeteksi otomatis:
    - Jika input_text diawali '/' -> Mode CLI Langsung (0 token AI)
    - Jika input_text bebas -> Mode Autonomous Agent (loop maks 8 langkah, streaming steps)
    """
    if not input_text or not input_text.strip():
        session = load_session()
        return {
            "status": "ok",
            "mode": "cli",
            "active_workspace": session.get("active_workspace", ""),
            "reply": "[INFO] Input kosong. Ketik /help untuk melihat daftar perintah.",
            "logs": ["[INFO] Input kosong."],
            "steps": [],
            "action_cards": []
        }

    raw = input_text.strip()
    session = load_session()

    if raw.startswith("/"):
        return handle_cli_command(raw, attached_files=attached_files, session=session, event_callback=event_callback)
    else:
        return handle_agent_instruction(raw, attached_files=attached_files, session=session, event_callback=event_callback)

# ==============================================================================
# 5. REPL TERMINAL & STANDALONE CLI
# ==============================================================================
def main():
    parser = argparse.ArgumentParser(description="ContentStudio Autonomous Agent Console")
    parser.add_argument("-c", "--command", default=None, help="Jalankan satu perintah/instruksi lalu keluar")
    parser.add_argument("--files", nargs="*", default=[], help="File lampiran opsional")
    args = parser.parse_args()

    load_runtime_env()

    def _term_callback(ev: Dict[str, Any]):
        t_type = ev.get("type")
        if t_type == "step":
            print(f"  --> [STEP] {ev.get('tool')}: {ev.get('status')} ({ev.get('detail')})")
        elif t_type == "progress":
            print(f"  --> [PROG] {ev.get('stage')}: {ev.get('pct')}% - {ev.get('msg')}")
        else:
            pct = ev.get("pct", 0)
            msg = ev.get("msg", "")
            if msg:
                print(f"  --> [{pct}%] {msg}")

    if args.command:
        res = execute_command(args.command, attached_files=args.files, event_callback=_term_callback)
        print("\n" + res.get("reply", ""))
        steps = res.get("steps", [])
        if steps:
            print("\n[STEPS]:")
            for s in steps:
                print(f"  * {s.get('tool')}: {s.get('status')} - {s.get('detail')}")
        cards = res.get("action_cards", [])
        if cards:
            print("\n[ACTION CARDS]:")
            for c in cards:
                print(f"  * [{c.get('type', '').upper()}] {c.get('title', '')} -> {c.get('primary_cmd', c.get('path', ''))}")
        sys.exit(0)

    # Mode Interaktif REPL
    session = load_session()
    print("=" * 72)
    print(" CONTENTSTUDIO AUTONOMOUS AGENT CONSOLE (100% IN-PROCESS & ANDROID-SAFE) ".center(72))
    print(" Ketik /help untuk daftar perintah CLI, atau ketik langsung instruksi AI. ".center(72))
    print("=" * 72)

    while True:
        try:
            session = load_session()
            active_ws = session.get("active_workspace", "default")
            prompt_str = f"[{active_ws}] > "
            user_in = input(prompt_str).strip()
            if not user_in:
                continue
            if user_in.lower() in ("exit", "quit", "q"):
                print("[OK] Selesai. Keluar dari console.")
                break

            res = execute_command(user_in, event_callback=_term_callback)
            print("\n" + res.get("reply", "") + "\n")
            steps = res.get("steps", [])
            if steps:
                print("[STEPS]:")
                for s in steps:
                    print(f"  * {s.get('tool')}: {s.get('status')} - {s.get('detail')}")
                print()
            cards = res.get("action_cards", [])
            if cards:
                print("[ACTION CARDS]:")
                for c in cards:
                    print(f"  * [{c.get('type', '').upper()}] {c.get('title', '')} -> {c.get('primary_cmd', c.get('path', ''))}\n")
        except (KeyboardInterrupt, EOFError):
            print("\n[OK] Selesai. Keluar dari console.")
            break
        except Exception as e:
            print(f"\n[ERR] Terjadi kesalahan: {e}\n")

if __name__ == "__main__":
    main()
