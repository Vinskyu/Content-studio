#!/usr/bin/env python3
"""
ContentStudio Graphic Worker (engine/assets/graphic_worker.py)

Merender kartu grafis tajam 100% lokal (Pillow, fallback FFmpeg drawtext/color)
pada dimensi target berapa pun: 16:9, 9:16, 1:1, 4:5.

Template universal (tanpa niche hardcode):
  * `stat`       - angka besar + konteks
  * `comparison` - dua sisi bersanding (A vs B, sebelum/sesudah, skor)
  * `chapter`    - kartu judul bab untuk video panjang
  * `quote`      - kutipan / fakta tajam
  * `tier`       - daftar tier/label berjenjang
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
from typing import Any, Dict, List, Optional, Tuple

from engine.runtime import get_fonts_dir, get_ffmpeg_bin
from engine.reference_dna import dimensions_for, load_reference_dna, normalize_aspect_ratio

PALETTE = {
    "canvas": "#0B0C0E",
    "surface": "#15171C",
    "active": "#1E2128",
    "accent": "#2A2F3A",
    "text": "#F2F4F8",
    "muted": "#8A919E",
    "danger": "#D96B6B",
}

GRAPHIC_TEMPLATES = ("stat", "comparison", "chapter", "quote", "tier")

_FONT_CANDIDATES_BOLD = [
    os.path.join(get_fonts_dir(), "Inter-Bold.ttf"),
    os.path.join(get_fonts_dir(), "Roboto-Bold.ttf"),
    "/system/fonts/Roboto-Bold.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
]
_FONT_CANDIDATES_REGULAR = [
    os.path.join(get_fonts_dir(), "Inter-Regular.ttf"),
    os.path.join(get_fonts_dir(), "Roboto-Regular.ttf"),
    "/system/fonts/Roboto-Regular.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
]


def _hex(value: str, fallback: str = "#F2F4F8") -> str:
    text = str(value or "").strip()
    if text.startswith("#") and len(text) in (4, 7):
        return text
    return fallback


def _find_font(bold: bool = True) -> Optional[str]:
    for candidate in (_FONT_CANDIDATES_BOLD if bold else _FONT_CANDIDATES_REGULAR):
        if candidate and os.path.exists(candidate):
            return candidate
    return None


def _font(size: int, bold: bool = True):
    from PIL import ImageFont  # type: ignore

    path = _find_font(bold)
    if path:
        try:
            return ImageFont.truetype(path, size)
        except Exception:
            pass
    try:
        return ImageFont.load_default(size=size)
    except Exception:
        return ImageFont.load_default()


def _text_size(draw, text: str, font) -> Tuple[int, int]:
    try:
        box = draw.textbbox((0, 0), text, font=font)
        return box[2] - box[0], box[3] - box[1]
    except Exception:
        return draw.textsize(text, font=font)  # type: ignore[attr-defined]


def _wrap(draw, text: str, font, max_width: int) -> List[str]:
    words = str(text or "").split()
    if not words:
        return []
    lines: List[str] = []
    current = words[0]
    for word in words[1:]:
        candidate = current + " " + word
        if _text_size(draw, candidate, font)[0] <= max_width:
            current = candidate
        else:
            lines.append(current)
            current = word
    lines.append(current)
    return lines


def _fit_font(draw, text: str, max_width: int, start_size: int, min_size: int = 18, bold: bool = True):
    size = start_size
    while size > min_size:
        font = _font(size, bold)
        if _text_size(draw, text, font)[0] <= max_width:
            return font
        size -= 2
    return _font(min_size, bold)


def _resolution_for(dna: Optional[Dict[str, Any]], aspect_ratio: Optional[str], resolution: Optional[str]) -> Tuple[int, int, str]:
    ratio = normalize_aspect_ratio(aspect_ratio or (dna or {}).get("aspect_ratio"), "16:9")
    if resolution and "x" in str(resolution).lower():
        try:
            w_str, h_str = str(resolution).lower().split("x", 1)
            return int(w_str), int(h_str), ratio
        except Exception:
            pass
    res_str = (dna or {}).get("resolution")
    if isinstance(res_str, str) and "x" in res_str:
        try:
            w_str, h_str = res_str.lower().split("x", 1)
            return int(w_str), int(h_str), ratio
        except Exception:
            pass
    width, height = dimensions_for(ratio, "1080p")
    return width, height, ratio


# ---------------------------------------------------------------------------
# Renderer Pillow
# ---------------------------------------------------------------------------

def _draw_pillow(out_path: str, template: str, data: Dict[str, Any], width: int, height: int) -> None:
    from PIL import Image, ImageDraw  # type: ignore

    canvas = Image.new("RGB", (width, height), _hex(PALETTE["canvas"]))
    draw = ImageDraw.Draw(canvas)
    pad = int(min(width, height) * 0.075)
    content_w = width - pad * 2

    # Garis aksen kiri tipis (bukan border kotak).
    bar_w = max(3, int(width * 0.006))
    draw.rectangle([0, int(height * 0.12), bar_w, int(height * 0.88)], fill=_hex(PALETTE["accent"]))

    eyebrow = str(data.get("eyebrow") or data.get("label") or "").strip().upper()
    title = str(data.get("title") or data.get("text") or data.get("quote") or "").strip()
    subtitle = str(data.get("subtitle") or data.get("caption") or data.get("attribution") or "").strip()

    y = pad

    def draw_eyebrow(text: str, y_pos: int) -> int:
        if not text:
            return y_pos
        font = _font(max(13, int(height * 0.022)), True)
        draw.text((pad, y_pos), text, font=font, fill=_hex(PALETTE["muted"]))
        return y_pos + _text_size(draw, text, font)[1] + int(height * 0.03)

    def draw_paragraph(text: str, y_pos: int, size: int, color: str, bold: bool = True,
                       max_lines: int = 6, line_gap: float = 1.25) -> int:
        if not text:
            return y_pos
        font = _fit_font(draw, text, content_w, size, max(14, int(size * 0.5)), bold)
        lines = _wrap(draw, text, font, content_w)[:max_lines]
        line_h = int(_text_size(draw, "Ag", font)[1] * line_gap)
        for line in lines:
            draw.text((pad, y_pos), line, font=font, fill=_hex(color))
            y_pos += line_h
        return y_pos

    if template == "chapter":
        idx_text = str(data.get("chapter") or data.get("index") or "").strip()
        if idx_text:
            num_font = _font(int(height * 0.16), True)
            draw.text((pad, y), idx_text, font=num_font, fill=_hex(PALETTE["active"]))
            y += int(_text_size(draw, idx_text, num_font)[1] * 0.82)
        y = draw_eyebrow(eyebrow or "BAB", y)
        y = draw_paragraph(title, y, int(height * 0.075), PALETTE["text"], True, 3)
        y += int(height * 0.02)
        draw_paragraph(subtitle, y, int(height * 0.034), PALETTE["muted"], False, 3)

    elif template == "stat":
        y = draw_eyebrow(eyebrow, y)
        big = str(data.get("value") or data.get("number") or title or "").strip()
        if big:
            font = _fit_font(draw, big, content_w, int(height * 0.19), int(height * 0.07), True)
            draw.text((pad, y), big, font=font, fill=_hex(PALETTE["text"]))
            y += int(_text_size(draw, big, font)[1] * 1.08)
        draw_paragraph(title if big else "", y, int(height * 0.045), PALETTE["muted"], False, 3)
        y += int(height * 0.02)
        draw_paragraph(subtitle, y, int(height * 0.032), PALETTE["muted"], False, 3)

    elif template == "comparison":
        y = draw_eyebrow(eyebrow, y)
        left_label = str(data.get("left_label") or data.get("a_label") or "A").strip()
        right_label = str(data.get("right_label") or data.get("b_label") or "B").strip()
        left_value = str(data.get("left_value") or data.get("a_value") or "").strip()
        right_value = str(data.get("right_value") or data.get("b_value") or "").strip()
        col_w = int((content_w - pad) / 2)
        value_font = _font(int(height * 0.1), True)
        label_font = _font(int(height * 0.028), True)
        draw.text((pad, y), left_label.upper(), font=label_font, fill=_hex(PALETTE["muted"]))
        draw.text((pad + col_w + pad, y), right_label.upper(), font=label_font, fill=_hex(PALETTE["muted"]))
        y += int(_text_size(draw, left_label, label_font)[1] * 2.0)
        draw.text((pad, y), left_value, font=value_font, fill=_hex(PALETTE["text"]))
        draw.text((pad + col_w + pad, y), right_value, font=value_font, fill=_hex(PALETTE["danger"]))
        mid_x = pad + col_w + int(pad / 2)
        draw.rectangle([mid_x, y, mid_x + max(2, int(width * 0.003)), y + int(height * 0.14)], fill=_hex(PALETTE["accent"]))
        y += int(height * 0.19)
        draw_paragraph(title, y, int(height * 0.036), PALETTE["muted"], False, 3)

    elif template == "quote":
        mark_font = _font(int(height * 0.16), True)
        draw.text((pad, y - int(height * 0.02)), "\u201c", font=mark_font, fill=_hex(PALETTE["active"]))
        y += int(height * 0.11)
        draw_paragraph(title, y, int(height * 0.056), PALETTE["text"], True, 5)
        y += int(height * 0.03)
        draw_paragraph(subtitle, y, int(height * 0.03), PALETTE["muted"], False, 2)

    elif template == "tier":
        y = draw_eyebrow(eyebrow or "TIER", y)
        tiers = data.get("tiers")
        if not isinstance(tiers, list) or not tiers:
            tiers = [{"label": title or "Tier 1", "value": subtitle or ""}]
        row_h = int(height * 0.1)
        label_font = _font(int(height * 0.036), True)
        value_font = _font(int(height * 0.03), False)
        for item in tiers[:6]:
            if isinstance(item, dict):
                label = str(item.get("label") or item.get("name") or "").strip()
                value = str(item.get("value") or item.get("note") or "").strip()
            else:
                label, value = str(item).strip(), ""
            draw.rectangle([pad, y, width - pad, y + row_h - int(row_h * 0.22)], fill=_hex(PALETTE["surface"]))
            draw.rectangle([pad, y, pad + max(3, int(width * 0.005)), y + row_h - int(row_h * 0.22)], fill=_hex(PALETTE["accent"]))
            draw.text((pad + int(width * 0.025), y + int(row_h * 0.14)), label, font=label_font, fill=_hex(PALETTE["text"]))
            if value:
                vw = _text_size(draw, value, value_font)[0]
                draw.text((width - pad - int(width * 0.02) - vw, y + int(row_h * 0.18)), value, font=value_font, fill=_hex(PALETTE["muted"]))
            y += row_h
    else:
        y = draw_eyebrow(eyebrow, y)
        draw_paragraph(title, y, int(height * 0.06), PALETTE["text"], True, 5)
        y += int(height * 0.02)
        draw_paragraph(subtitle, y, int(height * 0.032), PALETTE["muted"], False, 3)

    # Footer tipis: label kecil di kanan bawah, bukan border.
    brand = str(data.get("footer") or "").strip()
    if brand:
        font = _font(max(11, int(height * 0.018)), False)
        tw = _text_size(draw, brand, font)[0]
        draw.text((width - pad - tw, height - pad - int(height * 0.02)), brand, font=font, fill=_hex(PALETTE["muted"]))

    os.makedirs(os.path.dirname(os.path.abspath(out_path)) or ".", exist_ok=True)
    canvas.save(out_path, "PNG", optimize=True)


# ---------------------------------------------------------------------------
# Fallback FFmpeg
# ---------------------------------------------------------------------------

def _escape_drawtext(text: str) -> str:
    return (
        str(text or "")
        .replace("\\", "\\\\")
        .replace(":", "\\:")
        .replace("'", "\u2019")
        .replace("%", "\\%")
        .replace("\n", " ")
    )


def _draw_ffmpeg(out_path: str, template: str, data: Dict[str, Any], width: int, height: int) -> bool:
    ffmpeg = get_ffmpeg_bin()
    if not ffmpeg or (not os.path.isabs(ffmpeg) and not shutil.which(ffmpeg)):
        return False
    lines: List[str] = []
    eyebrow = str(data.get("eyebrow") or data.get("label") or "").strip().upper()
    title = str(data.get("title") or data.get("text") or data.get("quote") or "").strip()
    subtitle = str(data.get("subtitle") or data.get("caption") or "").strip()
    if template == "stat":
        lines.append(str(data.get("value") or data.get("number") or "").strip())
    if eyebrow:
        lines.append(eyebrow)
    if title:
        lines.append(title)
    if subtitle:
        lines.append(subtitle)
    lines = [ln for ln in lines if ln][:4]
    if not lines:
        return False
    font_path = _find_font(True)
    filters = []
    base_y = int(height * 0.32)
    for idx, line in enumerate(lines):
        size = int(height * (0.09 if idx == 0 else 0.045))
        color = "0xF2F4F8" if idx == 0 else "0x8A919E"
        draw = "drawtext=text='%s':fontcolor=%s:fontsize=%d:x=(w-text_w)/2:y=%d" % (
            _escape_drawtext(line), color, size, base_y + idx * int(height * 0.12),
        )
        if font_path:
            draw += ":fontfile='%s'" % font_path.replace(":", "\\:")
        filters.append(draw)
    cmd = [
        ffmpeg, "-y", "-f", "lavfi",
        "-i", "color=c=0x0B0C0E:s=%dx%d:d=1" % (width, height),
        "-vf", ",".join(filters),
        "-frames:v", "1", out_path,
    ]
    try:
        proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=60)
        return proc.returncode == 0 and os.path.exists(out_path) and os.path.getsize(out_path) > 0
    except Exception:
        return False


# ---------------------------------------------------------------------------
# API publik
# ---------------------------------------------------------------------------

def _normalize_template(template: str, data: Dict[str, Any]) -> str:
    token = str(template or "").strip().lower()
    if token in GRAPHIC_TEMPLATES:
        return token
    hint = " ".join([
        str(data.get("title") or ""),
        str(data.get("eyebrow") or ""),
        str(data.get("label") or ""),
        str(data.get("chapter") or ""),
    ]).lower()
    if data.get("chapter") is not None:
        return "chapter"
    if isinstance(data.get("tiers"), list):
        return "tier"
    if data.get("left_value") or data.get("right_value") or data.get("a_value"):
        return "comparison"
    if data.get("quote"):
        return "quote"
    if "bab" in hint or "chapter" in hint:
        return "chapter"
    return "stat"


def render_graphic(
    out_path: str,
    template: str = "stat",
    data: Optional[Dict[str, Any]] = None,
    dna: Optional[Dict[str, Any]] = None,
    aspect_ratio: Optional[str] = None,
    resolution: Optional[str] = None,
) -> Dict[str, Any]:
    """Render satu kartu grafis PNG ke `out_path`. Selalu mengembalikan dict status."""
    payload = dict(data or {})
    chosen = _normalize_template(template, payload)
    width, height, ratio = _resolution_for(dna, aspect_ratio, resolution)
    engine_used = "pillow"
    try:
        _draw_pillow(out_path, chosen, payload, width, height)
    except Exception as pillow_err:
        engine_used = "ffmpeg"
        if not _draw_ffmpeg(out_path, chosen, payload, width, height):
            return {
                "status": "error",
                "message": "Render grafis gagal: Pillow dan FFmpeg tidak tersedia (%s)" % str(pillow_err)[:120],
                "file": out_path,
            }
    if not os.path.exists(out_path) or os.path.getsize(out_path) <= 0:
        return {"status": "error", "message": "File grafis tidak terbentuk", "file": out_path}
    return {
        "status": "ok",
        "file": out_path,
        "template": chosen,
        "aspect_ratio": ratio,
        "width": width,
        "height": height,
        "engine": engine_used,
        "kind": "image",
    }


def render_graphic_video(
    out_path: str,
    template: str = "stat",
    data: Optional[Dict[str, Any]] = None,
    duration: float = 3.0,
    dna: Optional[Dict[str, Any]] = None,
    aspect_ratio: Optional[str] = None,
    resolution: Optional[str] = None,
) -> Dict[str, Any]:
    """Render kartu grafis sebagai MP4 statis (loop frame) via FFmpeg."""
    ffmpeg = get_ffmpeg_bin()
    if not ffmpeg or (not os.path.isabs(ffmpeg) and not shutil.which(ffmpeg)):
        return {"status": "error", "message": "FFmpeg tidak tersedia untuk render video grafis"}
    png_path = os.path.splitext(out_path)[0] + ".png"
    png_res = render_graphic(png_path, template, data, dna, aspect_ratio, resolution)
    if png_res.get("status") != "ok":
        return png_res
    cmd = [
        ffmpeg, "-y", "-loop", "1", "-i", png_path,
        "-t", "%.2f" % max(0.5, float(duration)),
        "-vf", "format=yuv420p", "-r", "30",
        "-c:v", "libx264", "-preset", "ultrafast", "-crf", "20",
        "-movflags", "+faststart", out_path,
    ]
    try:
        proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=120)
        if proc.returncode != 0:
            return {"status": "error", "message": "FFmpeg gagal: " + (proc.stderr or "")[-200:]}
    except Exception as exc:
        return {"status": "error", "message": "FFmpeg gagal: " + str(exc)[:150]}
    return {
        "status": "ok", "file": out_path, "template": png_res.get("template"),
        "aspect_ratio": png_res.get("aspect_ratio"), "kind": "video",
    }


def worker_contract() -> Dict[str, Any]:
    return {"source": "graphic", "templates": list(GRAPHIC_TEMPLATES), "outputs": ["png", "mp4"]}


def cli_main(argv=None) -> int:
    import argparse

    parser = argparse.ArgumentParser(prog="graphic_worker.py", description="ContentStudio Graphic Worker")
    parser.add_argument("--project", default="")
    parser.add_argument("--out", required=True)
    parser.add_argument("--template", default="stat", choices=list(GRAPHIC_TEMPLATES))
    parser.add_argument("--title", default="")
    parser.add_argument("--subtitle", default="")
    parser.add_argument("--value", default="")
    parser.add_argument("--eyebrow", default="")
    parser.add_argument("--aspect-ratio", default=None)
    parser.add_argument("--resolution", default=None)
    parser.add_argument("--data-json", default="")
    parser.add_argument("--video", action="store_true")
    parser.add_argument("--duration", type=float, default=3.0)
    args = parser.parse_args(argv if argv is not None else sys.argv[1:])

    payload: Dict[str, Any] = {}
    if args.data_json:
        try:
            payload = json.loads(args.data_json)
        except Exception as exc:
            print(json.dumps({"status": "error", "message": "data-json tidak valid: %s" % exc}))
            return 1
    for key, value in (("title", args.title), ("subtitle", args.subtitle), ("value", args.value), ("eyebrow", args.eyebrow)):
        if value:
            payload[key] = value

    dna = load_reference_dna(args.project) if args.project else None
    if args.video:
        res = render_graphic_video(args.out, args.template, payload, args.duration, dna, args.aspect_ratio, args.resolution)
    else:
        res = render_graphic(args.out, args.template, payload, dna, args.aspect_ratio, args.resolution)
    print(json.dumps(res, indent=2, ensure_ascii=False))
    return 0 if res.get("status") == "ok" else 1


if __name__ == "__main__":
    raise SystemExit(cli_main())
