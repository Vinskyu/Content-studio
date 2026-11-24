#!/usr/bin/env python3
"""
ContentStudio Media & Stock Fetcher (engine/fetch.py)
Sprint C: Channel playlist metadata extraction (zero-copy), reference media download,
          and stock footage fetching (Pexels / Pixabay) with anti-limit & storage checks.
Usage:
  python3 engine/fetch.py channel <url> --limit 8 --out <list.json> [--sort popular|latest] [--max-dur 180]
  python3 engine/fetch.py media <url> --out <dir> [--audio-only] [--max-height 480] [--name <prefix>]
  python3 engine/fetch.py stock "<query>" --out <dir> [--orientation portrait|landscape] [--limit 3] [--max-height 1080] [--provider auto|pexels|pixabay]
"""

import sys
import os

# Mencegah direktori engine/ membayangi (shadowing) modul library standar Python seperti 'inspect'
_script_dir = os.path.dirname(os.path.abspath(__file__))
if sys.path and sys.path[0] == _script_dir:
    sys.path.pop(0)
_root_dir = os.path.dirname(_script_dir)
if _root_dir not in sys.path:
    sys.path.append(_root_dir)

import re
import json
import shutil
import argparse
import subprocess
import urllib.request
import urllib.parse
import urllib.error
from datetime import datetime
from typing import Dict, Any, List, Optional, Tuple, Callable

from engine.runtime import (
    get_root_dir,
    get_ffmpeg_bin,
    resolve_path,
    load_runtime_env
)

try:
    import yt_dlp
    HAS_YT_DLP = True
except ImportError:
    HAS_YT_DLP = False

def resolve_url(url: str, timeout: float = 8.0) -> str:
    """
    Mengurai (resolve) URL redirect (seperti fb.watch, facebook.com/share/..., vt.tiktok.com, vm.tiktok.com, bit.ly, youtu.be)
    menjadi URL target final sebelum diproses oleh yt-dlp.
    """
    if not url or not isinstance(url, str):
        return url
    url = url.strip()
    if not url.startswith(("http://", "https://")):
        return url

    redirect_domains = ("fb.watch", "facebook.com/share", "vt.tiktok.com", "vm.tiktok.com", "bit.ly", "t.co", "youtu.be")
    if not any(d in url for d in redirect_domains):
        return url

    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    try:
        req = urllib.request.Request(url, headers=headers, method="HEAD")
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            final_url = resp.geturl()
            if final_url and final_url != url:
                print(f"[*] Redirect resolved: {url} -> {final_url}")
                return final_url
    except Exception:
        try:
            req = urllib.request.Request(url, headers={**headers, "Range": "bytes=0-1024"})
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                final_url = resp.geturl()
                if final_url and final_url != url:
                    print(f"[*] Redirect resolved (GET): {url} -> {final_url}")
                    return final_url
        except Exception:
            pass

    return url

def load_env() -> Dict[str, str]:
    """Baca environment variables dengan fallback ke file .env di ContentStudio."""
    return load_runtime_env()

def get_env_key(key_name: str) -> str:
    """Ambil key pertama jika multi-key dipisah koma, atau string kosong jika tidak ada."""
    env = load_env()
    val = env.get(key_name, "").strip()
    if not val:
        return ""
    parts = [p.strip() for p in val.split(",") if p.strip()]
    return parts[0] if parts else ""

def check_storage(target_dir: str, required_gb: float = 1.0) -> bool:
    """Pastikan sisa storage perangkat >= required_gb sebelum download."""
    try:
        os.makedirs(target_dir, exist_ok=True)
        usage = shutil.disk_usage(target_dir)
        free_gb = usage.free / (1024 ** 3)
        if free_gb < required_gb:
            print(f"[!] Sisa storage di {target_dir} hanya {free_gb:.2f} GB (wajib >= {required_gb:.1f} GB).", file=sys.stderr)
            return False
        return True
    except Exception as e:
        print(f"[!] Gagal memeriksa storage: {e}", file=sys.stderr)
        return False

