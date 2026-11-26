#!/usr/bin/env python3
"""
ContentStudio Autonomous Director Engine (engine/director.py)
Sprint G: Otak Mandiri Sutradara Konten & Calon Backend APK Android
- Standar Produksi Wajib 1080p Full HD (1080x1920)
- Subcommands:
    * workspaces : Menampilkan daftar akun & status DNA Skill (.agents/skills/<ws>-director/SKILL.md)
    * plan       : Menyusun plan.json & contract.json otonom berbasis LLM + Guardrails + Self-Healing
    * render     : Eksekusi build.py dengan event streaming terstruktur (--json-events)
"""

import sys
import os

# Mencegah direktori engine/ membayangi modul library standar Python
_script_dir = os.path.dirname(os.path.abspath(__file__))
if sys.path and sys.path[0] == _script_dir:
    sys.path.pop(0)
_root_dir = os.path.dirname(_script_dir)
if _root_dir not in sys.path:
    sys.path.append(_root_dir)

import re
import json
import time
import argparse
import subprocess
import shutil
from typing import Dict, Any, List, Optional, Tuple, Callable

# Import router untuk pemanggilan AI multi-provider
from engine.router import route_call
from engine.runtime import (
    get_root_dir,
    get_ffmpeg_bin,
    get_ffprobe_bin,
    get_output_base_dir,
    get_workspaces_dir,
    get_skills_dir,
    get_assets_dir,
    get_fonts_dir,
    resolve_path,
    load_runtime_env
)

BASE_DIR = get_root_dir()
ENGINE_DIR = os.path.join(BASE_DIR, "engine")
WORKSPACES_DIR = get_workspaces_dir()
SKILLS_DIR = get_skills_dir()
ASSETS_LIB_PATH = os.path.join(get_assets_dir(), "library.json")
FONTS_DIR = get_fonts_dir()
DEFAULT_OUT_BASE = get_output_base_dir()
FALLBACK_OUT_BASE = os.path.join(BASE_DIR, "out")

# ==============================================================================
# EVENT EMITTER HELPER (ANDROID KOTLIN / FOREGROUND SERVICE READY)
# ==============================================================================
def emit_event(stage: str, pct: int, msg: str, json_events: bool = False,
               on_event: Optional[Callable[[Dict[str, Any]], None]] = None) -> None:
    """Mengeluarkan event progres terstruktur (JSON jika diminta, atau callback in-process)."""
    event = {
        "stage": stage,
        "pct": max(0, min(100, int(pct))),
        "msg": msg
    }
    if on_event:
        try:
            on_event(event)
        except Exception:
            pass
    if json_events:
        print(json.dumps(event, ensure_ascii=False), flush=True)
    elif not on_event:
        print(f"[{pct:>3}%] [{stage.upper():<12}] {msg}", flush=True)

# ==============================================================================
# MEDIA INSPECTION & PROBING
# ==============================================================================
def probe_media_file(file_path: str) -> Dict[str, Any]:
    """Membaca spesifikasi teknis media via ffprobe (stdlib murni)."""
    abs_path = os.path.abspath(file_path)
    if not os.path.exists(abs_path):
        raise FileNotFoundError(f"File media tidak ditemukan: {file_path}")

    ext = os.path.splitext(file_path)[1].lower()
    is_image = ext in {".png", ".jpg", ".jpeg", ".webp"}

    cmd = [
        get_ffprobe_bin(), "-v", "error",
        "-show_entries", "format=duration,size:stream=index,codec_name,codec_type,width,height,r_frame_rate,channels,duration",
        "-of", "json",
        abs_path
    ]
    try:
        res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=True)
        data = json.loads(res.stdout)
    except Exception as e:
        return {
            "path": abs_path,
            "filename": os.path.basename(abs_path),
            "is_image": is_image,
            "duration": 0.0,
            "width": 0,
            "height": 0,
            "aspect": "unknown",
            "error": str(e)
        }

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

    # Identifikasi gambar jika tidak ada audio dan durasi 0 atau format image
    if is_image or (v_stream and not a_stream and dur <= 0.1):
        is_image = True
        dur = 0.0

    aspect = "16:9"
    if w > 0 and h > 0:
        ratio = w / h
        if abs(ratio - 9/16) < 0.1:
            aspect = "9:16"
        elif abs(ratio - 16/9) < 0.1:
            aspect = "16:9"
        elif abs(ratio - 1.0) < 0.1:
            aspect = "1:1"
        elif abs(ratio - 4/5) < 0.1:
            aspect = "4:5"
        else:
            aspect = f"{w}:{h}"

    return {
        "path": abs_path,
        "filename": os.path.basename(abs_path),
        "is_image": is_image,
        "duration": round(dur, 2),
        "width": w,
        "height": h,
        "aspect": aspect,
        "has_audio": a_stream is not None,
        "has_video": v_stream is not None
    }

