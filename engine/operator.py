#!/usr/bin/env python3
"""
ContentStudio Autonomous CLI Agent & Interactive Operator (engine/operator.py)
Sprint J: Real CLI Agent dengan Native Tool-Calling, Auto-Skill, Auto-MCP Remote,
           Workspace Session Isolation, dan Perilaku Eksekutor Proaktif (Zero Pasif).
100% In-Process Python 3.11 (Chaquopy-Safe, 0 subprocess python3).
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
    get_inbox_dir,
    get_assets_dir,
    resolve_path,
    load_runtime_env
)
from engine.router import chat_with_tools, get_all_providers_status
from engine.skills import get_skill_index, load_skill, install_skill, list_skills
from engine.mcp_client import get_all_mcp_tools, is_mcp_tool, call_mcp_tool, list_mcp_servers
from engine.fetch import hunt_media
from engine.assets import load_library, scan_assets_library
from engine.reference_dna import load_reference_dna
from engine.topic_history import get_variation_context, sync_statuses

GLOBAL_SESSION_FILE = "operator_session.json"
MAX_AGENT_STEPS = 12

# ==============================================================================
# SESSION & WORKSPACE ISOLATION
# ==============================================================================

def get_session_dir() -> str:
    """Folder penyimpanan session percakapan terisolasi per workspace."""
    s_dir = os.path.join(get_cache_dir(), "sessions")
    os.makedirs(s_dir, exist_ok=True)
    return s_dir

def get_workspace_session_path(workspace: str) -> str:
    """Path file riwayat chat khusus workspace tertentu."""
    clean_ws = re.sub(r"[^a-zA-Z0-9_-]+", "_", workspace.strip().lower())
    return os.path.join(get_session_dir(), f"{clean_ws}.json")

def load_session() -> Dict[str, Any]:
    """Membaca status sesi global (active_workspace, last_plan_path, last_output_video)."""
    p = os.path.join(get_cache_dir(), GLOBAL_SESSION_FILE)
    default_state = {
        "active_workspace": "default",
        "last_plan_path": None,
        "last_output_video": None
    }
    if os.path.exists(p):
        try:
            with open(p, "r", encoding="utf-8") as f:
                data = json.load(f)
                if isinstance(data, dict):
                    for k, v in default_state.items():
                        if k not in data:
                            data[k] = v
                    return data
        except Exception:
            pass

    # Verifikasi active_workspace awal dari folder workspaces
    ws_dir = get_workspaces_dir()
    if os.path.exists(ws_dir):
        avail = [d for d in os.listdir(ws_dir) if os.path.isdir(os.path.join(ws_dir, d)) and not d.startswith(".")]
        if avail and default_state["active_workspace"] not in avail:
            default_state["active_workspace"] = avail[0]

    return default_state

def save_session(session: Dict[str, Any]) -> None:
    """Menyimpan status sesi global secara atomic."""
    p = os.path.join(get_cache_dir(), GLOBAL_SESSION_FILE)
    tmp = p + ".tmp"
    try:
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(session, f, indent=2, ensure_ascii=False)
        os.replace(tmp, p)
    except Exception:
        if os.path.exists(tmp):
            try: os.remove(tmp)
            except Exception: pass

def load_chat_history(workspace: str) -> List[Dict[str, Any]]:
    """Membaca riwayat percakapan terisolasi untuk suatu workspace (maks 20 pesan terakhir)."""
    p = get_workspace_session_path(workspace)
    if os.path.exists(p):
        try:
            with open(p, "r", encoding="utf-8") as f:
                data = json.load(f)
                if isinstance(data, list):
                    return data[-20:]
        except Exception:
            pass
    return []

def save_chat_history(workspace: str, history: List[Dict[str, Any]]) -> None:
    """Menyimpan riwayat percakapan untuk workspace aktif."""
    p = get_workspace_session_path(workspace)
    tmp = p + ".tmp"
    try:
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(history[-20:], f, indent=2, ensure_ascii=False)
        os.replace(tmp, p)
    except Exception:
        if os.path.exists(tmp):
            try: os.remove(tmp)
            except Exception: pass

def clear_chat_history(workspace: str) -> None:
    """Menghapus riwayat percakapan workspace."""
    p = get_workspace_session_path(workspace)
    if os.path.exists(p):
        try: os.remove(p)
        except Exception: pass

def get_storage_info() -> Dict[str, Any]:
    """Membaca informasi kapasitas penyimpanan."""
    root_d = get_root_dir()
    out_d = get_output_base_dir()
    try:
        check_target = out_d if os.path.exists(out_d) else root_d
        usage = shutil.disk_usage(check_target)
        free_gb = usage.free / (1024 ** 3)
        total_gb = usage.total / (1024 ** 3)
        used_gb = usage.used / (1024 ** 3)
        pct = (usage.used / usage.total) * 100.0 if usage.total > 0 else 0.0
        return {
            "free_gb": round(free_gb, 2),
            "total_gb": round(total_gb, 2),
            "used_gb": round(used_gb, 2),
            "pct_used": round(pct, 1),
            "safe": free_gb >= 1.0
        }
    except Exception:
        return {"free_gb": 0.0, "total_gb": 0.0, "used_gb": 0.0, "pct_used": 0.0, "safe": False}

# ==============================================================================
# WORKSPACE CONTEXT EXTRACTOR
# ==============================================================================

def get_workspace_context(workspace: str) -> Dict[str, str]:
    """Membaca ringkasan DNA (niche, style, laws, skill) dari workspace aktif."""
    ws_dir = os.path.join(get_workspaces_dir(), workspace)
    context = {
        "workspace": workspace,
        "niche": "",
        "style": "",
        "laws": "",
        "skill": ""
    }
    if not os.path.exists(ws_dir):
        return context

    for k in ["niche", "style", "laws"]:
        f_path = os.path.join(ws_dir, f"{k}.md")
        if os.path.exists(f_path):
            try:
                with open(f_path, "r", encoding="utf-8") as f:
                    context[k] = f.read().strip()[:1000]
            except Exception:
                pass

    # Cari skill director terkait
    skill_data = load_skill(f"{workspace}-director")
    if skill_data.get("status") == "ok":
        context["skill"] = skill_data.get("content", "")[:1200]

    return context

# ==============================================================================
# CORE AGENT TOOLBOX DEFINITION (OPENAI SCHEMAS)
# ==============================================================================

CORE_TOOLS: List[Dict[str, Any]] = [
    {
        "type": "function",
        "function": {
            "name": "ls_dir",
            "description": "Melihat daftar file dan subdirektori di path tertentu (relatif terhadap root ContentStudio).",
            "parameters": {
                "type": "object",
                "properties": {
                    "path": {"type": "string", "description": "Path relatif direktori yang ingin dilihat (contoh: 'inbox', 'workspaces', 'assets'). Default '.'."}
                }
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "read_file",
            "description": "Membaca isi file teks di ContentStudio (project.json, plan.json, dsb).",
            "parameters": {
                "type": "object",
                "properties": {
                    "path": {"type": "string", "description": "Path relatif atau absolut ke file yang ingin dibaca."}
                },
                "required": ["path"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "write_file",
            "description": "Menulis atau memperbarui file teks di ContentStudio (membuat direktori otomatis jika belum ada).",
            "parameters": {
                "type": "object",
                "properties": {
                    "path": {"type": "string", "description": "Path tujuan file yang akan ditulis."},
                    "content": {"type": "string", "description": "Isi teks lengkap yang akan ditulis ke file."}
                },
                "required": ["path", "content"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "delete_path",
            "description": "Menghapus file atau direktori yang tidak diperlukan untuk menghemat penyimpanan.",
            "parameters": {
                "type": "object",
                "properties": {
                    "path": {"type": "string", "description": "Path file atau direktori yang akan dihapus."}
                },
                "required": ["path"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "create_workspace",
            "description": "Membuat akun/workspace baru beserta direktori proyek dan konfigurasinya.",
            "parameters": {
                "type": "object",
                "properties": {
                    "name": {"type": "string", "description": "Nama unik workspace (hanya huruf, angka, underscore)."},
                    "aspect_ratio": {"type": "string", "description": "Rasio aspek default ('9:16', '16:9', dsb). Default '9:16'."}
                },
                "required": ["name"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "list_assets",
            "description": "Mencari stiker, meme, SFX, atau background yang tersedia di bank aset lokal.",
            "parameters": {
                "type": "object",
                "properties": {
                    "category": {"type": "string", "description": "Filter kategori ('sticker', 'meme', 'sfx', 'background', atau kosong untuk semua)."},
                    "tag": {"type": "string", "description": "Kata kunci tag pencarian (contoh: 'hook', 'arrow', 'funny')."}
                }
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "load_skill",
            "description": "Memuat isi penuh SOP Skill operasional (on-demand) untuk memandu pembuatan konten spesifik.",
            "parameters": {
                "type": "object",
                "properties": {
                    "name": {"type": "string", "description": "Nama atau slug skill yang ingin dimuat (contoh: 'short-form-producer', 'media-hunter')."}
                },
                "required": ["name"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "install_skill",
            "description": "Menginstal skill baru secara dinamis dari URL GitHub repo, URL raw SKILL.md, atau path ZIP lokal.",
            "parameters": {
                "type": "object",
                "properties": {
                    "source": {"type": "string", "description": "URL raw SKILL.md, URL repository GitHub, atau path file ZIP lokal."}
                },
                "required": ["source"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "hunt_media",
            "description": "Berburu dan mengunduh bahan footage video (clip), meme reaksi (meme), atau efek suara (sfx) secara in-process via YouTube search.",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "Kata kunci pencarian media (contoh: 'cinematic drone nature', 'reaction meme', 'whoosh sound')."},
                    "media_type": {"type": "string", "description": "Tipe media yang dicari: 'clip' (video footage 1080p), 'meme' (video reaksi pendek), atau 'sfx' (audio efek suara). Default 'clip'."},
                    "duration": {"type": "number", "description": "Durasi potongan detik yang diinginkan (default 6.0 detik)."}
                },
                "required": ["query"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "validate_plan",
            "description": "Memvalidasi struktur teknis file plan.json terhadap aturan build.py sebelum dirender (Preflight check).",
            "parameters": {
                "type": "object",
                "properties": {
                    "plan_path": {"type": "string", "description": "Path relatif atau absolut ke file plan.json."}
                },
                "required": ["plan_path"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "prepare_render",
            "description": "Menyajikan rencana video final kepada pengguna dan menampilkan Action Card konfirmasi tombol render 1080p.",
            "parameters": {
                "type": "object",
                "properties": {
                    "plan_path": {"type": "string", "description": "Path file plan.json yang sudah divalidasi 100% sukses."},
                    "summary": {"type": "string", "description": "Ringkasan konsep video, durasi, hook, dan shot list untuk pengguna."}
                },
                "required": ["plan_path", "summary"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "run_studio_cli",
            "description": "Menjalankan perintah Master CLI ContentStudio (project, channel, template, script, scene, voice, audio, media, render, publish) secara otonom.",
            "parameters": {
                "type": "object",
                "properties": {
                    "args": {
                        "type": "array",
                        "items": {"type": "string"},
                        "description": "Argumen perintah Master CLI (contoh: ['project', 'create', '--name', 'proyek_01', '--ratio', '9:16'], ['script', 'split-scenes', '--project', 'proyek_01'], ['voice', 'generate', '--project', 'proyek_01'])"
                    }
                },
                "required": ["args"]
            }
        }
    }
]

# ==============================================================================
# TOOL HANDLERS EXECUTION
# ==============================================================================

def execute_local_tool(name: str, args: Dict[str, Any], session: Dict[str, Any]) -> str:
    """Mengeksekusi tool lokal bawaan secara in-process."""
    root_d = get_root_dir()

    if name == "ls_dir":
        rel_p = args.get("path", ".")
        target_p = os.path.abspath(os.path.join(root_d, rel_p)) if not os.path.isabs(rel_p) else rel_p
        if not os.path.exists(target_p):
            return f"[ERR] Direktori tidak ditemukan: {rel_p}"
        if not os.path.isdir(target_p):
            return f"[INFO] '{rel_p}' adalah file tunggal (size: {os.path.getsize(target_p)} bytes)."
        items = sorted(os.listdir(target_p))
        res = []
        for it in items[:40]:
            fp = os.path.join(target_p, it)
            t_str = "DIR" if os.path.isdir(fp) else "FILE"
            sz_str = f" ({os.path.getsize(fp)} B)" if t_str == "FILE" else ""
            res.append(f"[{t_str}] {it}{sz_str}")
        return "\n".join(res) if res else "[DIREKTORI KOSONG]"

    elif name == "read_file":
        rel_p = args.get("path", "")
        target_p = os.path.abspath(os.path.join(root_d, rel_p)) if not os.path.isabs(rel_p) else rel_p
        if not os.path.exists(target_p):
            return f"[ERR] File tidak ditemukan: {rel_p}"
        try:
            with open(target_p, "r", encoding="utf-8") as f:
                content = f.read()
            if len(content) > 12000:
                return content[:12000] + f"\n... [TERPOTONG: Total {len(content)} karakter]"
            return content
        except Exception as e:
            return f"[ERR] Gagal membaca file: {e}"

    elif name == "write_file":
        rel_p = args.get("path", "")
        content = args.get("content", "")
        target_p = os.path.abspath(os.path.join(root_d, rel_p)) if not os.path.isabs(rel_p) else rel_p
        try:
            os.makedirs(os.path.dirname(target_p), exist_ok=True)
            with open(target_p, "w", encoding="utf-8") as f:
                f.write(content)
            return f"[OK] File berhasil ditulis: {os.path.relpath(target_p, root_d)} ({len(content)} karakter)"
        except Exception as e:
            return f"[ERR] Gagal menulis file: {e}"

    elif name == "delete_path":
        rel_p = args.get("path", "")
        target_p = os.path.abspath(os.path.join(root_d, rel_p)) if not os.path.isabs(rel_p) else rel_p
        if not os.path.exists(target_p):
            return f"[WARN] Path tidak ditemukan: {rel_p}"
        try:
            if os.path.isdir(target_p):
                shutil.rmtree(target_p)
            else:
                os.remove(target_p)
            return f"[OK] Berhasil menghapus: {rel_p}"
        except Exception as e:
            return f"[ERR] Gagal menghapus: {e}"

    elif name == "create_workspace":
        ws_name = re.sub(r"[^a-zA-Z0-9_-]+", "_", args.get("name", "").strip().lower())
        if not ws_name:
            return "[ERR] Nama workspace tidak valid."
        ws_dir = os.path.join(get_workspaces_dir(), ws_name)
        os.makedirs(os.path.join(ws_dir, "projects"), exist_ok=True)
        os.makedirs(os.path.join(ws_dir, "scripts"), exist_ok=True)
        os.makedirs(os.path.join(ws_dir, "assets"), exist_ok=True)
        os.makedirs(os.path.join(ws_dir, "audio"), exist_ok=True)
        os.makedirs(os.path.join(ws_dir, "renders"), exist_ok=True)

        aspect = args.get("aspect_ratio", "9:16")
        proj_file = os.path.join(ws_dir, "project.json")
        if not os.path.exists(proj_file):
            with open(proj_file, "w", encoding="utf-8") as f:
                json.dump({
                    "name": ws_name,
                    "aspect_ratio": aspect,
                    "scenes": []
                }, f, indent=2)

        session["active_workspace"] = ws_name
        save_session(session)
        return f"[OK] Workspace '{ws_name}' berhasil dibuat dan dijadikan workspace aktif."

    elif name == "list_assets":
        cat = args.get("category", "").lower().strip()
        tag = args.get("tag", "").lower().strip()
        lib = load_library()
        assets = lib.get("assets", [])
        if cat:
            assets = [a for a in assets if a.get("type") == cat]
        if tag:
            assets = [a for a in assets if tag in [t.lower() for t in a.get("tags", [])]]

        if not assets:
            return "[INFO] Tidak ada aset yang cocok. Anda dapat berburu aset baru dengan tool hunt_media."
        lines = [f"Total {len(assets)} aset ditemukan:"]
        for a in assets[:20]:
            lines.append(f"- [{a.get('type').upper()}] {a.get('name')} -> {a.get('path')} (Tags: {', '.join(a.get('tags', []))})")
        return "\n".join(lines)

    elif name == "load_skill":
        s_name = args.get("name", "")
        res = load_skill(s_name)
        if res.get("status") == "ok":
            return f"=== SKILL: {res.get('name')} ===\n{res.get('content')}"
        return f"[ERR] {res.get('message')}"

    elif name == "install_skill":
        src = args.get("source", "")
        res = install_skill(src)
        if res.get("status") == "ok":
            return f"[OK] {res.get('message')} (Slug: {res.get('slug')})"
        return f"[ERR] {res.get('message')}"

    elif name == "hunt_media":
        q = args.get("query", "")
        m_type = args.get("media_type", "clip")
        dur = float(args.get("duration", 6.0))
        ws = session.get("active_workspace", "default")
        target_dir = os.path.join(get_workspaces_dir(), ws, "inbox") if m_type == "clip" else None
        res = hunt_media(query=q, media_type=m_type, duration=dur, out_dir=target_dir)
        if res.get("status") == "ok":
            cached_flag = "[DARI CACHE]" if res.get("cached") else "[UNDUHAN BARU]"
            return f"[OK] {cached_flag} Media {res.get('type').upper()} siap: {res.get('path')} ({res.get('title')})"
        return f"[ERR] Berburu media gagal: {res.get('message')}"

    elif name == "validate_plan":
        p_path = args.get("plan_path", "")
        target_p = os.path.abspath(os.path.join(root_d, p_path)) if not os.path.isabs(p_path) else p_path
        if not os.path.exists(target_p):
            return f"[ERR] File plan.json tidak ditemukan di: {p_path}"

        try:
            with open(target_p, "r", encoding="utf-8") as f:
                plan_dict = json.load(f)
        except Exception as e:
            return f"[ERR] Format JSON salah: {e}"

        # Hard Guardrail 1080p
        out_cfg = plan_dict.setdefault("output", {})
        out_cfg["resolution"] = "1080p"
        out_cfg["aspect"] = "9:16"
        out_cfg["fps"] = 30

        try:
            with open(target_p, "w", encoding="utf-8") as f:
                json.dump(plan_dict, f, indent=2, ensure_ascii=False)
        except Exception:
            pass

        try:
            from engine.build import validate_plan
            is_valid = validate_plan(plan_dict, target_p)
            if is_valid:
                session["last_plan_path"] = target_p
                save_session(session)
                return "[OK] Preflight validation SUKSES 100%. File plan.json siap dirender."
            return "[ERR] Preflight validation gagal. Periksa kembali segmen sumber, durasi, atau framing."
        except Exception as e:
            return f"[ERR] Error validasi: {e}"

    elif name == "prepare_render":
        p_path = args.get("plan_path", "")
        summary = args.get("summary", "")
        target_p = os.path.abspath(os.path.join(root_d, p_path)) if not os.path.isabs(p_path) else p_path
        session["last_plan_path"] = target_p
        save_session(session)
        return f"[RENDER_READY] Rencana video siap dirender:\nPath: {target_p}\nRingkasan: {summary}"

    elif name == "run_studio_cli":
        cli_args = args.get("args", [])
        cli_py = os.path.join(_script_dir, "studio_cli.py")
        cmd = [sys.executable, cli_py] + [str(a) for a in cli_args]
        env = dict(os.environ)
        if "BRIDGE_PORT" not in env:
            env["BRIDGE_PORT"] = os.environ.get("PORT", "18950")
        # Agar log CLI langsung mengalir ke kartu thinking, bukan tertahan buffer.
        env["PYTHONUNBUFFERED"] = "1"
        _emit_step("Menjalankan CLI: " + " ".join(str(a) for a in cli_args[:4]))
        try:
            proc = subprocess.run(cmd, capture_output=True, text=True, env=env, timeout=600)
            for line in proc.stderr.splitlines():
                l_str = line.strip()
                if l_str.startswith("[STEP]") or l_str.startswith("[UI_SYNC]"):
                    sys.stderr.write(l_str + "\n")
                    sys.stderr.flush()
            _emit_step("CLI selesai" + (" (exit 0)" if proc.returncode == 0 else f" (exit {proc.returncode})"))
            if proc.returncode != 0:
                return f"[ERR] studio_cli error: {proc.stderr or proc.stdout}"
            return proc.stdout.strip() or "[OK]"
        except Exception as e:
            _emit_step(f"CLI gagal: {e}")
            return f"[ERR] Gagal menjalankan studio_cli: {e}"

    return f"[ERR] Tool lokal '{name}' tidak dikenal."

# ==============================================================================
# MAIN AGENT LOOP (AUTONOMOUS EXECUTION ENGINE)
# ==============================================================================

def _emit_step(message: str) -> None:
    """Emit structured step trace to stderr immediately without buffering."""
    try:
        print(f"[STEP] {message}", file=sys.stderr, flush=True)
        sys.stdout.flush()
        sys.stderr.flush()
    except Exception:
        pass

def _emit_think(message: str) -> None:
    """Emit alur pikir naratif singkat ke stderr (live thinking, bukan stdout)."""
    try:
        print(f"[THINK] {message}", file=sys.stderr, flush=True)
        sys.stderr.flush()
    except Exception:
        pass


def execute_autonomous_agent(
    user_prompt: str,
    attached_files: List[str] = [],
    session: Optional[Dict[str, Any]] = None,
    event_callback: Optional[Callable[[Dict[str, Any]], None]] = None,
    mode: str = "director"
) -> Dict[str, Any]:
    """
    Eksekusi loop otonom Real CLI Agent hingga 12 langkah dengan Native Tool-Calling:
    - Auto-Inject Konteks (Skills, MCP, DNA Channel, Master CLI)
    - Proaktif & Self-Correction (Auto-Hunt, Studio CLI, Auto-Validation)
    """
    sess = session or load_session()
    active_ws = sess.get("active_workspace", "default")
    _emit_step(f"Memuat konteks proyek & skill ({active_ws})")
    history = load_chat_history(active_ws)

    # 1. Kumpulkan seluruh tools aktif (Core Tools + Remote MCP Tools)
    all_tools: List[Dict[str, Any]] = list(CORE_TOOLS)
    try:
        mcp_tools = get_all_mcp_tools()
        all_tools.extend(mcp_tools)
    except Exception as e:
        print(f"[!] Warning MCP: {e}", file=sys.stderr, flush=True)

    # 2. Ambil konteks proyek dan Channel DNA
    skill_index = get_skill_index()
    skill_idx_summary = "\n".join([f"- {s['slug']}: {s['description']}" for s in skill_index])

    # Mode orientation
    mode_instructions = {
        "director": "Mode SUTRADARA: Bertanggung jawab penuh memimpin alur produksi dari awal hingga selesai (naskah, adegan, media visual, voiceover, musik, render).",
        "scriptwriter": "Mode PENULIS NASKAH: Fokus menghasilkan naskah narasi yang kuat, hook memikat di detik awal, pacing rapat, dan emosi cerita yang mendalam.",
        "researcher": "Mode PENELITI: Fokus mencari referensi akurat, perburuan bahan footage/meme/audio yang relevan, dan perbandingan fakta.",
        "fast": "Mode CEPAT: Eksekusi langsung rencana dengan langkah paling singkat dan to-the-point tanpa penjelasan bertele-tele."
    }.get(mode, "Mode Sutradara Umum.")
    # Baca Project Memory & Global Preferences
    memory_notes = []
    ws_dir = os.path.join(get_workspaces_dir(), active_ws)
    mem_proj_file = os.path.join(ws_dir, "memory.json")
    if os.path.exists(mem_proj_file):
        try:
            with open(mem_proj_file, "r", encoding="utf-8") as f:
                p_mems = json.load(f)
                if isinstance(p_mems, list):
                    for m in p_mems:
                        note = m.get("note", "").strip()
                        cat = m.get("category", "decision")
                        if note:
                            memory_notes.append(f"- [Proyek:{cat}] {note}")
        except Exception:
            pass

    mem_global_file = os.path.join(get_cache_dir(), "user_preferences.json")
    if os.path.exists(mem_global_file):
        try:
            with open(mem_global_file, "r", encoding="utf-8") as f:
                g_mems = json.load(f)
                if isinstance(g_mems, list):
                    for m in g_mems:
                        note = m.get("note", "").strip()
                        cat = m.get("category", "decision")
                        if note:
                            memory_notes.append(f"- [Global:{cat}] {note}")
        except Exception:
            pass

    memory_summary = "\n".join(memory_notes) if memory_notes else "Belum ada catatan memori tersimpan."

    # 2b. Kontrak Reference DNA aktif + sinkronisasi riwayat topik (non-blocking)
    dna_summary = "Belum ada kontrak DNA. Susun dari referensi/instruksi user sebelum produksi."
    try:
        dna = load_reference_dna(active_ws)
        pacing = dna.get("pacing_profile") or {}
        dna_summary = (
            f"- format_scale: {dna.get('format_scale')} | aspect_ratio: {dna.get('aspect_ratio')} | resolution: {dna.get('resolution')}\n"
            f"- sources_priority: {', '.join(dna.get('sources_priority') or [])}\n"
            f"- visual_continuity: {dna.get('visual_continuity')} | layout: {dna.get('layout_pattern')} | audio: {dna.get('audio_modality')}\n"
            f"- pacing: {pacing.get('style')} ({pacing.get('avg_scene_sec')}s/scene, chapters={pacing.get('chapter_structure')})\n"
            f"- visual_style_anchor: {dna.get('visual_style_anchor') or '-'}\n"
            f"- mechanics_summary: {dna.get('mechanics_summary') or '-'}"
        )
    except Exception:
        pass
    try:
        sync_statuses()
    except Exception:
        pass

    # 3. Bangun System Prompt Proaktif V3.6
    system_prompt = f"""Kamu adalah ContentStudio Autonomous AI Agent (ContentStudio V3.6), sutradara dan produser video profesional.
