#!/usr/bin/env python3
"""
ContentStudio Topic Context Memory (engine/topic_history.py)

Menyimpan riwayat topik/angle/hook/payoff di `library/topic_history.json`
sebagai KONTEKS VARIASI, bukan pembatas.

ATURAN MUTLAK:
  * `get_variation_context()` tidak pernah menolak, memblokir, atau memberi
    cooldown pada topik apa pun. Semua topik bebas dipakai ulang kapan saja.
  * Status `published` hanya boleh diubah bila user menandainya eksplisit.
    Export/render tidak pernah otomatis menaikkan status.
  * Status `deleted` dideteksi saat sinkronisasi bila folder workspace sudah
    hilang dan status bukan `published`.
"""

import sys
import os

_script_dir = os.path.dirname(os.path.abspath(__file__))
if sys.path and sys.path[0] == _script_dir:
    sys.path.pop(0)
_root_dir = os.path.dirname(_script_dir)
if _root_dir not in sys.path:
    sys.path.append(_root_dir)

import json
import re
from datetime import datetime
from typing import Any, Dict, List, Optional

from engine.runtime import get_root_dir, get_workspaces_dir
from engine.reference_dna import normalize_format_scale

STATUSES = ("draft", "exported", "published", "deleted")


def library_dir() -> str:
    return os.path.join(get_root_dir(), "library")


def history_path() -> str:
    return os.path.join(library_dir(), "topic_history.json")


def _now() -> str:
    return datetime.now().isoformat(timespec="seconds")


def _normalize_topic(text: Any) -> str:
    return re.sub(r"\s+", " ", str(text or "").strip().lower())


def _clean_entry(entry: Any) -> Optional[Dict[str, Any]]:
    if not isinstance(entry, dict):
        return None
    topic = str(entry.get("topic") or "").strip()
    project = str(entry.get("project") or "").strip()
    if not topic and not project:
        return None
    status = str(entry.get("status") or "draft").strip().lower()
    if status not in STATUSES:
        status = "draft"
    return {
        "project": project,
        "topic": topic,
        "angle": str(entry.get("angle") or "").strip(),
        "format_scale": normalize_format_scale(entry.get("format_scale"), "short"),
        "hook_type": str(entry.get("hook_type") or "").strip(),
        "payoff_type": str(entry.get("payoff_type") or "").strip(),
        "status": status,
        "updated_at": str(entry.get("updated_at") or _now()),
    }


def load_history() -> List[Dict[str, Any]]:
    path = history_path()
    if not os.path.exists(path):
        return []
    try:
        with open(path, "r", encoding="utf-8") as f:
            raw = json.load(f)
    except Exception:
        return []
    items = raw.get("entries") if isinstance(raw, dict) else raw
    if not isinstance(items, list):
        return []
    cleaned = []
    for item in items:
        entry = _clean_entry(item)
        if entry:
            cleaned.append(entry)
    return cleaned


def save_history(entries: List[Dict[str, Any]]) -> bool:
    os.makedirs(library_dir(), exist_ok=True)
    payload = {
        "version": 1,
        "non_blocking": True,
        "updated_at": _now(),
        "entries": entries,
    }
    try:
        with open(history_path(), "w", encoding="utf-8") as f:
            json.dump(payload, f, indent=2, ensure_ascii=False)
        return True
    except Exception:
        return False


def record_topic(
    project: str,
    topic: str,
    angle: str = "",
    format_scale: str = "short",
    hook_type: str = "",
    payoff_type: str = "",
    status: str = "draft",
) -> Dict[str, Any]:
    """Catat/refresh satu topik. Tidak pernah menolak topik yang pernah ada."""
    entry = _clean_entry({
        "project": project,
        "topic": topic,
        "angle": angle,
        "format_scale": format_scale,
        "hook_type": hook_type,
        "payoff_type": payoff_type,
        "status": status,
        "updated_at": _now(),
    })
    if not entry:
        return {"status": "error", "message": "Project atau topik wajib diisi"}

    entries = load_history()
    replaced = False
    for idx, existing in enumerate(entries):
        same_project = entry["project"] and existing.get("project") == entry["project"]
        same_topic = _normalize_topic(existing.get("topic")) == _normalize_topic(entry["topic"])
        # Satu entri = satu pasangan project + topic. Topik yang sama di proyek
        # berbeda tetap dicatat terpisah agar variasi angle lintas proyek terlihat.
        if same_project and same_topic:
            # Status lama dipertahankan kecuali user memberi status eksplisit baru.
            merged = dict(existing)
            merged.update({k: v for k, v in entry.items() if v not in ("", None)})
            if status not in STATUSES or status == "draft":
                merged["status"] = existing.get("status", "draft")
            entries[idx] = merged
            replaced = True
            break
    if not replaced:
        entries.append(entry)
    save_history(entries)
    return {"status": "ok", "entry": entry, "replaced": replaced, "blocked": False}


def set_status(project: str = "", topic: str = "", status: str = "draft") -> Dict[str, Any]:
    """Ubah status secara eksplisit. Satu-satunya jalur menuju `published`."""
    clean_status = str(status or "").strip().lower()
    if clean_status not in STATUSES:
        return {"status": "error", "message": "Status harus salah satu dari: " + ", ".join(STATUSES)}
    entries = load_history()
    target_project = os.path.basename(str(project or "").strip())
    target_topic = _normalize_topic(topic)
    updated = 0
    for entry in entries:
        project_match = (not target_project) or entry.get("project") == target_project
        topic_match = (not target_topic) or _normalize_topic(entry.get("topic")) == target_topic
        if target_project and target_topic:
            matched = project_match and topic_match
        elif target_project or target_topic:
            matched = project_match and topic_match
        else:
            matched = False
        if matched:
            entry["status"] = clean_status
            entry["updated_at"] = _now()
            updated += 1
    if updated:
        save_history(entries)
    return {"status": "ok", "updated": updated, "new_status": clean_status}


