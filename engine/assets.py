#!/usr/bin/env python3
"""
ContentStudio Global Asset Library Manager (engine/assets.py)
Memindai dan mengelola katalog aset lintas niche (stickers, sfx, backgrounds).
100% Python stdlib + ffprobe.
"""

import sys
import os

# Mencegah direktori engine/ membayangi modul library standar Python (misal 'inspect')
_script_dir = os.path.dirname(os.path.abspath(__file__))
if sys.path and sys.path[0] == _script_dir:
    sys.path.pop(0)
_root_dir = os.path.dirname(_script_dir)
if _root_dir not in sys.path:
    sys.path.append(_root_dir)

import json
import argparse
import subprocess
from typing import Dict, Any, List, Optional
from engine.runtime import get_root_dir, get_assets_dir, get_ffprobe_bin

# Root direktori ContentStudio
BASE_DIR = get_root_dir()
ASSETS_DIR = get_assets_dir()
LIBRARY_JSON = os.path.join(ASSETS_DIR, "library.json")

FOLDERS = {
    "sticker": os.path.join(ASSETS_DIR, "stickers"),
    "meme": os.path.join(ASSETS_DIR, "memes"),
    "sfx": os.path.join(ASSETS_DIR, "sfx"),
    "background": os.path.join(ASSETS_DIR, "backgrounds")
}

DEFAULT_TAGS = {
    "arrow_red_hook.png": ["hook", "arrow", "pointer", "red"],
    "dark_grid_9x16.png": ["dark", "grid", "editorial", "clean"]
}

def probe_asset(file_path: str) -> Dict[str, Any]:
    """Ekstraksi metadata teknis aset (width, height, duration) via ffprobe."""
    meta: Dict[str, Any] = {}
    if not os.path.exists(file_path):
        return meta
    try:
        cmd = [
            get_ffprobe_bin(), "-v", "quiet",
            "-print_format", "json",
            "-show_format", "-show_streams",
            file_path
        ]
        res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        if res.returncode == 0:
            data = json.loads(res.stdout)
            streams = data.get("streams", [])
            fmt = data.get("format", {})
            v_stream = next((s for s in streams if s.get("codec_type") == "video"), None)
            a_stream = next((s for s in streams if s.get("codec_type") == "audio"), None)
            if v_stream:
                meta["width"] = int(v_stream.get("width") or 0)
                meta["height"] = int(v_stream.get("height") or 0)
            if a_stream:
                meta["duration"] = round(float(a_stream.get("duration") or fmt.get("duration") or 0.0), 2)
            elif fmt.get("duration"):
                meta["duration"] = round(float(fmt.get("duration") or 0.0), 2)
    except Exception:
        pass
    return meta