FORMAT & RASIO TIDAK DIKUNCI: dukung video pendek (short) MAUPUN video panjang berbab (long), serta rasio 16:9, 9:16, 1:1, dan 4:5 sesuai referensi atau instruksi user.

WORKSPACE AKTIF: '{active_ws}'

REFERENCE DNA AKTIF (sifat produksi yang harus dijaga):
{dna_summary}
MODE OPERASI: {mode.upper()} - {mode_instructions}

CROSS-SESSION PROJECT & GLOBAL MEMORY (ATURAN/KEPUTUSAN TETAP):
{memory_summary}

SKILL OPERASIONAL TERSEDIA (Gunakan tool load_skill jika memerlukan detail SOP):
{skill_idx_summary}

PERILAKU & ATURAN KERAS EKSEKUTOR (KONSTITUSI AGENTS.MD V3.6):
1. DILARANG MERESPONS PASIF ala Customer Service ("Maaf...", atau balik bertanya hal teknis sebelum bertindak).
2. DILARANG MENYURUH USER KLIK MANUAL DI UI. Gunakan tool 'run_studio_cli' untuk mengotomasi proyek:
   - Membuat/mengatur proyek: ['project', 'create', '--name', '<nama>', '--ratio', '9:16']
   - Menyimpan naskah: ['script', 'set', '--project', '<nama>', '--text', '<naskah>']
   - Memecah adegan: ['script', 'split-scenes', '--project', '<nama>']
   - Mencari & mengalokasikan media: ['media', 'assign', '--project', '<nama>', '--scene', '<idx>', '--url', '<url>']
   - Voiceover AI: ['voice', 'generate', '--project', '<nama>']
   - Audio mastering: ['voice', 'master', '--project', '<nama>', '--preset', 'podcast']
   - Background music: ['audio', 'set-bgm', '--project', '<nama>', '--file', '<path>']
   - Cross-session memory: ['memory', 'add', '--project', '<nama>', '--note', '<catatan>', '--category', 'decision']
   - Kontrak referensi: ['save-reference-dna', '--project', '<nama>', '--format-scale', 'short|long', '--aspect-ratio', '16:9|9:16|1:1|4:5', '--visual-continuity', 'strict_consistent|editorial_varied']
   - Resolusi aset hybrid: ['resolve-assets', '--project', '<nama>'] (checkpoint resume, tanpa mengulang scene valid)
   - Generate media: ['generate-media', '--project', '<nama>', '--prompt', '<deskripsi>', '--scene', '<idx>']
   - Kartu grafis: ['render-graphic', '--project', '<nama>', '--template', 'stat|comparison|chapter|quote|tier', '--title', '<judul>']
   - Riwayat topik: ['topic-history', 'record', '--project', '<nama>', '--topic', '<tema>', '--angle', '<sudut>'] / ['topic-history', 'context', '--topic', '<tema>']
