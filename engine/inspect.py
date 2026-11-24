#!/usr/bin/env python3
"""
ContentStudio Media Inspector (Mata & Telinga Agen)
Subcommands:
  - inbox      : Menampilkan daftar media di inbox dengan indeks #1, #2, dll.
  - probe      : Ekstraksi metadata video/audio ke probe.json via ffprobe.
  - signals    : Deteksi scene change (scdet), silence ranges, dan audio beat peaks via numpy pipe.
  - sheet      : Contact sheet ber-timestamp (Coarse & Fine 2-Pass) via FFmpeg tile + drawtext.
  - lyrics     : Ambil dan parse lirik sinkron (.lrc) dari LRCLIB atau file lokal.
  - energy     : Peta energi & chorus/drop finder otomatis via PCM pipe & RMS scoring (Sprint B).
  - transcribe : Transkripsi audio word-level via Groq Whisper API (Sprint B).
"""

import sys
import os

# Cegah tabrakan nama dengan modul standar 'inspect' di standard library saat numpy diimpor
_current_dir = os.path.dirname(os.path.abspath(__file__))
if sys.path and sys.path[0] == _current_dir:
    del sys.path[0]
_root_dir = os.path.dirname(_current_dir)
if _root_dir not in sys.path:
    sys.path.append(_root_dir)

import re
import json
import uuid
import argparse
import subprocess
import tempfile
import urllib.request
import urllib.parse
import urllib.error
from datetime import datetime
from typing import Tuple, Dict, Any, List, Optional
import numpy as np

from engine.runtime import (
    get_root_dir,
    get_ffmpeg_bin,
    get_ffprobe_bin,
    get_output_base_dir,
    get_inbox_dir,
    get_fonts_dir,
    resolve_path,
    load_runtime_env
)

# Font eksplisit fallback di Android
DEFAULT_FONT = os.path.join(get_fonts_dir(), "Roboto-Regular.ttf")
if not os.path.exists(DEFAULT_FONT):
    for f in ["/system/fonts/Roboto-Regular.ttf", "/system/fonts/DroidSans.ttf"]:
        if os.path.exists(f):
            DEFAULT_FONT = f
            break