def format_duration(seconds: Optional[float]) -> str:
    """Format detik menjadi HH:MM:SS atau MM:SS."""
    if seconds is None or seconds < 0:
        return "--:--"
    hrs = int(seconds // 3600)
    mins = int((seconds % 3600) // 60)
    secs = int(seconds % 60)
    if hrs > 0:
        return f"{hrs:02d}:{mins:02d}:{secs:02d}"
    return f"{mins:02d}:{secs:02d}"

def format_views(views: Optional[int]) -> str:
    """Format angka view menjadi format singkat (misal 1.2M, 45.3K)."""
    if views is None:
        return "N/A"
    if views >= 1_000_000:
        return f"{views / 1_000_000:.1f}M"
    if views >= 1_000:
        return f"{views / 1_000:.1f}K"
    return str(views)

# ==============================================================================
# 1. CHANNEL SUBCOMMAND (METADATA FLAT-PLAYLIST ZERO-STORAGE)
# ==============================================================================
def cmd_channel(args):
    url = args.url
    out_file = resolve_path(args.out)
    limit = int(args.limit or 8)
    sort_mode = (args.sort or "latest").lower()
    max_dur = float(args.max_dur or 0.0)

    print(f"[*] Mengekstrak metadata channel/playlist: {url}")
    print(f"  - Limit: {limit} item | Sort: {sort_mode} | Max Dur: {max_dur or 'unlimited'}s")

    fetch_count = max(limit * 3, 20)
    raw_data = None

    if HAS_YT_DLP:
        ydl_opts = {
            "extract_flat": True,
            "playlist_items": f"1-{fetch_count}",
            "quiet": True,
            "no_warnings": True,
        }
        try:
            with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                raw_data = ydl.extract_info(url, download=False)
        except Exception:
            raw_data = None

    if raw_data is None:
        cmd = [
            "yt-dlp",
            "--flat-playlist",
            "--dump-single-json",
            "--playlist-end", str(fetch_count),
            "--no-warnings",
            url
        ]
        try:
            res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=True)
            raw_data = json.loads(res.stdout)
        except subprocess.CalledProcessError as e:
            print(f"[!] yt-dlp gagal mengambil metadata: {e.stderr}", file=sys.stderr)
            return 1
        except Exception as e:
            print(f"[!] Gagal mengurai JSON metadata: {e}", file=sys.stderr)
            return 1

    entries = raw_data.get("entries", [])
    if not entries and raw_data.get("id"):
        # Single video URL
        entries = [raw_data]

    parsed_items = []
    for entry in entries:
        if not entry:
            continue
        v_id = entry.get("id") or ""
        v_title = entry.get("title") or "Untitled"
        v_dur = entry.get("duration")
        v_views = entry.get("view_count")
        v_url = entry.get("url") or entry.get("webpage_url") or f"https://www.youtube.com/watch?v={v_id}"
        v_ts = entry.get("timestamp") or 0

        # Filter durasi (berguna untuk filter Shorts/Reels)
        if max_dur > 0 and v_dur is not None and v_dur > max_dur:
            continue

        parsed_items.append({
            "id": v_id,
            "title": v_title,
            "duration": v_dur,
            "duration_str": format_duration(v_dur),
            "view_count": v_views,
            "view_count_str": format_views(v_views),
            "url": v_url,
            "timestamp": v_ts
        })

    # Sorting
    if sort_mode == "popular":
        parsed_items.sort(key=lambda x: (x["view_count"] or 0), reverse=True)
    else:
        # Default latest: pertahankan urutan atau sort timestamp jika ada
        if any(x["timestamp"] for x in parsed_items):
            parsed_items.sort(key=lambda x: (x["timestamp"] or 0), reverse=True)

    result_items = parsed_items[:limit]

    # Simpan hasil ke JSON
    os.makedirs(os.path.dirname(out_file), exist_ok=True)
    with open(out_file, "w", encoding="utf-8") as f:
        json.dump(result_items, f, indent=2, ensure_ascii=False)

    print(f"\n[*] Metadata tersimpan di: {out_file}")
    print(f"\n{'#':<4} {'DURASI':<9} {'VIEWS':<9} {'JUDUL':<45} {'URL'}")
    print("-" * 95)
    for idx, item in enumerate(result_items, 1):
        t_trunc = item["title"][:42] + "..." if len(item["title"]) > 45 else item["title"]
        print(f"#{idx:<3} {item['duration_str']:<9} {item['view_count_str']:<9} {t_trunc:<45} {item['url']}")
    print("-" * 95)
    print(f"Total: {len(result_items)} video terdaftar (0 byte video diunduh).\n")
    return 0

def parse_time_spec(t_str: str) -> float:
    """Parse string waktu ke detik (mendukung '70', '01:10', '00:01:10')."""
    parts = t_str.strip().split(":")
    if len(parts) == 1:
        return float(parts[0])
    elif len(parts) == 2:
        return float(parts[0]) * 60 + float(parts[1])
    elif len(parts) == 3:
        return float(parts[0]) * 3600 + float(parts[1]) * 60 + float(parts[2])
    else:
        raise ValueError(f"Format timestamp tidak dikenali: '{t_str}'")

def parse_section_arg(section_str: str) -> Tuple[str, str, float, float]:
    """Parse format section misal '00:01:10-00:01:20' atau '70-80'.
    Mengembalikan (start_raw, end_raw, start_sec, end_sec).
    """
    if not section_str or "-" not in section_str:
        raise ValueError("Format section harus '<start>-<end>' (contoh: '00:01:10-00:01:20' atau '70-80')")
    parts = section_str.strip().split("-", 1)
    s_raw, e_raw = parts[0].strip(), parts[1].strip()
    s_sec = parse_time_spec(s_raw)
    e_sec = parse_time_spec(e_raw)
    if s_sec < 0 or e_sec <= 0:
        raise ValueError("Timestamp start dan end harus bernilai positif")
    if s_sec >= e_sec:
        raise ValueError(f"Waktu mulai ({s_sec}s) harus lebih kecil dari waktu selesai ({e_sec}s)")
    return s_raw, e_raw, s_sec, e_sec

# ==============================================================================
# 2. MEDIA SUBCOMMAND (DOWNLOAD REFERENSI / VIRAL AUDIO)
# ==============================================================================
def download_media(
    url: str,
    out_dir: str,
    audio_only: bool = False,
    max_height: int = 480,
    name_prefix: Optional[str] = None,
    section: str = "",
    on_progress: Optional[Callable[[Dict[str, Any]], None]] = None
) -> Dict[str, Any]:
    """
    Mengunduh media video/audio in-process atau via fallback yt-dlp binary.
    Mendukung pemotongan rentang waktu (--section) dan batas resolusi.
    """
    url = resolve_url(url)
    out_dir = resolve_path(out_dir)
    os.makedirs(out_dir, exist_ok=True)

    if not check_storage(out_dir, required_gb=1.0):
        raise RuntimeError(f"Storage di {out_dir} tidak cukup (butuh >= 1.0 GB)")

    out_tmpl = f"{name_prefix}.%(ext)s" if name_prefix else "%(title)s.%(ext)s"
    out_pattern = os.path.join(out_dir, out_tmpl)

    s_raw, e_raw, s_sec, e_sec = (None, None, None, None)
    if section:
        s_raw, e_raw, s_sec, e_sec = parse_section_arg(section)
        print(f"  - Memotong section spesifik: {s_raw} s/d {e_raw} ({e_sec - s_sec:.1f} detik)")

    download_success = False
    err_message = ""

    if HAS_YT_DLP:
        print("[*] Menggunakan pustaka in-process yt_dlp...")
        ydl_opts: Dict[str, Any] = {
            "outtmpl": out_pattern,
            "no_warnings": True,
            "ffmpeg_location": get_ffmpeg_bin(),
        }

        if section and s_sec is not None and e_sec is not None:
            try:
                from yt_dlp.utils import download_range_func
                ydl_opts["download_ranges"] = download_range_func(None, [(s_sec, e_sec)])
                ydl_opts["force_keyframes_at_cuts"] = True
            except Exception as e:
                print(f"[!] Warning: Tidak dapat mengatur download_ranges: {e}")

        if audio_only:
            print(f"[*] Mengunduh audio murni dari: {url}")
            ydl_opts["format"] = "bestaudio/best"
            ydl_opts["postprocessors"] = [{
                "key": "FFmpegExtractAudio",
                "preferredcodec": "mp3",
                "preferredquality": "192",
            }]
        else:
            print(f"[*] Mengunduh video referensi hemat storage (max-height: {max_height}p) dari: {url}")
            ydl_opts["format"] = f"bestvideo[height<={max_height}]+bestaudio/best[height<={max_height}]/best"
            ydl_opts["merge_output_format"] = "mp4"

        if on_progress:
            def _progress_hook(d):
                if d.get("status") == "downloading":
                    total = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
                    downloaded = d.get("downloaded_bytes") or 0
                    pct = int((downloaded / total) * 100) if total > 0 else 50
                    on_progress({"stage": "downloading", "pct": pct, "msg": f"Downloading: {pct}%"})
                elif d.get("status") == "finished":
                    on_progress({"stage": "processing", "pct": 95, "msg": "Pemrosesan media..."})
            ydl_opts["progress_hooks"] = [_progress_hook]

        try:
            with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                ydl.download([url])
            download_success = True
        except Exception as e:
            err_message = str(e)
            print(f"[!] in-process yt_dlp gagal ({e}), mencoba fallback CLI...")

    if not download_success:
        print("[*] Menggunakan binary CLI yt-dlp fallback...")
        cmd = ["yt-dlp", "--no-warnings", "-o", out_pattern, "--ffmpeg-location", get_ffmpeg_bin()]

        if section and s_raw and e_raw:
            cmd.extend([
                "--download-sections", f"*{s_raw}-{e_raw}",
                "--force-keyframes-at-cuts"
            ])

        if audio_only:
            print(f"[*] Mengunduh audio murni dari: {url}")
            cmd.extend([
                "-x",
                "--audio-format", "mp3",
                "--audio-quality", "192K"
            ])
        else:
            print(f"[*] Mengunduh video referensi hemat storage (max-height: {max_height}p) dari: {url}")
            format_spec = f"bestvideo[height<={max_height}]+bestaudio/best[height<={max_height}]/best"
            cmd.extend([
                "-f", format_spec,
                "--merge-output-format", "mp4"
            ])

        cmd.append(url)

        try:
            res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=True)
            download_success = True
        except subprocess.CalledProcessError as e:
            raise RuntimeError(f"Gagal mengunduh media via CLI: {e.stderr or err_message}")

    # Cari file yang baru diunduh di out_dir
    files_in_dir = [
        os.path.join(out_dir, f) for f in os.listdir(out_dir)
        if os.path.isfile(os.path.join(out_dir, f)) and not f.startswith(".")
    ]
    if not files_in_dir:
        raise FileNotFoundError("File hasil unduhan tidak ditemukan di out_dir")

    files_in_dir.sort(key=lambda x: os.path.getmtime(x), reverse=True)
    downloaded_file = files_in_dir[0]
    sz_bytes = os.path.getsize(downloaded_file)

    if on_progress:
        on_progress({"stage": "completed", "pct": 100, "msg": f"Unduhan sukses: {downloaded_file}"})

    return {
        "status": "ok",
        "file_path": downloaded_file,
        "size_bytes": sz_bytes
    }