def load_asset_library() -> List[Dict[str, Any]]:
    """Membaca daftar aset fisik lokal dari assets/library.json (Anti-Halusinasi Path)."""
    if not os.path.exists(ASSETS_LIB_PATH):
        try:
            from engine.assets import scan_assets_library
            res = scan_assets_library()
            return res.get("assets", [])
        except Exception:
            return []
    try:
        with open(ASSETS_LIB_PATH, "r", encoding="utf-8") as f:
            data = json.load(f)
        return data.get("assets", [])
    except Exception:
        return []

# ==============================================================================
# SUBCOMMAND 1: WORKSPACES
# ==============================================================================
def get_workspaces_list() -> List[Dict[str, Any]]:
    """Memindai daftar workspace dan status Skill DNA sutradara secara in-process."""
    if not os.path.exists(WORKSPACES_DIR):
        return []

    results = []
    ws_entries = sorted([d for d in os.listdir(WORKSPACES_DIR) if not d.startswith(".") and os.path.isdir(os.path.join(WORKSPACES_DIR, d))])

    for ws in ws_entries:
        ws_path = os.path.join(WORKSPACES_DIR, ws)
        skill_dir = os.path.join(SKILLS_DIR, f"{ws}-director")
        skill_file = os.path.join(skill_dir, "SKILL.md")
        skill_ready = os.path.exists(skill_file)

        niche_title = "-"
        proj_file = os.path.join(ws_path, "project.json")
        if os.path.exists(proj_file):
            try:
                with open(proj_file, "r", encoding="utf-8") as f:
                    p_data = json.load(f)
                    niche_title = p_data.get("name", ws)
            except Exception:
                pass

        results.append({
            "workspace": ws,
            "status": "ready" if skill_ready else "missing",
            "skill_path": os.path.relpath(skill_file, BASE_DIR) if skill_ready else None,
            "niche": niche_title
        })

    return results

def cmd_workspaces(args) -> int:
    """Memindai daftar workspace dan status Skill DNA sutradara (CLI handler)."""
    if not os.path.exists(WORKSPACES_DIR):
        print(f"[!] Direktori workspaces tidak ditemukan di: {WORKSPACES_DIR}", file=sys.stderr)
        return 1

    results = get_workspaces_list()

    if getattr(args, "json", False):
        print(json.dumps(results, indent=2, ensure_ascii=False))
        return 0

    print("=" * 80)
    print("                     CONTENTSTUDIO WORKSPACE DIRECTORY                        ")
    print("=" * 80)
    print(f"{'No':<4} | {'Workspace':<18} | {'Status DNA':<10} | {'Niche / Deskripsi':<40}")
    print("-" * 80)
    for idx, item in enumerate(results, start=1):
        status_str = "READY" if item["status"] == "ready" else "MISSING"
        print(f"#{idx:<3} | {item['workspace']:<18} | {status_str:<10} | {item['niche']:<40}")
    print("=" * 80)
    return 0

