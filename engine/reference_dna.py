#!/usr/bin/env python3
"""
ContentStudio Reference DNA Contract (engine/reference_dna.py)

Menyimpan, membaca, dan memvalidasi kontrak produksi di
`workspaces/<project>/reference_dna.json` berbasis SIFAT PRODUKSI UNIVERSAL.

Tidak ada kategori/niche yang di-hardcode di sini. Semua nilai adalah sifat
produksi yang bisa dipakai topik apa pun: skala format, rasio, prioritas sumber
aset, kontinuitas visual, pola layout, modalitas audio, pacing, dan anchor gaya.

Modul ini juga menjadi satu-satunya sumber kebenaran untuk pemetaan
`aspect_ratio` -> resolusi target (render) dan -> ukuran generate (AI image),
sehingga worker, CLI, dan renderer tidak pernah mengunci diri ke 9:16.
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
from typing import Any, Dict, List, Optional, Tuple

from engine.runtime import get_workspaces_dir

DNA_FILENAME = "reference_dna.json"

FORMAT_SCALES: Tuple[str, ...] = ("short", "long")
ASPECT_RATIOS: Tuple[str, ...] = ("16:9", "9:16", "1:1", "4:5")
SOURCE_TYPES: Tuple[str, ...] = ("clip", "generate", "stock", "graphic", "local")
VISUAL_CONTINUITY: Tuple[str, ...] = ("strict_consistent", "editorial_varied")
LAYOUT_PATTERNS: Tuple[str, ...] = (
    "fullscreen",
    "grid_2x2",
    "vertical_3_tier",
    "split_screen",
    "card_overlay",
    "picture_in_picture",
)
AUDIO_MODALITIES: Tuple[str, ...] = (
    "visual_only_bgm",
    "vo_plus_bgm",
    "original_clip_audio",
    "ambient_bgm",
)
PACING_STYLES: Tuple[str, ...] = (
    "fast_cut",
    "escalating",
    "narrative_chapters",
    "slow_cinematic",
)

# Resolusi render final per rasio & preset kualitas (selalu angka genap).
ASPECT_RESOLUTIONS: Dict[str, Dict[str, str]] = {
    "16:9": {"1080p": "1920x1080", "720p": "1280x720", "preview": "640x360"},
    "9:16": {"1080p": "1080x1920", "720p": "720x1280", "preview": "360x640"},
    "1:1": {"1080p": "1080x1080", "720p": "720x720", "preview": "360x360"},
    "4:5": {"1080p": "1080x1350", "720p": "720x900", "preview": "360x450"},
}

# Ukuran kanvas untuk AI image generator (kelipatan ramah model diffusion).
ASPECT_GENERATION_SIZES: Dict[str, Tuple[int, int]] = {
    "16:9": (1280, 720),
    "9:16": (768, 1344),
    "1:1": (1024, 1024),
    "4:5": (896, 1120),
}

# Orientasi pencarian stok per rasio.
ASPECT_STOCK_ORIENTATION: Dict[str, str] = {
    "16:9": "landscape",
    "9:16": "portrait",
    "1:1": "square",
    "4:5": "portrait",
}


# ---------------------------------------------------------------------------
# Normalisasi & pemetaan teknis
# ---------------------------------------------------------------------------

def normalize_aspect_ratio(value: Any, fallback: str = "16:9") -> str:
    """Normalisasi rasio ('16/9', '9x16', '1.1') ke salah satu rasio resmi."""
    raw = str(value or "").strip().lower().replace("/", ":").replace("x", ":")
    raw = raw.replace(" ", "")
    aliases = {
        "16:9": "16:9",
        "9:16": "9:16",
        "1:1": "1:1",
        "4:5": "4:5",
        "landscape": "16:9",
        "portrait": "9:16",
        "square": "1:1",
    }
    if raw in aliases:
        return aliases[raw]
    try:
        if ":" in raw:
            w, h = raw.split(":", 1)
            ratio = float(w) / float(h)
            if abs(ratio - 16 / 9) < 0.06:
                return "16:9"
            if abs(ratio - 9 / 16) < 0.06:
                return "9:16"
            if abs(ratio - 1.0) < 0.03:
                return "1:1"
            if abs(ratio - 4 / 5) < 0.04:
                return "4:5"
    except Exception:
        pass
    return fallback if fallback in ASPECT_RATIOS else "16:9"


def normalize_format_scale(value: Any, fallback: str = "short") -> str:
    raw = str(value or "").strip().lower()
    if raw in ("long", "longform", "long-form", "panjang", "chaptered", "multi_chapter"):
        return "long"
    if raw in ("short", "shortform", "short-form", "pendek", "reels", "shorts"):
        return "short"
    return fallback if fallback in FORMAT_SCALES else "short"


def resolution_for(aspect_ratio: Any, quality: str = "1080p") -> str:
    """Peta rasio + preset kualitas ke string 'WxH'."""
    ratio = normalize_aspect_ratio(aspect_ratio)
    preset = str(quality or "1080p").strip().lower()
    table = ASPECT_RESOLUTIONS.get(ratio, ASPECT_RESOLUTIONS["16:9"])
    if preset not in table:
        preset = "1080p"
    return table[preset]


def dimensions_for(aspect_ratio: Any, quality: str = "1080p") -> Tuple[int, int]:
    w_str, h_str = resolution_for(aspect_ratio, quality).split("x")
    return int(w_str), int(h_str)


def generation_size(aspect_ratio: Any) -> Tuple[int, int]:
    """Ukuran kanvas AI generator untuk rasio target."""
    ratio = normalize_aspect_ratio(aspect_ratio)
    return ASPECT_GENERATION_SIZES.get(ratio, ASPECT_GENERATION_SIZES["16:9"])


def stock_orientation(aspect_ratio: Any) -> str:
    ratio = normalize_aspect_ratio(aspect_ratio)
    return ASPECT_STOCK_ORIENTATION.get(ratio, "landscape")


def is_portrait(aspect_ratio: Any) -> bool:
    ratio = normalize_aspect_ratio(aspect_ratio)
    return ratio in ("9:16", "4:5")


# ---------------------------------------------------------------------------
# Path & akses file
# ---------------------------------------------------------------------------

def project_dir(project: str) -> str:
    return os.path.join(get_workspaces_dir(), os.path.basename(str(project or "").strip()))


def dna_path(project: str) -> str:
    return os.path.join(project_dir(project), DNA_FILENAME)


def project_aspect_ratio(project: str) -> str:
    """Fallback rasio dari project.json bila DNA belum ada."""
    pj_path = os.path.join(project_dir(project), "project.json")
    try:
        with open(pj_path, "r", encoding="utf-8") as f:
            pj = json.load(f)
        if isinstance(pj, dict):
            return normalize_aspect_ratio(pj.get("aspect_ratio"), "16:9")
    except Exception:
        pass
    return "16:9"


# ---------------------------------------------------------------------------
# Default & validasi kontrak
# ---------------------------------------------------------------------------

def default_dna(aspect_ratio: Any = None, format_scale: Any = "short") -> Dict[str, Any]:
    ratio = normalize_aspect_ratio(aspect_ratio, "16:9")
    scale = normalize_format_scale(format_scale, "short")
    long_form = scale == "long"
    return {
        "version": 1,
        "format_scale": scale,
        "aspect_ratio": ratio,
        "resolution": resolution_for(ratio, "1080p"),
        "sources_priority": list(SOURCE_TYPES),
        "visual_continuity": "editorial_varied",
        "layout_pattern": "fullscreen",
        "audio_modality": "vo_plus_bgm",
        "pacing_profile": {
            "style": "narrative_chapters" if long_form else "fast_cut",
            "avg_scene_sec": 8 if long_form else 4,
            "chapter_structure": long_form,
        },
        "visual_style_anchor": "",
        "mechanics_summary": "",
        "user_overrides": "",
    }


def validate_dna(payload: Any, fallback_aspect: str = "16:9") -> Tuple[Dict[str, Any], List[str]]:
    """
    Validasi & normalisasi kontrak DNA.

    Mengembalikan (dna, warnings). Nilai yang tidak valid diganti default yang
    aman, bukan ditolak, supaya produksi tidak pernah macet karena satu field.
    """
    warnings: List[str] = []
    if not isinstance(payload, dict):
        warnings.append("Kontrak DNA bukan objek JSON, memakai default.")
        payload = {}

    scale = normalize_format_scale(payload.get("format_scale"), "short")
    if payload.get("format_scale") and scale != str(payload.get("format_scale")).strip().lower():
        warnings.append("format_scale tidak dikenal, dinormalisasi ke '%s'." % scale)

    raw_ratio = payload.get("aspect_ratio")
    ratio = normalize_aspect_ratio(raw_ratio, fallback_aspect)
    if raw_ratio and ratio != normalize_aspect_ratio(raw_ratio, ""):
        warnings.append("aspect_ratio tidak didukung, dinormalisasi ke '%s'." % ratio)

    dna = default_dna(ratio, scale)

    quality = str(payload.get("resolution_quality") or "").strip().lower()
    res_value = str(payload.get("resolution") or "").strip().lower()
    if "x" in res_value:
        try:
            w_str, h_str = res_value.split("x", 1)
            width, height = int(w_str), int(h_str)
            if width >= 256 and height >= 256:
                dna["resolution"] = "%dx%d" % (width - width % 2, height - height % 2)
            else:
                warnings.append("resolution terlalu kecil, memakai preset rasio.")
        except Exception:
            warnings.append("resolution tidak valid, memakai preset rasio.")
    elif quality in ("720p", "1080p", "preview"):
        dna["resolution"] = resolution_for(ratio, quality)
    else:
        dna["resolution"] = resolution_for(ratio, "1080p")

    priorities = payload.get("sources_priority")
    if isinstance(priorities, list) and priorities:
        clean_priorities: List[str] = []
        for item in priorities:
            token = str(item).strip().lower()
            if token in SOURCE_TYPES and token not in clean_priorities:
                clean_priorities.append(token)
            elif token:
                warnings.append("sumber '%s' tidak dikenal dan diabaikan." % token)
        dna["sources_priority"] = clean_priorities or list(SOURCE_TYPES)
    elif priorities is not None:
        warnings.append("sources_priority harus berupa list, memakai urutan default.")

    continuity = str(payload.get("visual_continuity") or "").strip().lower()
    if continuity in VISUAL_CONTINUITY:
        dna["visual_continuity"] = continuity
    elif continuity:
        warnings.append("visual_continuity tidak dikenal, memakai 'editorial_varied'.")

    layout = str(payload.get("layout_pattern") or "").strip().lower()
    if layout in LAYOUT_PATTERNS:
        dna["layout_pattern"] = layout
    elif layout:
        warnings.append("layout_pattern tidak dikenal, memakai 'fullscreen'.")

    audio = str(payload.get("audio_modality") or "").strip().lower()
    if audio in AUDIO_MODALITIES:
        dna["audio_modality"] = audio
    elif audio:
        warnings.append("audio_modality tidak dikenal, memakai 'vo_plus_bgm'.")

    pacing_raw = payload.get("pacing_profile")
    pacing = dna["pacing_profile"]
    if isinstance(pacing_raw, dict):
        style = str(pacing_raw.get("style") or "").strip().lower()
        if style in PACING_STYLES:
            pacing["style"] = style
        elif style:
            warnings.append("pacing style tidak dikenal, memakai default format.")
        try:
            avg = float(pacing_raw.get("avg_scene_sec", pacing["avg_scene_sec"]))
            pacing["avg_scene_sec"] = round(avg, 2) if avg > 0 else pacing["avg_scene_sec"]
        except Exception:
            warnings.append("avg_scene_sec bukan angka, memakai default.")
        chapter_flag = pacing_raw.get("chapter_structure")
        if isinstance(chapter_flag, bool):
            pacing["chapter_structure"] = chapter_flag
        elif chapter_flag is not None:
            pacing["chapter_structure"] = str(chapter_flag).strip().lower() in ("1", "true", "yes", "ya")
    elif pacing_raw is not None:
        warnings.append("pacing_profile harus berupa objek, memakai default.")

    for text_key in ("visual_style_anchor", "mechanics_summary", "user_overrides"):
        value = payload.get(text_key)
        if isinstance(value, str):
            dna[text_key] = value.strip()[:4000]
        elif isinstance(value, list):
            dna[text_key] = " ".join(str(v).strip() for v in value if str(v).strip())[:4000]
        elif value is not None:
            warnings.append("%s harus berupa teks." % text_key)

    # Nilai tambahan dari user tetap dipertahankan (kontrak masa depan).
    for key, value in payload.items():
        if key not in dna:
            dna[key] = value

    return dna, warnings


def load_reference_dna(project: str, allow_missing: bool = True) -> Dict[str, Any]:
    """
    Baca kontrak DNA proyek. Bila belum ada, susun default dari aspect_ratio
    project.json (fallback universal, bukan 9:16 hardcode).
    """
    path = dna_path(project)
    fallback_aspect = project_aspect_ratio(project)
    if not os.path.exists(path):
        if not allow_missing:
            raise FileNotFoundError("reference_dna.json belum ada untuk proyek '%s'" % project)
        return default_dna(fallback_aspect, "short")
    try:
        with open(path, "r", encoding="utf-8") as f:
            payload = json.load(f)
    except Exception:
        return default_dna(fallback_aspect, "short")
    dna, _warnings = validate_dna(payload, fallback_aspect)
    return dna


def save_reference_dna(project: str, payload: Any) -> Dict[str, Any]:
    """Validasi lalu tulis reference_dna.json. Mengembalikan kontrak final."""
    clean_project = os.path.basename(str(project or "").strip())
    if not clean_project:
        raise ValueError("Nama proyek wajib diisi")
    fallback_aspect = project_aspect_ratio(clean_project)
    dna, warnings = validate_dna(payload, fallback_aspect)
    target_dir = project_dir(clean_project)
    os.makedirs(target_dir, exist_ok=True)
    with open(os.path.join(target_dir, DNA_FILENAME), "w", encoding="utf-8") as f:
        json.dump(dna, f, indent=2, ensure_ascii=False)
    dna["_warnings"] = warnings
    return dna


def scene_contract(scene: Any, dna: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    """
    Normalisasi kontrak satu scene: source_type, visual_goal, keywords, dll.

    Dipakai router & worker agar pembacaan kontrak konsisten tanpa mengunci
    scene ke satu jenis sumber.
    """
    scene = scene if isinstance(scene, dict) else {}
    dna = dna or default_dna()
    source_type = str(scene.get("source_type") or "").strip().lower()
    if source_type not in SOURCE_TYPES:
        source_type = ""
    keywords = scene.get("keywords")
    if isinstance(keywords, str):
        keywords = [k.strip() for k in keywords.split(",") if k.strip()]
    elif not isinstance(keywords, list):
        keywords = []
    keywords = [str(k).strip() for k in keywords if str(k).strip()]
    try:
        duration = float(scene.get("duration", 0) or 0)
    except Exception:
        duration = 0.0
    if duration <= 0:
        duration = float(dna.get("pacing_profile", {}).get("avg_scene_sec", 4) or 4)
    fallback_chain = scene.get("fallback_chain")
    if not isinstance(fallback_chain, list):
        fallback_chain = []
    fallback_chain = [str(s).strip().lower() for s in fallback_chain if str(s).strip().lower() in SOURCE_TYPES]
    return {
        "index": int(scene.get("index", scene.get("scene_index", 0)) or 0),
        "title": str(scene.get("title") or "").strip(),
        "narration": str(scene.get("narration") or "").strip(),
        "source_type": source_type,
        "visual_goal": str(scene.get("visual_goal") or scene.get("prompt") or "").strip(),
        "keywords": keywords,
        "style_anchor": str(scene.get("style_anchor") or dna.get("visual_style_anchor") or "").strip(),
        "fallback_chain": fallback_chain,
        "duration": duration,
        "seed": scene.get("seed"),
        "chapter": scene.get("chapter"),
        "graphic_template": str(scene.get("graphic_template") or "").strip(),
        "graphic_data": scene.get("graphic_data") if isinstance(scene.get("graphic_data"), dict) else {},
        "visual": str(scene.get("visual") or "").strip(),
        "raw": scene,
    }


def cli_main(argv: Optional[List[str]] = None) -> int:
    """CLI ringan: tampilkan / simpan kontrak DNA tanpa dependensi lain."""
    import argparse

    parser = argparse.ArgumentParser(prog="reference_dna.py", description="ContentStudio Reference DNA")
    sub = parser.add_subparsers(dest="cmd")

    p_show = sub.add_parser("show", help="Tampilkan kontrak DNA proyek")
    p_show.add_argument("--project", required=True)

    p_save = sub.add_parser("save", help="Simpan kontrak DNA dari file JSON atau flag")
    p_save.add_argument("--project", required=True)
    p_save.add_argument("--json", dest="json_file")
    p_save.add_argument("--format-scale", default=None)
    p_save.add_argument("--aspect-ratio", default=None)
    p_save.add_argument("--resolution", default=None)
    p_save.add_argument("--visual-style-anchor", default=None)
    p_save.add_argument("--mechanics-summary", default=None)
    p_save.add_argument("--user-overrides", default=None)

    args = parser.parse_args(argv or sys.argv[1:])
    if args.cmd == "show":
        dna = load_reference_dna(args.project)
        print(json.dumps(dna, indent=2, ensure_ascii=False))
        return 0
    if args.cmd == "save":
        if args.json_file:
            with open(args.json_file, "r", encoding="utf-8") as f:
                payload = json.load(f)
        else:
            payload = load_reference_dna(args.project)
        if args.format_scale:
            payload["format_scale"] = args.format_scale
        if args.aspect_ratio:
            payload["aspect_ratio"] = args.aspect_ratio
        if args.resolution:
            payload["resolution"] = args.resolution
        if args.visual_style_anchor is not None:
            payload["visual_style_anchor"] = args.visual_style_anchor
        if args.mechanics_summary is not None:
            payload["mechanics_summary"] = args.mechanics_summary
        if args.user_overrides is not None:
            payload["user_overrides"] = args.user_overrides
        dna = save_reference_dna(args.project, payload)
        print(json.dumps(dna, indent=2, ensure_ascii=False))
        return 0
    parser.print_help()
    return 1


if __name__ == "__main__":
    raise SystemExit(cli_main())