def format_duration(seconds: float) -> str:
    """Format detik menjadi HH:MM:SS atau MM:SS."""
    if seconds is None or seconds < 0:
        return "--:--"
    hrs = int(seconds // 3600)
    mins = int((seconds % 3600) // 60)
    secs = int(seconds % 60)
    if hrs > 0:
        return f"{hrs:02d}:{mins:02d}:{secs:02d}"
    return f"{mins:02d}:{secs:02d}"

def format_size(bytes_val: int) -> str:
    """Format byte menjadi string terbaca."""
    for unit in ['B', 'KB', 'MB', 'GB']:
        if bytes_val < 1024.0:
            return f"{bytes_val:3.1f} {unit}"
        bytes_val /= 1024.0
    return f"{bytes_val:.1f} TB"

# ==========================================
# BRANKAS .env HELPER (Sprint B)
# ==========================================
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

def build_multipart_form_data(fields: dict, files: dict) -> Tuple[bytes, str]:
    """Membangun payload multipart/form-data murni tanpa pustaka eksternal."""
    boundary = "----ContentStudioBoundary" + uuid.uuid4().hex[:16]
    body = bytearray()
    for k, v in fields.items():
        if v is None:
            continue
        if isinstance(v, list):
            for item in v:
                body.extend(f"--{boundary}\r\n".encode("utf-8"))
                body.extend(f'Content-Disposition: form-data; name="{k}"\r\n\r\n'.encode("utf-8"))
                body.extend(f"{item}\r\n".encode("utf-8"))
        else:
            body.extend(f"--{boundary}\r\n".encode("utf-8"))
            body.extend(f'Content-Disposition: form-data; name="{k}"\r\n\r\n'.encode("utf-8"))
            body.extend(f"{v}\r\n".encode("utf-8"))
    for k, (fname, fbytes, ctype) in files.items():
        body.extend(f"--{boundary}\r\n".encode("utf-8"))
        body.extend(f'Content-Disposition: form-data; name="{k}"; filename="{fname}"\r\n'.encode("utf-8"))
        body.extend(f"Content-Type: {ctype}\r\n\r\n".encode("utf-8"))
        body.extend(fbytes)
        body.extend(b"\r\n")
    body.extend(f"--{boundary}--\r\n".encode("utf-8"))
    return bytes(body), f"multipart/form-data; boundary={boundary}"

# ==========================================
# 1. INBOX SUBCOMMAND
# ==========================================
def cmd_inbox(args):
    inbox_dir = resolve_path(args.dir) if args.dir else "/sdcard/ContentStudio/inbox"
    if not os.path.exists(inbox_dir):
        local_inbox = os.path.abspath("./inbox")
        if os.path.exists(local_inbox):
            inbox_dir = local_inbox
        else:
            print(f"[!] Direktori inbox tidak ditemukan: {inbox_dir}")
            return 1

    valid_exts = {
        'video': {'.mp4', '.mkv', '.mov', '.webm', '.avi'},
        'audio': {'.mp3', '.wav', '.m4a', '.aac', '.flac', '.ogg'},
        'image': {'.jpg', '.jpeg', '.png', '.webp'}
    }
    all_exts = set().union(*valid_exts.values())

    files = []
    try:
        entries = os.listdir(inbox_dir)
    except Exception as e:
        print(f"[!] Gagal membaca direktori {inbox_dir}: {e}")
        return 1

    for entry in entries:
        ext = os.path.splitext(entry)[1].lower()
        if ext in all_exts:
            full_path = os.path.join(inbox_dir, entry)
            if os.path.isfile(full_path):
                mtime = os.path.getmtime(full_path)
                size = os.path.getsize(full_path)
                mtype = 'IMAGE'
                if ext in valid_exts['video']:
                    mtype = 'VIDEO'
                elif ext in valid_exts['audio']:
                    mtype = 'AUDIO'
                files.append({
                    'name': entry,
                    'path': full_path,
                    'type': mtype,
                    'size': size,
                    'mtime': mtime
                })

    files.sort(key=lambda x: x['mtime'], reverse=True)

    if not files:
        print(f"[*] Inbox kosong ({inbox_dir}). Taruh file media di folder ini.")
        return 0

    print(f"\n=== DAFTAR MEDIA INBOX ({inbox_dir}) ===")
    print(f"{'#':<4} {'TIPE':<7} {'DURASI':<8} {'UKURAN':<9} {'TANGGAL':<16} {'NAMA FILE'}")
    print("-" * 75)

    for idx, item in enumerate(files, start=1):
        dur_str = "-"
        if item['type'] in ('VIDEO', 'AUDIO'):
            try:
                cmd = [
                    get_ffprobe_bin(), '-v', 'error',
                    '-show_entries', 'format=duration',
                    '-of', 'default=noprint_wrappers=1:nokey=1',
                    item['path']
                ]
                res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=2)
                if res.returncode == 0 and res.stdout.strip():
                    dur_val = float(res.stdout.strip())
                    dur_str = format_duration(dur_val)
            except Exception:
                dur_str = "--:--"

        dt_str = datetime.fromtimestamp(item['mtime']).strftime('%Y-%m-%d %H:%M')
        sz_str = format_size(item['size'])
        print(f"#{idx:<3} {item['type']:<7} {dur_str:<8} {sz_str:<9} {dt_str:<16} {item['name']}")

    print("-" * 75)
    print(f"Total: {len(files)} file media ditemukan.\n")
    return 0

# ==========================================
# 2. PROBE SUBCOMMAND
# ==========================================
def cmd_probe(args):
    media_path = resolve_path(args.media_path)
    out_dir = resolve_path(args.out)
    os.makedirs(out_dir, exist_ok=True)

    if not os.path.exists(media_path):
        print(f"[!] File media tidak ditemukan: {media_path}", file=sys.stderr)
        return 1

    cmd = [
        get_ffprobe_bin(), '-v', 'quiet',
        '-print_format', 'json',
        '-show_format',
        '-show_streams',
        media_path
    ]
    res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if res.returncode != 0:
        print(f"[!] ffprobe gagal: {res.stderr}", file=sys.stderr)
        return 1

    data = json.loads(res.stdout)
    streams = data.get('streams', [])
    fmt = data.get('format', {})

    v_stream = next((s for s in streams if s.get('codec_type') == 'video'), None)
    a_stream = next((s for s in streams if s.get('codec_type') == 'audio'), None)

    probe_result = {
        'path': media_path,
        'filename': os.path.basename(media_path),
        'format_name': fmt.get('format_name'),
        'duration': float(fmt.get('duration') or 0.0),
        'size_bytes': int(fmt.get('size') or 0),
        'bitrate_bps': int(fmt.get('bit_rate') or 0),
        'has_video': v_stream is not None,
        'has_audio': a_stream is not None,
        'video': {},
        'audio': {}
    }

    if v_stream:
        w = int(v_stream.get('width', 0))
        h = int(v_stream.get('height', 0))
        
        dar = v_stream.get('display_aspect_ratio')
        if not dar and w > 0 and h > 0:
            import math
            g = math.gcd(w, h)
            dar = f"{w//g}:{h//g}"

        r_fps_str = v_stream.get('r_frame_rate', '0/1')
        avg_fps_str = v_stream.get('avg_frame_rate', '0/1')
        def eval_frac(s):
            try:
                num, den = s.split('/')
                return float(num) / float(den) if float(den) != 0 else 0.0
            except Exception:
                return 0.0
        r_fps = eval_frac(r_fps_str)
        avg_fps = eval_frac(avg_fps_str)
        fps_mode = 'CFR'
        if abs(r_fps - avg_fps) > 0.08 and avg_fps > 0:
            fps_mode = 'VFR'

        rotation = 0
        side_data = v_stream.get('side_data_list', [])
        for sd in side_data:
            if 'rotation' in sd:
                rotation = int(sd['rotation'])
        if rotation == 0 and 'tags' in v_stream and 'rotate' in v_stream['tags']:
            try:
                rotation = int(v_stream['tags']['rotate'])
            except Exception:
                pass

        probe_result['video'] = {
            'codec': v_stream.get('codec_name'),
            'width': w,
            'height': h,
            'aspect_ratio': dar,
            'fps': round(avg_fps if avg_fps > 0 else r_fps, 2),
            'fps_mode': fps_mode,
            'rotation': rotation,
            'pix_fmt': v_stream.get('pix_fmt'),
            'duration': float(v_stream.get('duration') or probe_result['duration'])
        }

    if a_stream:
        probe_result['audio'] = {
            'codec': a_stream.get('codec_name'),
            'sample_rate': int(a_stream.get('sample_rate', 0)),
            'channels': int(a_stream.get('channels', 0)),
            'channel_layout': a_stream.get('channel_layout', 'stereo'),
            'bitrate_bps': int(a_stream.get('bit_rate') or 0),
            'duration': float(a_stream.get('duration') or probe_result['duration'])
        }

    out_file = os.path.join(out_dir, 'probe.json')
    with open(out_file, 'w', encoding='utf-8') as f:
        json.dump(probe_result, f, indent=2)

    print(f"[*] Probe berhasil disimpan: {out_file}")
    print(json.dumps(probe_result, indent=2))
    return 0

# ==========================================
# 3. SIGNALS SUBCOMMAND
# ==========================================
def cmd_signals(args):
    media_path = resolve_path(args.media_path)
    out_dir = resolve_path(args.out)
    os.makedirs(out_dir, exist_ok=True)

    if not os.path.exists(media_path):
        print(f"[!] File media tidak ditemukan: {media_path}", file=sys.stderr)
        return 1

    scene_thresh = args.scene_thresh
    silence_db = args.silence_db

    ff_chk = subprocess.run([
        get_ffprobe_bin(), '-v', 'error', '-show_entries', 'stream=codec_type',
        '-of', 'default=noprint_wrappers=1:nokey=1', media_path
    ], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    types = ff_chk.stdout.strip().split()
    has_video = 'video' in types
    has_audio = 'audio' in types

    signals_data = {
        'source': media_path,
        'has_video': has_video,
        'has_audio': has_audio,
        'scenes': [],
        'silence_ranges': [],
        'audio_peaks': {
            'max_volume_db': None,
            'beat_count': 0,
            'beats': []
        }
    }

    if has_video:
        cmd_sc = [
            get_ffmpeg_bin(), '-nostdin', '-v', 'info',
            '-i', media_path,
            '-vf', f'scdet=threshold={scene_thresh}',
            '-f', 'null', '-'
        ]
        res_sc = subprocess.run(cmd_sc, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        matches = re.findall(r'lavfi\.scd\.score:\s*([0-9.]+),\s*lavfi\.scd\.time:\s*([0-9.]+)', res_sc.stderr)
        for score, t in matches:
            signals_data['scenes'].append({
                'time': round(float(t), 3),
                'score': round(float(score), 2)
            })

    if has_audio:
        cmd_silence = [
            get_ffmpeg_bin(), '-nostdin',
            '-i', media_path, '-vn',
            '-af', f'silencedetect=noise={silence_db}dB:d=0.3',
            '-f', 'null', '-'
        ]
        res_silence = subprocess.run(cmd_silence, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        starts = re.findall(r'silence_start:\s*([0-9.]+)', res_silence.stderr)
        ends_durs = re.findall(r'silence_end:\s*([0-9.]+)\s*\|\s*silence_duration:\s*([0-9.]+)', res_silence.stderr)
        for i in range(min(len(starts), len(ends_durs))):
            signals_data['silence_ranges'].append({
                'start': round(float(starts[i]), 3),
                'end': round(float(ends_durs[i][0]), 3),
                'duration': round(float(ends_durs[i][1]), 3)
            })

        cmd_pcm = [
            get_ffmpeg_bin(), '-nostdin', '-v', 'error',
            '-i', media_path, '-vn',
            '-af', 'lowpass=f=150',
            '-f', 's16le', '-ac', '1', '-ar', '1000', '-'
        ]
        proc = subprocess.Popen(cmd_pcm, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        raw_pcm, _ = proc.communicate()

        if raw_pcm and len(raw_pcm) > 0:
            samples = np.frombuffer(raw_pcm, dtype=np.int16)
            abs_samples = np.abs(samples.astype(np.float32))
            max_val = np.max(abs_samples) if len(abs_samples) > 0 else 0
            if max_val > 0:
                signals_data['audio_peaks']['max_volume_db'] = round(float(20 * np.log10(max_val / 32768.0)), 2)

            win_size = 50
            if len(abs_samples) > win_size:
                smoothed = np.convolve(abs_samples, np.ones(win_size)/win_size, mode='same')
                mean_e = np.mean(smoothed)
                std_e = np.std(smoothed)
                thresh = mean_e + 1.2 * std_e

                min_spacing = 250
                last_beat_idx = -min_spacing
                beats = []

                for i in range(1, len(smoothed) - 1):
                    if smoothed[i] > thresh and smoothed[i] > smoothed[i - 1] and smoothed[i] >= smoothed[i + 1]:
                        if (i - last_beat_idx) >= min_spacing:
                            t_sec = round(i / 1000.0, 3)
                            beats.append(t_sec)
                            last_beat_idx = i

                signals_data['audio_peaks']['beat_count'] = len(beats)
                signals_data['audio_peaks']['beats'] = beats

    out_file = os.path.join(out_dir, 'signals.json')
    with open(out_file, 'w', encoding='utf-8') as f:
        json.dump(signals_data, f, indent=2)

    print(f"[*] Signals berhasil disimpan: {out_file}")
    print(f"  - Scenes terdeteksi : {len(signals_data['scenes'])}")
    print(f"  - Silence terdeteksi: {len(signals_data['silence_ranges'])}")
    print(f"  - Beats terdeteksi  : {signals_data['audio_peaks']['beat_count']} beat (Max Vol: {signals_data['audio_peaks']['max_volume_db']} dBFS)")
    return 0

# ==========================================
# 4. SHEET SUBCOMMAND (COARSE & FINE)
# ==========================================
def cmd_sheet(args):
    media_path = resolve_path(args.media_path)
    out_dir = resolve_path(args.out)
    os.makedirs(out_dir, exist_ok=True)

    if not os.path.exists(media_path):
        print(f"[!] File media tidak ditemukan: {media_path}", file=sys.stderr)
        return 1

    start = float(args.start)
    end = float(args.end)
    cols = int(args.cols)
    rows = int(args.rows)
    width = int(args.width)
    total_frames = cols * rows

    out_name = args.name
    if not out_name:
        out_name = "sheet_fine.jpg" if (end > start) else "sheet_coarse.jpg"
    out_path = os.path.join(out_dir, out_name)

    dur_cmd = [
        get_ffprobe_bin(), '-v', 'error',
        '-show_entries', 'format=duration',
        '-of', 'default=noprint_wrappers=1:nokey=1',
        media_path
    ]
    res_dur = subprocess.run(dur_cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    total_duration = float(res_dur.stdout.strip() or 1.0)

    font_path = DEFAULT_FONT

    if end > start:
        range_dur = max(end - start, 0.1)
        fps_val = total_frames / range_dur
        vf_filter = (
            f"trim=start={start}:end={end},"
            f"drawtext=fontfile='{font_path}':text='%{{pts\\:hms}}':x=10:y=h-th-10:fontsize=16:fontcolor=white:box=1:boxcolor=black@0.7:boxborderw=3,"
            f"setpts=PTS-STARTPTS,"
            f"fps={fps_val:.6f},"
            f"scale={width}:-2,"
            f"tile={cols}x{rows}"
        )
    else:
        fps_val = total_frames / max(total_duration, 0.5)
        vf_filter = (
            f"drawtext=fontfile='{font_path}':text='%{{pts\\:hms}}':x=10:y=h-th-10:fontsize=16:fontcolor=white:box=1:boxcolor=black@0.7:boxborderw=3,"
            f"fps={fps_val:.6f},"
            f"scale={width}:-2,"
            f"tile={cols}x{rows}"
        )

    cmd = [
        get_ffmpeg_bin(), '-nostdin', '-y',
        '-i', media_path,
        '-vf', vf_filter,
        '-frames:v', '1',
        '-update', '1',
        out_path
    ]

    res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if res.returncode != 0:
        print(f"[!] Gagal membuat Contact Sheet: {res.stderr}", file=sys.stderr)
        return 1

    print(f"[*] Contact Sheet ({'Fine' if end > start else 'Coarse'}) berhasil dibuat: {out_path}")
    print(f"  - Dimensi grid: {cols}x{rows} ({total_frames} frame, lebar kolom {width}px)")
    if end > start:
        print(f"  - Rentang zoom : {start:.2f}s - {end:.2f}s")
    else:
        print(f"  - Durasi penuh : {total_duration:.2f}s")
    return 0

# ==========================================
# 5. LYRICS SUBCOMMAND
# ==========================================
def parse_lrc(lrc_text: str):
    """Parse format lrc [mm:ss.xx] menjadi list objek terstruktur."""
    lines = []
    pattern = re.compile(r'\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?\](.*)')
    for raw_line in lrc_text.splitlines():
        raw_line = raw_line.strip()
        m = pattern.match(raw_line)
        if m:
            mins = int(m.group(1))
            secs = int(m.group(2))
            ms_str = m.group(3) or "0"
            if len(ms_str) == 2:
                ms = int(ms_str) * 10
            elif len(ms_str) == 1:
                ms = int(ms_str) * 100
            else:
                ms = int(ms_str[:3])
            t_sec = round(mins * 60 + secs + (ms / 1000.0), 3)
            lyric_text = m.group(4).strip()
            if lyric_text:
                lines.append({
                    'time': t_sec,
                    'time_str': f"{mins:02d}:{secs:02d}.{ms//10:02d}",
                    'text': lyric_text
                })
    lines.sort(key=lambda x: x['time'])
    return lines

def cmd_lyrics(args):
    lrc_content = None
    track_title = ""
    artist_name = ""

    if args.file:
        file_path = resolve_path(args.file)
        if not os.path.exists(file_path):
            print(f"[!] File lrc tidak ditemukan: {file_path}", file=sys.stderr)
            return 1
        with open(file_path, 'r', encoding='utf-8') as f:
            lrc_content = f.read()
    elif args.query:
        track_title = args.query
        artist_name = args.artist or ""
        headers = {'User-Agent': 'ContentStudio/1.0 (agent@contentstudio.ai)'}

        if artist_name:
            params = urllib.parse.urlencode({'track_name': track_title, 'artist_name': artist_name})
            url = f"https://lrclib.net/api/get?{params}"
            try:
                req = urllib.request.Request(url, headers=headers)
                with urllib.request.urlopen(req, timeout=8) as resp:
                    data = json.loads(resp.read().decode('utf-8'))
                    lrc_content = data.get('syncedLyrics')
            except Exception:
                lrc_content = None

        if not lrc_content:
            q_str = f"{track_title} {artist_name}".strip()
            url = f"https://lrclib.net/api/search?q={urllib.parse.quote(q_str)}"
            try:
                req = urllib.request.Request(url, headers=headers)
                with urllib.request.urlopen(req, timeout=8) as resp:
                    items = json.loads(resp.read().decode('utf-8'))
                    for item in items:
                        if item.get('syncedLyrics'):
                            lrc_content = item.get('syncedLyrics')
                            track_title = item.get('trackName', track_title)
                            artist_name = item.get('artistName', artist_name)
                            break
            except Exception as e:
                print(f"[!] Error saat menghubungi LRCLIB: {e}", file=sys.stderr)
                return 1

    if not lrc_content:
        print("[!] Lirik sinkron (.lrc) tidak ditemukan.", file=sys.stderr)
        return 1

    parsed = parse_lrc(lrc_content)

    if args.out:
        out_path = resolve_path(args.out)
        os.makedirs(os.path.dirname(out_path), exist_ok=True)
        if out_path.endswith('.json'):
            with open(out_path, 'w', encoding='utf-8') as f:
                json.dump(parsed, f, indent=2, ensure_ascii=False)
        else:
            with open(out_path, 'w', encoding='utf-8') as f:
                f.write(lrc_content)
        print(f"[*] Lirik berhasil disimpan ke: {out_path}")

    print(f"\n=== LIRIK SINKRON: {track_title} - {artist_name} ({len(parsed)} baris) ===")
    for line in parsed[:10]:
        print(f"[{line['time_str']} -> {line['time']}s] {line['text']}")
    if len(parsed) > 10:
        print(f"... ({len(parsed) - 10} baris lirik berikutnya disembunyikan)")
    print("=" * 60)
    return 0

# ==========================================
# 6. ENERGY SUBCOMMAND (CHORUS & DROP FINDER)
# ==========================================
def cmd_energy(args):
    """Peta energi & chorus/drop finder otomatis via PCM pipe & RMS scoring."""
    media_path = resolve_path(args.media_path)
    out_dir = resolve_path(args.out)
    os.makedirs(out_dir, exist_ok=True)

    if not os.path.exists(media_path):
        print(f"[!] File media tidak ditemukan: {media_path}", file=sys.stderr)
        return 1

    window = float(args.window or 20.0)
    step = float(args.step or 2.0)
    sr = 8000

    print(f"[*] Mengekstrak PCM audio {sr}Hz (Full-band & Vocal-band) in-memory via pipe...")
    cmd_full = [
        get_ffmpeg_bin(), "-nostdin", "-v", "error",
        "-i", media_path, "-vn",
        "-ac", "1", "-ar", str(sr),
        "-f", "s16le", "-"
    ]
    p_full = subprocess.run(cmd_full, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if p_full.returncode != 0 or not p_full.stdout:
        err_msg = p_full.stderr.decode("utf-8", errors="ignore")
        print(f"[!] Gagal mengekstrak full-band audio: {err_msg}", file=sys.stderr)
        return 1
    pcm_full = np.frombuffer(p_full.stdout, dtype=np.int16)

    cmd_vocal = [
        get_ffmpeg_bin(), "-nostdin", "-v", "error",
        "-i", media_path, "-vn",
        "-af", "highpass=f=300,lowpass=f=3400",
        "-ac", "1", "-ar", str(sr),
        "-f", "s16le", "-"
    ]
    p_vocal = subprocess.run(cmd_vocal, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if p_vocal.returncode != 0 or not p_vocal.stdout:
        err_msg = p_vocal.stderr.decode("utf-8", errors="ignore")
        print(f"[!] Gagal mengekstrak vocal-band audio: {err_msg}", file=sys.stderr)
        return 1
    pcm_vocal = np.frombuffer(p_vocal.stdout, dtype=np.int16)

    total_samples = min(len(pcm_full), len(pcm_vocal))
    total_dur = round(total_samples / float(sr), 2)
    step_samples = int(step * sr)
    n_steps = total_samples // step_samples

    if n_steps < 2:
        print(f"[!] Durasi audio terlalu pendek ({total_dur}s) untuk analisis energi.", file=sys.stderr)
        return 1

    timeline = []
    for i in range(n_steps):
        s_f = pcm_full[i * step_samples : (i + 1) * step_samples].astype(np.float64)
        s_v = pcm_vocal[i * step_samples : (i + 1) * step_samples].astype(np.float64)

        rms_f = float(np.sqrt(np.mean(s_f**2))) if len(s_f) > 0 else 0.0
        rms_v = float(np.sqrt(np.mean(s_v**2))) if len(s_v) > 0 else 0.0

        db_f = round(float(20 * np.log10(max(rms_f, 1e-5) / 32768.0)), 2)
        db_v = round(float(20 * np.log10(max(rms_v, 1e-5) / 32768.0)), 2)

        timeline.append({
            "step": i,
            "time": round(i * step, 2),
            "db_full": db_f,
            "db_vocal": db_v
        })

    all_db_f = [t["db_full"] for t in timeline]
    all_db_v = [t["db_vocal"] for t in timeline]
    min_f, max_f = min(all_db_f), max(all_db_f)
    min_v, max_v = min(all_db_v), max(all_db_v)
    span_f = max(max_f - min_f, 1e-3)
    span_v = max(max_v - min_v, 1e-3)

    for t in timeline:
        t["energy_score"] = round(float(max(0.0, min(1.0, (t["db_full"] - min_f) / span_f))), 3)
        t["vocal_score"] = round(float(max(0.0, min(1.0, (t["db_vocal"] - min_v) / span_v))), 3)

    win_steps = max(1, int(round(window / step)))
    candidates = []

    min_time = 3.0 if total_dur > (window + 6.0) else 0.0
    max_time = (total_dur - 3.0) if total_dur > (window + 6.0) else total_dur

    for i in range(n_steps - win_steps + 1):
        t_start = timeline[i]["time"]
        t_end = round(t_start + window, 2)
        if t_start < min_time or t_end > max_time:
            continue

        win_entries = timeline[i : i + win_steps]
        avg_energy = float(np.mean([e["energy_score"] for e in win_entries]))
        avg_vocal = float(np.mean([e["vocal_score"] for e in win_entries]))
        avg_db_f = float(np.mean([e["db_full"] for e in win_entries]))
        avg_db_v = float(np.mean([e["db_vocal"] for e in win_entries]))

        prev_idx_start = max(0, i - int(round(4.0 / step)))
        prev_entries = timeline[prev_idx_start : i]
        entry_entries = timeline[i : min(i + int(round(4.0 / step)), len(timeline))]

        if prev_entries and entry_entries:
            prev_db_f = float(np.mean([e["db_full"] for e in prev_entries]))
            entry_db_f = float(np.mean([e["db_full"] for e in entry_entries]))
            contrast_db = round(entry_db_f - prev_db_f, 2)
        else:
            contrast_db = 0.0

        contrast_score = float(max(0.0, min(1.0, (contrast_db + 6.0) / 12.0)))
        comp_score = round(0.45 * avg_energy + 0.35 * avg_vocal + 0.20 * contrast_score, 3)

        reason_parts = []
        if avg_energy >= 0.75:
            reason_parts.append("Klimaks energi penuh tinggi")
        elif avg_energy >= 0.5:
            reason_parts.append("Energi ritme stabil")
        else:
            reason_parts.append("Nuansa ambience tenang")

        if avg_vocal >= 0.7:
            reason_parts.append("vokal/melodi dominan")
        elif avg_vocal >= 0.45:
            reason_parts.append("kehadiran vokal jelas")

        if contrast_db >= 3.0:
            reason_parts.append(f"lonjakan drop tegas (+{contrast_db:.1f} dB)")
        elif contrast_db >= 1.0:
            reason_parts.append(f"transisi build-up (+{contrast_db:.1f} dB)")

        reason_str = " + ".join(reason_parts) if reason_parts else "Pola energi konsisten"

        candidates.append({
            "start": t_start,
            "end": t_end,
            "score": comp_score,
            "full_energy_db": round(avg_db_f, 1),
            "vocal_energy_db": round(avg_db_v, 1),
            "contrast_jump_db": contrast_db,
            "reason": reason_str
        })

    candidates.sort(key=lambda x: x["score"], reverse=True)

    top_candidates = []
    for c in candidates:
        overlap = False
        for chosen in top_candidates:
            if not (c["end"] <= chosen["start"] or c["start"] >= chosen["end"]):
                overlap = True
                break
        if not overlap:
            c["rank"] = len(top_candidates) + 1
            top_candidates.append(c)
            if len(top_candidates) == 3:
                break

    energy_data = {
        "source": media_path,
        "duration": total_dur,
        "window": window,
        "step": step,
        "top_candidates": top_candidates,
        "timeline": timeline
    }

    out_file = os.path.join(out_dir, "energy.json")
    with open(out_file, "w", encoding="utf-8") as f:
        json.dump(energy_data, f, indent=2)

    print(f"\n[*] Peta Energi & Chorus Finder berhasil disimpan: {out_file}")
    print(f"  - Total Durasi : {total_dur:.2f}s (Jendela: {window}s, Resolusi Step: {step}s)")
    print(f"\n=== TOP 3 KANDIDAT CHORUS / KLIMAKS ENERGI TERBAIK ===")
    print(f"{'RANK':<5} {'RENTANG DETIK':<16} {'SKOR':<7} {'FULL dB':<9} {'VOCAL dB':<10} {'LONJAKAN':<10} {'ALASAN ANALISIS'}")
    print("-" * 95)
    for c in top_candidates:
        r_str = f"#{c['rank']}"
        time_str = f"{c['start']:.1f}s - {c['end']:.1f}s"
        cj_str = f"+{c['contrast_jump_db']:.1f} dB" if c['contrast_jump_db'] >= 0 else f"{c['contrast_jump_db']:.1f} dB"
        print(f"{r_str:<5} {time_str:<16} {c['score']:<7.3f} {c['full_energy_db']:<9.1f} {c['vocal_energy_db']:<10.1f} {cj_str:<10} {c['reason']}")
    print("-" * 95)
    return 0

# ==========================================
# 7. TRANSCRIBE SUBCOMMAND (GROQ WHISPER)
# ==========================================
def transcribe_audio(
    media_path: str,
    out_dir: str,
    start_sec: float = 0.0,
    end_sec: float = 0.0,
    lang: str = "id",
    prompt: str = ""
) -> Dict[str, Any]:
    """Transkripsi audio word-level via Groq Whisper API (whisper-large-v3-turbo) in-process."""
    m_path = resolve_path(media_path)
    o_dir = resolve_path(out_dir)
    os.makedirs(o_dir, exist_ok=True)

    if not os.path.exists(m_path):
        raise FileNotFoundError(f"File media tidak ditemukan: {m_path}")

    api_key = get_env_key("GROQ_API_KEY")
    if not api_key:
        raise ValueError("GROQ_API_KEY belum disetel di environment atau .env")

    start_sec = float(start_sec or 0.0)
    end_sec = float(end_sec or 0.0)
    lang = lang or "id"
    prompt = prompt or ""

    dur_cmd = [
        get_ffprobe_bin(), "-v", "error",
        "-show_entries", "format=duration",
        "-of", "default=noprint_wrappers=1:nokey=1",
        m_path
    ]
    res_dur = subprocess.run(dur_cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    total_dur = float(res_dur.stdout.strip() or 0.0)

    if end_sec <= 0.0 or end_sec > total_dur:
        end_sec = total_dur

    clip_dur = max(0.1, end_sec - start_sec)

    temp_dir = tempfile.gettempdir()
    temp_mp3 = os.path.join(temp_dir, f"transcribe_{uuid.uuid4().hex[:12]}.mp3")

    try:
        print(f"[*] Mengompresi audio ({start_sec:.2f}s - {end_sec:.2f}s, durasi {clip_dur:.2f}s) ke 16kHz mono 32kbps...")
        cmd_cut = [
            get_ffmpeg_bin(), "-nostdin", "-y",
            "-ss", f"{start_sec:.4f}",
            "-t", f"{clip_dur:.4f}",
            "-i", m_path,
            "-vn",
            "-ac", "1",
            "-ar", "16000",
            "-c:a", "libmp3lame",
            "-b:a", "32k",
            temp_mp3
        ]
        res_cut = subprocess.run(cmd_cut, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        if res_cut.returncode != 0 or not os.path.exists(temp_mp3):
            raise RuntimeError(f"Gagal memotong dan mengompresi audio: {res_cut.stderr}")

        comp_size = os.path.getsize(temp_mp3)
        print(f"  ✓ Ukuran audio kompresi: {comp_size / 1024:.1f} KB (siap kirim ke Groq)")

        with open(temp_mp3, "rb") as f:
            audio_bytes = f.read()

        fields = {
            "model": "whisper-large-v3-turbo",
            "response_format": "verbose_json",
            "timestamp_granularities[]": ["word", "segment"]
        }
        if lang:
            fields["language"] = lang
        if prompt:
            fields["prompt"] = prompt

        files = {
            "file": ("audio.mp3", audio_bytes, "audio/mpeg")
        }

        payload_bytes, content_type = build_multipart_form_data(fields, files)

        url = "https://api.groq.com/openai/v1/audio/transcriptions"
        headers = {
            "Authorization": f"Bearer {api_key}",
            "Content-Type": content_type,
            "User-Agent": "ContentStudio/2.0"
        }

        print("[*] Mengirim audio ke Groq Whisper API (whisper-large-v3-turbo)...")
        req = urllib.request.Request(url, data=payload_bytes, headers=headers, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                resp_data = json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            err_body = e.read().decode("utf-8", errors="ignore")
            raise RuntimeError(f"Groq API HTTP Error {e.code}: {err_body}")
        except Exception as e:
            raise RuntimeError(f"Gagal menghubungi Groq API: {e}")

        raw_words = resp_data.get("words", [])
        transformed_words = []
        for w in raw_words:
            w_start = round(float(w.get("start", 0.0)), 3)
            w_end = round(float(w.get("end", 0.0)), 3)
            transformed_words.append({
                "word": w.get("word", "").strip(),
                "clip_time": {"start": w_start, "end": w_end},
                "source_time": {"start": round(w_start + start_sec, 3), "end": round(w_end + start_sec, 3)}
            })

        raw_segments = resp_data.get("segments", [])
        transformed_segments = []
        for s in raw_segments:
            s_start = round(float(s.get("start", 0.0)), 3)
            s_end = round(float(s.get("end", 0.0)), 3)
            transformed_segments.append({
                "id": s.get("id"),
                "text": s.get("text", "").strip(),
                "clip_time": {"start": s_start, "end": s_end},
                "source_time": {"start": round(s_start + start_sec, 3), "end": round(s_end + start_sec, 3)}
            })

        output_data = {
            "source": m_path,
            "offset_start": start_sec,
            "offset_end": end_sec,
            "duration": clip_dur,
            "text": resp_data.get("text", "").strip(),
            "words": transformed_words,
            "segments": transformed_segments
        }

        out_file = os.path.join(o_dir, "transcript.json")
        with open(out_file, "w", encoding="utf-8") as f:
            json.dump(output_data, f, indent=2, ensure_ascii=False)

        return output_data

    finally:
        if os.path.exists(temp_mp3):
            try:
                os.remove(temp_mp3)
            except Exception:
                pass

def cmd_transcribe(args):
    """Transkripsi audio word-level via Groq Whisper API (CLI wrapper)."""
    try:
        data = transcribe_audio(
            media_path=args.media_path,
            out_dir=args.out,
            start_sec=float(args.start or 0.0),
            end_sec=float(args.end or 0.0),
            lang=args.lang or "id",
            prompt=args.prompt or ""
        )
        out_file = os.path.join(resolve_path(args.out), "transcript.json")
        print(f"\n[*] Transkripsi berhasil disimpan: {out_file}")
        print(f"  - Total Kata     : {len(data.get('words', []))} kata")
        print(f"  - Total Segmen   : {len(data.get('segments', []))} kalimat/segmen")
        print(f"  - Teks Keseluruhan: {data.get('text', '')}\n")
        return 0
    except Exception as e:
        print(f"[!] Transcribe gagal: {e}", file=sys.stderr)
        return 1

# ==========================================
# MAIN ROUTER
# ==========================================
def main():
    parser = argparse.ArgumentParser(description="ContentStudio Media Inspector (Mata & Telinga Agen)")
    subparsers = parser.add_subparsers(dest="subcommand", required=True)

    # inbox
    p_inbox = subparsers.add_parser("inbox", help="Daftar media di inbox")
    p_inbox.add_argument("--dir", help="Direktori target inbox (default /sdcard/ContentStudio/inbox)")
    p_inbox.set_defaults(func=cmd_inbox)

    # probe
    p_probe = subparsers.add_parser("probe", help="Ekstraksi metadata media ke probe.json")
    p_probe.add_argument("media_path", help="Path ke file video/audio")
    p_probe.add_argument("--out", required=True, help="Direktori output probe.json")
    p_probe.set_defaults(func=cmd_probe)

    # signals
    p_sig = subparsers.add_parser("signals", help="Ekstraksi signals (scene change, silence, audio beats)")
    p_sig.add_argument("media_path", help="Path ke file video/audio")
    p_sig.add_argument("--out", required=True, help="Direktori output signals.json")
    p_sig.add_argument("--scene-thresh", type=float, default=12.0, help="Ambang scdet (default 12)")
    p_sig.add_argument("--silence-db", type=float, default=-32.0, help="Ambang silence dB (default -32)")
    p_sig.set_defaults(func=cmd_signals)

    # sheet
    p_sheet = subparsers.add_parser("sheet", help="Buat Contact Sheet gambar ber-timestamp")
    p_sheet.add_argument("media_path", help="Path ke file video")
    p_sheet.add_argument("--out", required=True, help="Direktori output gambar sheet")
    p_sheet.add_argument("--start", type=float, default=0.0, help="Detik mulai zoom (Fine mode)")
    p_sheet.add_argument("--end", type=float, default=0.0, help="Detik selesai zoom (Fine mode)")
    p_sheet.add_argument("--cols", type=int, default=4, help="Jumlah kolom (default 4)")
    p_sheet.add_argument("--rows", type=int, default=4, help="Jumlah baris (default 4)")
    p_sheet.add_argument("--width", type=int, default=320, help="Lebar per frame (default 320)")
    p_sheet.add_argument("--name", default="", help="Nama file gambar output")
    p_sheet.set_defaults(func=cmd_sheet)

    # lyrics
    p_lyr = subparsers.add_parser("lyrics", help="Ambil dan sinkronisasi lirik LRCLIB")
    p_lyr.add_argument("--query", help="Judul lagu untuk dicari di LRCLIB")
    p_lyr.add_argument("--artist", help="Nama artis")
    p_lyr.add_argument("--file", help="Path file .lrc lokal")
    p_lyr.add_argument("--out", help="Path penyimpanan hasil (.lrc atau .json)")
    p_lyr.set_defaults(func=cmd_lyrics)

    # energy (Sprint B)
    p_ene = subparsers.add_parser("energy", help="Peta energi & chorus/drop finder otomatis")
    p_ene.add_argument("media_path", help="Path ke file video/audio")
    p_ene.add_argument("--out", required=True, help="Direktori output energy.json")
    p_ene.add_argument("--window", type=float, default=20.0, help="Panjang rentang kandidat dalam detik (default 20)")
    p_ene.add_argument("--step", type=float, default=2.0, help="Resolusi langkah analisis dalam detik (default 2)")
    p_ene.set_defaults(func=cmd_energy)

    # transcribe (Sprint B)
    p_tra = subparsers.add_parser("transcribe", help="Transkripsi audio word-level via Groq Whisper API")
    p_tra.add_argument("media_path", help="Path ke file video/audio")
    p_tra.add_argument("--out", required=True, help="Direktori output transcript.json")
    p_tra.add_argument("--start", type=float, default=0.0, help="Detik mulai potong audio (default 0)")
    p_tra.add_argument("--end", type=float, default=0.0, help="Detik selesai potong audio (default durasi penuh)")
    p_tra.add_argument("--lang", default="id", help="Kode bahasa Whisper (default id)")
    p_tra.add_argument("--prompt", default="", help="Prompt konteks tambahan untuk Whisper")
    p_tra.set_defaults(func=cmd_transcribe)

    args = parser.parse_args()
    return args.func(args)

if __name__ == "__main__":
    sys.exit(main() or 0)
