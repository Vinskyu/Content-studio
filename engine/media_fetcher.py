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

_script_dir = os.path.dirname(os.path.abspath(__file__))
if sys.path and sys.path[0] == _script_dir:
    sys.path.pop(0)
_root_dir = os.path.dirname(_script_dir)
if _root_dir not in sys.path:
    sys.path.append(_root_dir)

import json
import subprocess
import shutil
import argparse
import urllib.request
import urllib.parse
import time
import re
from pathlib import Path

def _get_ffmpeg_bin() -> str:
    cache_bin = _ensure_cache_bin() / "ffmpeg"
    if cache_bin.exists():
        return str(cache_bin)
    which = shutil.which("ffmpeg")
    if which:
        return which
    for p in ["/data/user/0/com.agy/files/usr/bin/ffmpeg", "/data/data/com.agy/files/usr/bin/ffmpeg", "/system/bin/ffmpeg"]:
        if os.path.exists(p):
            return p
    return "ffmpeg"

def _get_ffprobe_bin() -> str:
    cache_bin = _ensure_cache_bin() / "ffprobe"
    if cache_bin.exists():
        return str(cache_bin)
    which = shutil.which("ffprobe")
    if which:
        return which
    for p in ["/data/user/0/com.agy/files/usr/bin/ffprobe", "/data/data/com.agy/files/usr/bin/ffprobe", "/system/bin/ffprobe"]:
        if os.path.exists(p):
            return p
    return "ffprobe"

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
# Media Auto-QA ("Mata" Agent)
# ---------------------------------------------------------------------------

def run_qa(file_path: str, expected_ratio: str = None) -> dict:
    _log_step("Memeriksa kualitas media (QA)...")
    p = Path(file_path)
    if not p.exists():
        return {
            "ok": False,
            "passed": False,
            "width": 0,
            "height": 0,
            "durationSec": 0,
            "orientation": "unknown",
            "codec": "",
            "hasAudio": False,
            "issues": [f"File tidak ditemukan: {file_path}"],
            "qaFrames": []
        }

    ffprobe = _get_ffprobe_bin()
    ffmpeg = _get_ffmpeg_bin()

    width = 0
    height = 0
    duration_sec = 0.0
    codec = ""
    has_audio = False
    issues = []

    # 1. Probe video stream
    try:
        cmd_v = [
            ffprobe, "-v", "error",
            "-select_streams", "v:0",
            "-show_entries", "stream=width,height,codec_name,duration",
            "-of", "json",
            str(p)
        ]
        res_v = subprocess.run(cmd_v, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=10)
        if res_v.returncode == 0 and res_v.stdout:
            data_v = json.loads(res_v.stdout)
            streams = data_v.get("streams", [])
            if streams:
                st = streams[0]
                width = int(st.get("width", 0))
                height = int(st.get("height", 0))
                codec = st.get("codec_name", "")
                if st.get("duration"):
                    try:
                        duration_sec = float(st.get("duration"))
                    except Exception:
                        pass
    except Exception as e:
        _log_step(f"Warning saat ffprobe video: {e}")

    # 2. Probe format (duration & bitrate)
    try:
        cmd_f = [
            ffprobe, "-v", "error",
            "-show_entries", "format=duration,bit_rate",
            "-of", "json",
            str(p)
        ]
        res_f = subprocess.run(cmd_f, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=10)
        if res_f.returncode == 0 and res_f.stdout:
            data_f = json.loads(res_f.stdout)
            fmt = data_f.get("format", {})
            if duration_sec <= 0 and fmt.get("duration"):
                try:
                    duration_sec = float(fmt.get("duration"))
                except Exception:
                    pass
    except Exception as e:
        _log_step(f"Warning saat ffprobe format: {e}")

    # 3. Probe audio stream
    try:
        cmd_a = [
            ffprobe, "-v", "error",
            "-select_streams", "a:0",
            "-show_entries", "stream=codec_name",
            "-of", "json",
            str(p)
        ]
        res_a = subprocess.run(cmd_a, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=10)
        if res_a.returncode == 0 and res_a.stdout:
            data_a = json.loads(res_a.stdout)
            if data_a.get("streams"):
                has_audio = True
                if not codec:
                    codec = data_a["streams"][0].get("codec_name", "")
    except Exception as e:
        _log_step(f"Warning saat ffprobe audio: {e}")

    # 4. Orientation determination
    if height > width and width > 0:
        orientation = "vertical"
    elif width > height and height > 0:
        orientation = "horizontal"
    elif width == height and width > 0:
        orientation = "square"
    else:
        orientation = "unknown"

    # 5. Quality & spec evaluation
    if width == 0 and height == 0 and duration_sec <= 0:
        issues.append("File rusak atau tidak dapat dibaca oleh ffprobe")
    elif width > 0 and height > 0 and min(width, height) < 480:
        issues.append(f"Resolusi terlalu rendah: {width}x{height} (minimal 480p)")

    is_media_with_time = duration_sec > 0 or p.suffix.lower() in [".mp4", ".mov", ".mkv", ".webm", ".mp3", ".wav", ".aac", ".m4a"]
    if is_media_with_time and 0 < duration_sec < 1.0:
        issues.append(f"Durasi terlalu pendek: {duration_sec:.1f} detik (kurang dari 1 detik)")

    if expected_ratio:
        exp = expected_ratio.strip()
        if exp == "9:16" and orientation == "horizontal":
            issues.append("Orientasi horizontal bertolak belakang dengan expected ratio 9:16")
        elif exp == "16:9" and orientation == "vertical":
            issues.append("Orientasi vertical bertolak belakang dengan expected ratio 16:9")

    # 6. Extract 3 keyframes
    qa_frames = []
    qa_dir = Path(os.getenv("HOME", "~")) / ".cache" / "qa_frames"
    try:
        qa_dir.mkdir(parents=True, exist_ok=True)
        stem = re.sub(r"[^a-zA-Z0-9_-]", "_", p.stem)
        if width > 0 and height > 0:
            if duration_sec >= 1.0:
                points = [0.10 * duration_sec, 0.50 * duration_sec, 0.85 * duration_sec]
                for idx, t_sec in enumerate(points, start=1):
                    frame_dest = qa_dir / f"{stem}_kf{idx}.png"
                    cmd_kf = [
                        ffmpeg, "-y",
                        "-ss", f"{t_sec:.2f}",
                        "-i", str(p),
                        "-vframes", "1",
                        "-vf", "scale='min(480,iw)':-1",
                        str(frame_dest)
                    ]
                    subprocess.run(cmd_kf, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)
                    if frame_dest.exists() and frame_dest.stat().st_size > 0:
                        qa_frames.append(str(frame_dest))
            else:
                frame_dest = qa_dir / f"{stem}_kf1.png"
                cmd_kf = [
                    ffmpeg, "-y",
                    "-i", str(p),
                    "-vframes", "1",
                    "-vf", "scale='min(480,iw)':-1",
                    str(frame_dest)
                ]
                subprocess.run(cmd_kf, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)
                if frame_dest.exists() and frame_dest.stat().st_size > 0:
                    qa_frames.append(str(frame_dest))
    except Exception as e:
        _log_step(f"Warning saat ekstraksi QA keyframe: {e}")

    passed = (len(issues) == 0)
    result = {
        "ok": True,
        "passed": passed,
        "width": width,
        "height": height,
        "durationSec": round(duration_sec, 2),
        "orientation": orientation,
        "codec": codec,
        "hasAudio": has_audio,
        "issues": issues,
        "qaFrames": qa_frames
    }
    return result

