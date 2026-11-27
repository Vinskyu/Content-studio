#!/usr/bin/env python3
"""
ContentStudio Autonomous Media Engine (engine/media_fetcher.py)

Features:
  * `update-ytdlp` – download the latest yt‑dlp zipapp into $HOME/.cache/bin/yt‑dlp,
    make it executable, and ensure FFmpeg/FFprobe shims are available.
  * `search` – unified multi‑source stock media search (auto, ytdlp, wikimedia, pexels, pixabay).
  * `download` – download a media URL (direct file or platform video) using yt‑dlp + optional FFmpeg slicing.

All steps print to *stderr* with a `[STEP] …` prefix and flush immediately.
"""

import sys
import os
import json
import subprocess
import shutil
import argparse
import urllib.request
import urllib.parse
import time
from pathlib import Path

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _log_step(message: str) -> None:
    sys.stderr.write(f"[STEP] {message}\n")
    sys.stderr.flush()

def _ensure_cache_bin() -> Path:
    cache_dir = Path(os.getenv("HOME", "~")) / ".cache" / "bin"
    cache_dir.mkdir(parents=True, exist_ok=True)
    return cache_dir

def _download_file(url: str, dest: Path, timeout: int = 30) -> None:
    _log_step(f"Downloading {url} → {dest}")
    with urllib.request.urlopen(url, timeout=timeout) as resp, open(dest, "wb") as out:
        shutil.copyfileobj(resp, out)
    _log_step(f"Saved {dest.name} ({dest.stat().st_size} bytes)")

# ---------------------------------------------------------------------------
# yt‑dlp setup
# ---------------------------------------------------------------------------

def update_ytdlp() -> None:
    cache_bin = _ensure_cache_bin()
    ytdlp_path = cache_bin / "yt-dlp"
    if ytdlp_path.exists():
        _log_step("Removing old yt‑dlp binary")
        ytdlp_path.unlink()
    url = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp"
    _download_file(url, ytdlp_path)
    ytdlp_path.chmod(0o755)
    _log_step("yt‑dlp updated and executable")

    # FFmpeg shim – look for libffmpeg.so / ffmpeg in PATH
    ffmpeg_shim_dir = cache_bin
    ffmpeg_shim = ffmpeg_shim_dir / "ffmpeg"
    ffprobe_shim = ffmpeg_shim_dir / "ffprobe"
    # If real ffmpeg exists in PATH we are fine; otherwise try shared libs.
    def _create_shim(name: str, lib_name: str):
        if shutil.which(name):
            _log_step(f"System {name} found in PATH – no shim needed")
            return
        lib_path = shutil.which(lib_name)  # may be None
        if lib_path:
            _log_step(f"Creating shim for {name} -> {lib_path}")
            script = f"#!/usr/bin/env bash\nexec \"{lib_path}\" \"$@\""
            shim_path = ffmpeg_shim_dir / name
            shim_path.write_text(script)
            shim_path.chmod(0o755)
        else:
            _log_step(f"Warning: {name} not found and no shared lib {lib_name} – yt‑dlp may fail")
    _create_shim("ffmpeg", "ffmpeg")
    _create_shim("ffprobe", "ffprobe")
    _log_step("FFmpeg/FFprobe shim preparation complete")

# ---------------------------------------------------------------------------
# Search implementations
# ---------------------------------------------------------------------------

def _search_wikimedia(query: str, limit: int):
    params = {
        "action": "query",
        "format": "json",
        "generator": "search",
        "gsrsearch": query,
        "gsrlimit": limit,
        "prop": "imageinfo",
        "iiprop": "url|size|metadata",
        "iiurlwidth": 400,
    }
    url = "https://commons.wikimedia.org/w/api.php?" + urllib.parse.urlencode(params)
    _log_step(f"Wikimedia search: {query}")
    with urllib.request.urlopen(url) as resp:
        data = json.load(resp)
    results = []
    for page in data.get("query", {}).get("pages", {}).values():
        img = page.get("imageinfo", [{}])[0]
        results.append({
            "id": str(page.get("pageid")),
            "provider": "wikimedia",
            "type": "photo",
            "title": page.get("title", ""),
            "thumbnailUrl": img.get("thumburl", ""),
            "downloadUrl": img.get("url", ""),
            "width": img.get("width", 0),
            "height": img.get("height", 0),
            "durationSec": 0,
            "author": "",
        })
    return results[:limit]