def load_library() -> Dict[str, Any]:
    """Memuat library.json jika ada."""
    if os.path.exists(LIBRARY_JSON):
        try:
            with open(LIBRARY_JSON, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            pass
    return {"version": 1, "assets": []}

def save_library(data: Dict[str, Any]) -> None:
    """Menyimpan library.json."""
    os.makedirs(ASSETS_DIR, exist_ok=True)
    with open(LIBRARY_JSON, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)

# ==============================================================================
# SUBCOMMAND: SCAN
# ==============================================================================
def scan_assets_library() -> Dict[str, Any]:
    """Memindai folder assets/ dan memperbarui library.json secara in-process."""
    lib = load_library()
    existing_items: Dict[str, Dict[str, Any]] = {
        item.get("id"): item for item in lib.get("assets", []) if item.get("id")
    }

    scanned_assets: List[Dict[str, Any]] = []
    total_found = 0

    for a_type, folder_path in FOLDERS.items():
        if not os.path.exists(folder_path):
            os.makedirs(folder_path, exist_ok=True)
            continue

        for fname in sorted(os.listdir(folder_path)):
            if fname.startswith("."):
                continue
            full_path = os.path.join(folder_path, fname)
            if not os.path.isfile(full_path):
                continue

            rel_path = os.path.relpath(full_path, BASE_DIR)
            asset_id = f"{a_type}:{fname}"
            total_found += 1

            # Jika sudah ada, pertahankan tag dan deskripsi manual
            existing = existing_items.get(asset_id, {})
            name_base = os.path.splitext(fname)[0].replace("_", " ").title()

            tags = existing.get("tags")
            if tags is None:
                # Default tags fallback
                if fname in DEFAULT_TAGS:
                    tags = list(DEFAULT_TAGS[fname])
                else:
                    # Inisialisasi tag otomatis dari potongan nama file
                    tokens = [t.lower() for t in fname.replace(".", "_").split("_") if len(t) > 1]
                    tags = list(dict.fromkeys(tokens))

            # Selalu probe ulang spesifikasi fisik aktual file
            meta = probe_asset(full_path)
            if not meta and existing.get("meta"):
                meta = existing.get("meta")

            scanned_assets.append({
                "id": asset_id,
                "name": existing.get("name", name_base),
                "type": a_type,
                "filename": fname,
                "path": rel_path,
                "tags": tags,
                "meta": meta
            })

    lib["assets"] = scanned_assets
    save_library(lib)
    return {
        "status": "ok",
        "total": len(scanned_assets),
        "assets": scanned_assets,
        "library_path": LIBRARY_JSON
    }

def cmd_scan(args) -> int:
    """Memindai folder assets/ dan memperbarui library.json (CLI wrapper)."""
    res = scan_assets_library()
    scanned_assets = res.get("assets", [])
    print(f"[*] Pemindaian selesai: {len(scanned_assets)} aset terdaftar di '{os.path.relpath(LIBRARY_JSON, BASE_DIR)}'")
    for a in scanned_assets:
        meta_info = f"{a['meta'].get('width')}x{a['meta'].get('height')}" if a['meta'].get('width') else f"{a['meta'].get('duration', '')}s"
        print(f"  - [{a['type'].upper():10s}] {a['name']:25s} | Tags: {', '.join(a['tags'])} ({meta_info})")
    return 0

# ==============================================================================
# SUBCOMMAND: SEARCH
# ==============================================================================
def cmd_search(args) -> int:
    """Mencari aset berdasarkan tag dan type."""
    lib = load_library()
    query_type = (args.type or "all").lower()
    if query_type in ("bg", "backgrounds"):
        query_type = "background"
    elif query_type in ("stickers", "sticker"):
        query_type = "sticker"

    query_tag = (args.tag or "").strip().lower()

    matches = []
    for item in lib.get("assets", []):
        if query_type not in ("all", "") and item.get("type") != query_type:
            continue

        if query_tag:
            tags = [t.lower() for t in item.get("tags", [])]
            name = item.get("name", "").lower()
            fname = item.get("filename", "").lower()
            if not any(query_tag in t for t in tags) and query_tag not in name and query_tag not in fname:
                continue

        matches.append(item)

    if args.json:
        print(json.dumps(matches, indent=2, ensure_ascii=False))
        return 0

    print(f"\n[*] Hasil Pencarian Bank Aset (Type: '{query_type}', Tag: '{query_tag or '*'}'):")
    print(f"{'No':<4} | {'Tipe':<11} | {'Nama Aset':<24} | {'Path':<40} | {'Tags'}")
    print("-" * 105)

    if not matches:
        print("  (Tidak ada aset yang cocok)")
    else:
        for idx, m in enumerate(matches, start=1):
            tag_str = ", ".join(m.get("tags", []))
            print(f"#{idx:<3} | {m.get('type', ''):<11} | {m.get('name', ''):<24} | {m.get('path', ''):<40} | {tag_str}")

    print(f"\nTotal: {len(matches)} aset ditemukan.\n")
    return 0

# ==============================================================================
# MAIN ROUTER
# ==============================================================================
def main():
    parser = argparse.ArgumentParser(description="ContentStudio Global Asset Bank (engine/assets.py)")
    subparsers = parser.add_subparsers(dest="subcommand", required=True)

    # scan
    p_scan = subparsers.add_parser("scan", help="Pindai folder assets/ dan perbarui library.json")
    p_scan.set_defaults(func=cmd_scan)

    # search
    p_search = subparsers.add_parser("search", help="Cari aset berdasarkan tipe dan tag")
    p_search.add_argument("--type", choices=["sticker", "sfx", "bg", "background", "all"], default="all", help="Filter tipe aset")
    p_search.add_argument("--tag", default="", help="Kata kunci pencarian tag atau nama")
    p_search.add_argument("--json", action="store_true", help="Output format JSON")
    p_search.set_defaults(func=cmd_search)

    args = parser.parse_args()
    return args.func(args)

if __name__ == "__main__":
    sys.exit(main() or 0)