3. Jika belum ada bahan media: gunakan tool 'hunt_media' atau 'run_studio_cli' untuk mencari aset video/audio.
4. Tampilkan tabel ringkasan shot list (adegan, narasi, visual, durasi) kepada pengguna di akhir.
5. PROTOKOL INTERAKTIF V3.4:
   - Catat preferensi atau koreksi penting user ke memory add.
   - Untuk aksi multi-langkah berat/destruktif (misal render final, download massal, timpa naskah total), keluarkan:
     [ACTION_PLAN] {{"title": "...", "steps": ["...", "..."], "approvePrompt": "Lanjutkan eksekusi rencana ini sekarang"}}
   - Di akhir setiap jawaban, sertakan 2-3 rekomendasi langkah lanjutan ringkas (maks 4-5 kata per saran):
     [SUGGESTIONS] ["Saran 1", "Saran 2", "Saran 3"]
6. KUNCI BAHASA: SELALU merespons dalam Bahasa Indonesia yang ringkas, langsung ke inti, dan natural mengikuti bahasa pengguna. DILARANG berpindah ke Bahasa Inggris di tengah percakapan.
7. MEKANIK VS OBJEK REFERENSI: Saat pengguna memberi referensi konten (video/link/deskripsi), ambil CARA KERJA-nya: pola struktur, ritme/pacing, dan format produksi. DILARANG menyalin mentah objek/topik referensi kecuali pengguna memang meminta topik tersebut.
8. ADAPTASI KONDISI NYATA: Jika pengguna meminta "buat berdasarkan kondisi", langsung sesuaikan dengan alat/aset yang tersedia di ContentStudio (tanpa kamera/rekaman tangan) secara cerdas dan ringkas, tanpa memaksa pengguna menjelaskan ulang hal dasar konten.
9. EKSEKUSI SEKUENSIAL: DILARANG memicu banyak subagent atau aksi berat secara paralel (misalnya 4-5 generate gambar sekaligus) karena membuat proses hang. Jalankan bertahap satu per satu dan WAJIB laporkan progres nyata setiap aksi dengan print("[STEP] ...", file=sys.stderr, flush=True).
10. DILARANG MENULIS LAPORAN PROGRES DI JAWABAN UTAMA: jangan pernah menulis kalimat filler seperti "Sedang memeriksa...", "Mohon tunggu sebentar...", "Sudah membedah...", atau "Cache telah dibersihkan" di teks balasan utama (stdout). Semua status proses HANYA lewat marker [STEP] ke stderr.
11. LANGSUNG KE INTI & TAJAM: jawaban akhir langsung masuk ke hasil analisis, ide, atau keputusan, dengan nada partner kreatif yang cerdas, ringkas, dan natural dalam Bahasa Indonesia. Tanpa pembuka basa-basi dan tanpa menutup dengan ringkasan berulang.
12. SEMBUNYIKAN ISTILAH DAPUR: jangan menyebut nama file sementara internal seperti ref_01, ref_02, ref_03, direktori cache internal, atau nama script internal di teks jawaban ke pengguna, kecuali pengguna memintanya secara spesifik.
13. GUNAKAN MARKDOWN RAPI: heading singkat, poin bernomor atau bullet yang bersih, dan tabel ringkas hanya jika benar-benar memperjelas perbandingan. Jangan menumpuk heading dan tabel untuk informasi yang cukup satu paragraf.
14. KUNCI DOMAIN, FORMAT & MODALITAS REFERENSI: Ekstrak pola kerja, ritme, struktur, SEKALIGUS jenis/domain kontennya. Wajib konsisten: referensi menggambar/visual art -> tetap membuat konten menggambar/visual art (visual + label singkat + BGM, TANPA mengubahnya jadi video pembahasan/narasi bicara). Referensi pembahasan/commentary -> tetap membuat konten pembahasan. Referensi vibe/ambient/mood -> tetap membuat konten vibe/ambient. DILARANG KERAS melompat domain (mis. mengubah referensi menggambar menjadi memasak mie instan atau video edukasi bernarasi) kecuali ada perintah eksplisit dari user.
15. TETAP OTONOM: Jika user memberi referensi dan menyuruh eksekusi tanpa menyebut objek spesifik, AI HARUS berani menentukan sendiri objek/topik baru yang segar dan 100% masih satu domain dengan referensi, lalu langsung produksi tanpa kebanyakan tanya.
16. ZERO PROGRESS FILLER & ZERO RAW PATH: DILARANG menulis kalimat progres seperti "Memproses bedah teknis...", "Mengunduh video pertama...", atau mencetak URL mentah file:///data/user/0/... di jawaban utama (stdout). Semua progres WAJIB hanya ke stderr dengan [STEP].
17. MULTI-FORMAT & MULTI-RASIO: Jangan pernah mengasumsikan semua proyek adalah Shorts 9:16. Deteksi format (short vs long) dan rasio (16:9, 9:16, 1:1, 4:5) dari referensi atau instruksi user. Untuk video panjang, susun struktur per-bab (chapters), siapkan transisi antar-bab termasuk kartu bab grafis bila cocok, dan jaga ritme retensi jangka panjang. Untuk video pendek, fokus 1 busur hook-escalation-payoff.
18. REFERENCE-FIRST DNA LOCK: Setiap kali user memberi referensi, WAJIB simpan sifat produksinya lewat 'save-reference-dna' SEBELUM produksi: format_scale, aspect_ratio, sources_priority, visual_continuity, layout_pattern, audio_modality, pacing_profile, visual_style_anchor, mechanics_summary. Pertahankan modalitas & domain asli referensi kecuali user memerintahkan perubahan.
19. IDEATION FILTER (ANTI-ASAL, ANTI-MONOTON): Saring 3-5 kandidat ide di belakang layar berdasarkan hook kuat, eskalasi/kedalaman sesuai format, pemicu interaksi alami, dan kelayakan visual dengan 5 worker. Riwayat topik TIDAK PERNAH memblokir tema apa pun; jika mengangkat tema yang sama, WAJIB rotasi angle, hook, atau payoff agar tidak klise. Cek variasi via 'topic-history context --topic <tema>'.
20. HYBRID ASSET CONTRACT PER SCENE: Setiap scene wajib menentukan source_type paling masuk akal (clip, generate, graphic, stock, local) plus visual_goal, keywords, style_anchor, duration, dan chapter bila video panjang. Jalankan 'resolve-assets' agar router menerapkan matriks fallback ketat + QA gate + checkpoint resume. DILARANG memaksa satu sumber untuk semua scene.
21. LIVE THINKING STREAM: Alur pikir mengalir ke stderr secara real-time via print("[THINK] ...", file=sys.stderr, flush=True) untuk analisis/pertimbangan, dan print("[STEP] ...") untuk aksi alat. Jangan pernah menulis alur pikir ke stdout, jangan membocorkan path internal mentah, dan jangan mencetak stack trace.
"""

    # 4. Susun pesan percakapan
    messages: List[Dict[str, Any]] = []
    # Masukkan riwayat sebelumnya
    for h in history:
        messages.append(h)

    # Tambahkan input pengguna saat ini
    user_content = user_prompt.strip()
    if attached_files:
        user_content += f"\n[Lampiran File Media: {', '.join(attached_files)}]"
    messages.append({"role": "user", "content": user_content})

    steps_recorded: List[Dict[str, Any]] = []
    action_cards: List[Dict[str, Any]] = []
    final_reply = ""

    # 5. Loop Agentik (Maksimal 12 Langkah)
    for step_num in range(1, MAX_AGENT_STEPS + 1):
        if step_num == 1:
            _emit_think("Membaca arah permintaan, kontrak DNA, dan riwayat topik sebelum menyusun langkah.")
        else:
            _emit_think("Menimbang hasil langkah sebelumnya untuk menentukan aksi berikutnya.")
        _emit_step(f"Memproses permintaan ke model AI (tahap {step_num})")
        try:
            llm_res = chat_with_tools(
                messages=messages,
                tools=all_tools,
                system_prompt=system_prompt,
                cache_dir=get_cache_dir()
            )
        except Exception as e:
            err_msg = f"[ERR] Pemanggilan AI Router gagal: {e}"
            steps_recorded.append({"tool": "ai_router", "status": "error", "detail": str(e)[:120]})
            final_reply = f"Maaf, terjadi gangguan pada AI Router: {e}. Pastikan API Key di Setelan sudah benar."
            break

        content = llm_res.get("content") or ""
        tool_calls = llm_res.get("tool_calls") or []
        raw_msg = llm_res.get("raw_assistant_msg") or {"role": "assistant", "content": content}
        messages.append(raw_msg)

        if not tool_calls:
            # Selesai: LLM memberikan jawaban akhir
            _emit_think("Konteks sudah cukup. Merangkum hasil produksi dan menyusun jawaban akhir.")
            _emit_step("Menyusun jawaban")
            final_reply = content
            break

        # Eksekusi seluruh tool call yang diminta LLM
        for tc in tool_calls:
            t_id = tc.get("id") or f"call_{int(time.time()*1000)}"
            t_name = tc.get("name", "")
            t_args = tc.get("arguments") or {}

            _emit_think(f"Aksi paling masuk akal sekarang: menjalankan {t_name}.")
            _emit_step(f"Menjalankan tool: {t_name}")
            # Emit event ke Android Kotlin Service
            if event_callback:
                event_callback({
                    "type": "step",
                    "tool": t_name,
                    "status": "running",
                    "detail": f"Menjalankan {t_name}..."
                })

            # Eksekusi tool (Lokal atau Remote MCP)
            if is_mcp_tool(t_name):
                tool_output = call_mcp_tool(t_name, t_args)
            else:
                tool_output = execute_local_tool(t_name, t_args, sess)

            # Jika tool prepare_render dipanggil, ciptakan Action Card untuk UI Android
            if t_name == "prepare_render":
                p_path = t_args.get("plan_path", "")
                action_cards.append({
                    "type": "render",
                    "title": f"ACC PRODUKSI: {os.path.basename(p_path)}",
                    "hook": "Full HD 1080p (1080x1920) 30fps",
                    "plan_path": p_path,
                    "primary_cmd": f"/render {p_path}"
                })

            step_item = {
                "tool": t_name,
                "status": "done" if not str(tool_output).startswith("[ERR") else "error",
                "detail": str(tool_output).replace("\n", " ")[:120]
            }
            steps_recorded.append(step_item)
            _emit_step(f"Selesai {t_name}")

            if event_callback:
                event_callback({
                    "type": "step",
                    "tool": t_name,
                    "status": step_item["status"],
                    "detail": step_item["detail"]
                })

            # Masukkan hasil tool ke messages
            messages.append({
                "role": "tool",
                "tool_call_id": t_id,
                "name": t_name,
                "content": str(tool_output)
            })

    # Simpan riwayat chat yang diperbarui
    # Hanya simpan user dan assistant messages yang bersih
    filtered_history = []
    for m in messages[-20:]:
        if m.get("role") in {"user", "assistant"}:
            c = m.get("content")
            if c:
                filtered_history.append({"role": m["role"], "content": str(c)})
    save_chat_history(active_ws, filtered_history)

    return {
        "status": "ok",
        "mode": "agent",
        "active_workspace": active_ws,
        "reply": final_reply or "Instruksi telah diproses sepenuhnya.",
        "steps": steps_recorded,
        "action_cards": action_cards,
        "logs": [f"Eksekusi {len(steps_recorded)} langkah selesai."]
    }

# ==============================================================================
# CLI COMMAND PROCESSOR (MODE '/')
# ==============================================================================

def handle_slash_command(cmd_text: str, session: Dict[str, Any], event_callback: Optional[Callable] = None) -> Dict[str, Any]:
    """Menangani perintah cepat diawali '/' secara instan (0 Token AI)."""
    parts = cmd_text.strip().split()
    cmd = parts[0].lower()
    args = parts[1:]
    root_d = get_root_dir()

    if cmd in {"/use", "/switch"}:
        if not args:
            return {
                "status": "ok", "mode": "cli", "active_workspace": session.get("active_workspace", "default"),
                "reply": f"Workspace aktif saat ini: '{session.get('active_workspace')}'.\nGunakan '/use <nama_workspace>' untuk berpindah.",
                "steps": [], "action_cards": []
            }
        target_ws = args[0].strip().lower()
        ws_path = os.path.join(get_workspaces_dir(), target_ws)
        if not os.path.exists(ws_path):
            return {
                "status": "error", "mode": "cli", "active_workspace": session.get("active_workspace", "default"),
                "reply": f"[ERR] Workspace '{target_ws}' tidak ditemukan di {ws_path}",
                "steps": [], "action_cards": []
            }
        session["active_workspace"] = target_ws
        save_session(session)
        return {
            "status": "ok", "mode": "cli", "active_workspace": target_ws,
            "reply": f"[OK] Beralih ke workspace '{target_ws}'.",
            "steps": [{"tool": "switch_workspace", "status": "done", "detail": target_ws}],
            "action_cards": []
        }

    elif cmd == "/workspaces":
        ws_dir = get_workspaces_dir()
        entries = sorted([d for d in os.listdir(ws_dir) if os.path.isdir(os.path.join(ws_dir, d)) and not d.startswith(".")])
        cur = session.get("active_workspace")
        lines = ["Daftar Akun / Workspace ContentStudio:"]
        for e in entries:
            mark = "* [AKTIF]" if e == cur else " "
            lines.append(f"{mark} {e}")
        return {
            "status": "ok", "mode": "cli", "active_workspace": cur,
            "reply": "\n".join(lines),
            "steps": [], "action_cards": []
        }

    elif cmd == "/clear":
        active_ws = session.get("active_workspace", "default")
        clear_chat_history(active_ws)
        return {
            "status": "ok", "mode": "cli", "active_workspace": active_ws,
            "reply": f"[OK] Riwayat percakapan untuk workspace '{active_ws}' telah dibersihkan.",
            "steps": [{"tool": "clear_history", "status": "done", "detail": active_ws}],
            "action_cards": []
        }

    elif cmd == "/status":
        st = get_storage_info()
        provs = get_all_providers_status()
        prov_lines = []
        for p_name, p_st in provs.get("providers", {}).items():
            prov_lines.append(f"- {p_name.upper()}: {p_st.get('status')} ({p_st.get('ready')} ready)")
        msg = f"""=== STATUS SISTEM CONTENTSTUDIO ===