# ==============================================================================
# SUBCOMMAND 2: PLAN (SUTRADARA OTONOM + GUARDRAIL + SELF-HEALING)
# ==============================================================================
def clean_llm_json(raw_text: str) -> str:
    """Membersihkan format output LLM (menghilangkan triple backticks / narasi sampingan)."""
    text = raw_text.strip()
    # Ekstrak substring antara ```json dan ``` jika ada
    m = re.search(r"```(?:json)?\s*([\s\S]*?)\s*```", text)
    if m:
        text = m.group(1).strip()
    else:
        # Coba ambil substring dari kurung kurawal pertama hingga kurung kurawal terakhir
        first_brace = text.find("{")
        last_brace = text.rfind("}")
        if first_brace != -1 and last_brace != -1 and last_brace > first_brace:
            text = text[first_brace : last_brace + 1].strip()
    return text

def apply_hard_guardrails(plan: Dict[str, Any], workspace: str, project: str,
                          media_specs: List[Dict[str, Any]], target_duration: float,
                          vo_path: Optional[str] = None, bgm_path: Optional[str] = None) -> Dict[str, Any]:
    """
    Menerapkan Hard Guardrail Python untuk mengunci kualitas & mencegah halusinasi:
    1. Output resolution wajib minimal 1080p (1080x1920)
    2. Path segmen dipetakan ke media fisik nyata
    3. Gambar statis diberi properti motion & durasi
    4. Guardrail audio vo/bgm
    """
    # 1. Output Guardrail
    out_cfg = plan.setdefault("output", {})
    # KUNCI 1080P: Jika LLM menulis 720p atau nilai lain di bawah 1080p, paksa 1080p
    out_cfg["resolution"] = "1080p"
    out_cfg["aspect"] = "9:16"
    out_cfg["fps"] = 30
    out_cfg["quality"] = "balanced"

    # Penentuan out_dir aman
    out_base = get_output_base_dir()
    out_cfg["out_dir"] = os.path.join(out_base, workspace, project)
    out_cfg["filename"] = out_cfg.get("filename", "output.mp4") or "output.mp4"

    # 2. Visual Polish Guardrail
    v_polish = plan.setdefault("visual_polish", {})
    if not v_polish.get("color_preset"):
        v_polish["color_preset"] = "vibrant_gameplay"

    # 3. Segments Guardrail & Source Matching
    valid_paths = [m["path"] for m in media_specs]
    segments = plan.get("segments", [])
    if not isinstance(segments, list) or len(segments) == 0:
        # Buat segmen default dari media yang ada jika LLM gagal menulis segmen
        segments = []
        seg_dur = target_duration / max(1, len(media_specs))
        cur_t = 0.0
        for m in media_specs:
            seg = {
                "source": m["path"],
                "start": 0.0,
                "end": round(cur_t + seg_dur, 2),
                "framing": "blur_fill" if m["aspect"] == "16:9" else "crop"
            }
            if m["is_image"]:
                seg["motion"] = "zoom_in"
                seg["duration"] = round(seg_dur, 2)
            segments.append(seg)
            cur_t += seg_dur

    normalized_segments = []
    current_time = 0.0

    for idx, seg in enumerate(segments):
        src = seg.get("source", "")
        # Cocokkan src dengan file media terdekat
        matched_spec = None
        for m in media_specs:
            if src == m["path"] or os.path.basename(src) == m["filename"] or m["filename"] in src:
                matched_spec = m
                break
        if not matched_spec:
            # Fallback ke index segmen modulo media
            matched_spec = media_specs[idx % len(media_specs)]

        seg["source"] = matched_spec["path"]

        # Framing
        if "framing" not in seg or seg["framing"] not in {"crop", "fit_blur", "fit_black", "split", "blur_fill"}:
            seg["framing"] = "blur_fill" if matched_spec["aspect"] == "16:9" else "crop"

        # Durasi & Motion
        if matched_spec["is_image"]:
            if not seg.get("motion") or seg["motion"] not in {"zoom_in", "zoom_out", "pan_left", "pan_right", "static"}:
                seg["motion"] = "zoom_in"
            dur = float(seg.get("duration", 0.0) or (float(seg.get("end", 0.0)) - float(seg.get("start", 0.0))))
            if dur <= 0.5:
                dur = 3.5
            seg["duration"] = round(dur, 2)
            seg["start"] = 0.0
            seg["end"] = round(dur, 2)
            current_time += dur
        else:
            s_val = float(seg.get("start", 0.0))
            e_val = float(seg.get("end", 0.0))
            if e_val <= s_val:
                clip_dur = matched_spec["duration"] if matched_spec["duration"] > 0 else 4.0
                e_val = min(s_val + 3.5, clip_dur)
            seg["start"] = round(s_val, 2)
            seg["end"] = round(e_val, 2)
            current_time += (e_val - s_val)

        normalized_segments.append(seg)

    # Sesuaikan segmen terakhir agar total durasi mendekati target_duration
    if normalized_segments and abs(current_time - target_duration) > 0.5:
        diff = target_duration - current_time
        last_seg = normalized_segments[-1]
        if "duration" in last_seg and last_seg.get("motion"):
            new_dur = max(1.0, round(float(last_seg["duration"]) + diff, 2))
            last_seg["duration"] = new_dur
            last_seg["end"] = new_dur
        else:
            new_end = max(float(last_seg["start"]) + 1.0, round(float(last_seg["end"]) + diff, 2))
            last_seg["end"] = new_end

    plan["segments"] = normalized_segments

    # 4. Audio Guardrail
    audio_cfg = plan.setdefault("audio_tracks", {})
    if vo_path and os.path.exists(vo_path):
        audio_cfg["voiceover"] = {
            "source": os.path.abspath(vo_path),
            "volume_db": -1.5,
            "start": 0.0
        }
    if bgm_path and os.path.exists(bgm_path):
        audio_cfg["bgm"] = {
            "source": os.path.abspath(bgm_path),
            "volume_db": -16.0,
            "offset": 0.0,
            "loop": True,
            "fade_in": 0.5,
            "fade_out": 1.0
        }

    if vo_path and bgm_path:
        audio_cfg["auto_ducking"] = True
        out_cfg["dual_audio"] = True

    # 5. Overlays Guardrail (Buang yang path-nya fiktif)
    overlays = plan.get("overlays", [])
    if isinstance(overlays, list):
        clean_overlays = []
        for ov in overlays:
            if isinstance(ov, dict) and ov.get("src") and os.path.exists(os.path.abspath(ov["src"])):
                clean_overlays.append(ov)
        plan["overlays"] = clean_overlays

    # 6. Texts Guardrail
    texts = plan.get("texts", [])
    if isinstance(texts, list):
        for t in texts:
            if isinstance(t, dict):
                # Normalisasi text/content
                if not t.get("text") and t.get("content"):
                    t["text"] = t.get("content")
                # Normalisasi font agar valid dengan manifest
                t["font"] = "Montserrat-ExtraBold"
                # Validasi animasi
                if t.get("animation") not in {"none", "pop_punch", "clean_minimal", "header_hook", "chapter_card"}:
                    t["animation"] = "header_hook" if float(t.get("start", 0)) < 3.0 else "clean_minimal"

    return plan