def cmd_media(args):
    """Subcommand media CLI handler."""
    try:
        res = download_media(
            url=args.url,
            out_dir=args.out,
            audio_only=bool(args.audio_only),
            max_height=int(args.max_height or 480),
            name_prefix=args.name,
            section=getattr(args, "section", "") or ""
        )
        downloaded_file = res["file_path"]
        sz_mb = res["size_bytes"] / (1024 * 1024)
        print(f"\n  ✓ Unduhan sukses: {downloaded_file} ({sz_mb:.2f} MB)")
        print(f"FILE_PATH={downloaded_file}")
        return 0
    except Exception as e:
        print(f"[!] Gagal mengunduh media: {e}", file=sys.stderr)
        return 1

# ==============================================================================
# 3. STOCK SUBCOMMAND (PEXELS / PIXABAY STOK VISUAL)
# ==============================================================================
def fetch_pexels_stock(query: str, orientation: str, limit: int, max_height: int, api_key: str) -> List[Dict[str, Any]]:
    """Pencarian video via Pexels API."""
    orient_param = "portrait" if orientation == "portrait" else "landscape"
    params = urllib.parse.urlencode({
        "query": query,
        "orientation": orient_param,
        "per_page": min(limit * 3, 20)
    })
    url = f"https://api.pexels.com/videos/search?{params}"
    headers = {
        "Authorization": api_key,
        "User-Agent": "ContentStudio/2.0"
    }
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=15) as resp:
        data = json.loads(resp.read().decode("utf-8"))

    results = []
    for item in data.get("videos", []):
        v_dur = float(item.get("duration", 0.0))
        user_name = item.get("user", {}).get("name", "Pexels Creator")
        page_url = item.get("url", "")

        # Pilih video_file yang paling sesuai dengan max_height dan orientation
        best_file = None
        for vf in item.get("video_files", []):
            w = int(vf.get("width", 0))
            h = int(vf.get("height", 0))
            link = vf.get("link", "")
            if not link:
                continue

            # Periksa orientasi
            if orientation == "portrait" and h < w:
                continue
            if orientation == "landscape" and w < h:
                continue

            if h <= (max_height + 100):
                if best_file is None or h > best_file["height"]:
                    best_file = {"url": link, "width": w, "height": h}

        if best_file:
            results.append({
                "id": str(item.get("id")),
                "provider": "pexels",
                "download_url": best_file["url"],
                "source_url": page_url,
                "credit": f"Pexels / {user_name}",
                "duration": v_dur,
                "width": best_file["width"],
                "height": best_file["height"]
            })
            if len(results) >= limit:
                break
    return results