def _search_pexels(query: str, limit: int, key: str):
    url = f"https://api.pexels.com/v1/search?query={urllib.parse.quote(query)}&per_page={limit}"
    req = urllib.request.Request(url, headers={"Authorization": key})
    _log_step("Pexels search")
    with urllib.request.urlopen(req) as resp:
        data = json.load(resp)
    results = []
    for photo in data.get("photos", []):
        results.append({
            "id": str(photo.get("id")),
            "provider": "pexels",
            "type": "photo",
            "title": photo.get("url", ""),
            "thumbnailUrl": photo.get("src", {}).get("medium", ""),
            "downloadUrl": photo.get("src", {}).get("original", ""),
            "width": photo.get("width", 0),
            "height": photo.get("height", 0),
            "durationSec": 0,
            "author": photo.get("photographer", ""),
        })
    return results[:limit]

def _search_pixabay(query: str, limit: int, key: str):
    params = {"key": key, "q": query, "per_page": limit, "image_type": "photo"}
    url = "https://pixabay.com/api/?" + urllib.parse.urlencode(params)
    _log_step("Pixabay search")
    with urllib.request.urlopen(url) as resp:
        data = json.load(resp)
    results = []
    for hit in data.get("hits", []):
        results.append({
            "id": str(hit.get("id")),
            "provider": "pixabay",
            "type": "photo",
            "title": hit.get("tags", ""),
            "thumbnailUrl": hit.get("previewURL", ""),
            "downloadUrl": hit.get("largeImageURL", ""),
            "width": hit.get("imageWidth", 0),
            "height": hit.get("imageHeight", 0),
            "durationSec": 0,
            "author": hit.get("user", ""),
        })
    return results[:limit]

def _search_ytdlp(query: str, limit: int):
    # yt‑dlp can perform a YouTube search and return JSON lines.
    cache_bin = _ensure_cache_bin()
    ytdlp = cache_bin / "yt-dlp"
    if not ytdlp.exists():
        _log_step("yt‑dlp not found – running update‑ytdlp first")
        update_ytdlp()
    cmd = [str(ytdlp), f"ytsearch{limit}:{query}", "--dump-json", "--skip-download", "--no-warnings"]
    _log_step(f"yt‑dlp search command: {' '.join(cmd)}")
    proc = subprocess.run(cmd, capture_output=True, text=True)
    results = []
    for line in proc.stdout.splitlines():
        try:
            entry = json.loads(line)
            results.append({
                "id": entry.get("id", ""),
                "provider": "yt-dlp",
                "type": "video",
                "title": entry.get("title", ""),
                "thumbnailUrl": entry.get("thumbnail", ""),
                "downloadUrl": f"https://www.youtube.com/watch?v={entry.get('id')}",
                "width": 0,
                "height": 0,
                "durationSec": entry.get("duration", 0),
                "author": entry.get("uploader", ""),
            })
        except Exception:
            continue
    return results