def run_self_healing_validation(plan_path: str, max_retries: int = 2,
                                json_events: bool = False,
                                on_event: Optional[Callable[[Dict[str, Any]], None]] = None) -> Tuple[bool, str]:
    """Menjalankan validasi build.py --validate dengan loop self-healing LLM jika ada kesalahan (in-process)."""
    from engine.build import validate_plan_file
    for attempt in range(max_retries + 1):
        is_ok, val_msg = validate_plan_file(plan_path)

        if is_ok:
            return True, "Preflight validation sukses 100%."

        err_out = val_msg.strip()
        emit_event("heal_retry", 85, f"Validasi gagal (percobaan {attempt + 1}/{max_retries + 1}): {err_out[:120]}...", json_events, on_event=on_event)

        if attempt == max_retries:
            return False, f"Gagal validasi setelah {max_retries} perbaikan otomatis: {err_out}"

        # Jalankan LLM Repair via router.py
        with open(plan_path, "r", encoding="utf-8") as f:
            broken_json_str = f.read()

        repair_prompt = f"""Kamu adalah asisten validator ContentStudio engine/build.py.
Rencana video (plan.json) berikut menghasilkan error validasi:
---
{err_out}
---
Berikut adalah isi plan.json yang bermasalah:
{broken_json_str}

Instruksi Perbaikan:
1. Perbaiki kesalahan spesifik yang disebutkan di atas.
2. Pastikan 'resolution' tetap '1080p', 'aspect' tetap '9:16'.
3. Seluruh path file harus sesuai dengan file asli.
4. Keluarkan HANYA satu JSON objek lengkap yang telah diperbaiki tanpa kata pengantar atau markdown.
"""
        try:
            repaired_raw = route_call(task="json", prompt=repair_prompt)
            repaired_clean = clean_llm_json(repaired_raw)
            repaired_dict = json.loads(repaired_clean)
            with open(plan_path, "w", encoding="utf-8") as f:
                json.dump(repaired_dict, f, indent=2, ensure_ascii=False)
        except Exception as e:
            emit_event("heal_error", 85, f"Error saat perbaikan mandiri via LLM: {e}", json_events)

    return False, "Batas perbaikan terlampaui."