def fetch_pixabay_stock(query: str, orientation: str, limit: int, max_height: int, api_key: str) -> List[Dict[str, Any]]:
    """Pencarian video via Pixabay API."""
    params = urllib.parse.urlencode({
        "key": api_key,
        "q": query,
        "per_page": min(limit * 3, 20),
        "video_type": "film"
    })
    url = f"https://pixabay.com/api/videos/?{params}"
    req = urllib.request.Request(url, headers={"User-Agent": "ContentStudio/2.0"})
    with urllib.request.urlopen(req, timeout=15) as resp:
        data = json.loads(resp.read().decode("utf-8"))

    results = []
    for item in data.get("hits", []):
        v_dur = float(item.get("duration", 0.0))
        user_name = item.get("user", "Pixabay Creator")
        page_url = item.get("pageURL", "")

        videos_obj = item.get("videos", {})
        # Cari resolusi (medium, large, small)
        best_file = None
        for sz_key in ["medium", "large", "small", "tiny"]:
            vf = videos_obj.get(sz_key)
            if not vf:
                continue
            w = int(vf.get("width", 0))
            h = int(vf.get("height", 0))
            link = vf.get("url", "")
            if not link:
                continue

            if orientation == "portrait" and h < w:
                continue
            if orientation == "landscape" and w < h:
                continue

            if h <= (max_height + 100):
                if best_file is None or h > best_file["height"]:
                    best_file = {"url": link, "width": w, "height": h}

        if best_file:
            results.append({
                "id": str(item.get("id")),
                "provider": "pixabay",
                "download_url": best_file["url"],
                "source_url": page_url,
                "credit": f"Pixabay / {user_name}",
                "duration": v_dur,
                "width": best_file["width"],
                "height": best_file["height"]
            })
            if len(results) >= limit:
                break
    return results