def search(args):
    parser = argparse.ArgumentParser(prog="media_fetcher.py search")
    parser.add_argument("--query", required=True)
    parser.add_argument("--source", default="auto", choices=["auto", "ytdlp", "wikimedia", "pexels", "pixabay"])
    parser.add_argument("--type", default="photo", choices=["video", "photo", "audio"])
    parser.add_argument("--orientation", default="all", choices=["portrait", "landscape", "all"])
    parser.add_argument("--limit", type=int, default=8)
    opts = parser.parse_args(args)

    # Resolve BYOK keys
    key_path = Path(os.getenv("HOME", "~")) / ".cache" / "media_keys.json"
    pexels_key = pixabay_key = None
    if key_path.exists():
        try:
            keys = json.load(open(key_path))
            pexels_key = keys.get("pexelsKey")
            pixabay_key = keys.get("pixabayKey")
        except Exception:
            pass

    results = []
    sources = []
    if opts.source == "auto":
        sources = ["ytdlp", "wikimedia", "pexels", "pixabay"]
    else:
        sources = [opts.source]

    for src in sources:
        try:
            if src == "wikimedia" and opts.type == "photo":
                results.extend(_search_wikimedia(opts.query, opts.limit))
            elif src == "pexels" and pexels_key and opts.type == "photo":
                results.extend(_search_pexels(opts.query, opts.limit, pexels_key))
            elif src == "pixabay" and pixabay_key and opts.type == "photo":
                results.extend(_search_pixabay(opts.query, opts.limit, pixabay_key))
            elif src == "ytdlp" and opts.type == "video":
                results.extend(_search_ytdlp(opts.query, opts.limit))
        except Exception as e:
            _log_step(f"Error during {src} search: {e}")

    # Trim to limit overall
    results = results[: opts.limit]
    json.dump(results, sys.stdout, ensure_ascii=False, indent=2)
    sys.stdout.flush()

# ---------------------------------------------------------------------------
# Download implementation
# ---------------------------------------------------------------------------

def download(args):
    parser = argparse.ArgumentParser(prog="media_fetcher.py download")
    parser.add_argument("--url", required=True)
    parser.add_argument("--filename")
    parser.add_argument("--project")
    parser.add_argument("--audio-only", action="store_true")
    parser.add_argument("--start")
    parser.add_argument("--end")
    opts = parser.parse_args(args)

    cache_bin = _ensure_cache_bin()
    ytdlp = cache_bin / "yt-dlp"
    if not ytdlp.exists():
        _log_step("yt‑dlp not found – updating first")
        update_ytdlp()

    # Destination directory
    lib_media_dir = Path("library/media")
    lib_media_dir.mkdir(parents=True, exist_ok=True)

    # Determine output template
    out_template = "%(title)s.%(ext)s" if not opts.filename else opts.filename
    cmd = [str(ytdlp), opts.url, "-o", str(lib_media_dir / out_template)]
    if opts.audio_only:
        cmd.extend(["-x", "--audio-format", "mp3"])
    if opts.start and opts.end:
        cmd.append(f"--download-sections=\"*{opts.start}-{opts.end}\"")
    # Ensure ffmpeg location when needed
    ffmpeg_dir = str(cache_bin)
    cmd.append(f"--ffmpeg-location={ffmpeg_dir}")
    _log_step(f"Running yt‑dlp download: {' '.join(cmd)}")
    subprocess.run(cmd, check=True)

    # If a project was supplied, copy to appropriate workspace folder
    if opts.project:
        ws_dir = Path("workspaces") / opts.project
        if ws_dir.is_dir():
            assets_dir = ws_dir / "assets"
            audio_dir = ws_dir / "audio"
            assets_dir.mkdir(parents=True, exist_ok=True)
            audio_dir.mkdir(parents=True, exist_ok=True)
            for f in lib_media_dir.iterdir():
                if opts.audio_only or f.suffix.lower() in [".mp3", ".wav", ".aac"]:
                    shutil.copy2(f, audio_dir / f.name)
                else:
                    shutil.copy2(f, assets_dir / f.name)
            _log_step(f"Copied media into project '{opts.project}'")

    _log_step("Download completed")

# ---------------------------------------------------------------------------
# CLI dispatcher
# ---------------------------------------------------------------------------

def main():
    if len(sys.argv) < 2:
        sys.stderr.write("Usage: media_fetcher.py <command> [options]\n")
        sys.exit(1)
    cmd = sys.argv[1]
    if cmd == "update-ytdlp":
        update_ytdlp()
    elif cmd == "search":
        search(sys.argv[2:])
    elif cmd == "download":
        download(sys.argv[2:])
    else:
        sys.stderr.write(f"Unknown command: {cmd}\n")
        sys.exit(2)

if __name__ == "__main__":
    main()
