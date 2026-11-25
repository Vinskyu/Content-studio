#!/usr/bin/env python3
"""
ContentStudio Dynamic Video Builder (Mesin Rakit Dinamis Anti-OOM, Dual-Audio, & Stems)
Sprint A: Upgraded with OFL Pro Fonts, 5 .ASS Animation Presets, Visual & Audio Polish,
          Anti-OOM Rolling Pairwise Merging for Transitions, and Auto Word-Wrap.
Usage:
  python3 engine/build.py <plan.json> [--validate] [--preview] [--final]
"""

import sys
import os

# Cegah tabrakan nama dengan modul standar 'inspect' di library Python/numpy
_current_dir = os.path.dirname(os.path.abspath(__file__))
if sys.path and sys.path[0] == _current_dir:
    del sys.path[0]
_root_dir = os.path.dirname(_current_dir)
if _root_dir not in sys.path:
    sys.path.append(_root_dir)

import json
import math
import shutil
import struct
import argparse
import subprocess
import tempfile
from typing import Dict, Any, Tuple, List, Optional, Callable
from engine.runtime import (
    get_root_dir,
    get_ffmpeg_bin,
    get_ffprobe_bin,
    get_output_base_dir,
    get_fonts_dir,
    resolve_path
)

FONTS_DIR = get_fonts_dir()
DEFAULT_FONT_NAME = "Roboto"

def probe_file(path: str) -> Dict[str, Any]:
    """Ekstraksi metadata teknis menggunakan ffprobe."""
    if not os.path.exists(path):
        return {}
    cmd = [
        get_ffprobe_bin(), "-v", "quiet",
        "-print_format", "json",
        "-show_format", "-show_streams",
        path
    ]
    res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if res.returncode != 0:
        return {}
    try:
        data = json.loads(res.stdout)
    except Exception:
        return {}
    
    streams = data.get("streams", [])
    fmt = data.get("format", {})
    v_stream = next((s for s in streams if s.get("codec_type") == "video"), None)
    a_stream = next((s for s in streams if s.get("codec_type") == "audio"), None)

    dur = float(fmt.get("duration") or 0.0)
    w, h = 0, 0
    if v_stream:
        w = int(v_stream.get("width") or 0)
        h = int(v_stream.get("height") or 0)
        if dur <= 0:
            dur = float(v_stream.get("duration") or 0.0)

    ch = 0
    if a_stream:
        ch = int(a_stream.get("channels") or 0)

    return {
        "duration": dur,
        "width": w,
        "height": h,
        "channels": ch,
        "has_video": v_stream is not None,
        "has_audio": a_stream is not None
    }

# ==============================================================================
# FONT MANIFEST & ALIAS RESOLVER (Python struct OpenType parser)
# ==============================================================================
def parse_ttf_names(file_path: str) -> Dict[int, str]:
    """Ekstraksi metadata nama TrueType/OpenType via Python struct stdlib murni."""
    try:
        with open(file_path, "rb") as f:
            data = f.read()
        if len(data) < 12:
            return {}
        sfnt_version, num_tables = struct.unpack(">IH", data[:6])
        name_offset = None
        name_length = None
        for i in range(num_tables):
            entry = data[12 + i * 16 : 28 + i * 16]
            tag, checksum, offset, length = struct.unpack(">4sIII", entry)
            if tag == b"name":
                name_offset = offset
                name_length = length
                break
        if name_offset is None:
            return {}
        table_data = data[name_offset : name_offset + name_length]
        fmt, count, string_offset = struct.unpack(">HHH", table_data[:6])
        names = {}
        for i in range(count):
            rec = table_data[6 + i * 12 : 18 + i * 12]
            plat_id, enc_id, lang_id, name_id, length, offset = struct.unpack(">HHHHHH", rec)
            str_bytes = table_data[string_offset + offset : string_offset + offset + length]
            val = None
            if plat_id == 3 or plat_id == 0:
                try: val = str_bytes.decode("utf-16-be")
                except Exception: pass
            elif plat_id == 1:
                try: val = str_bytes.decode("mac_roman")
                except Exception: pass
            if not val:
                try: val = str_bytes.decode("utf-8")
                except Exception: pass
            if val and name_id not in names:
                names[name_id] = val
        return names
    except Exception:
        return {}

def load_font_manifest() -> Dict[str, Any]:
    """Muat atau generate otomatis manifest font dari assets/fonts/."""
    manifest_path = os.path.join(FONTS_DIR, "manifest.json")
    if os.path.exists(manifest_path):
        try:
            with open(manifest_path, "r", encoding="utf-8") as f:
                data = json.load(f)
                if data.get("fonts") and data.get("alias_map"):
                    return data
        except Exception:
            pass

    category_rules = {
        'playfair': ('serif', ['playfair', 'playfair display', 'playfair_display', 'serif']),
        'plusjakarta': ('sans', ['jakarta', 'plus jakarta sans', 'plus_jakarta_sans', 'plusjakartasans', 'sans']),
        'poppins': ('sans_bold', ['poppins', 'poppins extrabold', 'poppins-extrabold', 'bold_sans', 'impact_sans']),
        'montserrat': ('sans_bold', ['montserrat', 'montserrat extrabold', 'montserrat-extrabold']),
        'bebas': ('condensed', ['bebas', 'bebas neue', 'bebas_neue', 'bebasneue', 'condensed', 'headline']),
        'roboto': ('default', ['roboto', 'roboto regular', 'default', 'sans_clean'])
    }
    manifest = {'fonts': {}, 'alias_map': {}}
    if os.path.exists(FONTS_DIR):
        for fname in sorted(os.listdir(FONTS_DIR)):
            if fname.endswith(('.ttf', '.otf')):
                fpath = os.path.join(FONTS_DIR, fname)
                names = parse_ttf_names(fpath)
                family = names.get(1) or names.get(16) or names.get(4) or os.path.splitext(fname)[0]
                full_name = names.get(4) or family
                ps_name = names.get(6) or fname
                cat = 'general'
                aliases = [fname.lower(), os.path.splitext(fname)[0].lower(), family.lower()]
                for k, (c, al_list) in category_rules.items():
                    if k in fname.lower() or k in family.lower():
                        cat = c
                        aliases.extend(al_list)
                        break
                unique_aliases = list(dict.fromkeys(aliases))
                manifest['fonts'][fname] = {
                    'file': fname,
                    'libass_name': family,
                    'family': family,
                    'subfamily': names.get(2, 'Regular'),
                    'full_name': full_name,
                    'postscript_name': ps_name,
                    'category': cat,
                    'aliases': unique_aliases
                }
                for al in unique_aliases:
                    manifest['alias_map'][al] = family
        try:
            with open(manifest_path, "w", encoding="utf-8") as f:
                json.dump(manifest, f, indent=2)
        except Exception:
            pass
    return manifest

def resolve_font_name(font_query: Any, anim_preset: str = "") -> str:
    """Petakan nama alias font ke internal family name yang dibaca libass."""
    manifest = load_font_manifest()
    alias_map = manifest.get("alias_map", {})
    if font_query:
        clean = str(font_query).strip().lower()
        if clean in alias_map:
            return alias_map[clean]
        for al, real_name in alias_map.items():
            if clean in al or al in clean:
                return real_name
        return str(font_query).strip()

    # Default per preset animasi
    if anim_preset == "cinematic_fade":
        return alias_map.get("serif", "Playfair Display")
    elif anim_preset == "slide_up":
        return alias_map.get("sans", "Plus Jakarta Sans")
    elif anim_preset in ("karaoke_word", "pop_punch"):
        return alias_map.get("bold_sans", "Poppins ExtraBold")
    elif anim_preset == "clean_minimal":
        return alias_map.get("sans", "Plus Jakarta Sans")
    return DEFAULT_FONT_NAME

# ==============================================================================
# AUTO WORD-WRAP HELPER
# ==============================================================================
def auto_wrap_text(text: str, max_chars: int) -> str:
    """Pecah teks otomatis pada spasi jika melebihi max_chars dan belum mengandung \\N."""
    if not text:
        return ""
    if r"\N" in text or r"\n" in text or "\n" in text:
        return text.replace(r"\n", r"\N").replace("\n", r"\N")
    words = text.split(" ")
    lines = []
    curr = []
    curr_len = 0
    for w in words:
        w_len = len(w)
        add_len = w_len + (1 if curr else 0)
        if curr_len + add_len <= max_chars:
            curr.append(w)
            curr_len += add_len
        else:
            if curr:
                lines.append(" ".join(curr))
            curr = [w]
            curr_len = w_len
    if curr:
        lines.append(" ".join(curr))
    return r"\N".join(lines)