def create_plan_pipeline(req_dict: Dict[str, Any],
                         on_event: Optional[Callable[[Dict[str, Any]], None]] = None,
                         json_events: bool = False) -> Dict[str, Any]:
    """Menyusun plan.json & contract.json secara in-process berbasis LLM + Guardrails + Self-Healing."""
    workspace = req_dict.get("workspace", "")
    project = req_dict.get("project", "")
    topic = req_dict.get("topic", "")
    media_paths = req_dict.get("media") or []
    vo_path = req_dict.get("vo")
    bgm_path = req_dict.get("bgm")
    target_duration = float(req_dict.get("duration", 45.0) or 45.0)

    # 1. INIT & BACA KONSTITUSI WORKSPACE
    emit_event("init", 5, f"Menginisialisasi workspace '{workspace}' untuk proyek '{project}'...", json_events, on_event=on_event)
    ws_dir = os.path.join(WORKSPACES_DIR, workspace)
    if not os.path.exists(ws_dir):
        msg = f"Workspace '{workspace}' tidak ditemukan di {ws_dir}"
        emit_event("failed", 0, msg, json_events, on_event=on_event)
        return {"status": "error", "message": msg}

    project_dir = os.path.join(ws_dir, "projects", project)
    os.makedirs(project_dir, exist_ok=True)

    # Baca Skill DNA & aturan
    skill_file = os.path.join(SKILLS_DIR, f"{workspace}-director", "SKILL.md")
    skill_dna_content = ""
    if os.path.exists(skill_file):
        with open(skill_file, "r", encoding="utf-8") as f:
            skill_dna_content = f.read()

    style_content = ""
    proj_file = os.path.join(ws_dir, "project.json")
    if os.path.exists(proj_file):
        try:
            with open(proj_file, "r", encoding="utf-8") as f:
                style_content = f.read()
        except Exception:
            pass

    # 2. INSPEKSI MEDIA
    emit_event("inspect", 15, f"Menginspeksi {len(media_paths)} file media sumber...", json_events, on_event=on_event)
    media_specs = []
    for p in media_paths:
        try:
            spec = probe_media_file(p)
            media_specs.append(spec)
        except Exception as e:
            emit_event("warn", 15, f"Gagal inspeksi media {p}: {e}", json_events, on_event=on_event)

    vo_transcript = ""
    if vo_path and os.path.exists(vo_path):
        emit_event("transcribe", 25, "Memproses transkripsi Voiceover via inspect.py (in-process)...", json_events, on_event=on_event)
        try:
            from engine.inspect import transcribe_audio
            tr_data = transcribe_audio(media_path=vo_path, out_dir=project_dir)
            vo_transcript = tr_data.get("text", "")
        except Exception as e:
            emit_event("warn", 25, f"Transkripsi audio dilewati: {e}", json_events, on_event=on_event)

    # 3. BACA KATALOG ASET LOKAL (ANTI HALUSINASI)
    emit_event("assets", 35, "Memuat katalog aset fisik dari assets/library.json...", json_events, on_event=on_event)
    available_assets = load_asset_library()
    asset_context = []
    for a in available_assets:
        asset_context.append({
            "id": a.get("id"),
            "name": a.get("name"),
            "type": a.get("type"),
            "path": a.get("path"),
            "tags": a.get("tags", []),
            "meta": a.get("meta", {})
        })

    # 4. SUSUN PROMPT KETAT UNTUK ROUTER LLM
    emit_event("prompting", 50, "Menyusun skenario kontrak video sesuai standar Full HD 1080p...", json_events, on_event=on_event)
    prompt_builder = f"""Kamu adalah Sutradara Konten Otomatis & Video Editor Pro di ContentStudio.
Tugasmu adalah membuat file 'plan.json' yang valid 100% untuk dieksekusi oleh 'engine/build.py'.

INFORMASI PROYEK:
- Workspace: {workspace}
- Proyek: {project}
- Topik / Ide Konten: "{topic}"
- Target Total Durasi: {target_duration} detik
- Standar Wajib: Full HD 1080p (resolution="1080p", aspect="9:16", fps=30)

SKILL DNA & PEDOMAN GAYA WORKSPACE:
{skill_dna_content[:1500]}
{style_content[:1000]}

MEDIA FISIK YANG TERSEDIA (DILARANG MENGARANG PATH LAIN!):
{json.dumps(media_specs, indent=2)}

ASET STIKER/BACKGROUND/SFX FISIK TERSEDIA (HANYA GUNAKAN PATH BERIKUT JIKA PAKAI OVERLAY):
{json.dumps(asset_context, indent=2)}

AUDIO:
- Voiceover: {vo_path or "Tidak ada (Gunakan Text Hook & Gameplay Audio)"}
{f"- Transkrip VO: {vo_transcript}" if vo_transcript else ""}
- BGM: {bgm_path or "Tidak ada"}

ATURAN STRUKTUR PLAN.JSON UNTUK ENGINE/BUILD.PY:
1. "output":
   "resolution": "1080p" (Wajib 1080p)
   "aspect": "9:16"
   "fps": 30
   "quality": "balanced"
   "audio_strategy": "balanced"
   "out_dir": "{DEFAULT_OUT_BASE}/{workspace}/{project}"
   "filename": "output.mp4"
   "dual_audio": true (jika ada VO dan BGM)

2. "segments":
   - Bagi total durasi ({target_duration}s) ke dalam segmen-segmen berdurasi 2.5 - 4.5 detik.
   - PENTING: Jika segmen berupa video 16:9 gameplay, gunakan "framing": "blur_fill" dengan "bg_dim": 0.15.
   - PENTING: Jika segmen berupa gambar statis (.png/.jpg), sertakan "motion": "zoom_in" (atau "zoom_out"/"pan_left") dan "duration": <detik>.
   - "source" WAJIB menggunakan path persis dari daftar MEDIA FISIK di atas!

3. "texts":
   - Buat Hook pembuka (detik 0.0 - 3.5) menggunakan "animation": "header_hook", "font": "Montserrat-ExtraBold", "line_colors": ["#FFEE00", "#FFFFFF", "#FF2222"].
   - Buat subtitle/poin lanjutan menggunakan "animation": "chapter_card" atau "pop_punch".

4. "overlays":
   - Opsional: Munculkan panah hook penunjuk dari daftar aset di atas jika relevan.

FORMAT KELUARAN:
Hanya keluarkan 1 JSON object lengkap tanpa markdown, tanpa ```json, tanpa penjelasan awal/akhir.
"""

    # 5. PEMANGGILAN ROUTER LLM (GROQ / GEMINI / OPENROUTER)
    emit_event("llm_call", 65, "Menghubungi AI Router untuk perumusan komposisi shot & subtitle...", json_events, on_event=on_event)
    try:
        raw_response = route_call(task="json", prompt=prompt_builder)
    except Exception as e:
        emit_event("warn", 65, f"AI Router call fallback: {e}", json_events, on_event=on_event)
        raw_response = "{}"

    # 6. PARSING & HARD GUARDRAIL PYTHON
    emit_event("guardrail", 75, "Menerapkan Hard Guardrail 1080p & normalisasi path...", json_events, on_event=on_event)
    cleaned_json = clean_llm_json(raw_response)
    try:
        raw_plan_dict = json.loads(cleaned_json)
    except Exception as e:
        emit_event("warn", 75, f"Gagal parse JSON murni dari LLM ({e}), menerapkan guardrail fallback...", json_events, on_event=on_event)
        raw_plan_dict = {}

    guarded_plan = apply_hard_guardrails(
        plan=raw_plan_dict,
        workspace=workspace,
        project=project,
        media_specs=media_specs,
        target_duration=target_duration,
        vo_path=vo_path,
        bgm_path=bgm_path
    )

    plan_file_path = os.path.join(project_dir, "plan.json")
    with open(plan_file_path, "w", encoding="utf-8") as f:
        json.dump(guarded_plan, f, indent=2, ensure_ascii=False)

    # 7. SELF-HEALING VALIDATION LOOP
    emit_event("validation", 85, "Memvalidasi plan.json terhadap engine/build.py (Self-Healing Loop)...", json_events, on_event=on_event)
    is_valid, val_msg = run_self_healing_validation(plan_file_path, max_retries=2, json_events=json_events, on_event=on_event)
    if not is_valid:
        msg = f"Validasi gagal: {val_msg}"
        emit_event("failed", 85, msg, json_events, on_event=on_event)
        return {"status": "error", "message": msg}

    # 8. BUAT RINGKASAN KONTRAK (CONTRACT.JSON)
    emit_event("contract", 95, "Menyimpan ringkasan kontrak proyek (contract.json)...", json_events, on_event=on_event)
    with open(plan_file_path, "r", encoding="utf-8") as f:
        final_plan = json.load(f)

    contract = {
        "project": project,
        "workspace": workspace,
        "topic": topic,
        "target_resolution": final_plan.get("output", {}).get("resolution", "1080p"),
        "aspect": final_plan.get("output", {}).get("aspect", "9:16"),
        "total_segments": len(final_plan.get("segments", [])),
        "target_duration": target_duration,
        "segments_summary": [
            {
                "index": i + 1,
                "source": os.path.basename(s.get("source", "")),
                "duration": s.get("duration") or round(float(s.get("end", 0)) - float(s.get("start", 0)), 2),
                "framing": s.get("framing"),
                "motion": s.get("motion", "none")
            }
            for i, s in enumerate(final_plan.get("segments", []))
        ],
        "texts_count": len(final_plan.get("texts", [])),
        "status": "ready_for_render"
    }
    contract_file_path = os.path.join(project_dir, "contract.json")
    with open(contract_file_path, "w", encoding="utf-8") as f:
        json.dump(contract, f, indent=2, ensure_ascii=False)

    emit_event("finalize", 100, f"Rencana selesai! Siap dirender di: {plan_file_path}", json_events, on_event=on_event)
    return {
        "status": "ok",
        "plan_path": plan_file_path,
        "contract_path": contract_file_path,
        "plan": final_plan,
        "contract": contract
    }