Workspace Aktif: {session.get('active_workspace')}
Penyimpanan: {st.get('free_gb')} GB bebas dari {st.get('total_gb')} GB ({st.get('pct_used')}% terpakai)
Kesiapan Provider AI:
{chr(10).join(prov_lines)}
"""
        return {
            "status": "ok", "mode": "cli", "active_workspace": session.get("active_workspace", "default"),
            "reply": msg,
            "steps": [], "action_cards": []
        }

    elif cmd == "/render":
        if not args:
            last_p = session.get("last_plan_path")
            if not last_p or not os.path.exists(last_p):
                return {
                    "status": "error", "mode": "cli", "active_workspace": session.get("active_workspace"),
                    "reply": "Harap tentukan path plan.json: /render <path_plan.json>",
                    "steps": [], "action_cards": []
                }
            plan_path = last_p
        else:
            plan_path = resolve_path(args[0])

        if not os.path.exists(plan_path):
            return {
                "status": "error", "mode": "cli", "active_workspace": session.get("active_workspace"),
                "reply": f"[ERR] File plan tidak ditemukan: {plan_path}",
                "steps": [], "action_cards": []
            }

        try:
            from engine.bridge import render_project
            render_res_str = render_project(plan_path, event_callback)
            render_res = json.loads(render_res_str)
            if render_res.get("status") == "ok":
                v_out = render_res.get("output_video")
                session["last_output_video"] = v_out
                save_session(session)
                return {
                    "status": "ok", "mode": "cli", "active_workspace": session.get("active_workspace"),
                    "reply": f"[SUKSES] Video Full HD 1080p berhasil dirender:\n{v_out}",
                    "steps": [{"tool": "render_project", "status": "done", "detail": os.path.basename(v_out)}],
                    "action_cards": [{
                        "type": "video_ready",
                        "title": "VIDEO SELESAI DIRENDER",
                        "hook": f"Resolusi {render_res.get('resolution')} ({(render_res.get('size_bytes', 0)/(1024*1024)):.2f} MB)",
                        "path": v_out
                    }]
                }
            else:
                return {
                    "status": "error", "mode": "cli", "active_workspace": session.get("active_workspace"),
                    "reply": f"[ERR] Render gagal: {render_res.get('message')}",
                    "steps": [{"tool": "render_project", "status": "error", "detail": render_res.get('message', '')}],
                    "action_cards": []
                }
        except Exception as e:
            return {
                "status": "error", "mode": "cli", "active_workspace": session.get("active_workspace"),
                "reply": f"[ERR] Error saat rendering: {e}",
                "steps": [], "action_cards": []
            }

    elif cmd == "/help":
        msg = """=== DAFTAR PERINTAH CEPAT (MODE CLI) ===