# ==============================================================================
# 1. PREFLIGHT VALIDATOR
# ==============================================================================
def validate_plan(plan: Dict[str, Any], plan_file: str) -> bool:
    """Validasi ketat detik ke-0 tanpa menjalankan FFmpeg."""
    print("[1/5] Memvalidasi rencana (Preflight Check)...")

    # A. Periksa bagian output
    output_cfg = plan.get("output", {})
    out_dir = resolve_path(output_cfg.get("out_dir", "./out"))
    os.makedirs(out_dir, exist_ok=True)

    # Cek free space disk >= 1.0 GB
    try:
        usage = shutil.disk_usage(out_dir)
        free_gb = usage.free / (1024 ** 3)
        if free_gb < 1.0:
            print(f"[!] ERROR PREFLIGHT: Sisa storage di {out_dir} hanya {free_gb:.2f} GB (wajib >= 1.0 GB).", file=sys.stderr)
            return False
    except Exception as e:
        print(f"[!] PERINGATAN: Tidak dapat memeriksa kapasitas disk: {e}", file=sys.stderr)

    # B. Periksa fontsdir & manifest
    if not os.path.exists(FONTS_DIR) or not os.listdir(FONTS_DIR):
        print(f"[!] ERROR PREFLIGHT: Direktori font {FONTS_DIR} kosong atau tidak ada.", file=sys.stderr)
        return False
    font_manifest = load_font_manifest()
    known_aliases = set(font_manifest.get("alias_map", {}).keys())

    # C. Periksa Visual Polish global (opsional)
    g_polish = plan.get("visual_polish", {})
    if g_polish and isinstance(g_polish, dict):
        dim = g_polish.get("dim")
        if dim is not None and not (isinstance(dim, (int, float)) and 0.0 <= float(dim) <= 0.6):
            print(f"[!] ERROR PREFLIGHT: visual_polish 'dim' ({dim}) harus angka 0.0 s/d 0.6.", file=sys.stderr)
            return False
        cp = g_polish.get("color_preset")
        if cp is not None and cp not in {"none", "moody_dark", "cinematic_warm", "crisp_clean", "vibrant_gameplay"}:
            print(f"[!] ERROR PREFLIGHT: visual_polish 'color_preset' '{cp}' tidak valid. Pilih dari {{'none', 'moody_dark', 'cinematic_warm', 'crisp_clean', 'vibrant_gameplay'}}.", file=sys.stderr)
            return False
        v_in = g_polish.get("video_fade_in")
        if v_in is not None and float(v_in) < 0.0:
            print(f"[!] ERROR PREFLIGHT: video_fade_in ({v_in}) harus >= 0.0.", file=sys.stderr)
            return False
        v_out = g_polish.get("video_fade_out")
        if v_out is not None and float(v_out) < 0.0:
            print(f"[!] ERROR PREFLIGHT: video_fade_out ({v_out}) harus >= 0.0.", file=sys.stderr)
            return False

    # D. Periksa segmen
    segments = plan.get("segments", [])
    if not segments or not isinstance(segments, list):
        print(f"[!] ERROR PREFLIGHT: 'segments' kosong atau bukan list di {plan_file}.", file=sys.stderr)
        return False

    valid_framings = {"crop", "fit_blur", "fit_black", "split", "blur_fill", "blur_bg"}
    valid_transitions = {"none", "fade", "fadeblack", "slideleft", "slideright", "wipeleft"}

    # Validasi overlays opsional
    overlays = plan.get("overlays", [])
    if overlays:
        if not isinstance(overlays, list):
            print(f"[!] ERROR PREFLIGHT: 'overlays' harus berupa list.", file=sys.stderr)
            return False
        for o_idx, ov in enumerate(overlays, start=1):
            if not isinstance(ov, dict):
                print(f"[!] ERROR PREFLIGHT: Item overlay #{o_idx} bukan object/dict.", file=sys.stderr)
                return False
            o_src = ov.get("src")
            if not o_src or not os.path.exists(resolve_path(o_src)):
                print(f"[!] ERROR PREFLIGHT: File overlay #{o_idx} ('src') tidak ditemukan: {o_src}", file=sys.stderr)
                return False
            o_start = float(ov.get("start", 0.0))
            o_end = float(ov.get("end", 0.0))
            if o_end <= o_start:
                print(f"[!] ERROR PREFLIGHT: Overlay #{o_idx} rentang waktu salah (start={o_start}, end={o_end}).", file=sys.stderr)
                return False

    for idx, seg in enumerate(segments, start=1):
        src = resolve_path(seg.get("source"))
        if not src or not os.path.exists(src):
            print(f"[!] ERROR PREFLIGHT: File sumber segmen #{idx} tidak ditemukan: {seg.get('source')}", file=sys.stderr)
            return False

        meta = probe_file(src)
        dur = meta.get("duration", 0.0)
        start = float(seg.get("start", 0.0))
        end = float(seg.get("end", 0.0))

        ext = os.path.splitext(src)[1].lower()
        is_image_file = ext in {".jpg", ".jpeg", ".png", ".webp"} or not meta.get("has_video")

        valid_motions = {"zoom_in", "zoom_out", "pan_left", "pan_right", "static"}
        mot = seg.get("motion")
        if mot is not None and str(mot).lower() not in valid_motions:
            print(f"[!] ERROR PREFLIGHT: Segmen #{idx} motion '{mot}' tidak valid. Pilih dari {valid_motions}.", file=sys.stderr)
            return False

        if is_image_file:
            seg_dur = float(seg.get("duration", 0.0) or (end - start))
            if seg_dur <= 0.0:
                print(f"[!] ERROR PREFLIGHT: Segmen gambar #{idx} wajib memiliki 'duration' > 0 atau rentang end > start.", file=sys.stderr)
                return False
        else:
            # Cek durasi untuk video
            if meta.get("has_video") and dur > 0:
                if not (0 <= start < end):
                    print(f"[!] ERROR PREFLIGHT: Segmen #{idx} rentang waktu salah (start={start}, end={end}).", file=sys.stderr)
                    return False
                if end > (dur + 0.5):
                    print(f"[!] ERROR PREFLIGHT: Segmen #{idx} end ({end}s) melebihi durasi file ({dur:.2f}s).", file=sys.stderr)
                    return False

        # Cek koordinat framing
        cx = float(seg.get("cx", 0.5))
        cy = float(seg.get("cy", 0.5))
        if not (0.0 <= cx <= 1.0 and 0.0 <= cy <= 1.0):
            print(f"[!] ERROR PREFLIGHT: Segmen #{idx} koordinat fokus (cx={cx}, cy={cy}) di luar batas 0.0-1.0.", file=sys.stderr)
            return False

        framing = seg.get("framing", "crop")
        if framing not in valid_framings:
            print(f"[!] ERROR PREFLIGHT: Segmen #{idx} mode framing '{framing}' tidak valid.", file=sys.stderr)
            return False

        if framing == "split" and seg.get("split_source"):
            ssrc = resolve_path(seg.get("split_source"))
            if not os.path.exists(ssrc):
                print(f"[!] ERROR PREFLIGHT: File split_source segmen #{idx} tidak ditemukan: {ssrc}", file=sys.stderr)
                return False

        # Cek transition segmen
        tr = seg.get("transition")
        if tr is not None and str(tr).lower() not in valid_transitions:
            print(f"[!] ERROR PREFLIGHT: Segmen #{idx} mode transisi '{tr}' tidak valid.", file=sys.stderr)
            return False
        td = seg.get("transition_duration")
        if td is not None:
            td_val = float(td)
            seg_dur = end - start if end > start else dur
            if td_val <= 0 or (seg_dur > 0 and td_val >= seg_dur / 2.0):
                print(f"[!] ERROR PREFLIGHT: Segmen #{idx} transition_duration ({td_val}s) harus > 0 dan < 0.5 * durasi segmen ({seg_dur:.2f}s).", file=sys.stderr)
                return False

        # Cek LUT jika ada
        if seg.get("lut"):
            lpath = resolve_path(seg.get("lut"))
            if not os.path.exists(lpath):
                print(f"[!] ERROR PREFLIGHT: File LUT segmen #{idx} tidak ditemukan: {lpath}", file=sys.stderr)
                return False

    # E. Periksa texts
    valid_animations = {"cinematic_fade", "slide_up", "karaoke_word", "pop_punch", "clean_minimal", "header_hook", "chapter_card", "word_highlight", "bold_pop", "clean_box"}
    texts = plan.get("texts", [])
    for idx, txt in enumerate(texts, start=1):
        x = float(txt.get("x", 0.5))
        y = float(txt.get("y", 0.8))
        size = float(txt.get("size", 0.04))
        if not (0.0 <= x <= 1.0 and 0.0 <= y <= 1.0):
            print(f"[!] ERROR PREFLIGHT: Teks #{idx} koordinat (x={x}, y={y}) di luar batas 0.0-1.0.", file=sys.stderr)
            return False
        if size <= 0:
            print(f"[!] ERROR PREFLIGHT: Teks #{idx} ukuran {size} harus > 0.0.", file=sys.stderr)
            return False
        anim = txt.get("animation")
        if anim is not None and str(anim).lower() not in valid_animations:
            print(f"[!] ERROR PREFLIGHT: Teks #{idx} animasi '{anim}' tidak valid. Pilih dari {valid_animations}.", file=sys.stderr)
            return False
        f_name = txt.get("font")
        if f_name is not None:
            clean_f = str(f_name).strip().lower()
            if clean_f not in known_aliases and not any(clean_f in al for al in known_aliases):
                print(f"[!] PERINGATAN PREFLIGHT: Teks #{idx} font '{f_name}' tidak terdaftar di manifest.json, akan fallback ke default.", file=sys.stderr)

    # F. Periksa audio tracks
    audio_tracks = plan.get("audio_tracks", {})
    if audio_tracks.get("bgm") and audio_tracks["bgm"].get("source"):
        bgm_src = resolve_path(audio_tracks["bgm"]["source"])
        if not os.path.exists(bgm_src):
            print(f"[!] ERROR PREFLIGHT: File BGM tidak ditemukan: {bgm_src}", file=sys.stderr)
            return False

    v_track = audio_tracks.get("voice") or audio_tracks.get("voiceover")
    if v_track and v_track.get("source"):
        v_src = resolve_path(v_track["source"])
        if not os.path.exists(v_src):
            print(f"[!] ERROR PREFLIGHT: File Voice/Voiceover tidak ditemukan: {v_src}", file=sys.stderr)
            return False

    for idx, sfx in enumerate(audio_tracks.get("sfx", []), start=1):
        if sfx.get("source"):
            s_src = resolve_path(sfx["source"])
            if not os.path.exists(s_src):
                print(f"[!] ERROR PREFLIGHT: File SFX #{idx} tidak ditemukan: {s_src}", file=sys.stderr)
                return False

    print("  ✓ Preflight validation sukses. Seluruh parameter dan sumber daya valid.")
    return True

