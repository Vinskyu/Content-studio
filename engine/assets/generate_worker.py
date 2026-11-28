#!/usr/bin/env python3
"""
ContentStudio Generate Worker (engine/assets/generate_worker.py)

Menghasilkan gambar AI dengan rasio dinamis (16:9, 9:16, 1:1, 4:5).

Prioritas provider:
  1. BYOK `GEMINI_API_KEY` (bila aktif)
  2. BYOK `OPENAI_API_KEY` (bila aktif)
  3. Fallback zero-cost Pollinations Flux (selalu tersedia tanpa key)

Saat `visual_continuity == "strict_consistent"`, `style_anchor` + seed dasar
proyek digabung agar seluruh scene/bab seragam gayanya.
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

import base64
import hashlib
import json
import shutil
import subprocess
import time
import urllib.parse
import urllib.request
from typing import Any, Dict, Optional, Tuple

from engine.runtime import get_ffmpeg_bin
from engine.reference_dna import generation_size, normalize_aspect_ratio, normalize_format_scale

POLLINATIONS_ENDPOINT = "https://image.pollinations.ai/prompt/"
DEFAULT_TIMEOUT = 90
MAX_RETRIES = 3

GERBANG_KUALITAS = (
    "clean composition, sharp focus, high detail, professional lighting, "
    "no text artifacts, no watermark, no logo"
)


def _emit_step(message: str) -> None:
    sys.stderr.write("[STEP] %s\n" % message)
    sys.stderr.flush()


def _emit_think(message: str) -> None:
    sys.stderr.write("[THINK] %s\n" % message)
    sys.stderr.flush()


def _has_key(name: str) -> bool:
    return bool(str(os.environ.get(name) or "").strip())


def _seed_from_project(project: str) -> int:
    digest = hashlib.sha256(str(project or "contentstudio").encode("utf-8")).hexdigest()
    return int(digest[:8], 16) % 1_000_000


def build_prompt(visual_goal: str, dna: Optional[Dict[str, Any]] = None, shorten: bool = False,
                 negative_prompt: str = "", chapter: Any = None) -> str:
    """Gabungkan tujuan visual + anchor gaya + anti-prompt jadi satu prompt utuh."""
    dna = dna or {}
    style_anchor = str(dna.get("visual_style_anchor") or "").strip()
    layout = str(dna.get("layout_pattern") or "").strip()
    parts = [str(visual_goal or "").strip()]
    if style_anchor and not shorten:
        parts.append("gaya visual: " + style_anchor)
    if layout and layout != "fullscreen" and not shorten:
        parts.append("komposisi layout: " + layout.replace("_", " "))
    if chapter not in (None, "") and not shorten:
        parts.append("bagian dari bab %s" % str(chapter))
    parts.append(GERBANG_KUALITAS)
    if negative_prompt:
        parts.append("hindari: " + str(negative_prompt))
    prompt = ", ".join([p for p in parts if p])
    if shorten:
        prompt = prompt[:260]
    return prompt


def _download_bytes(url: str, timeout: int = DEFAULT_TIMEOUT) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "ContentStudio/3.6"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read()


def _looks_like_image(data: bytes) -> bool:
    if len(data) < 512:
        return False
    return (
        data.startswith(b"\xff\xd8\xff")
        or data.startswith(b"\x89PNG\r\n\x1a\n")
        or (data.startswith(b"RIFF") and data[8:12] == b"WEBP")
    )


def _pollinations(prompt: str, out_path: str, width: int, height: int, seed: int) -> None:
    params = {
        "width": str(width),
        "height": str(height),
        "seed": str(seed),
        "model": "flux",
        "nologo": "true",
    }
    url = POLLINATIONS_ENDPOINT + urllib.parse.quote(prompt, safe="") + "?" + urllib.parse.urlencode(params)
    last_error: Optional[Exception] = None
    for attempt in range(1, MAX_RETRIES + 1):
        try:
            _emit_think("Menyiapkan kanvas %dx%d dengan seed %d (percobaan %d)." % (width, height, seed, attempt))
            data = _download_bytes(url)
            if not _looks_like_image(data):
                raise ValueError("Respons bukan gambar valid")
            os.makedirs(os.path.dirname(os.path.abspath(out_path)) or ".", exist_ok=True)
            with open(out_path, "wb") as f:
                f.write(data)
            return
        except Exception as exc:
            last_error = exc
            time.sleep(min(4, attempt * 1.5))
    raise RuntimeError("Pollinations gagal setelah %d percobaan: %s" % (MAX_RETRIES, last_error))


def _gemini(prompt: str, out_path: str, seed: int) -> None:
    key = str(os.environ.get("GEMINI_API_KEY") or "").strip()
    if not key:
        raise RuntimeError("GEMINI_API_KEY tidak tersedia")
    model = str(os.environ.get("GEMINI_IMAGE_MODEL") or "gemini-2.5-flash-image").strip()
    url = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s" % (model, urllib.parse.quote(key))
    body = json.dumps({
        "contents": [{"parts": [{"text": prompt}]}],
        "generationConfig": {"seed": seed},
    }).encode("utf-8")
    req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=DEFAULT_TIMEOUT) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    for candidate in data.get("candidates", []):
        for part in (candidate.get("content") or {}).get("parts", []):
            inline = part.get("inlineData") or part.get("inline_data")
            if inline and inline.get("data"):
                raw = base64.b64decode(inline["data"])
                os.makedirs(os.path.dirname(os.path.abspath(out_path)) or ".", exist_ok=True)
                with open(out_path, "wb") as f:
                    f.write(raw)
                return
    raise RuntimeError("Respons Gemini tidak memuat gambar")


def _openai_size(aspect_ratio: str) -> str:
    ratio = normalize_aspect_ratio(aspect_ratio)
    if ratio == "16:9":
        return "1536x1024"
    if ratio in ("9:16", "4:5"):
        return "1024x1536"
    return "1024x1024"


def _openai(prompt: str, out_path: str, aspect_ratio: str) -> None:
    key = str(os.environ.get("OPENAI_API_KEY") or "").strip()
    if not key:
        raise RuntimeError("OPENAI_API_KEY tidak tersedia")
    base = str(os.environ.get("OPENAI_BASE_URL") or "https://api.openai.com/v1").rstrip("/")
    model = str(os.environ.get("OPENAI_IMAGE_MODEL") or "gpt-image-1").strip()
    body = json.dumps({
        "model": model,
        "prompt": prompt,
        "size": _openai_size(aspect_ratio),
        "n": 1,
    }).encode("utf-8")
    req = urllib.request.Request(
        base + "/images/generations",
        data=body,
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + key},
    )
    with urllib.request.urlopen(req, timeout=DEFAULT_TIMEOUT) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    item = (data.get("data") or [{}])[0]
    if item.get("b64_json"):
        raw = base64.b64decode(item["b64_json"])
    elif item.get("url"):
        raw = _download_bytes(item["url"])
    else:
        raise RuntimeError("Respons OpenAI tidak memuat gambar")
    os.makedirs(os.path.dirname(os.path.abspath(out_path)) or ".", exist_ok=True)
    with open(out_path, "wb") as f:
        f.write(raw)


def _crop_to_aspect(file_path: str, aspect_ratio: str) -> None:
    """Rapikan hasil provider yang ukurannya tidak persis sama dengan target."""
    ratio = normalize_aspect_ratio(aspect_ratio)
    try:
        from PIL import Image  # type: ignore
        with Image.open(file_path) as img:
            width, height = img.size
            target = {"16:9": 16 / 9, "9:16": 9 / 16, "1:1": 1.0, "4:5": 0.8}[ratio]
            current = width / float(height)
            if abs(current - target) < 0.02:
                return
            if current > target:
                new_w = int(round(height * target))
                left = (width - new_w) // 2
                img.crop((left, 0, left + new_w, height)).save(file_path)
            else:
                new_h = int(round(width / target))
                top = (height - new_h) // 2
                img.crop((0, top, width, top + new_h)).save(file_path)
            return
    except Exception:
        pass
    ffmpeg = get_ffmpeg_bin()
    if not ffmpeg or (not os.path.isabs(ffmpeg) and not shutil.which(ffmpeg)):
        return
    try:
        from engine.reference_dna import dimensions_for
        w, h = dimensions_for(ratio, "1080p")
        tmp = file_path + ".norm.png"
        subprocess.run(
            [ffmpeg, "-y", "-i", file_path,
             "-vf", "scale=%d:%d:force_original_aspect_ratio=increase,crop=%d:%d" % (w, h, w, h),
             tmp],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=60,
        )
        if os.path.exists(tmp) and os.path.getsize(tmp) > 0:
            os.replace(tmp, file_path)
    except Exception:
        pass


def generate_image(
    out_path: str,
    prompt: str,
    dna: Optional[Dict[str, Any]] = None,
    aspect_ratio: Optional[str] = None,
    seed: Optional[int] = None,
    negative_prompt: str = "",
    shorten: bool = False,
    project: str = "",
    scene_index: int = 0,
    force_provider: Optional[str] = None,
) -> Dict[str, Any]:
    """Generate satu gambar. Mengembalikan dict status + provider yang dipakai."""
    dna = dna or {}
    ratio = normalize_aspect_ratio(aspect_ratio or dna.get("aspect_ratio"), "16:9")
    width, height = generation_size(ratio)

    strict = str(dna.get("visual_continuity") or "") == "strict_consistent"
    base_seed = _seed_from_project(project) if (strict and project) else int(time.time() * 1000) % 1_000_000
    if seed is not None:
        try:
            base_seed = int(seed)
        except Exception:
            pass
    scene_seed = base_seed if strict else (base_seed + int(scene_index or 0) * 7) % 1_000_000

    full_prompt = build_prompt(prompt, dna, shorten=shorten, negative_prompt=negative_prompt)

    order = ["gemini", "openai", "pollinations"] if force_provider is None else [force_provider]
    if force_provider is None:
        order = ([p for p in ("gemini", "openai") if _has_key(p.upper() + "_API_KEY")] or []) + ["pollinations"]

    errors = []
    for provider in order:
        try:
            _emit_step("Generate gambar via %s (%dx%d, seed %d)" % (provider, width, height, scene_seed))
            if provider == "gemini":
                _gemini(full_prompt, out_path, scene_seed)
            elif provider == "openai":
                _openai(full_prompt, out_path, ratio)
            else:
                _pollinations(full_prompt, out_path, width, height, scene_seed)
            if not os.path.exists(out_path) or os.path.getsize(out_path) <= 0:
                raise RuntimeError("File hasil kosong")
            if provider in ("openai",):
                _crop_to_aspect(out_path, ratio)
            return {
                "status": "ok",
                "file": out_path,
                "provider": provider,
                "seed": scene_seed,
                "aspect_ratio": ratio,
                "width": width,
                "height": height,
                "kind": "image",
                "prompt": full_prompt,
            }
        except Exception as exc:
            errors.append("%s: %s" % (provider, str(exc)[:160]))
            _emit_think("Provider %s belum berhasil, menimbang jalur berikutnya." % provider)
            continue

    return {
        "status": "error",
        "message": "Semua provider generate gagal (" + "; ".join(errors) + ")",
        "file": out_path,
    }


def worker_contract() -> Dict[str, Any]:
    return {"source": "generate", "providers": ["gemini", "openai", "pollinations"], "kind": "image"}


def cli_main(argv=None) -> int:
    import argparse

    parser = argparse.ArgumentParser(prog="generate_worker.py", description="ContentStudio Generate Worker")
    parser.add_argument("--project", default="")
    parser.add_argument("--out", required=True)
    parser.add_argument("--prompt", required=True)
    parser.add_argument("--aspect-ratio", default=None)
    parser.add_argument("--seed", type=int, default=None)
    parser.add_argument("--negative-prompt", default="")
    parser.add_argument("--scene-index", type=int, default=0)
    parser.add_argument("--provider", default=None, choices=["gemini", "openai", "pollinations"])
    args = parser.parse_args(argv if argv is not None else sys.argv[1:])

    dna = None
    if args.project:
        from engine.reference_dna import load_reference_dna
        dna = load_reference_dna(args.project)
    res = generate_image(
        args.out, args.prompt, dna=dna, aspect_ratio=args.aspect_ratio,
        seed=args.seed, negative_prompt=args.negative_prompt,
        project=args.project, scene_index=args.scene_index, force_provider=args.provider,
    )
    print(json.dumps(res, indent=2, ensure_ascii=False))
    return 0 if res.get("status") == "ok" else 1


if __name__ == "__main__":
    raise SystemExit(cli_main())