def cmd_plan(args) -> int:
    """Menyusun plan.json secara otonom dari input media dan instruksi ide konten (CLI wrapper)."""
    res = create_plan_pipeline(
        req_dict=vars(args),
        on_event=None,
        json_events=getattr(args, "json_events", False)
    )
    if res.get("status") == "ok":
        project_dir = os.path.dirname(res["plan_path"])
        print(f"\n[✓] SUKSES: plan.json dan contract.json tersimpan di {project_dir}")
        return 0
    else:
        print(f"[!] Error: {res.get('message')}", file=sys.stderr)
        return 1

# ==============================================================================
# SUBCOMMAND 3: RENDER (ORCHESTRATOR EKSEKUSI IN-PROCESS)
# ==============================================================================
def cmd_render(args, on_event: Optional[Callable[[Dict[str, Any]], None]] = None) -> int:
    """Mengeksekusi render pipeline secara in-process dan mengalirkan event progres."""
    json_events = getattr(args, "json_events", False)
    plan_path = os.path.abspath(args.plan)

    if not os.path.exists(plan_path):
        print(f"[!] File plan tidak ditemukan: {plan_path}", file=sys.stderr)
        return 1

    emit_event("start_render", 5, f"Memulai build pipeline untuk {os.path.basename(plan_path)}...", json_events, on_event=on_event)

    def _progress_adapter(stage: str, pct: int, msg: str):
        emit_event(stage, pct, msg, json_events=json_events, on_event=on_event)

    try:
        from engine.build import render_pipeline
        final_video = render_pipeline(
            plan_source=plan_path,
            is_preview=getattr(args, "preview", False),
            on_progress=_progress_adapter
        )
        emit_event("done", 100, f"Proses render selesai dengan sempurna 100%. Output: {final_video}", json_events, on_event=on_event)
        if final_video and not json_events:
            print(f"\n[OUTPUT_VIDEO] {final_video}")
        return 0
    except Exception as e:
        emit_event("failed", 0, f"Rendering gagal: {e}", json_events, on_event=on_event)
        return 1

