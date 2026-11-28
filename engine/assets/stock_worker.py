#!/usr/bin/env python3
"""
ContentStudio Stock Worker (engine/assets/stock_worker.py)

B-Roll stok beragam, anti-duplikat, dan sadar-rasio.

  * Menerima 2-3 variasi keyword per scene.
  * Orientasi pencarian dipetakan dari `aspect_ratio`
    (16:9 -> landscape, 9:16/4:5 -> portrait, 1:1 -> square).
  * `workspaces/<project>/used_assets.json` mencegah aset yang sama dipakai
    ulang di scene lain dalam proyek yang sama (penting untuk video panjang).
  * Memilih bervariasi dari top-5 hasil paling relevan.
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
import subprocess
import urllib.request
from typing import Any, Dict, List, Optional

from engine.runtime import get_ffmpeg_bin, get_workspaces_dir
from engine.reference_dna import dimensions_for, normalize_aspect_ratio, stock_orientation

USED_FILENAME = "used_assets.json"
MAX_CANDIDATES = 5


def _emit_step(message: str) -> None:
    sys.stderr.write("[STEP] %s\n" % message)
    sys.stderr.flush()


def _emit_think(message: str) -> None:
    sys.stderr.write("[THINK] %s\n" % message)
    sys.stderr.flush()


def used_assets_path(project: str) -> str:
    return os.path.join(get_workspaces_dir(), os.path.basename(str(project or "").strip()), USED_FILENAME)


def load_used(project: str) -> List[Dict[str, Any]]:
    path = used_assets_path(project)
    if not os.path.exists(path):
        return []
    try:
        with open(path, "r", encoding="utf-8") as f:
            raw = json.load(f)
    except Exception:
        return []
    items = raw.get("used") if isinstance(raw, dict) else raw
    return items if isinstance(items, list) else []


def mark_used(project: str, item: Dict[str, Any]) -> None:
    items = load_used(project)
    items.append(item)
    path = used_assets_path(project)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    try:
        with open(path, "w", encoding="utf-8") as f:
            json.dump({"version": 1, "used": items}, f, indent=2, ensure_ascii=False)
    except Exception:
        pass


def _used_keys(project: str) -> set:
    keys = set()
    for item in load_used(project):
        for field in ("id", "url", "downloadUrl", "file"):
            value = str(item.get(field) or "").strip()
            if value:
                keys.add(value)
    return keys


def _search(query: str, orientation: str, limit: int = 8) -> List[Dict[str, Any]]:
    """Cari stok lintas provider yang tersedia; selalu degrade ke Wikimedia."""
    from engine import media_fetcher as mf

    providers: List[str] = []
    if str(os.environ.get("PEXELS_API_KEY") or "").strip():
        providers.append("pexels")
    if str(os.environ.get("PIXABAY_API_KEY") or "").strip():
        providers.append("pixabay")
    providers.append("wikimedia")

    for provider in providers:
        try:
            if provider == "pexels":
                results = mf._search_pexels(query, limit, str(os.environ.get("PEXELS_API_KEY")).strip())
            elif provider == "pixabay":
                results = mf._search_pixabay(query, limit, str(os.environ.get("PIXABAY_API_KEY")).strip())
            else:
                results = mf._search_wikimedia(query, limit)
            if results:
                filtered = _filter_orientation(results, orientation)
                return (filtered or results)[:limit]
        except Exception as exc:
            _emit_think("Sumber %s belum bisa dipakai untuk kata kunci ini." % provider)
            continue
    return []


def _filter_orientation(results: List[Dict[str, Any]], orientation: str) -> List[Dict[str, Any]]:
    if orientation not in ("portrait", "landscape", "square"):
        return results
    kept = []
    for item in results:
        width = item.get("width") or 0
        height = item.get("height") or 0
        if not width or not height:
            continue
        ratio = width / float(height)
        if orientation == "portrait" and ratio < 1.0:
            kept.append(item)
        elif orientation == "landscape" and ratio > 1.0:
            kept.append(item)
        elif orientation == "square" and 0.85 <= ratio <= 1.18:
            kept.append(item)
    return kept


def _download(url: str, dest: str, timeout: int = 90) -> None:
    req = urllib.request.Request(url, headers={"User-Agent": "ContentStudio/3.6"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        data = resp.read()
    if len(data) < 1024:
        raise RuntimeError("Ukuran unduhan terlalu kecil")
    os.makedirs(os.path.dirname(os.path.abspath(dest)) or ".", exist_ok=True)
    with open(dest, "wb") as f:
        f.write(data)


def _normalize_image(file_path: str, aspect_ratio: str) -> None:
    """Crop-to-fill ke rasio target; blur-fill bila sumber terlalu jauh rasionya."""
    ratio = normalize_aspect_ratio(aspect_ratio)
    width, height = dimensions_for(ratio, "1080p")
    try:
        from PIL import Image  # type: ignore
        with Image.open(file_path) as img:
            src_w, src_h = img.size
            target_ratio = width / float(height)
            current_ratio = src_w / float(src_h)
            if abs(current_ratio - target_ratio) < 0.02:
                return
            if abs(current_ratio - target_ratio) <= 0.9:
                if current_ratio > target_ratio:
                    new_w = int(round(src_h * target_ratio))
                    left = max(0, (src_w - new_w) // 2)
                    img.crop((left, 0, left + new_w, src_h)).convert("RGB").save(file_path)
                else:
                    new_h = int(round(src_w / target_ratio))
                    top = max(0, (src_h - new_h) // 2)
                    img.crop((0, top, src_w, top + new_h)).convert("RGB").save(file_path)
                return
            blurred = img.convert("RGB").resize((width, height))
            from PIL import ImageFilter  # type: ignore
            blurred = blurred.filter(ImageFilter.GaussianBlur(radius=max(8, width // 60)))
            cover_ratio = min(width / float(src_w), height / float(src_h))
            new_size = (max(1, int(src_w * cover_ratio)), max(1, int(src_h * cover_ratio)))
            foreground = img.convert("RGB").resize(new_size)
            canvas = blurred.copy()
            canvas.paste(foreground, ((width - new_size[0]) // 2, (height - new_size[1]) // 2))
            canvas.save(file_path)
            return
    except Exception:
        pass

    ffmpeg = get_ffmpeg_bin()
    if not ffmpeg or (not os.path.isabs(ffmpeg) and not shutil.which(ffmpeg)):
        return
    tmp = file_path + ".norm.png"
    try:
        subprocess.run(
            [ffmpeg, "-y", "-i", file_path,
             "-vf", "scale=%d:%d:force_original_aspect_ratio=increase,crop=%d:%d" % (width, height, width, height),
             tmp],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=90,
        )
        if os.path.exists(tmp) and os.path.getsize(tmp) > 0:
            os.replace(tmp, file_path)
    except Exception:
        pass


def fetch_stock(
    out_path: str,
    queries: List[str],
    dna: Optional[Dict[str, Any]] = None,
    aspect_ratio: Optional[str] = None,
    project: str = "",
    scene_index: int = 0,
) -> Dict[str, Any]:
    """Cari + unduh satu aset stok yang belum pernah dipakai di proyek ini."""
    dna = dna or {}
    ratio = normalize_aspect_ratio(aspect_ratio or dna.get("aspect_ratio"), "16:9")
    orientation = stock_orientation(ratio)
    clean_queries = [q.strip() for q in queries if str(q).strip()]
    if not clean_queries:
        return {"status": "error", "message": "Tidak ada kata kunci stok"}

    used = _used_keys(project)
    candidates: List[Dict[str, Any]] = []
    for query in clean_queries[:3]:
        _emit_step("Mencari stok: %s (%s)" % (query, orientation))
        results = _search(query, orientation, limit=8)
        for item in results:
            keys = {str(item.get(k) or "").strip() for k in ("id", "url", "downloadUrl")}
            if keys & used:
                continue
            if any(str(item.get("downloadUrl")) == str(c.get("downloadUrl")) for c in candidates):
                continue
            candidates.append(item)
        if len(candidates) >= MAX_CANDIDATES:
            break

    if not candidates:
        return {"status": "error", "message": "Tidak ada stok baru yang relevan"}

    top = candidates[:MAX_CANDIDATES]
    pick = top[int(scene_index or 0) % len(top)]
    download_url = str(pick.get("downloadUrl") or pick.get("thumbnailUrl") or "").strip()
    if not download_url:
        return {"status": "error", "message": "Kandidat stok tidak punya URL unduhan"}

    is_video = str(pick.get("type") or "").lower() == "video"
    if is_video:
        ext = os.path.splitext(out_path)[1] or ".mp4"
        if ext.lower() not in (".mp4", ".mov", ".webm", ".mkv"):
            out_path = os.path.splitext(out_path)[0] + ".mp4"
    else:
        if os.path.splitext(out_path)[1].lower() not in (".jpg", ".jpeg", ".png", ".webp"):
            out_path = os.path.splitext(out_path)[0] + ".jpg"

    _emit_step("Mengunduh stok terpilih (indeks %d dari %d kandidat)" % (int(scene_index or 0) % len(top), len(top)))
    try:
        _download(download_url, out_path)
    except Exception as exc:
        return {"status": "error", "message": "Unduhan stok gagal: " + str(exc)[:150]}

    if not is_video:
        _normalize_image(out_path, ratio)

    mark_used(project, {
        "id": pick.get("id"),
        "url": pick.get("url") or download_url,
        "downloadUrl": download_url,
        "file": os.path.basename(out_path),
        "scene": scene_index,
        "provider": pick.get("provider"),
    })
    return {
        "status": "ok",
        "file": out_path,
        "provider": pick.get("provider"),
        "source_id": pick.get("id"),
        "aspect_ratio": ratio,
        "kind": "video" if is_video else "image",
        "query": clean_queries[0],
    }


def worker_contract() -> Dict[str, Any]:
    return {"source": "stock", "anti_duplicate": USED_FILENAME, "kinds": ["image", "video"]}


def cli_main(argv=None) -> int:
    import argparse

    parser = argparse.ArgumentParser(prog="stock_worker.py", description="ContentStudio Stock Worker")
    parser.add_argument("--project", default="")
    parser.add_argument("--out", required=True)
    parser.add_argument("--query", action="append", required=True)
    parser.add_argument("--aspect-ratio", default=None)
    parser.add_argument("--scene-index", type=int, default=0)
    args = parser.parse_args(argv if argv is not None else sys.argv[1:])
    dna = None
    if args.project:
        from engine.reference_dna import load_reference_dna
        dna = load_reference_dna(args.project)
    res = fetch_stock(args.out, args.query, dna, args.aspect_ratio, args.project, args.scene_index)
    print(json.dumps(res, indent=2, ensure_ascii=False))
    return 0 if res.get("status") == "ok" else 1


if __name__ == "__main__":
    raise SystemExit(cli_main())