def qa(args):
    parser = argparse.ArgumentParser(prog="media_fetcher.py qa")
    parser.add_argument("--file", required=True)
    parser.add_argument("--expected-ratio", default=None, choices=["9:16", "16:9"])
    opts = parser.parse_args(args)
    res = run_qa(opts.file, opts.expected_ratio)
    json.dump(res, sys.stdout, indent=2, ensure_ascii=False)
    sys.stdout.write("\n")
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
    before_files = set(lib_media_dir.iterdir()) if lib_media_dir.exists() else set()

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

    after_files = set(lib_media_dir.iterdir()) if lib_media_dir.exists() else set()
    new_files = list(after_files - before_files)
    downloaded_file = new_files[0] if new_files else None
    if not downloaded_file and lib_media_dir.exists():
        files_by_mtime = sorted(lib_media_dir.iterdir(), key=lambda f: f.stat().st_mtime, reverse=True)
        if files_by_mtime:
            downloaded_file = files_by_mtime[0]

    # If a project was supplied, copy to appropriate workspace folder
    project_saved_path = None
    if opts.project and downloaded_file:
        ws_dir = Path("workspaces") / opts.project
        if ws_dir.is_dir():
            assets_dir = ws_dir / "assets"
            audio_dir = ws_dir / "audio"
            assets_dir.mkdir(parents=True, exist_ok=True)
            audio_dir.mkdir(parents=True, exist_ok=True)
            dest = audio_dir / downloaded_file.name if (opts.audio_only or downloaded_file.suffix.lower() in [".mp3", ".wav", ".aac"]) else assets_dir / downloaded_file.name
            shutil.copy2(downloaded_file, dest)
            project_saved_path = str(dest)
            _log_step(f"Copied media into project '{opts.project}' -> {dest.name}")

    _log_step("Download completed")

    # Run Auto-QA
    qa_res = None
    if downloaded_file:
        target_check = project_saved_path if project_saved_path else str(downloaded_file)
        qa_res = run_qa(target_check)

    res_out = {
        "status": "ok",
        "file": str(downloaded_file) if downloaded_file else None,
        "projectFile": project_saved_path,
        "project": opts.project,
        "qa": qa_res
    }
    json.dump(res_out, sys.stdout, indent=2, ensure_ascii=False)
    sys.stdout.write("\n")
    sys.stdout.flush()

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
    elif cmd == "qa":
        qa(sys.argv[2:])
    else:
        sys.stderr.write(f"Unknown command: {cmd}\n")
        sys.exit(2)

if __name__ == "__main__":
    main()