# ==============================================================================
# MAIN CLI ENTRYPOINT
# ==============================================================================
def main():
    parser = argparse.ArgumentParser(
        description="ContentStudio Autonomous Director Engine (Sprint G)",
        formatter_class=argparse.RawDescriptionHelpFormatter
    )
    subparsers = parser.add_subparsers(dest="subcommand", help="Subcommand")

    # 1. workspaces
    p_ws = subparsers.add_parser("workspaces", help="Daftar workspace dan status Skill DNA")
    p_ws.add_argument("--json", action="store_true", help="Keluarkan output JSON murni")

    # 2. plan
    p_plan = subparsers.add_parser("plan", help="Susun rencana video 1080p secara mandiri berbasis AI")
    p_plan.add_argument("--workspace", required=True, help="Nama workspace target (misal: my_channel)")
    p_plan.add_argument("--project", required=True, help="Nama proyek baru")
    p_plan.add_argument("--topic", required=True, help="Judul atau ide konten video")
    p_plan.add_argument("--media", nargs="+", required=True, help="Daftar file media sumber (video/gambar)")
    p_plan.add_argument("--vo", default=None, help="File audio voiceover (opsional)")
    p_plan.add_argument("--bgm", default=None, help="File audio BGM (opsional)")
    p_plan.add_argument("--duration", type=float, default=45.0, help="Target total durasi video (default: 45s)")
    p_plan.add_argument("--json-events", action="store_true", help="Keluarkan streaming JSON event ke stdout")

    # 3. render
    p_rnd = subparsers.add_parser("render", help="Eksekusi build.py dengan event streaming")
    p_rnd.add_argument("plan", help="Path ke file plan.json")
    p_rnd.add_argument("--preview", action="store_true", help="Render mode cepat 360p")
    p_rnd.add_argument("--json-events", action="store_true", help="Keluarkan streaming JSON event ke stdout")

    args = parser.parse_args()
    if not args.subcommand:
        parser.print_help()
        sys.exit(1)

    if args.subcommand == "workspaces":
        sys.exit(cmd_workspaces(args))
    elif args.subcommand == "plan":
        sys.exit(cmd_plan(args))
    elif args.subcommand == "render":
        sys.exit(cmd_render(args))

if __name__ == "__main__":
    main()