/use <workspace>    : Berpindah ke akun / workspace lain
/workspaces         : Menampilkan daftar seluruh akun
/clear              : Mereset percakapan akun aktif
/render [plan.json] : Menjalankan render video 1080p
/status             : Mengecek sisa penyimpanan & status API
/help               : Menampilkan bantuan ini

Atau ketik instruksi bahasa alami bebas untuk memerintahkan AI Agent menyusun dan memproduksi konten secara otonom.
"""
        return {
            "status": "ok", "mode": "cli", "active_workspace": session.get("active_workspace"),
            "reply": msg,
            "steps": [], "action_cards": []
        }

    return {
        "status": "error", "mode": "cli", "active_workspace": session.get("active_workspace"),
        "reply": f"[ERR] Perintah CLI '{cmd}' tidak dikenali. Ketik /help untuk panduan.",
        "steps": [], "action_cards": []
    }

# ==============================================================================
# MAIN CONSOLE ENTRYPOINT FOR CHAQUOPY & CLI
# ==============================================================================

def execute_command(
    input_text: str,
    attached_files: List[str] = [],
    event_callback: Optional[Callable[[Dict[str, Any]], None]] = None,
    mode: str = "director"
) -> Dict[str, Any]:
    """
    Pintu masuk interaktif utama yang dipanggil oleh bridge.run_console:
    Mendukung instruksi natural language (Autonomous Agent) maupun perintah cepat ('/').
    """
    session = load_session()
    text = (input_text or "").strip()

    if text.startswith("/"):
        return handle_slash_command(text, session, event_callback)

    return execute_autonomous_agent(
        user_prompt=text,
        attached_files=attached_files,
        session=session,
        event_callback=event_callback,
        mode=mode
    )

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="ContentStudio Autonomous CLI Agent")
    parser.add_argument("prompt", nargs="?", default="", help="Instruksi untuk agen atau perintah '/'")
    parser.add_argument("--file", action="append", default=[], help="Lampiran file media")
    parser.add_argument("--mode", default="director", choices=["director", "scriptwriter", "researcher", "fast"], help="Mode agen AI")
    parser.add_argument("--bridge-port", default=None, help="Port server studio-bridge lokal")
    args = parser.parse_args()

    if args.bridge_port:
        os.environ["BRIDGE_PORT"] = args.bridge_port

    if args.prompt:
        res = execute_command(args.prompt, args.file, mode=args.mode)
        print(json.dumps(res, indent=2, ensure_ascii=False))
    else:
        print("ContentStudio Autonomous CLI Agent Ready.")
        print(f"Active Workspace: {load_session().get('active_workspace')}")