def sync_statuses(existing_projects: Optional[List[str]] = None) -> Dict[str, Any]:
    """
    Sinkronkan riwayat dengan kondisi folder workspaces/.

    Hanya menandai `deleted` untuk proyek yang foldernya sudah tidak ada dan
    statusnya bukan `published`. Status `published` tidak pernah diturunkan.
    """
    if existing_projects is None:
        base = get_workspaces_dir()
        existing_projects = []
        if os.path.isdir(base):
            existing_projects = [
                name for name in os.listdir(base)
                if os.path.isdir(os.path.join(base, name)) and not name.startswith(".")
            ]
    present = {os.path.basename(name) for name in existing_projects}
    entries = load_history()
    changed = 0
    for entry in entries:
        project = entry.get("project") or ""
        if not project or project in present:
            continue
        if entry.get("status") in ("published", "deleted"):
            continue
        entry["status"] = "deleted"
        entry["updated_at"] = _now()
        changed += 1
    if changed:
        save_history(entries)
    return {"status": "ok", "marked_deleted": changed, "entries": len(entries)}


def get_variation_context(topic: str) -> Dict[str, Any]:
    """
    Kembalikan angle/hook/payoff yang pernah dicoba untuk tema terkait.

    TIDAK PERNAH memblokir. Field `blocked` selalu False dan `allowed` selalu
    True agar agent bebas mengangkat topik yang sama dengan sudut baru.
    """
    wanted = _normalize_topic(topic)
    entries = load_history()
    related: List[Dict[str, Any]] = []
    angles: List[str] = []
    hooks: List[str] = []
    payoffs: List[str] = []

    def _push_unique(bucket: List[str], value: Any) -> None:
        text = str(value or "").strip()
        if text and text not in bucket:
            bucket.append(text)

    for entry in entries:
        entry_topic = _normalize_topic(entry.get("topic"))
        if not wanted or not entry_topic:
            continue
        if wanted in entry_topic or entry_topic in wanted:
            related.append(entry)
            _push_unique(angles, entry.get("angle"))
            _push_unique(hooks, entry.get("hook_type"))
            _push_unique(payoffs, entry.get("payoff_type"))

    return {
        "topic": str(topic or "").strip(),
        "allowed": True,
        "blocked": False,
        "cooldown": False,
        "used_count": len(related),
        "tried_angles": angles,
        "tried_hooks": hooks,
        "tried_payoffs": payoffs,
        "related_projects": [e.get("project") for e in related if e.get("project")],
        "guidance": (
            "Topik ini bebas dipakai lagi. Pilih angle, hook, atau payoff yang belum "
            "dicoba agar tidak monoton."
            if related else
            "Belum ada riwayat untuk tema ini. Bebas menentukan angle yang paling kuat."
        ),
        "entries": related,
    }


def list_entries(status: Optional[str] = None) -> List[Dict[str, Any]]:
    entries = load_history()
    clean_status = str(status or "").strip().lower()
    if clean_status in STATUSES:
        entries = [e for e in entries if e.get("status") == clean_status]
    return entries


def cli_main(argv: Optional[List[str]] = None) -> int:
    import argparse

    parser = argparse.ArgumentParser(prog="topic_history.py", description="ContentStudio Topic History (non-blocking)")
    sub = parser.add_subparsers(dest="cmd")

    p_list = sub.add_parser("list")
    p_list.add_argument("--status", default=None, choices=list(STATUSES))

    p_rec = sub.add_parser("record")
    p_rec.add_argument("--project", required=True)
    p_rec.add_argument("--topic", required=True)
    p_rec.add_argument("--angle", default="")
    p_rec.add_argument("--format-scale", default="short")
    p_rec.add_argument("--hook-type", default="")
    p_rec.add_argument("--payoff-type", default="")
    p_rec.add_argument("--status", default="draft", choices=list(STATUSES))

    p_set = sub.add_parser("set-status")
    p_set.add_argument("--project", default="")
    p_set.add_argument("--topic", default="")
    p_set.add_argument("--status", required=True, choices=list(STATUSES))

    p_sync = sub.add_parser("sync")

    p_ctx = sub.add_parser("context")
    p_ctx.add_argument("--topic", required=True)

    args = parser.parse_args(argv or sys.argv[1:])
    if args.cmd == "list":
        print(json.dumps(list_entries(args.status), indent=2, ensure_ascii=False))
        return 0
    if args.cmd == "record":
        print(json.dumps(record_topic(
            args.project, args.topic, args.angle, args.format_scale,
            args.hook_type, args.payoff_type, args.status,
        ), indent=2, ensure_ascii=False))
        return 0
    if args.cmd == "set-status":
        print(json.dumps(set_status(args.project, args.topic, args.status), indent=2, ensure_ascii=False))
        return 0
    if args.cmd == "sync":
        print(json.dumps(sync_statuses(), indent=2, ensure_ascii=False))
        return 0
    if args.cmd == "context":
        print(json.dumps(get_variation_context(args.topic), indent=2, ensure_ascii=False))
        return 0
    parser.print_help()
    return 1


if __name__ == "__main__":
    raise SystemExit(cli_main())
