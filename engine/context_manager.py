#!/usr/bin/env python3
"""
ContentStudio Zero-Loss Context Handoff (V3.7)

Menyimpan satu `session_state.json` per sesi/proyek yang berisi:
- working_memory  : tujuan, kontrak produksi, keputusan terkunci, langkah selesai
- visual_memory   : cache ringkasan visual per lampiran (gambar/video)
- rolling_summary : padatan percakapan lama
- recent_turns    : maksimal 10 giliran terakhir (netral, tanpa log mentah)

Modul ini murni stdlib + engine.runtime, tidak memanggil LLM maupun jaringan.
Semua penulisan dilakukan atomik (tmp + os.replace) agar pergantian provider
tidak pernah membaca state setengah jadi.
"""

import os
import sys

# Cegah engine/ membayangi modul stdlib 'operator' saat dijalankan langsung.
_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if sys.path and sys.path[0] == _SCRIPT_DIR:
    sys.path.pop(0)
_ROOT_DIR = os.path.dirname(_SCRIPT_DIR)
if _ROOT_DIR not in sys.path:
    sys.path.append(_ROOT_DIR)


import hashlib
import json
import os
import re
import sys
import threading
import time
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional


from engine.runtime import get_root_dir, get_workspaces_dir

MAX_RECENT_TURNS = 10
MAX_LOCKED_DECISIONS = 24
MAX_COMPLETED_STEPS = 48
MAX_VISUAL_ITEMS = 24
MAX_ROLLING_CHARS = 4000
MAX_TURN_CHARS = 6000
MAX_TOOL_SUMMARY_CHARS = 320