def cmd_stock(args):
    query = args.query
    out_dir = resolve_path(args.out)
    orientation = (args.orientation or "portrait").lower()
    limit = int(args.limit or 3)
    max_height = int(args.max_height or 1080)
    provider = (args.provider or "auto").lower()

    if not check_storage(out_dir, required_gb=1.0):
        return 1

    pexels_key = get_env_key("PEXELS_API_KEY")
    pixabay_key = get_env_key("PIXABAY_API_KEY")

    if not pexels_key and not pixabay_key:
        print("[!] Kunci API stok visual belum disetel.", file=sys.stderr)
        print("    Silakan isi PEXELS_API_KEY atau PIXABAY_API_KEY di /data/user/0/com.agy/files/ContentStudio/.env", file=sys.stderr)
        return 1

    chosen_provider = provider
    if chosen_provider == "auto":
        chosen_provider = "pexels" if pexels_key else "pixabay"

    if chosen_provider == "pexels" and not pexels_key:
        print("[!] PEXELS_API_KEY belum disetel di .env.", file=sys.stderr)
        return 1
    if chosen_provider == "pixabay" and not pixabay_key:
        print("[!] PIXABAY_API_KEY belum disetel di .env.", file=sys.stderr)
        return 1

    print(f"[*] Mencari video stok ({chosen_provider}) untuk: '{query}' ({orientation}, max {max_height}p)...")
    try:
        if chosen_provider == "pexels":
            items = fetch_pexels_stock(query, orientation, limit, max_height, pexels_key)
        else:
            items = fetch_pixabay_stock(query, orientation, limit, max_height, pixabay_key)
    except Exception as e:
        print(f"[!] Gagal mencari video stok: {e}", file=sys.stderr)
        return 1

    if not items:
        print(f"[!] Tidak ada video stok yang cocok untuk kueri '{query}' dengan orientasi {orientation}.", file=sys.stderr)
        return 0

    manifest_videos = []
    for idx, item in enumerate(items, 1):
        target_name = f"stock_{idx:02d}.mp4"
        target_path = os.path.join(out_dir, target_name)
        print(f"  -> Mengunduh [{idx}/{len(items)}]: {item['credit']} ({item['width']}x{item['height']}, {item['duration']:.1f}s)...")
        try:
            req = urllib.request.Request(item["download_url"], headers={"User-Agent": "ContentStudio/2.0"})
            with urllib.request.urlopen(req, timeout=30) as resp, open(target_path, "wb") as f:
                f.write(resp.read())
            sz_mb = os.path.getsize(target_path) / (1024 * 1024)
            print(f"     ✓ Tersimpan: {target_name} ({sz_mb:.2f} MB)")
            manifest_videos.append({
                "file": target_name,
                "path": target_path,
                "source_url": item["source_url"],
                "credit": item["credit"],
                "duration": item["duration"],
                "width": item["width"],
                "height": item["height"]
            })
        except Exception as e:
            print(f"[!] Gagal mengunduh {target_name}: {e}", file=sys.stderr)

    manifest_file = os.path.join(out_dir, "stock_manifest.json")
    manifest_data = {
        "query": query,
        "provider": chosen_provider,
        "orientation": orientation,
        "count": len(manifest_videos),
        "videos": manifest_videos
    }
    with open(manifest_file, "w", encoding="utf-8") as f:
        json.dump(manifest_data, f, indent=2, ensure_ascii=False)

    print(f"\n[*] Manifest stok berhasil disimpan di: {manifest_file}")
    return 0