# ==============================================================================
# 2. RESOLUSI & ASPEK RASIO DINAMIS
# ==============================================================================
def parse_dimensions(plan: Dict[str, Any], preview_mode: bool) -> Tuple[int, int, float]:
    """Hitung lebar dan tinggi target kanvas (selalu genap) serta FPS."""
    output_cfg = plan.get("output", {})
    aspect_str = str(output_cfg.get("aspect", "16:9")).lower()
    res_spec = output_cfg.get("resolution", "1080p")
    fps_val = output_cfg.get("fps", 30)

    # Sumber default jika 'source'
    first_src = resolve_path(plan["segments"][0]["source"])
    meta = probe_file(first_src)
    src_w = meta.get("width", 1920) or 1920
    src_h = meta.get("height", 1080) or 1080

    # Rasio aspek
    if aspect_str == "source":
        ratio = src_w / src_h
    elif aspect_str in ["16:9", "16/9"]:
        ratio = 16.0 / 9.0
    elif aspect_str in ["9:16", "9/16"]:
        ratio = 9.0 / 16.0
    elif aspect_str in ["1:1", "1/1"]:
        ratio = 1.0
    elif aspect_str in ["4:5", "4/5"]:
        ratio = 4.0 / 5.0
    elif ":" in aspect_str or "/" in aspect_str:
        sep = ":" if ":" in aspect_str else "/"
        parts = aspect_str.split(sep)
        ratio = float(parts[0]) / float(parts[1])
    else:
        ratio = 16.0 / 9.0

    if preview_mode:
        # Skala preview: sisi pendek dikunci ke 360px
        if ratio >= 1.0:
            target_h = 360
            target_w = int(round(360 * ratio))
        else:
            target_w = 360
            target_h = int(round(360 / ratio))
    else:
        # Mode final / standar
        if isinstance(res_spec, str) and "x" in res_spec.lower():
            w_str, h_str = res_spec.lower().split("x")
            target_w = int(w_str)
            target_h = int(h_str)
        elif res_spec == "source":
            target_w = src_w
            target_h = src_h
        else:
            clean_res = str(res_spec).lower().replace("p", "")
            try:
                short_side = int(clean_res)
            except Exception:
                short_side = 1080

            if ratio >= 1.0:
                target_h = short_side
                target_w = int(round(short_side * ratio))
            else:
                target_w = short_side
                target_h = int(round(short_side / ratio))

    # Pastikan selalu genap
    tw = (target_w // 2) * 2
    th = (target_h // 2) * 2

    # FPS
    if str(fps_val).lower() == "source":
        final_fps = 30.0
    else:
        try:
            final_fps = float(fps_val)
        except Exception:
            final_fps = 30.0

    return tw, th, final_fps

# ==============================================================================
# 3. PER-SEGMENT CHUNKED RENDERER & VISUAL POLISH
# ==============================================================================
def build_segment_filter(
    seg: Dict[str, Any],
    tw: int, th: int,
    sw: int, sh: int,
    has_split: bool,
    ssw: int = 0, ssh: int = 0,
    global_polish: Dict[str, Any] = None,
    fps: float = 30.0,
    eff_dur: float = 1.0,
    is_image: bool = False
) -> str:
    """Membangun string filter FFmpeg untuk 4 mode framing, Ken Burns motion, serta visual polish."""
    fit_mode = str(seg.get("fit_mode", "")).lower()
    framing = seg.get("framing")
    if not framing:
        if fit_mode == "blur_bg" and sw > sh:
            framing = "blur_fill"
        elif fit_mode in ("blur_bg", "blur_fill", "fit_blur", "fit_black", "split", "crop"):
            framing = fit_mode
        else:
            framing = "crop"
    speed = float(seg.get("speed", 1.0))
    lut = seg.get("lut")

    tar = tw / th
    sar = sw / sh if sh > 0 else tar

    cx = float(seg.get("cx", 0.5))
    cy = float(seg.get("cy", 0.5))

    vf_main = ""

    # Ken Burns Motion untuk input gambar statis
    motion = str(seg.get("motion") or ("zoom_in" if is_image else "static")).lower()
    has_motion = is_image and motion in ("zoom_in", "zoom_out", "pan_left", "pan_right")

    if has_motion:
        total_frames = max(1, int(round(eff_dur * fps)))
        tf_expr = str(total_frames)

        if motion == "zoom_in":
            z_expr = f"1.0+0.15*(on/{tf_expr})"
            x_expr = "(iw-iw/zoom)/2"
            y_expr = "(ih-ih/zoom)/2"
        elif motion == "zoom_out":
            z_expr = f"1.15-0.15*(on/{tf_expr})"
            x_expr = "(iw-iw/zoom)/2"
            y_expr = "(ih-ih/zoom)/2"
        elif motion == "pan_left":
            z_expr = "1.12"
            x_expr = f"(iw-iw/zoom)*(1.0-on/{tf_expr})"
            y_expr = "(ih-ih/zoom)/2"
        elif motion == "pan_right":
            z_expr = "1.12"
            x_expr = f"(iw-iw/zoom)*(on/{tf_expr})"
            y_expr = "(ih-ih/zoom)/2"
        else:
            z_expr = "1.0"
            x_expr = "(iw-iw/zoom)/2"
            y_expr = "(ih-ih/zoom)/2"

        if framing in ("blur_fill", "fit_blur", "blur_bg"):
            bw = max(2, (tw // 4) // 2 * 2)
            bh = max(2, (th // 4) // 2 * 2)
            bg_dim = float(seg.get("bg_dim", 0.15))
            dim_filter = f",drawbox=color=black@{bg_dim:.2f}:t=fill" if bg_dim > 0.0 else ""

            if sar >= tar:
                fg_w = tw
                fg_h = max(2, int(round(tw / sar)) // 2 * 2)
            else:
                fg_h = th
                fg_w = max(2, int(round(th * sar)) // 2 * 2)

            pre_scale = f"scale={int(fg_w * 1.3)}:-2:force_original_aspect_ratio=increase"
            vf_bg = f"[0:v]scale={bw}:{bh}:force_original_aspect_ratio=increase,crop={bw}:{bh},boxblur=6:2{dim_filter},scale={tw}:{th}:flags=bilinear[bg_blur]"
            vf_fg = f"[0:v]{pre_scale},zoompan=z='{z_expr}':x='{x_expr}':y='{y_expr}':d={total_frames}:s={fg_w}x{fg_h}:fps={fps}[fg_s]"
            vf_main = f"{vf_bg};{vf_fg};[bg_blur][fg_s]overlay=(W-w)/2:(H-h)/2"

        elif framing == "fit_black":
            if sar >= tar:
                fg_w = tw
                fg_h = max(2, int(round(tw / sar)) // 2 * 2)
            else:
                fg_h = th
                fg_w = max(2, int(round(th * sar)) // 2 * 2)
            pre_scale = f"scale={int(fg_w * 1.3)}:-2:force_original_aspect_ratio=increase"
            vf_main = f"{pre_scale},zoompan=z='{z_expr}':x='{x_expr}':y='{y_expr}':d={total_frames}:s={fg_w}x{fg_h}:fps={fps},pad={tw}:{th}:(ow-iw)/2:(oh-ih)/2:black"

        else: # crop (default)
            pre_scale = f"scale={int(tw * 1.3)}:-2:force_original_aspect_ratio=increase"
            vf_main = f"{pre_scale},zoompan=z='{z_expr}':x='{x_expr}':y='{y_expr}':d={total_frames}:s={tw}x{th}:fps={fps}"

    elif framing == "crop":
        if sar > tar:
            cw = int(round(sh * tar))
            ch = sh
            cw = min(cw, sw)
            max_x = sw - cw
            x = int(round(max_x * cx))
            y = 0
        elif sar < tar:
            cw = sw
            ch = int(round(sw / tar))
            ch = min(ch, sh)
            max_y = sh - ch
            x = 0
            y = int(round(max_y * cy))
        else:
            cw, ch, x, y = sw, sh, 0, 0

        cw = max(2, (cw // 2) * 2)
        ch = max(2, (ch // 2) * 2)
        x = max(0, min(x, sw - cw))
        y = max(0, min(y, sh - ch))
        vf_main = f"crop={cw}:{ch}:{x}:{y},scale={tw}:{th}"

    elif framing in ("blur_fill", "fit_blur", "blur_bg"):
        # Anti-OOM layout hemat RAM (downscale-blur-upscale)
        bw = max(2, (tw // 4) // 2 * 2)
        bh = max(2, (th // 4) // 2 * 2)
        bg_dim = float(seg.get("bg_dim", 0.15))
        dim_filter = f",drawbox=color=black@{bg_dim:.2f}:t=fill" if bg_dim > 0.0 else ""
        vf_main = (
            f"[0:v]split=2[fg][bg];"
            f"[bg]scale={bw}:{bh}:force_original_aspect_ratio=increase,crop={bw}:{bh},boxblur=6:2{dim_filter},scale={tw}:{th}:flags=bilinear[bg_blur];"
            f"[fg]scale={tw}:-2:force_original_aspect_ratio=decrease[fg_s];"
            f"[bg_blur][fg_s]overlay=(W-w)/2:(H-h)/2"
        )

    elif framing == "fit_black":
        vf_main = f"scale={tw}:{th}:force_original_aspect_ratio=decrease,pad={tw}:{th}:(ow-iw)/2:(oh-ih)/2:black"

    elif framing == "split":
        h1 = (th // 2) // 2 * 2
        h2 = th - h1
        tar_split = tw / h1

        if sar > tar_split:
            cw1 = int(round(sh * tar_split))
            ch1 = sh
            cw1 = min(cw1, sw)
            x1 = int(round((sw - cw1) * cx))
            y1 = 0
        else:
            cw1 = sw
            ch1 = int(round(sw / tar_split))
            ch1 = min(ch1, sh)
            x1 = 0
            y1 = int(round((sh - ch1) * cy))

        cw1 = max(2, (cw1 // 2) * 2)
        ch1 = max(2, (ch1 // 2) * 2)
        x1 = max(0, min(x1, sw - cw1))
        y1 = max(0, min(y1, sh - ch1))

        if has_split:
            scx = float(seg.get("split_cx", 0.5))
            scy = float(seg.get("split_cy", 0.5))
            ssar = ssw / ssh if ssh > 0 else tar_split

            if ssar > tar_split:
                cw2 = int(round(ssh * tar_split))
                ch2 = ssh
                cw2 = min(cw2, ssw)
                x2 = int(round((ssw - cw2) * scx))
                y2 = 0
            else:
                cw2 = ssw
                ch2 = int(round(ssw / tar_split))
                ch2 = min(ch2, ssh)
                x2 = 0
                y2 = int(round((ssh - ch2) * scy))

            cw2 = max(2, (cw2 // 2) * 2)
            ch2 = max(2, (ch2 // 2) * 2)
            x2 = max(0, min(x2, ssw - cw2))
            y2 = max(0, min(y2, ssh - ch2))

            vf_main = (
                f"[0:v]crop={cw1}:{ch1}:{x1}:{y1},scale={tw}:{h1}[v_top];"
                f"[1:v]crop={cw2}:{ch2}:{x2}:{y2},scale={tw}:{h2}[v_bot];"
                f"[v_top][v_bot]vstack"
            )
        else:
            scx = float(seg.get("split_cx", 0.5))
            scy = float(seg.get("split_cy", 0.8))
            x2 = int(round((sw - cw1) * scx)) if sar > tar_split else 0
            y2 = int(round((sh - ch1) * scy)) if sar <= tar_split else 0
            x2 = max(0, min(x2, sw - cw1))
            y2 = max(0, min(y2, sh - ch1))

            vf_main = (
                f"[0:v]split[v1][v2];"
                f"[v1]crop={cw1}:{ch1}:{x1}:{y1},scale={tw}:{h1}[v_top];"
                f"[v2]crop={cw1}:{ch1}:{x2}:{y2},scale={tw}:{h2}[v_bot];"
                f"[v_top][v_bot]vstack"
            )

    extras = []
    if speed != 1.0:
        extras.append(f"setpts={1.0 / speed:.6f}*PTS")
    if lut:
        lut_res = resolve_path(lut)
        extras.append(f"lut3d='{lut_res}'")

    # Visual Polish (per segmen atau global)
    polish = dict(global_polish or {})
    polish.update(seg.get("visual_polish", {}) or {})

    dim_val = float(polish.get("dim", 0.0) or 0.0)
    if 0.0 < dim_val <= 0.6:
        extras.append(f"drawbox=x=0:y=0:w=iw:h=ih:color=black@{dim_val:.2f}:t=fill")

    vignette_val = polish.get("vignette")
    if vignette_val:
        if isinstance(vignette_val, (int, float)) and vignette_val > 0:
            ang = round(float((math.pi / 4.0) * min(1.0, max(0.1, float(vignette_val)))), 4)
            extras.append(f"vignette={ang}")
        else:
            extras.append("vignette=PI/4")

    color_preset = str(polish.get("color_preset", "none")).lower()
    if color_preset == "moody_dark":
        extras.append("eq=saturation=0.88:contrast=1.05:brightness=-0.03,colorbalance=rs=-0.04:gs=0.0:bs=0.05")
    elif color_preset == "cinematic_warm":
        extras.append("colorbalance=rh=0.08:gh=0.03:bh=-0.06:rs=0.04:bs=-0.04,eq=contrast=1.08:saturation=1.05")
    elif color_preset == "crisp_clean":
        extras.append("eq=contrast=1.1:saturation=1.12:brightness=0.02,unsharp=5:5:0.5:5:5:0.0")
    elif color_preset == "vibrant_gameplay":
        extras.append("eq=contrast=1.12:saturation=1.20:brightness=0.01,unsharp=5:5:0.6:5:5:0.0")

    extras.append("setsar=1")
    extra_str = ",".join(extras)

    return f"{vf_main},{extra_str}"

def render_segment(
    seg: Dict[str, Any],
    idx: int,
    tw: int, th: int, fps: float,
    preset: str, crf: int,
    tmp_dir: str,
    global_polish: Dict[str, Any] = None
) -> str:
    """Render 1 segmen secara terpisah untuk mencegah OOM (RAM safe)."""
    src = resolve_path(seg["source"])
    meta = probe_file(src)
    start = float(seg.get("start", 0.0))
    end = float(seg.get("end", 0.0))
    dur = float(seg.get("duration", 0.0) or (end - start) or 1.0)
    speed = float(seg.get("speed", 1.0))
    eff_dur = dur / speed

    out_seg = os.path.join(tmp_dir, f"seg_{idx:03d}.mp4")

    is_image = not meta.get("has_video") or src.lower().endswith((".jpg", ".jpeg", ".png", ".webp"))
    motion = str(seg.get("motion") or ("zoom_in" if is_image else "static")).lower()
    has_motion = is_image and motion in ("zoom_in", "zoom_out", "pan_left", "pan_right")

    has_split = False
    split_src = None
    ssw, ssh = 0, 0
    if seg.get("framing") == "split" and seg.get("split_source"):
        split_src = resolve_path(seg["split_source"])
        smeta = probe_file(split_src)
        ssw = smeta.get("width", 1280)
        ssh = smeta.get("height", 720)
        has_split = True

    vf_str = build_segment_filter(
        seg, tw, th,
        meta.get("width", 1920), meta.get("height", 1080),
        has_split, ssw, ssh,
        global_polish=global_polish,
        fps=fps,
        eff_dur=eff_dur,
        is_image=is_image
    )

    cmd = [get_ffmpeg_bin(), "-nostdin", "-y"]

    if is_image:
        if has_motion:
            cmd.extend(["-i", src])
        else:
            cmd.extend(["-loop", "1", "-t", f"{eff_dur:.4f}", "-i", src])
    else:
        cmd.extend(["-ss", f"{start:.4f}", "-t", f"{dur:.4f}", "-i", src])

    if has_split:
        cmd.extend(["-ss", f"{float(seg.get('split_start', 0.0)):.4f}", "-t", f"{dur:.4f}", "-i", split_src])

    # Audio handling: pastikan selalu ada audio stereo 48kHz
    if meta.get("has_audio") and not is_image:
        if speed == 1.0:
            af_str = "aresample=48000,aformat=channel_layouts=stereo:sample_fmts=fltp"
        else:
            atempos = []
            cur_s = speed
            while cur_s > 2.0:
                atempos.append("atempo=2.0")
                cur_s /= 2.0
            while cur_s < 0.5:
                atempos.append("atempo=0.5")
                cur_s /= 0.5
            atempos.append(f"atempo={cur_s:.4f}")
            af_str = f"{','.join(atempos)},aresample=48000,aformat=channel_layouts=stereo:sample_fmts=fltp"

        cmd.extend([
            "-filter_complex", f"{vf_str}[v_out];[0:a]{af_str}[a_out]",
            "-map", "[v_out]", "-map", "[a_out]"
        ])
    else:
        cmd.extend([
            "-f", "lavfi", "-t", f"{eff_dur:.4f}", "-i", "anullsrc=channel_layout=stereo:sample_rate=48000",
            "-filter_complex", f"{vf_str}[v_out]",
            "-map", "[v_out]",
            "-map", f"{1 if not has_split else 2}:a"
        ])

    cmd.extend([
        "-threads", "2",
        "-r", f"{fps:.2f}",
        "-c:v", "libx264",
        "-preset", preset,
        "-crf", str(crf),
        "-pix_fmt", "yuv420p",
        "-c:a", "aac",
        "-b:a", "192k",
        "-t", f"{eff_dur:.4f}",
        out_seg
    ])

    res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if res.returncode != 0:
        print(f"[!] Gagal render segmen #{idx}: {res.stderr}", file=sys.stderr)
        raise RuntimeError(f"FFmpeg segment error at #{idx}")

    return out_seg

# ==============================================================================
# 4. SUBTITLE ASS & SRT GENERATOR (5 PRESET KREATOR PRO & AUTO-WRAP)
# ==============================================================================
def format_ass_time(sec: float) -> str:
    """Format waktu untuk event ASS: h:mm:ss.cc"""
    hrs = int(sec // 3600)
    mins = int((sec % 3600) // 60)
    secs = int(sec % 60)
    csec = int(round((sec - int(sec)) * 100))
    if csec >= 100:
        csec = 99
    return f"{hrs}:{mins:02d}:{secs:02d}.{csec:02d}"

def format_srt_time(sec: float) -> str:
    """Format waktu untuk SRT: hh:mm:ss,mmm"""
    hrs = int(sec // 3600)
    mins = int((sec % 3600) // 60)
    secs = int(sec % 60)
    msec = int(round((sec - int(sec)) * 1000))
    if msec >= 1000:
        msec = 999
    return f"{hrs:02d}:{mins:02d}:{secs:02d},{msec:03d}"

def hex_to_ass_color(hex_str: str) -> str:
    """Konversi hex #RRGGBB menjadi ASS &H00BBGGRR&."""
    hex_str = hex_str.strip().lstrip("#")
    if len(hex_str) == 6:
        r = hex_str[0:2]
        g = hex_str[2:4]
        b = hex_str[4:6]
        return f"&H00{b}{g}{r}&"
    return "&H00FFFFFF&"

def apply_multiline_styling(text_str: str, line_colors: Optional[List[str]] = None, line_scales: Optional[List[float]] = None) -> str:
    """Terapkan pewarnaan & skala font per baris untuk teks bertingkat."""
    if not line_colors and not line_scales:
        return text_str
    lines = text_str.split(r"\N")
    styled_lines = []
    for idx, l in enumerate(lines):
        prefix_tags = []
        if line_colors and idx < len(line_colors):
            c_tag = hex_to_ass_color(line_colors[idx])
            prefix_tags.append(f"\\1c{c_tag}")
        if line_scales and idx < len(line_scales):
            sc = int(round(float(line_scales[idx]) * 100))
            prefix_tags.append(f"\\fscx{sc}\\fscy{sc}")
        if prefix_tags:
            styled_lines.append(f"{{{ ''.join(prefix_tags) }}}{l}")
        else:
            styled_lines.append(l)
    return r"\N".join(styled_lines)

def generate_ass(texts: List[Dict[str, Any]], tw: int, th: int, out_file: str, watermark: Optional[Any] = None):
    """Buat file .ass proporsional dengan font eksplisit, preset Shorts, dan watermark opsional."""
    is_vertical = tw < th
    default_max_chars = 24 if is_vertical else 40
    margin_lr = max(20, int(round(tw * 0.08)))
    base_fontsize = max(14, int(round(th * 0.038)))

    content = f"""[Script Info]
ScriptType: v4.00+
PlayResX: {tw}
PlayResY: {th}
ScaledBorderAndShadow: yes

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Default,{DEFAULT_FONT_NAME},{base_fontsize},&H00FFFFFF,&H000000FF,&H00000000,&H80000000,0,0,0,0,100,100,0,0,1,2,1,2,{margin_lr},{margin_lr},20,1
Style: KaraokeStyle,Poppins ExtraBold,{base_fontsize},&H0000E5FF,&H00FFFFFF,&H00000000,&H80000000,-1,0,0,0,100,100,0,0,1,2,1,2,{margin_lr},{margin_lr},20,1
Style: CleanBox,{DEFAULT_FONT_NAME},{base_fontsize},&H00FFFFFF,&H000000FF,&HAA000000,&HAA000000,-1,0,0,0,100,100,0,0,3,8,0,2,{margin_lr},{margin_lr},20,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
"""
    for txt in texts:
        s_time_sec = float(txt.get("start", 0.0))
        e_time_sec = float(txt.get("end", 1.0))
        s_time = format_ass_time(s_time_sec)
        e_time = format_ass_time(e_time_sec)
        raw_text = txt.get("text") or txt.get("content", "")
        max_chars = int(txt.get("max_chars") or default_max_chars)
        wrapped_text = auto_wrap_text(raw_text, max_chars)

        anim = str(txt.get("animation") or txt.get("preset") or "").lower()
        font_name = resolve_font_name(txt.get("font"), anim)

        align_spec = str(txt.get("alignment") or txt.get("align") or "").lower()
        if align_spec in ("top", "8"):
            an_tag = r"\an8"
            def_y = 0.15
        elif align_spec in ("center", "middle", "5"):
            an_tag = r"\an5"
            def_y = 0.50
        elif align_spec in ("bottom", "2"):
            an_tag = r"\an2"
            def_y = 0.78
        else:
            an_tag = r"\an5"
            def_y = 0.78

        y_ratio = float(txt.get("y")) if txt.get("y") is not None else def_y
        x_ratio = float(txt.get("x", 0.5))
        x_px = int(round(x_ratio * tw))
        y_px = int(round(y_ratio * th))
        fs = max(12, int(round(float(txt.get("size", 0.038)) * th)))
        c_ass = hex_to_ass_color(txt.get("color", "#FFFFFF"))
        out_ass = hex_to_ass_color(txt.get("outline_color", "#000000"))

        line_colors = txt.get("line_colors")
        line_scales = txt.get("line_scales")
        styled_text = apply_multiline_styling(wrapped_text, line_colors, line_scales)

        if anim == "header_hook":
            bord = float(txt.get("outline_width", 3.2))
            shad = float(txt.get("shadow", 1.8))
            y_px = int(round(float(txt.get("y", 0.40)) * th))
            fs = max(14, int(round(float(txt.get("size", 0.052)) * th)))
            default_colors = line_colors or ["#FFEE00", "#FFFFFF", "#FF2222"]
            hook_styled = apply_multiline_styling(wrapped_text, default_colors, line_scales)
            tags = (
                f"{an_tag}\\pos({x_px},{y_px})\\fn{font_name}\\fs{fs}\\1c{c_ass}\\3c{out_ass}"
                f"\\bord{bord}\\shad{shad}\\fscx90\\fscy90\\t(0,110,\\fscx106\\fscy106)\\t(110,200,\\fscx100\\fscy100)"
            )
            content += f"Dialogue: 0,{s_time},{e_time},Default,,0,0,0,,{{{tags}}}{hook_styled}\n"

        elif anim == "chapter_card":
            bord = float(txt.get("outline_width", 3.0))
            shad = float(txt.get("shadow", 2.0))
            y_px = int(round(float(txt.get("y", 0.50)) * th))
            fs = max(16, int(round(float(txt.get("size", 0.056)) * th)))
            tags = (
                f"\\an5\\pos({x_px},{y_px})\\fn{font_name}\\fs{fs}\\1c{c_ass}\\3c{out_ass}"
                f"\\bord{bord}\\shad{shad}\\fad(180,180)\\fsp1"
            )
            content += f"Dialogue: 0,{s_time},{e_time},Default,,0,0,0,,{{{tags}}}{styled_text}\n"

        elif anim == "cinematic_fade":
            lsp = int(txt.get("letter_spacing", 2))
            shad = float(txt.get("shadow", 2.2))
            bord = float(txt.get("outline_width", 0.6))
            tags = (
                f"{an_tag}\\pos({x_px},{y_px})\\fn{font_name}\\fs{fs}\\1c{c_ass}\\3c{out_ass}"
                f"\\4c&H80000000&\\bord{bord}\\shad{shad}\\fsp{lsp}\\fad(350,350)"
                f"\\blur1.2\\t(0,250,\\blur0.3)"
            )
            content += f"Dialogue: 0,{s_time},{e_time},Default,,0,0,0,,{{{tags}}}{styled_text}\n"

        elif anim == "slide_up":
            y_start = y_px + int(round(th * 0.025))
            shad = float(txt.get("shadow", 1.8))
            bord = float(txt.get("outline_width", 1.0))
            tags = (
                f"{an_tag}\\move({x_px},{y_start},{x_px},{y_px},0,280)\\fn{font_name}\\fs{fs}"
                f"\\1c{c_ass}\\3c{out_ass}\\4c&H80000000&\\bord{bord}\\shad{shad}\\fad(250,250)"
            )
            content += f"Dialogue: 0,{s_time},{e_time},Default,,0,0,0,,{{{tags}}}{styled_text}\n"

        elif anim in ("karaoke_word", "word_highlight"):
            dur_cs = max(10, int(round((e_time_sec - s_time_sec) * 100)))
            highlight_c = hex_to_ass_color(txt.get("highlight_color", "#FFFF00"))
            base_c = hex_to_ass_color(txt.get("color", "#FFFFFF"))
            bord = float(txt.get("outline_width", 2.0))
            shad = float(txt.get("shadow", 1.2))

            words_input = txt.get("words")
            if words_input and isinstance(words_input, list):
                k_parts = []
                for w_item in words_input:
                    w_str = w_item.get("word", "")
                    w_cs = max(5, int(round((float(w_item.get("end", 0)) - float(w_item.get("start", 0))) * 100)))
                    k_parts.append(f"{{\\kf{w_cs}}}{w_str}")
                karaoke_body = " ".join(k_parts)
            else:
                words_tokens = wrapped_text.replace(r"\N", r" \N ").split(" ")
                words_tokens = [w for w in words_tokens if w]
                non_wrap_tokens = [w for w in words_tokens if w != r"\N"]
                total_chars = sum(len(w) for w in non_wrap_tokens) or 1
                k_parts = []
                for token in words_tokens:
                    if token == r"\N":
                        k_parts.append(r"\N")
                    else:
                        w_cs = max(6, int(round(dur_cs * (len(token) / total_chars))))
                        k_parts.append(f"{{\\kf{w_cs}}}{token}")
                karaoke_body = " ".join(k_parts).replace(r" \N ", r"\N")

            tags = (
                f"{an_tag}\\pos({x_px},{y_px})\\fn{font_name}\\fs{fs}\\1c{highlight_c}"
                f"\\2c{base_c}\\3c{out_ass}\\bord{bord}\\shad{shad}"
            )
            content += f"Dialogue: 0,{s_time},{e_time},KaraokeStyle,,0,0,0,,{{{tags}}}{karaoke_body}\n"

        elif anim in ("pop_punch", "bold_pop"):
            bord = float(txt.get("outline_width", 2.8))
            shad = float(txt.get("shadow", 1.5))
            tags = (
                f"{an_tag}\\pos({x_px},{y_px})\\fn{font_name}\\fs{fs}\\b1\\1c{c_ass}\\3c{out_ass}"
                f"\\bord{bord}\\shad{shad}\\fscx85\\fscy85\\t(0,110,\\fscx106\\fscy106)\\t(110,200,\\fscx100\\fscy100)"
            )
            content += f"Dialogue: 0,{s_time},{e_time},Default,,0,0,0,,{{{tags}}}{styled_text}\n"

        elif anim == "clean_box":
            box_c = hex_to_ass_color(txt.get("box_color", "#000000"))
            bord = float(txt.get("outline_width", 8.0))
            tags = (
                f"{an_tag}\\pos({x_px},{y_px})\\fn{font_name}\\fs{fs}\\b1\\1c{c_ass}"
                f"\\3c&HAA{box_c[4:]}&\\4c&HAA{box_c[4:]}&\\bord{bord}\\shad0\\fad(100,100)"
            )
            content += f"Dialogue: 0,{s_time},{e_time},CleanBox,,0,0,0,,{{{tags}}}{styled_text}\n"

        elif anim == "clean_minimal":
            bord = float(txt.get("outline_width", 1.0))
            shad = float(txt.get("shadow", 1.0))
            tags = (
                f"{an_tag}\\pos({x_px},{y_px})\\fn{font_name}\\fs{fs}\\1c{c_ass}\\3c{out_ass}"
                f"\\bord{bord}\\shad{shad}\\fad(150,150)"
            )
            content += f"Dialogue: 0,{s_time},{e_time},Default,,0,0,0,,{{{tags}}}{styled_text}\n"

        else:
            bord = int(txt.get("outline_width", 3))
            tags = f"{an_tag}\\pos({x_px},{y_px})\\fn{font_name}\\fs{fs}\\1c{c_ass}\\3c{out_ass}\\bord{bord}"
            content += f"Dialogue: 0,{s_time},{e_time},Default,,0,0,0,,{{{tags}}}{styled_text}\n"

    # Watermark teks opsional
    if watermark:
        wm_cfg = {"text": str(watermark)} if isinstance(watermark, str) else watermark
        if isinstance(wm_cfg, dict) and wm_cfg.get("text"):
            wm_text = str(wm_cfg["text"]).replace("\n", " ")
            wm_x = int(round(float(wm_cfg.get("x", 0.5)) * tw))
            wm_y = int(round(float(wm_cfg.get("y", 0.08)) * th))
            wm_fs = max(10, int(round(float(wm_cfg.get("size", 0.024)) * th)))
            wm_alpha = max(0, min(255, int(round((1.0 - float(wm_cfg.get("opacity", 0.55))) * 255))))
            wm_alpha_hex = f"&H{wm_alpha:02X}"
            wm_tags = f"\\an5\\pos({wm_x},{wm_y})\\fn{DEFAULT_FONT_NAME}\\fs{wm_fs}\\1c&HFFFFFF&\\1a{wm_alpha_hex}\\3a{wm_alpha_hex}\\bord1.5\\shad0"
            content += f"Dialogue: 1,0:00:00.00,99:00:00.00,Default,,0,0,0,,{{{wm_tags}}}{wm_text}\n"

    with open(out_file, "w", encoding="utf-8") as f:
        f.write(content)

def generate_srt(texts: List[Dict[str, Any]], out_file: str):
    """Buat file .srt standar untuk ekspor stems."""
    lines = []
    for idx, txt in enumerate(texts, start=1):
        s_time = format_srt_time(float(txt.get("start", 0.0)))
        e_time = format_srt_time(float(txt.get("end", 1.0)))
        raw_text = txt.get("text", "").replace(r"\N", " ").replace("\n", " ")
        lines.append(f"{idx}\n{s_time} --> {e_time}\n{raw_text}\n")
    with open(out_file, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))

# ==============================================================================
# 5. AUDIO MIXING & DUAL AUDIO PIPELINE (DENGAN POLISH AUDIO & VIDEO FADES)
# ==============================================================================
def mix_and_finalize(
    plan: Dict[str, Any],
    concat_video: str,
    ass_path: str,
    tw: int, th: int,
    preset: str, crf: int,
    out_dir: str,
    tmp_dir: str
):
    """Penggabungan visual dengan subtitle ASS serta mixing audio (Dual Audio & Stems)."""
    output_cfg = plan.get("output", {})
    audio_cfg = plan.get("audio_tracks", {})
    audio_strategy = output_cfg.get("audio_strategy", "viral_sync")
    dual_audio = output_cfg.get("dual_audio", False)
    export_stems = output_cfg.get("export_stems", False)
    base_name = output_cfg.get("filename", "final.mp4")
    if base_name.endswith(".mp4"):
        base_name = base_name[:-4]

    has_ass = ass_path and os.path.exists(ass_path) and os.path.getsize(ass_path) > 100
    concat_meta = probe_file(concat_video)
    concat_dur = float(concat_meta.get("duration", 0.0))

    # Global Video Polish: video_fade_in dan video_fade_out
    g_polish = plan.get("visual_polish", {}) or {}
    v_fade_in = float(g_polish.get("video_fade_in", 0.0) or 0.0)
    v_fade_out = float(g_polish.get("video_fade_out", 0.0) or 0.0)

    # Overlays support (meme, stickers, arrows, avatars)
    overlays = plan.get("overlays", []) or []
    valid_overlays = []
    overlay_inputs = []
    for ov in overlays:
        if isinstance(ov, dict) and ov.get("src"):
            s_res = resolve_path(ov["src"])
            if os.path.exists(s_res):
                valid_overlays.append(ov)
                ext = os.path.splitext(s_res)[1].lower()
                if ext in {".png", ".jpg", ".jpeg", ".webp", ".bmp", ".gif"}:
                    overlay_inputs.extend(["-loop", "1", "-t", f"{concat_dur:.4f}", "-i", s_res])
                else:
                    overlay_inputs.extend(["-i", s_res])

    num_overlays = len(valid_overlays)

    v_nodes = []
    cur_v = "[0:v]"
    if v_fade_in > 0:
        v_nodes.append(f"{cur_v}fade=t=in:st=0:d={v_fade_in:.4f}[v_fade_in]")
        cur_v = "[v_fade_in]"
    if v_fade_out > 0 and concat_dur > v_fade_out:
        v_nodes.append(f"{cur_v}fade=t=out:st={concat_dur - v_fade_out:.4f}:d={v_fade_out:.4f}[v_fade_out]")
        cur_v = "[v_fade_out]"

    for k, ov in enumerate(valid_overlays):
        ov_in = f"[{1 + k}:v]"
        st = float(ov.get("start", 0.0))
        et = float(ov.get("end", concat_dur))
        w_spec = ov.get("width", 0.25)
        h_spec = ov.get("height")
        x_spec = ov.get("x", "center")
        y_spec = ov.get("y", "center")
        fade_dur = float(ov.get("fade", 0.0) or 0.0)

        if isinstance(w_spec, (int, float)) and 0.0 < float(w_spec) <= 1.0:
            target_w = max(2, int(round(tw * float(w_spec))) // 2 * 2)
        else:
            target_w = max(2, int(float(w_spec)) // 2 * 2)

        if h_spec and isinstance(h_spec, (int, float)) and 0.0 < float(h_spec) <= 1.0:
            target_h = max(2, int(round(th * float(h_spec))) // 2 * 2)
            scale_filter = f"scale={target_w}:{target_h}"
        else:
            scale_filter = f"scale={target_w}:-2"

        fade_filter = ""
        dur_ov = et - st
        if fade_dur > 0.0 and dur_ov > 0.0:
            actual_fade = min(fade_dur, dur_ov / 2.0)
            fade_filter = f",fade=t=in:st={st:.3f}:d={actual_fade:.3f}:alpha=1,fade=t=out:st={et - actual_fade:.3f}:d={actual_fade:.3f}:alpha=1"

        ov_ready = f"[ov_{k}_ready]"
        v_nodes.append(f"{ov_in}format=rgba,{scale_filter}{fade_filter}{ov_ready}")

        if x_spec == "center":
            x_pos = "(W-w)/2"
        elif x_spec == "left":
            x_pos = "0.05*W"
        elif x_spec == "right":
            x_pos = "W-w-0.05*W"
        elif isinstance(x_spec, (int, float)) and 0.0 <= float(x_spec) <= 1.0:
            x_pos = f"(W*{float(x_spec):.4f}-w/2)"
        else:
            x_pos = str(x_spec)

        if y_spec == "center":
            y_pos = "(H-h)/2"
        elif y_spec == "top":
            y_pos = "0.05*H"
        elif y_spec == "bottom":
            y_pos = "H-h-0.08*H"
        elif isinstance(y_spec, (int, float)) and 0.0 <= float(y_spec) <= 1.0:
            y_pos = f"(H*{float(y_spec):.4f}-h/2)"
        else:
            y_pos = str(y_spec)

        next_v = f"[v_ov_{k}]"
        enable_str = f":enable='between(t,{st:.3f},{et:.3f})'"
        v_nodes.append(f"{cur_v}{ov_ready}overlay=x={x_pos}:y={y_pos}{enable_str}:eof_action=repeat:format=auto{next_v}")
        cur_v = next_v

    if has_ass:
        v_nodes.append(f"{cur_v}ass=filename='{ass_path}':fontsdir='{FONTS_DIR}'[v_final]")
    else:
        v_nodes.append(f"{cur_v}null[v_final]")

    vf_main_filter = ";".join(v_nodes)

    def parse_vol(val: Any, default: float = 1.0) -> float:
        if val is None:
            return default
        if isinstance(val, (int, float)):
            return max(0.0, float(val))
        s = str(val).strip().lower()
        if s.endswith("db"):
            try:
                db = float(s[:-2].strip())
                return max(0.0, 10.0 ** (db / 20.0))
            except Exception:
                return default
        try:
            return max(0.0, float(s))
        except Exception:
            return default

    bgm_info = audio_cfg.get("bgm", {})
    voice_info = audio_cfg.get("voice") or audio_cfg.get("voiceover") or {}
    sfx_list = audio_cfg.get("sfx", [])
    clip_vol = parse_vol(audio_cfg.get("clip_audio_volume", 1.0), 1.0)
    auto_ducking = audio_cfg.get("auto_ducking", True)

    has_bgm = bool(bgm_info and bgm_info.get("source") and os.path.exists(resolve_path(bgm_info["source"])))
    has_voice = bool(voice_info and voice_info.get("source") and os.path.exists(resolve_path(voice_info["source"])))
    has_sfx = bool(sfx_list and any(os.path.exists(resolve_path(s.get("source", ""))) for s in sfx_list))

    def build_audio_filter(include_bgm: bool, start_idx: int = 1) -> Tuple[List[str], str, str]:
        inputs = []
        filter_nodes = []
        mix_inputs = []

        in_idx = start_idx
        duck_trigger = None

        # 1. Clip Audio (dari video latar)
        if clip_vol > 0.001:
            pan_clip = "pan=stereo|c0=c0|c1=c0," if (concat_meta.get("channels", 2) or 2) == 1 else ""
            filter_nodes.append(f"[0:a]{pan_clip}volume={clip_vol:.4f},aresample=48000,aformat=channel_layouts=stereo[a_clip_base]")
            if not has_voice and (include_bgm and has_bgm and auto_ducking):
                filter_nodes.append("[a_clip_base]asplit=2[clip_sc][clip_mix]")
                mix_inputs.append("[clip_mix]")
                duck_trigger = "[clip_sc]"
            else:
                mix_inputs.append("[a_clip_base]")

        # 2. Voice / Voiceover
        if has_voice:
            v_src = resolve_path(voice_info["source"])
            v_start = int(float(voice_info.get("start", 0.0)) * 1000)
            if "volume_db" in voice_info:
                try: v_vol = 10.0 ** (float(voice_info["volume_db"]) / 20.0)
                except Exception: v_vol = 1.0
            else:
                v_vol = parse_vol(voice_info.get("volume", 1.0), 1.0)

            pan_vo = "pan=stereo|c0=c0|c1=c0," if (probe_file(v_src).get("channels", 2) or 2) == 1 else ""
            inputs.extend(["-i", v_src])
            filter_nodes.append(f"[{in_idx}:a]{pan_vo}adelay={v_start}|{v_start},volume={v_vol:.4f},aresample=48000,aformat=channel_layouts=stereo[a_voice_base]")
            if include_bgm and has_bgm and auto_ducking:
                filter_nodes.append("[a_voice_base]asplit=2[vo_sc][vo_mix]")
                mix_inputs.append("[vo_mix]")
                duck_trigger = "[vo_sc]"
            else:
                mix_inputs.append("[a_voice_base]")
            in_idx += 1

        # 3. SFX
        for s_item in sfx_list:
            s_src_raw = s_item.get("source")
            if s_src_raw and os.path.exists(resolve_path(s_src_raw)):
                s_src = resolve_path(s_src_raw)
                s_start = int(float(s_item.get("start", 0.0)) * 1000)
                s_vol = parse_vol(s_item.get("volume", 1.0), 1.0)
                pan_sfx = "pan=stereo|c0=c0|c1=c0," if (probe_file(s_src).get("channels", 2) or 2) == 1 else ""
                inputs.extend(["-i", s_src])
                s_label = f"[a_sfx_{in_idx}]"
                filter_nodes.append(f"[{in_idx}:a]{pan_sfx}adelay={s_start}|{s_start},volume={s_vol:.4f},aresample=48000,aformat=channel_layouts=stereo{s_label}")
                mix_inputs.append(s_label)
                in_idx += 1

        # 4. BGM (Audio Polish: fade_in & fade_out)
        if include_bgm and has_bgm:
            b_src = resolve_path(bgm_info["source"])
            b_start = int(float(bgm_info.get("start", 0.0)) * 1000)
            b_offset = float(bgm_info.get("offset", 0.0))
            if "volume_db" in bgm_info:
                try: b_vol = 10.0 ** (float(bgm_info["volume_db"]) / 20.0)
                except Exception: b_vol = 0.4
            elif "bgm_volume_db" in bgm_info:
                try: b_vol = 10.0 ** (float(bgm_info["bgm_volume_db"]) / 20.0)
                except Exception: b_vol = 0.4
            elif "bgm_volume_db" in audio_cfg:
                try: b_vol = 10.0 ** (float(audio_cfg["bgm_volume_db"]) / 20.0)
                except Exception: b_vol = 0.4
            else:
                b_vol = parse_vol(bgm_info.get("volume", 0.4), 0.4)

            b_loop = bgm_info.get("loop", True)
            b_fade_in = float(bgm_info.get("fade_in", 0.6))
            b_fade_out = float(bgm_info.get("fade_out", 1.2))

            bgm_args = []
            if b_loop:
                bgm_args.extend(["-stream_loop", "-1"])
            if b_offset > 0:
                bgm_args.extend(["-ss", f"{b_offset:.4f}"])
            bgm_args.extend(["-i", b_src])
            inputs.extend(bgm_args)

            bgm_af = []
            if (probe_file(b_src).get("channels", 2) or 2) == 1:
                bgm_af.append("pan=stereo|c0=c0|c1=c0")
            bgm_af.extend([
                f"adelay={b_start}|{b_start}",
                f"volume={b_vol:.4f}",
                "aresample=48000",
                "aformat=channel_layouts=stereo"
            ])
            if b_fade_in > 0:
                bgm_af.append(f"afade=t=in:st=0:d={b_fade_in:.4f}")
            if b_fade_out > 0 and concat_dur > b_fade_out:
                bgm_af.append(f"afade=t=out:st={concat_dur - b_fade_out:.4f}:d={b_fade_out:.4f}")

            filter_nodes.append(f"[{in_idx}:a]{','.join(bgm_af)}[bgm_scaled]")

            if auto_ducking and duck_trigger:
                filter_nodes.append(f"[bgm_scaled]{duck_trigger}sidechaincompress=threshold=0.05:ratio=4:attack=20:release=300[bgm_ducked]")
                mix_inputs.append("[bgm_ducked]")
            else:
                mix_inputs.append("[bgm_scaled]")

            in_idx += 1

        # 5. Mixing dengan normalize=0 (anti-penurunan volume)
        if len(mix_inputs) > 1:
            all_in_str = "".join(mix_inputs)
            filter_nodes.append(f"{all_in_str}amix=inputs={len(mix_inputs)}:duration=first:dropout_transition=0:normalize=0[a_mixed]")
            cur_a = "[a_mixed]"
        elif len(mix_inputs) == 1:
            cur_a = mix_inputs[0]
        else:
            filter_nodes.append("anullsrc=r=48000:cl=stereo[a_silent]")
            cur_a = "[a_silent]"

        # 6. Anti-clipping limiter (limit 0.95 = -0.45 dBFS)
        filter_nodes.append(f"{cur_a}alimiter=limit=0.95[a_limited]")
        cur_a = "[a_limited]"

        if audio_strategy == "clean_monetize":
            filter_nodes.append(f"{cur_a}loudnorm=I=-14:LRA=11:TP=-1.5[a_final]")
            final_audio_node = "[a_final]"
        else:
            final_audio_node = cur_a

        full_filter = ";".join(filter_nodes)
        return inputs, full_filter, final_audio_node

    # 1. RENDER FULL VERSION
    print("  - Merender visual subtitle & audio master...")
    inputs_full, afilter_full, node_full = build_audio_filter(include_bgm=True, start_idx=1 + num_overlays)
    full_filter_complex = f"{vf_main_filter};{afilter_full}"

    target_full_file = os.path.join(out_dir, f"{base_name}_full.mp4" if dual_audio else f"{base_name}.mp4")
    tmp_full_file = os.path.join(out_dir, f"{os.path.basename(target_full_file)}.tmp.mp4")

    cmd_full = [
        get_ffmpeg_bin(), "-nostdin", "-y",
        "-i", concat_video
    ]
    cmd_full.extend(overlay_inputs)
    cmd_full.extend(inputs_full)
    cmd_full.extend([
        "-filter_complex", full_filter_complex,
        "-map", "[v_final]",
        "-map", node_full,
        "-threads", "2",
        "-c:v", "libx264",
        "-preset", preset,
        "-crf", str(crf),
        "-pix_fmt", "yuv420p",
        "-c:a", "aac",
        "-b:a", "192k",
        "-movflags", "+faststart",
        tmp_full_file
    ])

    res = subprocess.run(cmd_full, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if res.returncode != 0:
        print(f"[!] Gagal render final_full: {res.stderr}", file=sys.stderr)
        raise RuntimeError("FFmpeg final render failed")

    os.replace(tmp_full_file, target_full_file)
    print(f"  ✓ Output selesai: {target_full_file}")

    # 2. DUAL AUDIO: RENDER NO_BGM & SYNC_INFO.TXT
    if dual_audio:
        print("  - Merender versi no-BGM (Dual-Audio mode)...")
        inputs_nobgm, afilter_nobgm, node_nobgm = build_audio_filter(include_bgm=False, start_idx=1 + num_overlays)
        nobgm_filter_complex = f"{vf_main_filter};{afilter_nobgm}"

        target_nobgm_file = os.path.join(out_dir, f"{base_name}_no_bgm.mp4")
        tmp_nobgm_file = os.path.join(out_dir, f"{os.path.basename(target_nobgm_file)}.tmp.mp4")

        cmd_nobgm = [
            get_ffmpeg_bin(), "-nostdin", "-y",
            "-i", concat_video
        ]
        cmd_nobgm.extend(overlay_inputs)
        cmd_nobgm.extend(inputs_nobgm)
        cmd_nobgm.extend([
            "-filter_complex", nobgm_filter_complex,
            "-map", "[v_final]",
            "-map", node_nobgm,
            "-threads", "2",
            "-c:v", "libx264",
            "-preset", preset,
            "-crf", str(crf),
            "-pix_fmt", "yuv420p",
            "-c:a", "aac",
            "-b:a", "192k",
            "-movflags", "+faststart",
            tmp_nobgm_file
        ])

        res_nb = subprocess.run(cmd_nobgm, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        if res_nb.returncode != 0:
            print(f"[!] Gagal render no_bgm: {res_nb.stderr}", file=sys.stderr)
            raise RuntimeError("FFmpeg no_bgm render failed")

        os.replace(tmp_nobgm_file, target_nobgm_file)
        print(f"  ✓ Output No-BGM selesai: {target_nobgm_file}")

        # Tulis sync_info.txt
        sync_txt_file = os.path.join(out_dir, "sync_info.txt")
        b_name = os.path.basename(bgm_info.get("source", "audio_viral.mp3")) if has_bgm else "none"
        b_start_f = float(bgm_info.get("offset", 0.0)) if has_bgm else 0.0
        b_vol_p = int(parse_vol(bgm_info.get("volume", 1.0), 1.0) * 100) if has_bgm else 100
        sync_content = (
            f"=== CONTENTSTUDIO AUDIO SYNC GUIDE ===\n"
            f"BGM Source      : {b_name}\n"
            f"BGM Start Time  : {b_start_f:.2f}s\n"
            f"BGM Volume Target: {b_vol_p}%\n"
            f"Panduan Upload  : Gunakan '{os.path.basename(target_nobgm_file)}' di Instagram Reels / TikTok,\n"
            f"pilih musik resmi dari katalog platform, dan sesuaikan titik mulai pada {b_start_f:.2f}s\n"
            f"agar aman copyright dan tetap tersinkronisasi 100% sempurna dengan visual.\n"
        )
        with open(sync_txt_file, "w", encoding="utf-8") as f:
            f.write(sync_content)
        print(f"  ✓ Audio sync info tersimpan: {sync_txt_file}")

    # 3. EXPORT STEMS (jika export_stems True)
    if export_stems:
        print("  - Mengekspor stems terpisah (voice, bgm, srt)...")
        stems_dir = os.path.join(out_dir, f"{base_name}_stems")
        os.makedirs(stems_dir, exist_ok=True)

        srt_file = os.path.join(stems_dir, "subtitles.srt")
        generate_srt(plan.get("texts", []), srt_file)

        if has_voice:
            v_stem_file = os.path.join(stems_dir, "voice.wav")
            v_src = resolve_path(voice_info["source"])
            cmd_v = [get_ffmpeg_bin(), "-nostdin", "-y", "-i", v_src, "-c:a", "pcm_s16le", "-ar", "48000", v_stem_file]
            subprocess.run(cmd_v, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

        if has_bgm:
            b_stem_file = os.path.join(stems_dir, "bgm.wav")
            b_src = resolve_path(bgm_info["source"])
            b_off = float(bgm_info.get("offset", 0.0))
            cmd_b = [get_ffmpeg_bin(), "-nostdin", "-y"]
            if b_off > 0:
                cmd_b.extend(["-ss", f"{b_off:.4f}"])
            cmd_b.extend(["-i", b_src, "-t", f"{concat_dur:.4f}", "-c:a", "pcm_s16le", "-ar", "48000", b_stem_file])
            subprocess.run(cmd_b, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

        print(f"  ✓ Stems berhasil diekspor ke: {stems_dir}")

    return target_full_file

def validate_plan_file(plan_path: str) -> Tuple[bool, str]:
    """Validasi preflight plan.json secara in-process (Android & Kotlin-safe)."""
    p_path = resolve_path(plan_path)
    if not os.path.exists(p_path):
        return False, f"File plan tidak ditemukan: {p_path}"
    try:
        with open(p_path, "r", encoding="utf-8") as f:
            plan = json.load(f)
    except Exception as e:
        return False, f"Gagal membaca plan JSON: {e}"

    import io
    from contextlib import redirect_stdout, redirect_stderr
    buf_out = io.StringIO()
    buf_err = io.StringIO()
    try:
        with redirect_stdout(buf_out), redirect_stderr(buf_err):
            ok = validate_plan(plan, p_path)
    except Exception as e:
        return False, f"Exception saat validasi: {e}"

    if ok:
        return True, "Preflight validation sukses 100%."
    err_text = buf_err.getvalue().strip() or buf_out.getvalue().strip()
    return False, err_text or "Preflight validation failed."

def render_pipeline(
    plan_source: Any,
    is_preview: bool = False,
    on_progress: Optional[Callable[[str, int, str], None]] = None
) -> str:
    """
    Eksekusi rendering video in-process (Android & Kotlin Chaquopy Safe).
    Mengembalikan path absolut video hasil akhir.
    """
    if isinstance(plan_source, str):
        plan_path = resolve_path(plan_source)
        if not os.path.exists(plan_path):
            raise FileNotFoundError(f"File plan tidak ditemukan: {plan_path}")
        with open(plan_path, "r", encoding="utf-8") as f:
            plan = json.load(f)
    elif isinstance(plan_source, dict):
        plan = plan_source
        plan_path = "<in-memory>"
    else:
        raise ValueError("plan_source harus berupa path string atau dict")

    def _notify(stage: str, pct: int, msg: str):
        if on_progress:
            try:
                on_progress(stage, pct, msg)
            except Exception:
                pass

    _notify("preflight", 10, "Memvalidasi rencana (Preflight Check)...")
    if not validate_plan(plan, plan_path):
        raise ValueError(f"Validasi preflight gagal untuk plan: {plan_path}")

    out_dir = resolve_path(plan.get("output", {}).get("out_dir", "./out"))
    os.makedirs(out_dir, exist_ok=True)
    tmp_dir = os.path.join(out_dir, ".build_tmp")
    os.makedirs(tmp_dir, exist_ok=True)

    try:
        tw, th, fps = parse_dimensions(plan, preview_mode=is_preview)
        quality = plan.get("output", {}).get("quality", "balanced").lower()

        if is_preview:
            preset = "ultrafast"
            crf = 28
            print(f"[*] MODE PREVIEW AKTIF: {tw}x{th} @ {fps:.1f} FPS (preset: {preset}, crf: {crf})")
        else:
            if quality == "draft":
                preset, crf = "veryfast", 26
            elif quality == "high":
                preset, crf = "medium", 18
            else:
                preset, crf = "fast", 22
            print(f"[*] MODE FINAL AKTIF: {tw}x{th} @ {fps:.1f} FPS (preset: {preset}, crf: {crf})")

        _notify("canvas_setup", 20, f"Inisialisasi kanvas {tw}x{th} @ {fps:.1f} FPS...")

        segments = plan.get("segments", [])
        total_segs = len(segments)
        rendered_segs = []
        global_polish = plan.get("visual_polish", {}) or {}

        print(f"[2/5] Merender {total_segs} segmen secara mandiri (-threads 2 Anti-OOM)...")
        for i, seg in enumerate(segments, start=1):
            pct = int(20 + (i / max(1, total_segs)) * 45)  # 20% to 65%
            _notify("render_segment_progress", pct, f"Merender segmen #{i}/{total_segs}...")
            print(f"  -> Segmen {i}/{total_segs}: '{os.path.basename(seg.get('source'))}' (framing: {seg.get('framing', 'crop')})...")
            seg_path = render_segment(seg, i, tw, th, fps, preset, crf, tmp_dir, global_polish=global_polish)
            rendered_segs.append(seg_path)

        has_any_transition = any(
            seg.get("transition") and str(seg.get("transition")).lower() != "none"
            for seg in segments
        )

        _notify("concat", 70, "Menggabungkan segmen video...")
        if not has_any_transition or total_segs <= 1:
            print("[3/5] Menggabungkan seluruh segmen (Concat Demuxer Cepat)...")
            concat_list_file = os.path.join(tmp_dir, "concat.txt")
            with open(concat_list_file, "w", encoding="utf-8") as f:
                for s in rendered_segs:
                    f.write(f"file '{s}'\n")

            concatenated_video = os.path.join(tmp_dir, "concatenated.mp4")
            cmd_concat = [
                get_ffmpeg_bin(), "-nostdin", "-y",
                "-f", "concat",
                "-safe", "0",
                "-i", concat_list_file,
                "-c", "copy",
                "-movflags", "+faststart",
                concatenated_video
            ]
            res_cat = subprocess.run(cmd_concat, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            if res_cat.returncode != 0:
                print(f"[!] Gagal menggabungkan segmen via concat demuxer: {res_cat.stderr}", file=sys.stderr)
                raise RuntimeError(f"Concat demuxer failed: {res_cat.stderr}")
        else:
            print(f"[3/5] Menggabungkan {total_segs} segmen via Rolling Pairwise Merge (Anti-OOM Transisi)...")
            current_video = rendered_segs[0]
            inter_files_to_clean = []

            for i in range(1, total_segs):
                next_seg = rendered_segs[i]
                seg_cfg = segments[i]
                prev_cfg = segments[i - 1]

                trans_type = str(seg_cfg.get("transition") or prev_cfg.get("transition") or "fade").lower()
                trans_dur = float(seg_cfg.get("transition_duration") or prev_cfg.get("transition_duration") or 0.5)

                merged_file = os.path.join(tmp_dir, f"roll_merged_{i:03d}.mp4")

                if trans_type in [None, "none", ""]:
                    cmd_merge = [
                        get_ffmpeg_bin(), "-nostdin", "-y", "-threads", "2",
                        "-i", current_video, "-i", next_seg,
                        "-filter_complex", "[0:v][0:a][1:v][1:a]concat=n=2:v=1:a=1[v][a]",
                        "-map", "[v]", "-map", "[a]",
                        "-c:v", "libx264", "-preset", preset, "-crf", str(crf), "-pix_fmt", "yuv420p",
                        "-c:a", "aac", "-b:a", "192k",
                        merged_file
                    ]
                else:
                    dur_curr = probe_file(current_video).get("duration", 0.0)
                    offset = max(0.0, dur_curr - trans_dur)
                    vf_x = f"[0:v][1:v]xfade=transition={trans_type}:duration={trans_dur:.4f}:offset={offset:.4f}[v]"
                    af_x = f"[0:a][1:a]acrossfade=d={trans_dur:.4f}:c1=tri:c2=tri[a]"
                    cmd_merge = [
                        get_ffmpeg_bin(), "-nostdin", "-y", "-threads", "2",
                        "-i", current_video, "-i", next_seg,
                        "-filter_complex", f"{vf_x};{af_x}",
                        "-map", "[v]", "-map", "[a]",
                        "-c:v", "libx264", "-preset", preset, "-crf", str(crf), "-pix_fmt", "yuv420p",
                        "-c:a", "aac", "-b:a", "192k",
                        merged_file
                    ]

                print(f"  -> Rolling merge [{i}/{total_segs-1}]: transisi '{trans_type}' ({trans_dur}s)...")
                res_m = subprocess.run(cmd_merge, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
                if res_m.returncode != 0:
                    print(f"[!] Gagal rolling merge di tahap #{i}: {res_m.stderr}", file=sys.stderr)
                    raise RuntimeError(f"Rolling merge failed: {res_m.stderr}")

                if current_video in inter_files_to_clean and os.path.exists(current_video):
                    try:
                        os.remove(current_video)
                    except Exception:
                        pass

                inter_files_to_clean.append(merged_file)
                current_video = merged_file

            concatenated_video = current_video

        # 4. Generate Subtitles ASS & SRT
        _notify("mixing_audio", 85, "Mengompilasi subtitle ASS dan mixing audio...")
        print("[4/5] Mengompilasi subtitle ASS dan stems...")
        ass_file = os.path.join(tmp_dir, "subtitles.ass")
        generate_ass(
            plan.get("texts", []),
            tw, th,
            ass_file,
            watermark=plan.get("watermark") or plan.get("output", {}).get("watermark")
        )

        # 5. Finalisasi Visual + Audio Mixing + Delivery Lock
        print("[5/5] Finalisasi delivery lock & mixing audio...")
        final_video = mix_and_finalize(
            plan,
            concatenated_video,
            ass_file,
            tw, th,
            preset, crf,
            out_dir,
            tmp_dir
        )

        _notify("completed", 100, f"Render selesai! Output: {final_video}")
        return final_video

    finally:
        if os.path.exists(tmp_dir):
            shutil.rmtree(tmp_dir, ignore_errors=True)

# ==============================================================================
# 6. MAIN CONTROLLER (CLI BACKWARDS-COMPATIBILITY)
# ==============================================================================
def main():
    parser = argparse.ArgumentParser(description="ContentStudio Dynamic Video Builder (engine/build.py)")
    parser.add_argument("plan_file", help="Path ke plan.json")
    parser.add_argument("--validate", action="store_true", help="Hanya jalankan validasi preflight")
    parser.add_argument("--preview", action="store_true", help="Render pratinjau cepat 360p")
    parser.add_argument("--final", action="store_true", help="Render video final berkualitas penuh")
    args = parser.parse_args()

    plan_path = resolve_path(args.plan_file)
    if not os.path.exists(plan_path):
        print(f"[!] File plan tidak ditemukan: {plan_path}", file=sys.stderr)
        sys.exit(1)

    try:
        with open(plan_path, "r", encoding="utf-8") as f:
            plan = json.load(f)
    except Exception as e:
        print(f"[!] Gagal membaca plan JSON: {e}", file=sys.stderr)
        sys.exit(1)

    if not validate_plan(plan, plan_path):
        sys.exit(1)

    if args.validate:
        print("[*] Rencana valid 100%. Keluar sesuai instruksi --validate.")
        sys.exit(0)

    try:
        out_file = render_pipeline(plan_path, is_preview=args.preview)
        print("\n=======================================================")
        print(f"🎉 SUKSES: Render {'Preview' if args.preview else 'Final'} selesai!")
        print(f"📁 Output disimpan di: {os.path.dirname(out_file)}")
        print("=======================================================\n")
    except Exception as e:
        print(f"[!] Render gagal: {e}", file=sys.stderr)
        sys.exit(1)

if __name__ == "__main__":
    main()
