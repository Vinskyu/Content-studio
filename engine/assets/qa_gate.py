#!/usr/bin/env python3
"""
ContentStudio Asset QA Gate (engine/assets/qa_gate.py)

Gerbang verifikasi tunggal untuk SEMUA output worker. Setiap aset wajib lolos
sebelum dianggap valid oleh router.

Pemeriksaan:
  1. File ada dan ukuran layak (gambar/video > 15 KB).
  2. Magic bytes asli sesuai ekstensi (menolak HTML/JSON error yang disamarkan).
  3. ffprobe: stream valid, durasi > 0 untuk video/audio, resolusi layak.
  4. Anti-frame kosong: sampel frame (detik ke-1 / tengah) tidak boleh hitam
     pekat atau satu warna solid polos.
  5. Metadata ringan untuk clip/stock bila metadata disediakan.

Semua pemeriksaan degradable: bila ffprobe/ffmpeg/Pillow tidak tersedia,
pemeriksaan terkait dilewati dan alasannya dilaporkan (bukan crash).
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
import tempfile
from typing import Any, Dict, Optional, Tuple

from engine.runtime import get_ffmpeg_bin, get_ffprobe_bin

MIN_IMAGE_BYTES = 15 * 1024
MIN_VIDEO_BYTES = 15 * 1024
MIN_AUDIO_BYTES = 8 * 1024

IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif"}
VIDEO_EXTS = {".mp4", ".mov", ".m4v", ".webm", ".mkv", ".avi"}
AUDIO_EXTS = {".mp3", ".wav", ".m4a", ".aac", ".ogg", ".flac"}

# Batas deteksi frame kosong.
SOLID_STD_THRESHOLD = 2.0
DARK_MEAN_THRESHOLD = 6.0


def _expected_kind(expected_type: str) -> Tuple[str, ...]:
    token = str(expected_type or "").strip().lower()
    if token in ("image", "photo", "thumbnail", "still", "graphic", "picture"):
        return ("image",)
    if token in ("video", "clip", "footage"):
        return ("video",)
    if token == "stock":
        return ("image", "video")
    if token in ("audio", "voice", "bgm", "sound"):
        return ("audio",)
    return ("image", "video", "audio")


def sniff_kind(file_path: str) -> str:
    """Deteksi jenis media dari magic bytes, bukan dari ekstensi."""
    try:
        with open(file_path, "rb") as f:
            head = f.read(64)
    except Exception:
        return "unknown"
    if len(head) < 12:
        return "unknown"
    if head.startswith(b"\xff\xd8\xff"):
        return "image"
    if head.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image"
    if head.startswith(b"GIF8"):
        return "image"
    if head.startswith(b"BM"):
        return "image"
    if head.startswith(b"RIFF") and head[8:12] == b"WEBP":
        return "image"
    if head[4:8] == b"ftyp":
        brand = head[8:12]
        if brand in (b"M4A ", b"M4B "):
            return "audio"
        return "video"
    if head.startswith(b"\x1a\x45\xdf\xa3"):
        return "video"
    if head.startswith(b"OggS"):
        return "audio"
    if head.startswith(b"fLaC"):
        return "audio"
    if head.startswith(b"ID3"):
        return "audio"
    if head[0] == 0xFF and (head[1] & 0xE0) == 0xE0:
        return "audio"
    if head.startswith(b"RIFF") and head[8:12] == b"WAVE":
        return "audio"
    if head.startswith(b"RIFF") and head[8:12] == b"AVI ":
        return "video"
    return "unknown"


def probe_media(file_path: str) -> Optional[Dict[str, Any]]:
    """Probe ffprobe; None bila binary tidak tersedia atau gagal."""
    ffprobe = get_ffprobe_bin()
    if not ffprobe or (not os.path.isabs(ffprobe) and not shutil.which(ffprobe)):
        return None
    try:
        proc = subprocess.run(
            [ffprobe, "-v", "quiet", "-print_format", "json", "-show_format", "-show_streams", file_path],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=30,
        )
    except Exception:
        return None
    if proc.returncode != 0:
        return None
    try:
        data = json.loads(proc.stdout or "{}")
    except Exception:
        return None
    streams = data.get("streams") or []
    fmt = data.get("format") or {}
    video = next((s for s in streams if s.get("codec_type") == "video"), None)
    audio = next((s for s in streams if s.get("codec_type") == "audio"), None)
    try:
        duration = float(fmt.get("duration") or (video or {}).get("duration") or 0.0)
    except Exception:
        duration = 0.0
    return {
        "streams": streams,
        "video": video,
        "audio": audio,
        "width": int((video or {}).get("width") or 0),
        "height": int((video or {}).get("height") or 0),
        "duration": duration,
        "format": fmt,
    }


def _stats_from_image(file_path: str) -> Optional[Tuple[float, float]]:
    """Rata-rata & standar deviasi kecerahan via Pillow bila tersedia."""
    try:
        from PIL import Image  # type: ignore
    except Exception:
        return None
    try:
        with Image.open(file_path) as img:
            gray = img.convert("L").resize((48, 48))
            pixels = list(gray.getdata())
    except Exception:
        return None
    if not pixels:
        return None
    mean = sum(pixels) / float(len(pixels))
    variance = sum((p - mean) ** 2 for p in pixels) / float(len(pixels))
    return mean, variance ** 0.5


def _stats_from_video_frame(file_path: str, seek_sec: float = 1.0) -> Optional[Tuple[float, float]]:
    """Ekstrak 1 frame via ffmpeg lalu hitung kecerahan; None bila tidak bisa."""
    ffmpeg = get_ffmpeg_bin()
    if not ffmpeg or (not os.path.isabs(ffmpeg) and not shutil.which(ffmpeg)):
        return None
    tmp_png = os.path.join(tempfile.gettempdir(), "qa_frame_%d.png" % os.getpid())
    for seek in (seek_sec, 0.0):
        if seek > 0:
            cmd = [ffmpeg, "-y", "-ss", "%.2f" % seek, "-i", file_path, "-frames:v", "1", tmp_png]
        else:
            cmd = [ffmpeg, "-y", "-i", file_path, "-frames:v", "1", tmp_png]
        try:
            subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)
        except Exception:
            continue
        if os.path.exists(tmp_png) and os.path.getsize(tmp_png) > 0:
            stats = _stats_from_image(tmp_png)
            try:
                os.remove(tmp_png)
            except Exception:
                pass
            if stats:
                return stats
    return None


def frame_stats(file_path: str, kind: str) -> Tuple[Optional[Tuple[float, float]], str]:
    """Statistik kecerahan frame sampel + catatan metode."""
    if kind == "image":
        stats = _stats_from_image(file_path)
        if stats:
            return stats, "pillow"
        stats = _stats_from_video_frame(file_path)
        if stats:
            return stats, "ffmpeg"
        return None, "unavailable"
    if kind == "video":
        stats = _stats_from_video_frame(file_path)
        if stats:
            return stats, "ffmpeg"
        return None, "unavailable"
    return None, "not_applicable"


def verify_asset(
    file_path: str,
    expected_type: str = "auto",
    visual_goal: str = "",
    metadata: Optional[Dict[str, Any]] = None,
) -> Tuple[bool, str]:
    """
    Verifikasi satu aset. Mengembalikan (ok, reason).

    `expected_type`: image|video|audio|stock|auto (grup toleran).
    `metadata`: opsional; hanya key yang ada yang diperiksa.
    """
    if not file_path or not os.path.exists(file_path):
        return False, "File tidak ditemukan"
    if os.path.isdir(file_path):
        return False, "Path adalah direktori, bukan file"

    size = os.path.getsize(file_path)
    if size <= 0:
        return False, "File kosong (0 byte)"

    kind = sniff_kind(file_path)
    if kind == "unknown":
        ext = os.path.splitext(file_path)[1].lower()
        return False, "Magic bytes tidak dikenali, kemungkinan HTML/JSON error (ekstensi %s)" % (ext or "-")

    allowed = _expected_kind(expected_type)
    if kind not in allowed:
        return False, "Jenis file %s tidak sesuai target %s" % (kind, "/".join(allowed))

    min_bytes = MIN_IMAGE_BYTES if kind == "image" else (MIN_VIDEO_BYTES if kind == "video" else MIN_AUDIO_BYTES)
    if size < min_bytes:
        return False, "Ukuran file terlalu kecil (%d byte < %d byte)" % (size, min_bytes)

    probe = probe_media(file_path) if kind in ("video", "audio") else None
    if kind in ("video", "audio"):
        if probe is None:
            if kind == "video":
                return False, "ffprobe tidak tersedia, durasi video tidak bisa diverifikasi"
        else:
            if not probe.get("streams"):
                return False, "ffprobe tidak menemukan stream yang valid"
            if probe["duration"] <= 0:
                return False, "Durasi media 0 detik"
            if kind == "video":
                if not probe.get("video"):
                    return False, "Tidak ada stream video"
                if probe["width"] < 160 or probe["height"] < 160:
                    return False, "Resolusi video terlalu kecil (%dx%d)" % (probe["width"], probe["height"])
    elif kind == "image":
        try:
            from PIL import Image  # type: ignore
            with Image.open(file_path) as img:
                width, height = img.size
            if width < 160 or height < 160:
                return False, "Resolusi gambar terlalu kecil (%dx%d)" % (width, height)
        except ImportError:
            pass
        except Exception:
            return False, "Gambar korup atau tidak bisa dibaca"

    stats, method = frame_stats(file_path, kind)
    if stats is not None:
        mean, spread = stats
        if spread < SOLID_STD_THRESHOLD:
            return False, "Frame sampel satu warna solid polos (std %.2f)" % spread
        if mean < DARK_MEAN_THRESHOLD:
            return False, "Frame sampel terlalu gelap/hitam pekat (mean %.2f)" % mean

    if metadata:
        check = _check_metadata(probe, metadata)
        if check:
            return False, check

    note = "" if method not in ("unavailable",) else " (cek frame dilewati: tool tidak tersedia)"
    return True, "Aset valid%s" % note


def _check_metadata(probe: Optional[Dict[str, Any]], metadata: Dict[str, Any]) -> str:
    """Validasi ringan metadata clip/stock: durasi & rasio bila disediakan."""
    if not probe:
        return ""
    duration = probe.get("duration") or 0.0
    min_duration = metadata.get("min_duration")
    if isinstance(min_duration, (int, float)) and duration and duration < float(min_duration) - 0.1:
        return "Durasi %.2fs lebih pendek dari minimum %.2fs" % (duration, float(min_duration))
    max_duration = metadata.get("max_duration")
    if isinstance(max_duration, (int, float)) and duration and duration > float(max_duration) + 0.5:
        return "Durasi %.2fs melebihi maksimum %.2fs" % (duration, float(max_duration))
    expected_ratio = metadata.get("aspect_ratio")
    if expected_ratio and probe.get("width") and probe.get("height"):
        try:
            ew, eh = str(expected_ratio).replace("/", ":").split(":")
            expected = float(ew) / float(eh)
            actual = probe["width"] / float(probe["height"])
            if abs(actual - expected) / expected > 0.15:
                return "Rasio aset %.3f menyimpang dari target %s" % (actual, expected_ratio)
        except Exception:
            return ""
    return ""


def quick_check(file_path: str, expected_type: str = "auto") -> Dict[str, Any]:
    ok, reason = verify_asset(file_path, expected_type)
    return {"ok": ok, "reason": reason, "file": file_path, "kind": sniff_kind(file_path)}


def cli_main(argv=None) -> int:
    import argparse

    parser = argparse.ArgumentParser(prog="qa_gate.py", description="ContentStudio Asset QA Gate")
    parser.add_argument("--file", required=True)
    parser.add_argument("--type", default="auto", dest="expected_type")
    args = parser.parse_args(argv if argv is not None else sys.argv[1:])
    print(json.dumps(quick_check(args.file, args.expected_type), indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(cli_main())