# ==============================================================================
# MAIN ROUTER
# ==============================================================================
def main():
    parser = argparse.ArgumentParser(description="ContentStudio Media & Stock Fetcher (engine/fetch.py)")
    subparsers = parser.add_subparsers(dest="subcommand", required=True)

    # channel
    p_chan = subparsers.add_parser("channel", help="Ekstraksi metadata channel/playlist tanpa download")
    p_chan.add_argument("url", help="URL channel, playlist, atau kueri pencarian YouTube")
    p_chan.add_argument("--limit", type=int, default=8, help="Maksimal video yang didaftarkan (default 8)")
    p_chan.add_argument("--out", required=True, help="Path output file list.json")
    p_chan.add_argument("--sort", choices=["latest", "popular"], default="latest", help="Pengurutan (default latest)")
    p_chan.add_argument("--max-dur", type=float, default=0.0, help="Filter durasi maksimal video dalam detik")
    p_chan.set_defaults(func=cmd_channel)

    # media
    p_med = subparsers.add_parser("media", help="Download 1 video referensi atau audio viral")
    p_med.add_argument("url", help="URL video/audio")
    p_med.add_argument("--out", required=True, help="Direktori penyimpanan hasil unduhan")
    p_med.add_argument("--audio-only", action="store_true", help="Hanya unduh audio (mp3)")
    p_med.add_argument("--max-height", type=int, default=480, help="Maksimal tinggi video pixel (default 480p)")
    p_med.add_argument("--name", default="", help="Nama prefix file hasil unduhan")
    p_med.add_argument("--section", default="", help="Potong rentang detik tertentu saja (contoh: '00:01:10-00:01:20' atau '70-80')")
    p_med.set_defaults(func=cmd_media)

    # stock
    p_stk = subparsers.add_parser("stock", help="Download video stok bebas royalti (Pexels / Pixabay)")
    p_stk.add_argument("query", help="Kueri pencarian stok (misal: 'forest rain', 'cinematic coffee')")
    p_stk.add_argument("--out", required=True, help="Direktori penyimpanan video stok")
    p_stk.add_argument("--orientation", choices=["portrait", "landscape"], default="portrait", help="Orientasi (default portrait)")
    p_stk.add_argument("--limit", type=int, default=3, help="Jumlah video yang diunduh (default 3)")
    p_stk.add_argument("--max-height", type=int, default=1080, help="Maksimal tinggi video pixel (default 1080p)")
    p_stk.add_argument("--provider", choices=["auto", "pexels", "pixabay"], default="auto", help="Pilihan penyedia (default auto)")
    p_stk.set_defaults(func=cmd_stock)

    args = parser.parse_args()
    return args.func(args)

if __name__ == "__main__":
    sys.exit(main() or 0)