VISUAL_EXTENSIONS = (".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".mp4", ".mov", ".mkv", ".webm")

CLI_PROVIDERS = ("antigravity", "cline")
DIRECT_PROVIDERS = (
    "gemini", "groq", "mistral", "zai", "cohere", "nvidia", "sambanova",
    "cloudflare", "openrouter", "custom",
)

HANDOFF_INSTRUCTION = (
    "Konteks proyek dan hasil langkah sebelumnya sudah ada di atas. LANGSUNG lanjutkan "
    "dari instruksi terbaru user; DILARANG membaca ulang seluruh file proyek/referensi "
    "dari nol kecuali file tersebut benar-benar perlu diubah atau belum ada di ringkasan."
)

_LOCKS: Dict[str, threading.RLock] = {}
_LOCKS_GUARD = threading.Lock()


def _lock_for(path: str) -> threading.RLock:
    with _LOCKS_GUARD:
        lock = _LOCKS.get(path)
        if lock is None:
            lock = threading.RLock()
            _LOCKS[path] = lock
        return lock


def _now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def _safe_name(value: str) -> str:
    clean = re.sub(r"[^a-zA-Z0-9._-]+", "_", (value or "").strip())
    return clean or "default"


def _workspaces_dir() -> str:
    try:
        return get_workspaces_dir()
    except Exception:
        return os.path.join(get_root_dir(), "workspaces")


def session_state_path(session_id: Optional[str] = None, project: Optional[str] = None) -> str:
    """Path state: proyek menang bila ada, selain itu library/sessions/<id>.json."""
    if project and project.strip():
        return os.path.join(_workspaces_dir(), _safe_name(project), "session_state.json")
    sid = _safe_name(session_id or "global")
    return os.path.join(get_root_dir(), "library", "sessions", f"{sid}.json")


def default_state(session_id: Optional[str] = None, project: Optional[str] = None) -> Dict[str, Any]:
    return {
        "version": 1,
        "session_id": session_id or "",
        "project": project or "",
        "updated_at": _now_iso(),
        "working_memory": {
            "active_project": project or "",
            "format_scale": "",
            "aspect_ratio": "",
            "locked_decisions": [],
            "completed_steps": [],
            "current_goal": "",
        },
        "visual_memory": [],
        "rolling_summary": "",
        "recent_turns": [],
    }


def normalize_state(data: Any, session_id: Optional[str] = None,
                    project: Optional[str] = None) -> Dict[str, Any]:
    state = default_state(session_id, project)
    if not isinstance(data, dict):
        return state
    wm = data.get("working_memory") if isinstance(data.get("working_memory"), dict) else {}
    state["working_memory"].update({k: wm.get(k, state["working_memory"][k]) for k in state["working_memory"]})
    for key in ("locked_decisions", "completed_steps"):
        val = state["working_memory"].get(key)
        if not isinstance(val, list):
            state["working_memory"][key] = []
        else:
            state["working_memory"][key] = [str(v) for v in val if str(v).strip()]
    state["visual_memory"] = data.get("visual_memory") if isinstance(data.get("visual_memory"), list) else []
    state["rolling_summary"] = str(data.get("rolling_summary") or "")
    turns = data.get("recent_turns") if isinstance(data.get("recent_turns"), list) else []
    state["recent_turns"] = [t for t in turns if isinstance(t, dict)][-MAX_RECENT_TURNS:]
    state["session_id"] = str(data.get("session_id") or session_id or "")
    state["project"] = str(data.get("project") or project or "")
    state["updated_at"] = str(data.get("updated_at") or _now_iso())
    return state


def load_state(session_id: Optional[str] = None, project: Optional[str] = None) -> Dict[str, Any]:
    path = session_state_path(session_id, project)
    with _lock_for(path):
        data: Any = {}
        if os.path.exists(path):
            try:
                with open(path, "r", encoding="utf-8") as f:
                    data = json.load(f)
            except Exception:
                data = {}
        return normalize_state(data, session_id, project)


def save_state(state: Dict[str, Any], session_id: Optional[str] = None,
               project: Optional[str] = None) -> bool:
    project = project or state.get("project") or ""
    session_id = session_id or state.get("session_id") or ""
    path = session_state_path(session_id, project)
    tmp = f"{path}.{os.getpid()}.tmp"
    try:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        state["updated_at"] = _now_iso()
        with _lock_for(path):
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(state, f, indent=2, ensure_ascii=False)
            os.replace(tmp, path)
        return True
    except Exception:
        try:
            if os.path.exists(tmp):
                os.remove(tmp)
        except Exception:
            pass
        return False


def _clip(value: Any, limit: int) -> str:
    text = str(value or "").strip()
    if len(text) > limit:
        return text[:limit].rstrip() + "..."
    return text


def _unique_append(items: List[str], value: str, limit: int) -> List[str]:
    value = _clip(value, 400)
    if not value:
        return items
    if value not in items:
        items.append(value)
    return items[-limit:]


def refresh_working_memory(
    session_id: Optional[str] = None,
    project: Optional[str] = None,
    patch: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    """Perbarui working_memory tanpa menyentuh komponen lain."""
    state = load_state(session_id, project)
    wm = state["working_memory"]
    patch = patch or {}
    for key in ("active_project", "format_scale", "aspect_ratio", "current_goal"):
        if patch.get(key) is not None:
            wm[key] = _clip(patch.get(key), 500)
    for key in ("locked_decisions", "completed_steps"):
        for item in patch.get(key) or []:
            wm[key] = _unique_append(
                wm.get(key) or [], str(item),
                MAX_LOCKED_DECISIONS if key == "locked_decisions" else MAX_COMPLETED_STEPS,
            )
    save_state(state, session_id, project)
    return wm


def add_completed_step(step: str, session_id: Optional[str] = None,
                       project: Optional[str] = None) -> None:
    refresh_working_memory(session_id, project, {"completed_steps": [step]})


def add_locked_decision(decision: str, session_id: Optional[str] = None,
                        project: Optional[str] = None) -> None:
    refresh_working_memory(session_id, project, {"locked_decisions": [decision]})


def file_fingerprint(file_path: str) -> str:
    """SHA-256 untuk file kecil, fallback mtime+size untuk file besar/absen."""
    try:
        if not file_path or not os.path.exists(file_path):
            return "missing"
        size = os.path.getsize(file_path)
        if size <= 8 * 1024 * 1024:
            h = hashlib.sha256()
            with open(file_path, "rb") as f:
                for chunk in iter(lambda: f.read(1024 * 1024), b""):
                    h.update(chunk)
            return f"sha256:{h.hexdigest()}"
        return f"mtime:{int(os.path.getmtime(file_path))}:{size}"
    except Exception:
        return "unknown"


def record_visual_memory(
    file_path: str,
    visual_summary: str,
    session_id: Optional[str] = None,
    project: Optional[str] = None,
    model_used: str = "",
    frames: Optional[List[str]] = None,
) -> Dict[str, Any]:
    """Simpan/ganti ringkasan visual untuk satu lampiran agar model teks-only paham."""
    summary = _clip(visual_summary, 1200)
    if not file_path or not summary:
        return {}
    state = load_state(session_id, project)
    fp = file_fingerprint(file_path)
    key = os.path.basename(file_path)
    items: List[Dict[str, Any]] = [v for v in state["visual_memory"] if isinstance(v, dict)]
    item = {
        "file": file_path,
        "name": key,
        "sha256_or_mtime": fp,
        "visual_summary": summary,
        "model_used": model_used,
        "frames": [str(f) for f in (frames or [])][:9],
        "updated_at": _now_iso(),
    }
    replaced = False
    for idx, existing in enumerate(items):
        if existing.get("file") == file_path or existing.get("name") == key:
            items[idx] = item
            replaced = True
            break
    if not replaced:
        items.append(item)
    state["visual_memory"] = items[-MAX_VISUAL_ITEMS:]
    save_state(state, session_id, project)
    return item


def get_visual_memory(file_path: str, session_id: Optional[str] = None,
                      project: Optional[str] = None) -> Optional[Dict[str, Any]]:
    state = load_state(session_id, project)
    fp = file_fingerprint(file_path)
    for item in state["visual_memory"]:
        if not isinstance(item, dict):
            continue
        if item.get("file") == file_path or item.get("name") == os.path.basename(file_path):
            if item.get("sha256_or_mtime") == fp:
                return item
    return None


def _summarize_turn_for_rolling(turn: Dict[str, Any]) -> str:
    role = "User" if turn.get("role") == "user" else "Asisten"
    content = _clip(turn.get("content"), 280).replace("\n", " ")
    tools = _clip(turn.get("tool_summary"), 120).replace("\n", " ")
    line = f"- {role}: {content}"
    if tools:
        line += f" | aksi: {tools}"
    return line


def _compact(state: Dict[str, Any]) -> None:
    turns = state.get("recent_turns") or []
    overflow = max(0, len(turns) - MAX_RECENT_TURNS)
    if overflow <= 0:
        return
    moved, turns = turns[:overflow], turns[overflow:]
    lines = [_summarize_turn_for_rolling(t) for t in moved]
    existing = str(state.get("rolling_summary") or "").strip()
    merged = (existing + "\n" if existing else "") + "\n".join(lines)
    if len(merged) > MAX_ROLLING_CHARS:
        merged = merged[-MAX_ROLLING_CHARS:]
        first_nl = merged.find("\n")
        if first_nl > 0:
            merged = merged[first_nl + 1:]
    state["rolling_summary"] = merged.strip()
    state["recent_turns"] = turns[-MAX_RECENT_TURNS:]


def record_turn(
    role: str,
    content: str,
    model_used: str = "",
    provider: str = "",
    tool_summary: str = "",
    session_id: Optional[str] = None,
    project: Optional[str] = None,
    attachments: Optional[List[str]] = None,
    visual_summaries: Optional[Dict[str, str]] = None,
    meta: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    """Simpan satu giliran; padatkan giliran lama secara deterministik bila > 10."""
    state = load_state(session_id, project)
    turn: Dict[str, Any] = {
        "role": "user" if role == "user" else "assistant",
        "content": _clip(content, MAX_TURN_CHARS),
        "model_used": _clip(model_used, 160),
        "provider": _clip(provider, 80),
        "tool_summary": _clip(tool_summary, MAX_TOOL_SUMMARY_CHARS),
        "attachments": [os.path.basename(str(a)) for a in (attachments or [])][:12],
        "ts": _now_iso(),
    }
    if meta:
        turn["meta"] = {
            "mode": meta.get("mode"),
            "taskType": meta.get("taskType"),
            "multimodal": bool(meta.get("multimodal")),
            "latencyMs": meta.get("latencyMs"),
            "switchedFrom": meta.get("switchedFrom"),
        }
    state["recent_turns"].append(turn)
    _compact(state)

    if attachments and visual_summaries:
        for path in attachments:
            summary = visual_summaries.get(path) or visual_summaries.get(os.path.basename(path))
            if summary:
                fp = file_fingerprint(path)
                items = [v for v in state["visual_memory"] if isinstance(v, dict)]
                item = {
                    "file": path,
                    "name": os.path.basename(path),
                    "sha256_or_mtime": fp,
                    "visual_summary": _clip(summary, 1200),
                    "model_used": model_used,
                    "frames": [],
                    "updated_at": _now_iso(),
                }
                items = [v for v in items if v.get("file") != path and v.get("name") != item["name"]]
                items.append(item)
                state["visual_memory"] = items[-MAX_VISUAL_ITEMS:]

    wm = state["working_memory"]
    if role == "user":
        wm["current_goal"] = _clip(content, 500)
    elif project:
        wm["active_project"] = project
    state["project"] = state.get("project") or (project or "")

    save_state(state, session_id, project)
    return state


def record_visual_analysis(
    attachments: List[str],
    summary: str,
    model_used: str = "",
    session_id: Optional[str] = None,
    project: Optional[str] = None,
) -> None:
    """Shortcut: satu ringkasan vision untuk semua lampiran pada giliran itu."""
    for path in attachments or []:
        record_visual_memory(path, summary, session_id, project, model_used)


def _format_working_memory(wm: Dict[str, Any]) -> str:
    decisions = wm.get("locked_decisions") or []
    steps = wm.get("completed_steps") or []
    lines = [
        f"- Proyek aktif: {wm.get('active_project') or '-'}",
        f"- Format/rasio: {wm.get('format_scale') or '-'} / {wm.get('aspect_ratio') or '-'}",
        f"- Tujuan saat ini: {wm.get('current_goal') or '-'}",
    ]
    if decisions:
        lines.append("- Keputusan terkunci: " + " | ".join(str(d) for d in decisions[-8:]))
    if steps:
        lines.append("- Langkah selesai: " + " | ".join(str(s) for s in steps[-12:]))
    return "\n".join(lines)


def _format_visual_memory(items: List[Dict[str, Any]]) -> str:
    if not items:
        return "- Belum ada analisis visual tersimpan."
    lines = []
    for item in items[-8:]:
        name = item.get("name") or os.path.basename(str(item.get("file") or ""))
        summary = _clip(item.get("visual_summary"), 400).replace("\n", " ")
        lines.append(f"- {name}: {summary}")
    return "\n".join(lines)


def build_system_context(state: Dict[str, Any]) -> str:
    wm = state.get("working_memory") or {}
    visual = state.get("visual_memory") or []
    rolling = str(state.get("rolling_summary") or "").strip()
    parts = [
        "KONTEKS SESI BERSAMA (hasil langkah sebelumnya, jangan diulang dari nol):",
        "[MEMORI KERJA]\n" + _format_working_memory(wm),
        "[MEMORI VISUAL]\n" + _format_visual_memory(visual),
        "[RINGKASAN PERCAKAPAN LAMA]\n" + (rolling or "- Belum ada percakapan lama yang dipadatkan."),
    ]
    return "\n\n".join(parts)


def neutral_messages(state: Dict[str, Any], limit: int = MAX_RECENT_TURNS) -> List[Dict[str, str]]:
    msgs = []
    for t in (state.get("recent_turns") or [])[-limit:]:
        role = "user" if t.get("role") == "user" else "assistant"
        content = str(t.get("content") or "").strip()
        if content:
            msgs.append({"role": role, "content": content})
    return msgs


def build_cli_handoff_block(state: Dict[str, Any], target_provider: str = "",
                            target_model: str = "", limit: int = 10) -> str:
    head = "[SESSION HANDOFF - LANJUTKAN LANGSUNG]"
    target = f"Target: {target_provider}/{target_model}\n" if target_provider or target_model else ""
    turns = []
    for t in (state.get("recent_turns") or [])[-limit:]:
        role = "USER" if t.get("role") == "user" else "ASSISTANT"
        content = _clip(t.get("content"), 900).replace("\n", " ")
        extra = ""
        if t.get("tool_summary"):
            extra = " | aksi: " + _clip(t.get("tool_summary"), 200).replace("\n", " ")
        turns.append(f"- {role}: {content}{extra}")
    history = "\n".join(turns) if turns else "- Belum ada giliran sebelumnya."
    return (
        f"{head}\n{target}"
        f"[MEMORI KERJA]\n{_format_working_memory(state.get('working_memory') or {})}\n\n"
        f"[MEMORI VISUAL]\n{_format_visual_memory(state.get('visual_memory') or [])}\n\n"
        f"[RINGKASAN PERCAKAPAN LAMA]\n{str(state.get('rolling_summary') or '- belum ada').strip()}\n\n"
        f"[GILIRAN TERAKHIR]\n{history}\n\n"
        f"INSTRUKSI KERAS: {HANDOFF_INSTRUCTION}"
    )


def build_handoff_payload(
    session_id: Optional[str] = None,
    target_provider: str = "",
    target_model: str = "",
    project: Optional[str] = None,
    extra_system: str = "",
) -> Dict[str, Any]:
    """Kompilasi konteks ke format provider tujuan (Direct API atau CLI agent)."""
    state = load_state(session_id, project)
    system_context = build_system_context(state)
    if extra_system:
        system_context = extra_system.strip() + "\n\n" + system_context
    messages = neutral_messages(state)
    provider = (target_provider or "").lower()

    if provider in CLI_PROVIDERS or provider == "cli":
        block = build_cli_handoff_block(state, provider or "cli", target_model)
        return {
            "format": "cli",
            "provider": provider,
            "model": target_model,
            "system": system_context,
            "messages": messages,
            "handoff_block": block,
            "prompt_prefix": block + "\n\n",
            "state": state,
        }

    if provider == "gemini":
        contents = [
            {"role": "user" if m["role"] == "user" else "model", "parts": [{"text": m["content"]}]}
            for m in messages
        ]
        return {
            "format": "gemini",
            "provider": provider,
            "model": target_model,
            "system_instruction": {"parts": [{"text": system_context}]},
            "contents": contents,
            "system": system_context,
            "messages": messages,
            "state": state,
        }

    if provider == "cohere":
        history = [
            {"role": "USER" if m["role"] == "user" else "CHATBOT", "message": m["content"]}
            for m in messages
        ]
        return {
            "format": "cohere",
            "provider": provider,
            "model": target_model,
            "preamble": system_context,
            "chat_history": history,
            "messages": messages,
            "system": system_context,
            "state": state,
        }

    return {
        "format": "openai",
        "provider": provider or "openai",
        "model": target_model,
        "messages": [{"role": "system", "content": system_context}] + messages,
        "system": system_context,
        "state": state,
    }


def session_summary(session_id: Optional[str] = None, project: Optional[str] = None) -> Dict[str, Any]:
    state = load_state(session_id, project)
    wm = state.get("working_memory") or {}
    return {
        "session_id": state.get("session_id"),
        "project": state.get("project") or wm.get("active_project"),
        "working_memory": wm,
        "visual_count": len(state.get("visual_memory") or []),
        "recent_turns": len(state.get("recent_turns") or []),
        "rolling_summary_chars": len(state.get("rolling_summary") or ""),
        "updated_at": state.get("updated_at"),
    }


def cli_main(argv: Optional[List[str]] = None) -> int:
    import argparse

    parser = argparse.ArgumentParser(prog="context_manager.py", description="ContentStudio Context Handoff V3.7")
    sub = parser.add_subparsers(dest="cmd")
    p_show = sub.add_parser("show", help="Tampilkan ringkasan state sesi (JSON)")
    p_show.add_argument("--session-id", default=None)
    p_show.add_argument("--project", default=None)
    p_handoff = sub.add_parser("handoff", help="Bangun payload handoff untuk provider tujuan")
    p_handoff.add_argument("--session-id", default=None)
    p_handoff.add_argument("--project", default=None)
    p_handoff.add_argument("--provider", required=True)
    p_handoff.add_argument("--model", default="")
    p_turn = sub.add_parser("record", help="Catat satu giliran manual")
    p_turn.add_argument("--session-id", default=None)
    p_turn.add_argument("--project", default=None)
    p_turn.add_argument("--role", default="user", choices=["user", "assistant"])
    p_turn.add_argument("--content", required=True)
    p_turn.add_argument("--model", default="")
    p_turn.add_argument("--provider", default="")
    p_turn.add_argument("--tool-summary", default="")
    args = parser.parse_args(argv if argv is not None else sys.argv[1:])

    if args.cmd == "handoff":
        payload = build_handoff_payload(args.session_id, args.provider, args.model, args.project)
        payload.pop("state", None)
        print(json.dumps(payload, indent=2, ensure_ascii=False))
        return 0
    if args.cmd == "record":
        record_turn(args.role, args.content, args.model, args.provider, args.tool_summary,
                    session_id=args.session_id, project=args.project)
        print(json.dumps({"status": "ok"}, ensure_ascii=False))
        return 0
    print(json.dumps(session_summary(args.session_id, args.project), indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(cli_main())
