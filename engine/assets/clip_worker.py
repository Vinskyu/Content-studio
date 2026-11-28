#!/usr/bin/env python3
"""
ContentStudio Clip Worker (engine/assets/clip_worker.py)

Footage nyata dari yt-dlp (URL langsung atau `ytsearch5:<query>`), dipotong
presisi sesuai durasi scene agar hemat kuota & storage.

Normalisasi rasio dinamis:
  * orientasi dekat  -> smart crop (crop-to-fill)
  * orientasi lawan  -> blurred background rapi (mis. 16:9 -> 9:16)

Audio mengikuti `audio_modality` DNA: mute untuk `vo_plus_bgm`/`visual_only_bgm`,
dipertahankan untuk `original_clip_audio`.
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
from typing import Any, Dict, List, Optional, Tuple

from engine.runtime import get_ffmpeg_bin
from engine.reference_dna import dimensions_for, normalize_aspect_ratio, scene_contract

MAX_CLIP_BYTES = "90M"


def _emit_step(message: str) -> None:
    sys.stderr.write("[STEP] %s\n" % message)
    sys.stderr.flush()


def _emit_think(message: str) -> None:
    sys.stderr.write("[THINK] %s\n" % message)
    sys.stderr.flush()


def resolve_ytdlp() -> Optional[str]:
    """Cari binary yt-dlp di cache, PATH, atau folder Android."""
    try:
        from engine.media_fetcher import _ensure_cache_bin
        cached = _ensure_cache_bin() / "yt-dlp"
        if cached.exists():
            return str(cached)
    except Exception:
        pass
    which = shutil.which("yt-dlp")
    if which:
        return which
    for candidate in (
        os.path.join(os.environ.get("HOME", ""), ".cache", "bin", "yt-dlp"),
        "/data/user/0/com.agy/files/usr/bin/yt-dlp",
        "/data/data/com.agy/files/usr/bin/yt-dlp",
    ):
        if candidate and os.path.exists(candidate):
            return candidate
    return None


def _run(cmd: List[str], timeout: int = 300) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=timeout)


def _seconds(value: Any, default: float = 0.0) -> float:
    if isinstance(value, (int, float)):
        return float(value)
    text = str(value or "").strip()
    if not text:
        return default
    try:
        if ":" in text:
            parts = [float(p) for p in text.split(":")]
            total = 0.0
            for part in parts:
                total = total * 60 + part
            return total
        return float(text)
    except Exception:
        return default


def _format_ts(seconds: float) -> str:
    seconds = max(0.0, seconds)
    hours = int(seconds // 3600)
    minutes = int((seconds % 3600) // 60)
    secs = seconds % 60
    return "%02d:%02d:%05.2f" % (hours, minutes, secs)


def build_queries(contract: Dict[str, Any]) -> List[str]:
    """Susun query alternatif dari kontrak scene (tanpa niche hardcode)."""
    queries: List[str] = []
    for keyword in contract.get("keywords") or []:
        if keyword and keyword not in queries:
            queries.append(keyword)
    goal = str(contract.get("visual_goal") or "").strip()
    if goal:
        short_goal = " ".join(goal.split()[:8])
        if short_goal and short_goal not in queries:
            queries.append(short_goal)
    title = str(contract.get("title") or "").strip()
    if title and title not in queries:
        queries.append(title)
    return queries[:4]


def _download_section(target: str, raw_path: str, start: float, end: float) -> Tuple[bool, str]:
    ytdlp = resolve_ytdlp()
    if not ytdlp:
        return False, "yt-dlp tidak tersedia"
    section = "*%s-%s" % (_format_ts(start), _format_ts(end))
    cmd = [
        ytdlp, target,
        "-f", "bv*[height<=1080]+ba/b[height<=1080]/b",
        "--no-playlist", "--no-warnings", "--no-progress",
        "--retries", "2", "--fragment-retries", "2",
        "--max-filesize", MAX_CLIP_BYTES,
        "--download-sections", section,
        "--force-keyframes-at-cuts",
        "--merge-output-format", "mp4",
        "-o", raw_path,
    ]
    try:
        proc = _run(cmd, timeout=420)
    except Exception as exc:
        return False, "yt-dlp error: " + str(exc)[:120]
    if proc.returncode != 0:
        tail = (proc.stderr or "").strip().splitlines()[-1:] or ["gagal tanpa detail"]
        return False, "yt-dlp gagal: " + str(tail[0])[:160]
    if not os.path.exists(raw_path):
        # yt-dlp bisa menambahkan ekstensi lain.
        for ext in (".mp4", ".mkv", ".webm", ".m4a"):
            candidate = os.path.splitext(raw_path)[0] + ext
            if os.path.exists(candidate):
                return True, candidate
        return False, "File klip tidak terbentuk"
    return True, raw_path


def _probe_size(file_path: str) -> Tuple[int, int]:
    try:
        from engine.assets.qa_gate import probe_media
        probe = probe_media(file_path) or {}
        return int(probe.get("width") or 0), int(probe.get("height") or 0)
    except Exception:
        return 0, 0


def _normalize_clip(raw_path: str, out_path: str, aspect_ratio: str, duration: float,
                    audio_modality: str, start_offset: float = 0.0) -> Tuple[bool, str]:
    ffmpeg = get_ffmpeg_bin()
    if not ffmpeg or (not os.path.isabs(ffmpeg) and not shutil.which(ffmpeg)):
        os.makedirs(os.path.dirname(os.path.abspath(out_path)) or ".", exist_ok=True)
        shutil.copyfile(raw_path, out_path)
        return True, "ffmpeg tidak tersedia, klip disalin apa adanya"

    ratio = normalize_aspect_ratio(aspect_ratio)
    width, height = dimensions_for(ratio, "1080p")
    src_w, src_h = _probe_size(raw_path)
    keep_audio = str(audio_modality or "").strip() == "original_clip_audio"
    duration = max(0.6, float(duration or 4.0))
    target_ratio = width / float(height)
    same_orientation = True
    if src_w and src_h:
        src_ratio = src_w / float(src_h)
        same_orientation = abs(src_ratio - target_ratio) <= 0.45

    if same_orientation:
        vf = "scale=%d:%d:force_original_aspect_ratio=increase,crop=%d:%d,setsar=1" % (width, height, width, height)
        filter_arg = ["-vf", vf]
    else:
        filter_complex = (
            "[0:v]scale=%d:%d:force_original_aspect_ratio=increase,crop=%d:%d,gblur=sigma=28[bg];"
            "[0:v]scale=%d:%d:force_original_aspect_ratio=decrease[fg];"
            "[bg][fg]overlay=(W-w)/2:(H-h)/2,setsar=1[v]"
        ) % (width, height, width, height, width, height)
        filter_arg = ["-filter_complex", filter_complex, "-map", "[v]"]

    cmd = [ffmpeg, "-y"]
    if start_offset > 0:
        cmd += ["-ss", "%.2f" % start_offset]
    cmd += ["-i", raw_path, "-t", "%.2f" % duration]
    cmd += filter_arg
    if keep_audio:
        cmd += ["-map", "0:a?", "-c:a", "aac", "-b:a", "128k"]
    else:
        cmd += ["-an"]
    cmd += ["-r", "30", "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "veryfast", "-crf", "21",
            "-movflags", "+faststart", out_path]

    try:
        proc = _run(cmd, timeout=300)
    except Exception as exc:
        return False, "ffmpeg gagal: " + str(exc)[:120]
    if proc.returncode != 0 or not os.path.exists(out_path) or os.path.getsize(out_path) <= 0:
        return False, "Normalisasi klip gagal: " + (proc.stderr or "")[-160:]
    return True, "ok"


def fetch_still_from_url(url: str, out_path: str, aspect_ratio: str) -> Dict[str, Any]:
    """Ambil thumbnail sumber sebagai still image (fallback sebelum graphic)."""
    ytdlp = resolve_ytdlp()
    thumb_url = ""
    if ytdlp:
        try:
            proc = _run([ytdlp, url, "--get-thumbnail", "--no-warnings", "--skip-download"], timeout=90)
            if proc.returncode == 0:
                thumb_url = (proc.stdout or "").strip().splitlines()[0] if proc.stdout.strip() else ""
        except Exception:
            thumb_url = ""
    if not thumb_url:
        return {"status": "error", "message": "Thumbnail sumber tidak tersedia"}
    try:
        req = urllib.request.Request(thumb_url, headers={"User-Agent": "ContentStudio/3.6"})
        with urllib.request.urlopen(req, timeout=60) as resp:
            data = resp.read()
        if len(data) < 1024:
            return {"status": "error", "message": "Thumbnail terlalu kecil"}
        target = os.path.splitext(out_path)[0] + ".jpg"
        os.makedirs(os.path.dirname(os.path.abspath(target)) or ".", exist_ok=True)
        with open(target, "wb") as f:
            f.write(data)
    except Exception as exc:
        return {"status": "error", "message": "Unduhan thumbnail gagal: " + str(exc)[:120]}

    # Normalisasi rasio still dengan crop/blur memakai Pillow bila tersedia.
    from engine.assets.stock_worker import _normalize_image
    _normalize_image(target, aspect_ratio)
    return {"status": "ok", "file": target, "kind": "image", "source_url": url}


def fetch_clip(
    scene: Dict[str, Any],
    out_path: str,
    dna: Optional[Dict[str, Any]] = None,
    project: str = "",
    scene_index: int = 0,
    query_override: Optional[str] = None,
) -> Dict[str, Any]:
    """Unduh + normalisasi satu klip untuk scene. Mengembalikan dict status."""
    dna = dna or {}
    contract = scene_contract(scene, dna)
    ratio = normalize_aspect_ratio(dna.get("aspect_ratio"), "16:9")
    duration = float(contract.get("duration") or 4.0)
    audio_modality = str(dna.get("audio_modality") or "vo_plus_bgm")

    direct_url = str((scene or {}).get("source_url") or (scene or {}).get("url") or "").strip()
    queries = [query_override] if query_override else build_queries(contract)
    targets: List[str] = []
    if direct_url:
        targets.append(direct_url)
    for query in queries:
        targets.append("ytsearch5:%s" % query)
    if not targets:
        return {"status": "error", "message": "Tidak ada query klip"}

    start = _seconds((scene or {}).get("clip_start"), 0.0)
    tmp_dir = os.path.join(os.path.dirname(os.path.abspath(out_path)) or ".", ".clip_tmp")
    os.makedirs(tmp_dir, exist_ok=True)
    raw_path = os.path.join(tmp_dir, "scene_%02d_raw.mp4" % int(scene_index))

    errors: List[str] = []
    for target in targets[:4]:
        label = "URL langsung" if target == direct_url else "pencarian klip"
        _emit_step("Mengambil %s untuk scene %d" % (label, int(scene_index) + 1))
        if os.path.exists(raw_path):
            try:
                os.remove(raw_path)
            except Exception:
                pass
        ok, result = _download_section(target, raw_path, start, start + max(duration, 6.0) + 2.0)
        if not ok:
            errors.append(result)
            continue
        raw_actual = result
        ok_norm, norm_msg = _normalize_clip(raw_actual, out_path, ratio, duration, audio_modality)
        try:
            if os.path.exists(raw_actual) and raw_actual != out_path:
                os.remove(raw_actual)
        except Exception:
            pass
        if not ok_norm:
            errors.append(norm_msg)
            continue
        return {
            "status": "ok",
            "file": out_path,
            "kind": "video",
            "aspect_ratio": ratio,
            "duration": duration,
            "target": target if target == direct_url else target.replace("ytsearch5:", ""),
            "audio": "kept" if audio_modality == "original_clip_audio" else "muted",
        }

    return {"status": "error", "message": "Klip gagal: " + ("; ".join(errors[-2:]) or "tidak ada hasil")}


def worker_contract() -> Dict[str, Any]:
    return {"source": "clip", "tool": "yt-dlp", "sectioned": True, "normalize": ["crop", "blur_fill"]}


def cli_main(argv=None) -> int:
    import argparse

    parser = argparse.ArgumentParser(prog="clip_worker.py", description="ContentStudio Clip Worker")
    parser.add_argument("--project", default="")
    parser.add_argument("--out", required=True)
    parser.add_argument("--query", default="")
    parser.add_argument("--url", default="")
    parser.add_argument("--duration", type=float, default=4.0)
    parser.add_argument("--scene-index", type=int, default=0)
    args = parser.parse_args(argv if argv is not None else sys.argv[1:])
    dna = None
    if args.project:
        from engine.reference_dna import load_reference_dna
        dna = load_reference_dna(args.project)
    scene = {
        "duration": args.duration,
        "keywords": [args.query] if args.query else [],
        "source_url": args.url,
    }
    res = fetch_clip(scene, args.out, dna, args.project, args.scene_index)
    print(json.dumps(res, indent=2, ensure_ascii=False))
    return 0 if res.get("status") == "ok" else 1


if __name__ == "__main__":
    raise SystemExit(cli_main())
