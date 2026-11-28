#!/usr/bin/env python3
"""
ContentStudio Local Worker (engine/assets/local_worker.py)

Meresolusi aset yang SUDAH ada milik user/proyek:
  * `workspaces/<project>/assets/`
  * `library/media/`
  * bank aset global `assets/` (sticker/background)

Tidak pernah mencari ke internet. Bila file tidak ada, kembalikan error jelas.
"""

import sys
import os

_script_dir = os.path.dirname(os.path.abspath(__file__))
if sys.path and sys.path[0] == _script_dir:
    sys.path.pop(0)
_engine_dir = os.path.dirname(_script_dir)
_root_dir = os.path.dirname(_engine_dir)
for _p in (_engine_dir, _root_dir):
    if _p not in sys.path:
        sys.path.append(_p)

import json
import shutil
from typing import Any, Dict, List, Optional

from engine.runtime import get_assets_dir, get_root_dir, get_workspaces_dir
from engine.reference_dna import scene_contract

MEDIA_EXTS = (".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif", ".mp4", ".mov", ".webm", ".mkv", ".m4v")


def _emit_step(message: str) -> None:
    sys.stderr.write("[STEP] %s\n" % message)
    sys.stderr.flush()


def project_dir(project: str) -> str:
    return os.path.join(get_workspaces_dir(), os.path.basename(str(project or "").strip()))


def search_roots(project: str) -> List[str]:
    return [
        os.path.join(project_dir(project), "assets"),
        os.path.join(get_root_dir(), "library", "media"),
        get_assets_dir(),
    ]


def _iter_media(root: str, limit: int = 400):
    if not root or not os.path.isdir(root):
        return
    count = 0
    for current, dirs, files in os.walk(root):
        dirs[:] = [d for d in dirs if not d.startswith(".")]
        for name in files:
            if name.lower().endswith(MEDIA_EXTS):
                yield os.path.join(current, name)
                count += 1
                if count >= limit:
                    return


def find_local_asset(project: str, scene: Any) -> Optional[str]:
    """Cari aset lokal berdasarkan path visual eksplisit lalu kata kunci nama file."""
    contract = scene_contract(scene)
    explicit = contract.get("visual")
    candidates: List[str] = []
    if explicit:
        candidates.extend([
            explicit,
            os.path.join(project_dir(project), explicit),
            os.path.join(project_dir(project), "assets", explicit),
            os.path.join(get_root_dir(), "library", "media", explicit),
            os.path.join(get_assets_dir(), explicit),
        ])
    for candidate in candidates:
        if candidate and os.path.isfile(candidate):
            return candidate

    keywords = [k.lower() for k in contract.get("keywords") or []]
    tokens = [t for t in " ".join(keywords).split() if len(t) >= 3]
    if not tokens:
        return None
    for root in search_roots(project):
        for file_path in _iter_media(root) or []:
            name = os.path.basename(file_path).lower()
            if any(token in name for token in tokens):
                return file_path
    return None


def fetch_local(scene: Any, out_path: str, project: str = "") -> Dict[str, Any]:
    """Salin aset lokal ke path scene (atau pakai langsung bila sudah di tempat)."""
    source = find_local_asset(project, scene)
    if not source:
        return {
            "status": "error",
            "message": "Aset lokal tidak ditemukan di workspaces/%s/assets atau library/media" % project,
        }
    _emit_step("Memakai aset lokal: %s" % os.path.basename(source))
    try:
        if os.path.abspath(source) == os.path.abspath(out_path):
            return {"status": "ok", "file": out_path, "kind": _kind_of(out_path), "source": source}
        os.makedirs(os.path.dirname(os.path.abspath(out_path)) or ".", exist_ok=True)
        ext = os.path.splitext(source)[1].lower()
        target = out_path
        if ext and not target.lower().endswith(ext):
            target = os.path.splitext(out_path)[0] + ext
        shutil.copyfile(source, target)
        return {"status": "ok", "file": target, "kind": _kind_of(target), "source": source}
    except Exception as exc:
        return {"status": "error", "message": "Gagal menyalin aset lokal: " + str(exc)[:120]}


def _kind_of(file_path: str) -> str:
    ext = os.path.splitext(str(file_path))[1].lower()
    if ext in (".mp4", ".mov", ".webm", ".mkv", ".m4v"):
        return "video"
    return "image"


def worker_contract() -> Dict[str, Any]:
    return {"source": "local", "roots": ["workspaces/<project>/assets", "library/media", "assets"], "network": False}


def cli_main(argv=None) -> int:
    import argparse

    parser = argparse.ArgumentParser(prog="local_worker.py", description="ContentStudio Local Worker")
    parser.add_argument("--project", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--visual", default="")
    parser.add_argument("--keyword", action="append", default=[])
    args = parser.parse_args(argv if argv is not None else sys.argv[1:])
    res = fetch_local({"visual": args.visual, "keywords": args.keyword}, args.out, args.project)
    print(json.dumps(res, indent=2, ensure_ascii=False))
    return 0 if res.get("status") == "ok" else 1


if __name__ == "__main__":
    raise SystemExit(cli_main())
