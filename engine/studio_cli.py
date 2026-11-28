#!/usr/bin/env python3
"""
ContentStudio Master CLI (engine/studio_cli.py)
Sprint K: Pintu masuk terpadu eksekusi AI Agent untuk seluruh alur produksi:
          Projects, Channels, Patterns/Templates, Scripts, Scenes, Voiceover & Mastering,
          BGM, Media Assignment, Video Rendering, dan Publish Kit.
Menghasilkan output JSON di stdout serta marker [STEP] & [UI_SYNC] <scope>:<target> di stderr.
"""

import sys
import os

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
from typing import Dict, Any, List, Optional

from engine.runtime import (
    get_root_dir,
    get_cache_dir,
    get_workspaces_dir,
    get_ffmpeg_bin,
    get_ffprobe_bin,
    resolve_path
)

def emit_step(message: str) -> None:
    """Mencetak log tahap proses ke stderr agar tertangkap oleh UI SSE."""
    sys.stderr.write(f"[STEP] {message}\n")
    sys.stderr.flush()

def emit_think(message: str) -> None:
    """Mencetak alur pikir ringkas ke stderr (tidak pernah ke stdout)."""
    sys.stderr.write(f"[THINK] {message}\n")
    sys.stderr.flush()

def emit_ui_sync(scope: str, target: str) -> None:
    """Mencetak event sinkronisasi UI ke stderr agar layar Android otomatis me-refresh."""
    sys.stderr.write(f"[UI_SYNC] {scope}:{target}\n")
    sys.stderr.flush()

def get_bridge_url() -> str:
    """Mendapatkan base URL server bridge lokal."""
    port = os.environ.get("BRIDGE_PORT") or os.environ.get("PORT") or "18950"
    return f"http://127.0.0.1:{port}"

def call_bridge(method: str, path: str, payload: Optional[Dict[str, Any]] = None, timeout: float = 10.0) -> Optional[Dict[str, Any]]:
    """Memanggil endpoint studio-bridge.js jika server aktif."""
    url = f"{get_bridge_url()}{path}"
    headers = {"Content-Type": "application/json"}
    data_bytes = json.dumps(payload).encode("utf-8") if payload is not None else None
    try:
        req = urllib.request.Request(url, data=data_bytes, headers=headers, method=method)
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
            return json.loads(raw)
    except Exception:
        return None

# ─── STORAGE & FILE HELPERS ───────────────────────────────────────────────────

def get_channels_path() -> str:
    return os.path.join(get_root_dir(), "channels.json")

def get_patterns_path() -> str:
    return os.path.join(get_root_dir(), "patterns.json")

def read_json_file(file_path: str, default: Any = None) -> Any:
    if os.path.exists(file_path):
        try:
            with open(file_path, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            pass
    return default

def write_json_file(file_path: str, data: Any) -> bool:
    try:
        os.makedirs(os.path.dirname(os.path.abspath(file_path)), exist_ok=True)
        tmp = file_path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2, ensure_ascii=False)
        os.replace(tmp, file_path)
        return True
    except Exception:
        return False

def get_workspace_dir(ws_name: str) -> str:
    return os.path.join(get_workspaces_dir(), os.path.basename(ws_name.strip()))

def read_project_json(ws_name: str) -> Dict[str, Any]:
    ws_dir = get_workspace_dir(ws_name)
    pj_path = os.path.join(ws_dir, "project.json")
    data = read_json_file(pj_path, {})
    if not isinstance(data, dict):
        data = {}
    data.setdefault("version", 1)
    data.setdefault("name", os.path.basename(ws_name))
    data.setdefault("channel_id", None)
    data.setdefault("aspect_ratio", "9:16")
    data.setdefault("stage", "script")
    data.setdefault("scenes", [])
    data.setdefault("audio", {"vo_file": None, "bgm_file": None, "bgm_volume": 0.18})
    data.setdefault("output_file", None)
    data.setdefault("updated_at", datetime.now().isoformat())
    return data

def write_project_json(ws_name: str, data: Dict[str, Any]) -> bool:
    ws_dir = get_workspace_dir(ws_name)
    pj_path = os.path.join(ws_dir, "project.json")
    data["name"] = os.path.basename(ws_name)
    data["updated_at"] = datetime.now().isoformat()
    return write_json_file(pj_path, data)

# ─── PROJECTS MANAGEMENT ──────────────────────────────────────────────────────

def cmd_project_list() -> List[Dict[str, Any]]:
    emit_step("Membaca daftar proyek ContentStudio...")
    res = call_bridge("GET", "/workspaces")
    if res and res.get("status") == "ok":
        return res.get("workspaces", [])

    ws_base = get_workspaces_dir()
    projects = []
    if os.path.exists(ws_base):
        for name in sorted(os.listdir(ws_base)):
            p_dir = os.path.join(ws_base, name)
            if os.path.isdir(p_dir) and not name.startswith("."):
                pj = read_project_json(name)
                scenes = pj.get("scenes", [])
                projects.append({
                    "name": name,
                    "aspect_ratio": pj.get("aspect_ratio", "9:16"),
                    "stage": pj.get("stage", "script"),
                    "scenes_count": len(scenes) if isinstance(scenes, list) else 0,
                    "updated_at": pj.get("updated_at", "")
                })
    return projects

def cmd_project_create(name: str, ratio: str = "9:16", channel: Optional[str] = None) -> Dict[str, Any]:
    clean_name = os.path.basename(name.strip())
    emit_step(f"Membuat proyek baru '{clean_name}' (rasio {ratio})...")
    res = call_bridge("POST", "/workspaces", {"name": clean_name})
    ws_dir = get_workspace_dir(clean_name)
    os.makedirs(os.path.join(ws_dir, "scripts"), exist_ok=True)
    os.makedirs(os.path.join(ws_dir, "assets"), exist_ok=True)
    os.makedirs(os.path.join(ws_dir, "audio"), exist_ok=True)
    os.makedirs(os.path.join(ws_dir, "renders"), exist_ok=True)

    pj = read_project_json(clean_name)
    from engine.reference_dna import normalize_aspect_ratio, resolution_for
    pj["aspect_ratio"] = normalize_aspect_ratio(ratio, "9:16")
    pj["resolution"] = resolution_for(pj["aspect_ratio"], "1080p")
    if channel:
        pj["channel_id"] = channel.strip()
    write_project_json(clean_name, pj)

    emit_ui_sync("project", clean_name)
    emit_ui_sync("workspace", "list")
    return {"status": "ok", "project": clean_name, "aspect_ratio": pj["aspect_ratio"], "path": ws_dir}

def cmd_project_delete(name: str) -> Dict[str, Any]:
    clean_name = os.path.basename(name.strip())
    emit_step(f"Menghapus proyek '{clean_name}'...")
    res = call_bridge("DELETE", f"/workspaces/{clean_name}")
    ws_dir = get_workspace_dir(clean_name)
    if os.path.exists(ws_dir):
        shutil.rmtree(ws_dir, ignore_errors=True)
    emit_ui_sync("workspace", "list")
    return {"status": "ok", "deleted": clean_name}

def cmd_project_status(name: str) -> Dict[str, Any]:
    clean_name = os.path.basename(name.strip())
    pj = read_project_json(clean_name)
    ws_dir = get_workspace_dir(clean_name)
    script_file = os.path.join(ws_dir, "scripts", "main.txt")
    script_text = ""
    if os.path.exists(script_file):
        try:
            with open(script_file, "r", encoding="utf-8") as f:
                script_text = f.read()
        except Exception:
            pass

    return {
        "status": "ok",
        "name": clean_name,
        "aspect_ratio": pj.get("aspect_ratio", "9:16"),
        "stage": pj.get("stage", "script"),
        "channel_id": pj.get("channel_id"),
        "scenes_count": len(pj.get("scenes", [])),
        "has_vo": bool(pj.get("audio", {}).get("vo_file")),
        "has_bgm": bool(pj.get("audio", {}).get("bgm_file")),
        "script_length": len(script_text),
        "scenes": pj.get("scenes", []),
        "publish_kit": pj.get("publish_kit")
    }

# ─── CHANNELS & LIVING TEMPLATES ───────────────────────────────────────────────

def cmd_channel_list() -> List[Dict[str, Any]]:
    emit_step("Membaca daftar Channel DNA...")
    res = call_bridge("GET", "/channels")
    if res and res.get("status") == "ok":
        return res.get("channels", [])
    return read_json_file(get_channels_path(), [])

def cmd_channel_create(name: str, dna_notes: str = "", aspect_ratio: str = "9:16") -> Dict[str, Any]:
    emit_step(f"Membuat Channel DNA baru: '{name}'...")
    res = call_bridge("POST", "/channels", {
        "name": name,
        "aspect_ratio": aspect_ratio,
        "dna": {
            "reference_notes": dna_notes,
            "style_summary": dna_notes[:100],
            "avg_scene_duration": 3.5,
            "learned_traits": ["autonomous-v3"]
        }
    })
    if res and res.get("status") == "ok":
        emit_ui_sync("channel", "list")
        return res

    channels = read_json_file(get_channels_path(), [])
    cid = f"chan_{int(datetime.now().timestamp())}"
    new_c = {
        "id": cid,
        "name": name,
        "aspect_ratio": aspect_ratio,
        "dna": {
            "reference_notes": dna_notes,
            "style_summary": dna_notes[:100],
            "avg_scene_duration": 3.5,
            "learned_traits": ["autonomous-v3"]
        }
    }
    channels.append(new_c)
    write_json_file(get_channels_path(), channels)
    emit_ui_sync("channel", "list")
    return {"status": "ok", "channel": new_c}

def cmd_channel_link(project: str, channel_id: str) -> Dict[str, Any]:
    clean_p = os.path.basename(project.strip())
    emit_step(f"Menghubungkan proyek '{clean_p}' ke Channel '{channel_id}'...")
    pj = read_project_json(clean_p)
    pj["channel_id"] = channel_id.strip()
    write_project_json(clean_p, pj)
    emit_ui_sync("project", clean_p)
    return {"status": "ok", "project": clean_p, "channel_id": channel_id}

def cmd_template_list() -> List[Dict[str, Any]]:
    emit_step("Membaca daftar pola ritme template...")
    res = call_bridge("GET", "/patterns")
    if res and res.get("status") == "ok":
        return res.get("patterns", [])
    return read_json_file(get_patterns_path(), [])

def cmd_template_apply(project: str, template_id: str) -> Dict[str, Any]:
    clean_p = os.path.basename(project.strip())
    emit_step(f"Menerapkan pola ritme template '{template_id}' ke proyek '{clean_p}'...")
    res = call_bridge("POST", f"/workspaces/{clean_p}/apply-pattern", {"pattern_id": template_id})
    if res and res.get("status") == "ok":
        emit_ui_sync("project", clean_p)
        return res

    patterns = read_json_file(get_patterns_path(), [])
    matched = next((p for p in patterns if p.get("id") == template_id or p.get("name") == template_id), None)
    if not matched:
        return {"status": "error", "message": f"Template '{template_id}' tidak ditemukan"}

    beats = matched.get("beats", [])
    pj = read_project_json(clean_p)
    scenes = []
    for i, b in enumerate(beats):
        scenes.append({
            "id": f"scene_{i+1}",
            "title": b.get("role", f"Adegan {i+1}"),
            "beat_role": b.get("role", "Content"),
            "duration": float(b.get("duration", 3.0)),
            "narration": "",
            "prompt": "",
            "visual": None,
            "transition": "none"
        })
    pj["scenes"] = scenes
    pj["stage"] = "visual"
    write_project_json(clean_p, pj)
    emit_ui_sync("project", clean_p)
    return {"status": "ok", "project": clean_p, "scenes_count": len(scenes)}

# ─── SCRIPT & SCENES AUTOMATION ───────────────────────────────────────────────

def cmd_script_set(project: str, text: Optional[str] = None, file_path: Optional[str] = None) -> Dict[str, Any]:
    clean_p = os.path.basename(project.strip())
    content = ""
    if file_path and os.path.exists(file_path):
        with open(file_path, "r", encoding="utf-8") as f:
            content = f.read()
    elif text:
        content = text

    if not content.strip():
        return {"status": "error", "message": "Naskah tidak boleh kosong"}

    emit_step(f"Menyimpan naskah narasi untuk proyek '{clean_p}'...")
    ws_dir = get_workspace_dir(clean_p)
    sc_dir = os.path.join(ws_dir, "scripts")
    os.makedirs(sc_dir, exist_ok=True)
    with open(os.path.join(sc_dir, "main.txt"), "w", encoding="utf-8") as f:
        f.write(content.strip())

    pj = read_project_json(clean_p)
    pj["stage"] = "script"
    write_project_json(clean_p, pj)

    emit_ui_sync("project", clean_p)
    return {"status": "ok", "project": clean_p, "chars": len(content.strip())}

def cmd_script_split_scenes(project: str) -> Dict[str, Any]:
    clean_p = os.path.basename(project.strip())
    emit_step(f"Memecah naskah proyek '{clean_p}' menjadi rangkaian adegan dinamis...")
    ws_dir = get_workspace_dir(clean_p)
    sc_file = os.path.join(ws_dir, "scripts", "main.txt")
    if not os.path.exists(sc_file):
        return {"status": "error", "message": "File scripts/main.txt belum dibuat"}

    with open(sc_file, "r", encoding="utf-8") as f:
        raw = f.read().strip()

    paragraphs = [p.strip() for p in re.split(r"\n\s*\n|\r\n\s*\r\n", raw) if p.strip()]
    if len(paragraphs) <= 1:
        # Jika satu paragraf utuh, pecah berdasarkan batas kalimat
        sentences = re.split(r"(?<=[.!?])\s+", raw)
        paragraphs = [s.strip() for s in sentences if s.strip()]

    pj = read_project_json(clean_p)
    scenes = []
    roles = ["Hook", "Problem", "Insight", "Climax", "Call To Action"]

    for idx, p in enumerate(paragraphs):
        role = roles[idx] if idx < len(roles) else f"Beat {idx + 1}"
        word_count = len(p.split())
        dur = max(2.5, round(word_count * 0.35, 1))
        scenes.append({
            "id": f"scene_{idx + 1}",
            "title": f"Scene {idx + 1} - {role}",
            "beat_role": role,
            "duration": dur,
            "narration": p,
            "prompt": f"Cinematic shot illustrating {p[:60]}",
            "visual": None,
            "transition": "none"
        })

    pj["scenes"] = scenes
    pj["stage"] = "visual"
    write_project_json(clean_p, pj)
    emit_ui_sync("project", clean_p)
    return {"status": "ok", "project": clean_p, "scenes_count": len(scenes), "scenes": scenes}

def cmd_scene_set_visual(project: str, index: int, file_path: str) -> Dict[str, Any]:
    clean_p = os.path.basename(project.strip())
    pj = read_project_json(clean_p)
    scenes = pj.get("scenes", [])
    if index < 0 or index >= len(scenes):
        return {"status": "error", "message": f"Index adegan {index} di luar batas (0-{len(scenes)-1})"}

    emit_step(f"Menetapkan aset visual untuk adegan {index+1} proyek '{clean_p}'...")
    scenes[index]["visual"] = file_path
    write_project_json(clean_p, pj)
    emit_ui_sync("project", clean_p)
    return {"status": "ok", "scene_index": index, "visual": file_path}

def cmd_scene_set_prompt(project: str, index: int, prompt: str) -> Dict[str, Any]:
    clean_p = os.path.basename(project.strip())
    pj = read_project_json(clean_p)
    scenes = pj.get("scenes", [])
    if index < 0 or index >= len(scenes):
        return {"status": "error", "message": f"Index adegan {index} di luar batas"}

    emit_step(f"Menetapkan prompt visual adegan {index+1}...")
    scenes[index]["prompt"] = prompt.strip()
    write_project_json(clean_p, pj)
    emit_ui_sync("project", clean_p)
    return {"status": "ok", "scene_index": index, "prompt": prompt.strip()}

# ─── VOICEOVER, AUDIO MASTERING & BGM ─────────────────────────────────────────

def cmd_voice_generate(project: str, voice: str = "id-ID-ArdiNeural", text: Optional[str] = None) -> Dict[str, Any]:
    clean_p = os.path.basename(project.strip())
    emit_step(f"Menghasilkan audio Voiceover AI ({voice}) untuk proyek '{clean_p}'...")
    ws_dir = get_workspace_dir(clean_p)

    narration_text = text
    if not narration_text:
        sc_file = os.path.join(ws_dir, "scripts", "main.txt")
        if os.path.exists(sc_file):
            with open(sc_file, "r", encoding="utf-8") as f:
                narration_text = f.read().strip()

    if not narration_text:
        pj = read_project_json(clean_p)
        scenes = pj.get("scenes", [])
        narration_text = " ".join([s.get("narration", "") for s in scenes if s.get("narration")])

    if not narration_text:
        return {"status": "error", "message": "Tidak ada teks narasi untuk voiceover"}

    audio_dir = os.path.join(ws_dir, "audio")
    os.makedirs(audio_dir, exist_ok=True)
    out_vo = os.path.join(audio_dir, "vo.mp3")

    # Jalankan edge-tts via Python
    try:
        cmd = [
            sys.executable, "-m", "edge_tts",
            "--voice", voice,
            "--text", narration_text,
            "--write-media", out_vo
        ]
        res = subprocess.run(cmd, capture_output=True, text=True, timeout=90)
        if res.returncode != 0 or not os.path.exists(out_vo):
            raise RuntimeError(f"Gagal generate edge-tts: {res.stderr}")
    except Exception as e:
        emit_step(f"Peringatan edge-tts: {e}")
        return {"status": "error", "message": str(e)}

    # Baca durasi aktual voiceover dengan ffprobe
    total_sec = 10.0
    ffprobe_bin = get_ffprobe_bin()
    try:
        pr = subprocess.run([
            ffprobe_bin, "-v", "error",
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1",
            out_vo
        ], capture_output=True, text=True, timeout=10)
        if pr.returncode == 0:
            total_sec = max(1.0, float(pr.stdout.strip()))
    except Exception:
        pass

    pj = read_project_json(clean_p)
    pj.setdefault("audio", {})["vo_file"] = os.path.relpath(out_vo, ws_dir)

    # Sinkronisasi durasi scene terhadap panjang voiceover
    scenes = pj.get("scenes", [])
    if scenes:
        word_counts = [len(s.get("narration", "").split()) for s in scenes]
        total_w = sum(word_counts)
        if total_w > 0:
            accum = 0.0
            for i, s in enumerate(scenes):
                if i == len(scenes) - 1:
                    s["duration"] = max(1.0, round(total_sec - accum, 1))
                else:
                    ratio = word_counts[i] / total_w
                    d = round(total_sec * ratio, 1)
                    s["duration"] = max(1.0, d)
                    accum += d

    pj["stage"] = "audio"
    write_project_json(clean_p, pj)
    emit_ui_sync("project", clean_p)
    return {"status": "ok", "project": clean_p, "vo_file": pj["audio"]["vo_file"], "duration": total_sec}

def cmd_voice_master(project: str, preset: str = "podcast") -> Dict[str, Any]:
    clean_p = os.path.basename(project.strip())
    emit_step(f"Melakukan Audio Mastering (preset: {preset}) untuk proyek '{clean_p}'...")
    ws_dir = get_workspace_dir(clean_p)
    in_vo = os.path.join(ws_dir, "audio", "vo.mp3")
    out_master = os.path.join(ws_dir, "audio", f"vo_master_{preset}.mp3")

    if not os.path.exists(in_vo):
        return {"status": "error", "message": f"File VO belum tersedia di {in_vo}"}

    filters = {
        "podcast": "loudnorm=I=-16:TP=-1.5:LRA=11,afftdn=nf=-25",
        "cinematic": "loudnorm=I=-14:TP=-1.0:LRA=7,bass=g=3:f=110",
        "clean": "afftdn=nf=-30,highpass=f=80,lowpass=f=12000"
    }
    af = filters.get(preset, filters["podcast"])

    ffmpeg_bin = get_ffmpeg_bin()
    cmd = [ffmpeg_bin, "-y", "-i", in_vo, "-af", af, out_master]
    res = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
    if res.returncode == 0 and os.path.exists(out_master):
        pj = read_project_json(clean_p)
        pj.setdefault("audio", {})["vo_file"] = os.path.relpath(out_master, ws_dir)
        write_project_json(clean_p, pj)
        emit_ui_sync("project", clean_p)
        return {"status": "ok", "project": clean_p, "mastered_file": pj["audio"]["vo_file"]}
    return {"status": "error", "message": f"Mastering gagal: {res.stderr}"}

def cmd_audio_set_bgm(project: str, file_path: str, volume: float = 0.18) -> Dict[str, Any]:
    clean_p = os.path.basename(project.strip())
    emit_step(f"Menetapkan background music (volume: {volume}) untuk '{clean_p}'...")
    pj = read_project_json(clean_p)
    pj.setdefault("audio", {})["bgm_file"] = file_path
    pj["audio"]["bgm_volume"] = volume
    write_project_json(clean_p, pj)
    emit_ui_sync("project", clean_p)
    return {"status": "ok", "project": clean_p, "bgm_file": file_path, "bgm_volume": volume}

# ─── MEDIA INTEGRATION DELEGATION ─────────────────────────────────────────────

def cmd_media_assign(project: str, scene_idx: int, url: str, start: Optional[str] = None, end: Optional[str] = None) -> Dict[str, Any]:
    clean_p = os.path.basename(project.strip())
    emit_step(f"Mengunduh & menetapkan media untuk adegan {scene_idx+1} proyek '{clean_p}'...")

    # Panggil media_fetcher.py
    fetcher_py = os.path.join(_script_dir, "media_fetcher.py")
    cmd = [
        sys.executable, fetcher_py, "download",
        "--url", url,
        "--project", clean_p
    ]
    if start: cmd.extend(["--start", start])
    if end: cmd.extend(["--end", end])

    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
    if proc.returncode != 0:
        return {"status": "error", "message": f"Unduh media gagal: {proc.stderr}"}

    try:
        dl_res = json.loads(proc.stdout)
        dest_path = dl_res.get("project_path") or dl_res.get("file_path")
        if not dest_path:
            return {"status": "error", "message": "Gagal memperoleh path hasil unduhan"}

        ws_dir = get_workspace_dir(clean_p)
        rel_visual = os.path.relpath(dest_path, ws_dir) if dest_path.startswith(ws_dir) else dest_path

        pj = read_project_json(clean_p)
        scenes = pj.get("scenes", [])
        if 0 <= scene_idx < len(scenes):
            scenes[scene_idx]["visual"] = rel_visual
            write_project_json(clean_p, pj)
            emit_ui_sync("project", clean_p)
            return {"status": "ok", "scene_index": scene_idx, "visual": rel_visual}
        else:
            return {"status": "error", "message": f"Index {scene_idx} di luar jumlah adegan ({len(scenes)})"}
    except Exception as e:
        return {"status": "error", "message": str(e)}

# ─── EXPORT & PUBLISH KIT ─────────────────────────────────────────────────────

def cmd_render_start(project: str, safe_zone: bool = True) -> Dict[str, Any]:
    clean_p = os.path.basename(project.strip())
    emit_step(f"Memulai proses render video proyek '{clean_p}'...")
    pj_ratio = read_project_json(clean_p)
    res = call_bridge("POST", f"/render/{clean_p}", {
        "safe_zone": safe_zone,
        "aspect_ratio": pj_ratio.get("aspect_ratio", "9:16"),
        "resolution": pj_ratio.get("resolution"),
        "long_form": pj_ratio.get("format_scale") == "long",
    })
    if res:
        emit_ui_sync("project", clean_p)
        return res

    # Fallback lokal: tandai project siap render
    pj = read_project_json(clean_p)
    pj["stage"] = "render"
    write_project_json(clean_p, pj)
    emit_ui_sync("project", clean_p)
    return {"status": "ok", "project": clean_p, "stage": "render"}

def cmd_publish_set(project: str, title: str, description: str, tags: str) -> Dict[str, Any]:
    clean_p = os.path.basename(project.strip())
    emit_step(f"Menetapkan metadata Publish Kit untuk '{clean_p}'...")
    pj = read_project_json(clean_p)
    pj["publish_kit"] = {
        "title": title.strip(),
        "description": description.strip(),
        "tags": tags.strip(),
        "updated_at": int(datetime.now().timestamp() * 1000)
    }
    write_project_json(clean_p, pj)
    emit_ui_sync("project", clean_p)
    return {"status": "ok", "project": clean_p, "publish_kit": pj["publish_kit"]}

# ─── MEMORY (CROSS-SESSION PROJECT & GLOBAL) ───────────────────────────────────

def get_project_memory_path(project: str) -> str:
    clean_p = os.path.basename(project.strip())
    return os.path.join(get_workspace_dir(clean_p), "memory.json")

def get_global_memory_path() -> str:
    return os.path.join(get_cache_dir(), "user_preferences.json")

def cmd_memory_list(project: Optional[str] = None) -> Dict[str, Any]:
    emit_step(f"Membaca memori lintas-sesi ({project or 'global'})...")
    global_items = read_json_file(get_global_memory_path(), [])
    if not isinstance(global_items, list):
        global_items = []

    project_items = []
    if project:
        p_path = get_project_memory_path(project)
        p_items = read_json_file(p_path, [])
        if isinstance(p_items, list):
            project_items = p_items

    return {
        "status": "ok",
        "project": project,
        "projectMemory": project_items,
        "globalMemory": global_items
    }

def cmd_memory_add(note: str, project: Optional[str] = None, category: str = "decision") -> Dict[str, Any]:
    valid_categories = {"decision", "style", "rejected", "fact"}
    cat = category.lower() if category.lower() in valid_categories else "decision"
    clean_note = note.strip()
    mem_id = f"mem_{int(datetime.now().timestamp() * 1000)}"
    entry = {
        "id": mem_id,
        "note": clean_note,
        "category": cat,
        "createdAt": datetime.utcnow().isoformat() + "Z"
    }

    if project:
        clean_p = os.path.basename(project.strip())
        emit_step(f"Menyimpan catatan memori ke proyek '{clean_p}' [{cat}]...")
        p_path = get_project_memory_path(clean_p)
        items = read_json_file(p_path, [])
        if not isinstance(items, list):
            items = []
        items.append(entry)
        write_json_file(p_path, items)
        emit_ui_sync("memory", clean_p)
        return {"status": "ok", "scope": "project", "project": clean_p, "entry": entry}
    else:
        emit_step(f"Menyimpan catatan preferensi global [{cat}]...")
        g_path = get_global_memory_path()
        items = read_json_file(g_path, [])
        if not isinstance(items, list):
            items = []
        items.append(entry)
        write_json_file(g_path, items)
        emit_ui_sync("memory", "global")
        return {"status": "ok", "scope": "global", "entry": entry}

def cmd_memory_remove(id: str, project: Optional[str] = None) -> Dict[str, Any]:
    target_id = id.strip()
    if project:
        clean_p = os.path.basename(project.strip())
        emit_step(f"Menghapus memori '{target_id}' dari proyek '{clean_p}'...")
        p_path = get_project_memory_path(clean_p)
        items = read_json_file(p_path, [])
        if isinstance(items, list):
            items = [m for m in items if m.get("id") != target_id]
            write_json_file(p_path, items)
        emit_ui_sync("memory", clean_p)
        return {"status": "ok", "scope": "project", "project": clean_p, "removed_id": target_id}
    else:
        emit_step(f"Menghapus memori '{target_id}' dari preferensi global...")
        g_path = get_global_memory_path()
        items = read_json_file(g_path, [])
        if isinstance(items, list):
            items = [m for m in items if m.get("id") != target_id]
            write_json_file(g_path, items)
        emit_ui_sync("memory", "global")
        return {"status": "ok", "scope": "global", "removed_id": target_id}

# ─── REFERENCE DNA, ASSET ROUTER, GRAPHIC & TOPIC HISTORY (V3.6) ─────────────

def cmd_save_reference_dna(
    project: str,
    json_file: Optional[str] = None,
    format_scale: Optional[str] = None,
    aspect_ratio: Optional[str] = None,
    resolution: Optional[str] = None,
    visual_continuity: Optional[str] = None,
    layout_pattern: Optional[str] = None,
    audio_modality: Optional[str] = None,
    pacing_style: Optional[str] = None,
    avg_scene_sec: Optional[float] = None,
    chapter_structure: Optional[bool] = None,
    sources_priority: Optional[List[str]] = None,
    visual_style_anchor: Optional[str] = None,
    mechanics_summary: Optional[str] = None,
    user_overrides: Optional[str] = None,
) -> Dict[str, Any]:
    """Simpan kontrak Reference DNA (short/long + semua rasio) untuk proyek."""
    from engine.reference_dna import load_reference_dna, save_reference_dna

    clean_p = os.path.basename(project.strip())
    emit_step(f"Menyimpan kontrak Reference DNA proyek '{clean_p}'...")
    if json_file:
        payload = read_json_file(json_file, {})
        if not isinstance(payload, dict):
            return {"status": "error", "message": "File JSON kontrak tidak valid"}
    else:
        payload = load_reference_dna(clean_p)

    if format_scale:
        payload["format_scale"] = format_scale
    if aspect_ratio:
        payload["aspect_ratio"] = aspect_ratio
    if resolution:
        payload["resolution"] = resolution
    if visual_continuity:
        payload["visual_continuity"] = visual_continuity
    if layout_pattern:
        payload["layout_pattern"] = layout_pattern
    if audio_modality:
        payload["audio_modality"] = audio_modality
    if visual_style_anchor is not None:
        payload["visual_style_anchor"] = visual_style_anchor
    if mechanics_summary is not None:
        payload["mechanics_summary"] = mechanics_summary
    if user_overrides is not None:
        payload["user_overrides"] = user_overrides
    if sources_priority:
        payload["sources_priority"] = sources_priority
    pacing = payload.get("pacing_profile")
    if not isinstance(pacing, dict):
        pacing = {}
    if pacing_style:
        pacing["style"] = pacing_style
    if avg_scene_sec is not None:
        pacing["avg_scene_sec"] = float(avg_scene_sec)
    if chapter_structure is not None:
        pacing["chapter_structure"] = bool(chapter_structure)
    if pacing:
        payload["pacing_profile"] = pacing

    try:
        dna = save_reference_dna(clean_p, payload)
    except Exception as exc:
        return {"status": "error", "message": str(exc)}

    # Sinkronkan rasio proyek agar render & worker memakai kontrak yang sama.
    pj = read_project_json(clean_p)
    pj["aspect_ratio"] = dna.get("aspect_ratio", pj.get("aspect_ratio"))
    pj["resolution"] = dna.get("resolution")
    pj["format_scale"] = dna.get("format_scale")
    write_project_json(clean_p, pj)
    emit_ui_sync("project", clean_p)
    return {"status": "ok", "project": clean_p, "reference_dna": dna}


def cmd_resolve_assets(project: str, force: bool = False, scene: Optional[int] = None) -> Dict[str, Any]:
    """Jalankan router hybrid 5 worker untuk seluruh scene (checkpoint resume)."""
    from engine.assets.router import resolve_assets

    clean_p = os.path.basename(project.strip())
    emit_step(f"Mereresolusi aset proyek '{clean_p}' lewat router hybrid...")
    res = resolve_assets(clean_p, force=force, only=scene)
    emit_ui_sync("project", clean_p)
    return res


def cmd_generate_media(
    project: str,
    prompt: str,
    scene: Optional[int] = None,
    seed: Optional[int] = None,
    aspect_ratio: Optional[str] = None,
    out_path: Optional[str] = None,
) -> Dict[str, Any]:
    """Panggil langsung generate_worker sesuai rasio proyek."""
    from engine.reference_dna import load_reference_dna
    from engine.assets.generate_worker import generate_image

    clean_p = os.path.basename(project.strip())
    dna = load_reference_dna(clean_p)
    emit_step(f"Menyiapkan generate media untuk '{clean_p}'...")
    if not out_path:
        target_name = f"gen_scene_{scene + 1:02d}.png" if scene is not None else f"gen_{datetime.now().strftime('%H%M%S')}.png"
        out_path = os.path.join(get_workspace_dir(clean_p), "assets", target_name)
    res = generate_image(
        out_path, prompt, dna=dna,
        aspect_ratio=aspect_ratio or dna.get("aspect_ratio"),
        seed=seed, project=clean_p, scene_index=scene or 0,
    )
    if res.get("status") == "ok":
        emit_ui_sync("project", clean_p)
    return res


def cmd_render_graphic(
    project: str,
    template: str = "stat",
    title: str = "",
    subtitle: str = "",
    value: str = "",
    out_path: Optional[str] = None,
    aspect_ratio: Optional[str] = None,
    data_json: str = "",
    as_video: bool = False,
    duration: float = 3.0,
) -> Dict[str, Any]:
    """Render kartu grafis lokal pada rasio proyek."""
    from engine.reference_dna import load_reference_dna
    from engine.assets.graphic_worker import render_graphic, render_graphic_video

    clean_p = os.path.basename(project.strip())
    dna = load_reference_dna(clean_p)
    payload: Dict[str, Any] = {}
    if data_json:
        parsed = read_json_file(data_json, {})
        if isinstance(parsed, dict):
            payload = parsed
    for key, val in (("title", title), ("subtitle", subtitle), ("value", value)):
        if val:
            payload[key] = val
    emit_step(f"Merender kartu grafis '{template}' untuk '{clean_p}'...")
    if not out_path:
        suffix = ".mp4" if as_video else ".png"
        out_path = os.path.join(get_workspace_dir(clean_p), "assets", f"graphic_{template}{suffix}")
    if as_video:
        res = render_graphic_video(out_path, template, payload, duration, dna, aspect_ratio)
    else:
        res = render_graphic(out_path, template, payload, dna, aspect_ratio)
    if res.get("status") == "ok":
        emit_ui_sync("project", clean_p)
    return res


def cmd_topic_history(
    action: str,
    project: Optional[str] = None,
    topic: Optional[str] = None,
    angle: str = "",
    format_scale: str = "short",
    hook_type: str = "",
    payoff_type: str = "",
    status: Optional[str] = None,
) -> Dict[str, Any]:
    """Kelola riwayat topik sebagai konteks variasi (tidak pernah memblokir)."""
    from engine import topic_history

    if action == "list":
        entries = topic_history.list_entries(status)
        return {"status": "ok", "count": len(entries), "entries": entries, "blocked": False}
    if action == "record":
        if not project or not topic:
            return {"status": "error", "message": "--project dan --topic wajib diisi"}
        return topic_history.record_topic(project, topic, angle, format_scale, hook_type, payoff_type, status or "draft")
    if action == "set-status":
        if not status:
            return {"status": "error", "message": "--status wajib diisi"}
        return topic_history.set_status(project or "", topic or "", status)
    if action == "sync":
        return topic_history.sync_statuses()
    if action == "context":
        if not topic:
            return {"status": "error", "message": "--topic wajib diisi"}
        return topic_history.get_variation_context(topic)
    return {"status": "error", "message": "Aksi topic-history tidak dikenal"}

# ─── MAIN CLI PARSER ──────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="ContentStudio Master CLI (studio_cli.py)")
    subparsers = parser.add_subparsers(dest="subcommand", help="Kategori perintah")

    # ── PROJECT ──
    p_proj = subparsers.add_parser("project", help="Manajemen proyek")
    p_proj_sub = p_proj.add_subparsers(dest="action")
    p_proj_sub.add_parser("list")
    p_create = p_proj_sub.add_parser("create")
    p_create.add_argument("--name", required=True)
    p_create.add_argument("--ratio", default="9:16", choices=["9:16", "16:9", "1:1", "4:5"])
    p_create.add_argument("--channel", default=None)
    p_del = p_proj_sub.add_parser("delete")
    p_del.add_argument("--name", required=True)
    p_stat = p_proj_sub.add_parser("status")
    p_stat.add_argument("--name", required=True)

    # ── CHANNEL ──
    p_chan = subparsers.add_parser("channel", help="Manajemen Channel DNA")
    p_chan_sub = p_chan.add_subparsers(dest="action")
    p_chan_sub.add_parser("list")
    p_cc = p_chan_sub.add_parser("create")
    p_cc.add_argument("--name", required=True)
    p_cc.add_argument("--dna", default="")
    p_cc.add_argument("--aspect-ratio", default="9:16")
    p_cl = p_chan_sub.add_parser("link")
    p_cl.add_argument("--project", required=True)
    p_cl.add_argument("--channel", required=True)

    # ── TEMPLATE ──
    p_tpl = subparsers.add_parser("template", help="Living Templates & Ritme")
    p_tpl_sub = p_tpl.add_subparsers(dest="action")
    p_tpl_sub.add_parser("list")
    p_ta = p_tpl_sub.add_parser("apply")
    p_ta.add_argument("--project", required=True)
    p_ta.add_argument("--template", required=True)

    # ── SCRIPT & SCENE ──
    p_sc = subparsers.add_parser("script", help="Manajemen Naskah")
    p_sc_sub = p_sc.add_subparsers(dest="action")
    p_sset = p_sc_sub.add_parser("set")
    p_sset.add_argument("--project", required=True)
    p_sset.add_argument("--text", default=None)
    p_sset.add_argument("--file", default=None)
    p_ssplit = p_sc_sub.add_parser("split-scenes")
    p_ssplit.add_argument("--project", required=True)

    p_scene = subparsers.add_parser("scene", help="Manajemen Adegan")
    p_scene_sub = p_scene.add_subparsers(dest="action")
    p_sclist = p_scene_sub.add_parser("list")
    p_sclist.add_argument("--project", required=True)
    p_scvis = p_scene_sub.add_parser("set-visual")
    p_scvis.add_argument("--project", required=True)
    p_scvis.add_argument("--index", type=int, required=True)
    p_scvis.add_argument("--file", required=True)
    p_scpmt = p_scene_sub.add_parser("set-prompt")
    p_scpmt.add_argument("--project", required=True)
    p_scpmt.add_argument("--index", type=int, required=True)
    p_scpmt.add_argument("--prompt", required=True)

    # ── VOICE & AUDIO ──
    p_voice = subparsers.add_parser("voice", help="Voiceover AI & Mastering")
    p_voice_sub = p_voice.add_subparsers(dest="action")
    p_vgen = p_voice_sub.add_parser("generate")
    p_vgen.add_argument("--project", required=True)
    p_vgen.add_argument("--voice", default="id-ID-ArdiNeural")
    p_vgen.add_argument("--text", default=None)
    p_vmast = p_voice_sub.add_parser("master")
    p_vmast.add_argument("--project", required=True)
    p_vmast.add_argument("--preset", default="podcast", choices=["podcast", "cinematic", "clean"])

    p_audio = subparsers.add_parser("audio", help="Background Music")
    p_audio_sub = p_audio.add_subparsers(dest="action")
    p_abgm = p_audio_sub.add_parser("set-bgm")
    p_abgm.add_argument("--project", required=True)
    p_abgm.add_argument("--file", required=True)
    p_abgm.add_argument("--volume", type=float, default=0.18)

    # ── MEDIA ──
    p_media = subparsers.add_parser("media", help="Delegasi Pencarian & Alokasi Media")
    p_media_sub = p_media.add_subparsers(dest="action")
    p_mass = p_media_sub.add_parser("assign")
    p_mass.add_argument("--project", required=True)
    p_mass.add_argument("--scene", type=int, required=True)
    p_mass.add_argument("--url", required=True)
    p_mass.add_argument("--start", default=None)
    p_mass.add_argument("--end", default=None)

    # ── RENDER & PUBLISH ──
    p_ren = subparsers.add_parser("render", help="Rendering Video Final")
    p_ren_sub = p_ren.add_subparsers(dest="action")
    p_rstart = p_ren_sub.add_parser("start")
    p_rstart.add_argument("--project", required=True)
    p_rstart.add_argument("--safe-zone", action="store_true", default=True)

    p_pub = subparsers.add_parser("publish", help="Publish Kit Metadata")
    p_pub_sub = p_pub.add_subparsers(dest="action")
    p_pset = p_pub_sub.add_parser("set")
    p_pset.add_argument("--project", required=True)
    p_pset.add_argument("--title", required=True)
    p_pset.add_argument("--description", required=True)
    p_pset.add_argument("--tags", required=True)

    # ── REFERENCE DNA ──
    p_dna = subparsers.add_parser("save-reference-dna", help="Simpan kontrak Reference DNA (short/long + multi-rasio)")
    p_dna.add_argument("--project", required=True)
    p_dna.add_argument("--json", dest="json_file", default=None)
    p_dna.add_argument("--format-scale", default=None, choices=["short", "long"])
    p_dna.add_argument("--aspect-ratio", default=None, choices=["16:9", "9:16", "1:1", "4:5"])
    p_dna.add_argument("--resolution", default=None)
    p_dna.add_argument("--visual-continuity", default=None, choices=["strict_consistent", "editorial_varied"])
    p_dna.add_argument("--layout-pattern", default=None, choices=["fullscreen", "grid_2x2", "vertical_3_tier", "split_screen", "card_overlay", "picture_in_picture"])
    p_dna.add_argument("--audio-modality", default=None, choices=["visual_only_bgm", "vo_plus_bgm", "original_clip_audio", "ambient_bgm"])
    p_dna.add_argument("--pacing-style", default=None, choices=["fast_cut", "escalating", "narrative_chapters", "slow_cinematic"])
    p_dna.add_argument("--avg-scene-sec", type=float, default=None)
    p_dna.add_argument("--chapter-structure", action="store_true")
    p_dna.add_argument("--sources-priority", default=None, help="Contoh: clip,generate,stock,graphic,local")
    p_dna.add_argument("--visual-style-anchor", default=None)
    p_dna.add_argument("--mechanics-summary", default=None)
    p_dna.add_argument("--user-overrides", default=None)

    # ── ASSET ROUTER & WORKERS ──
    p_resolve = subparsers.add_parser("resolve-assets", help="Resolusi aset 5 worker hybrid dengan checkpoint resume")
    p_resolve.add_argument("--project", required=True)
    p_resolve.add_argument("--force", action="store_true")
    p_resolve.add_argument("--scene", type=int, default=None)

    p_gen = subparsers.add_parser("generate-media", help="Generate media AI sesuai rasio proyek")
    p_gen.add_argument("--project", required=True)
    p_gen.add_argument("--prompt", required=True)
    p_gen.add_argument("--scene", type=int, default=None)
    p_gen.add_argument("--seed", type=int, default=None)
    p_gen.add_argument("--aspect-ratio", default=None, choices=["16:9", "9:16", "1:1", "4:5"])
    p_gen.add_argument("--out", default=None)

    p_graph = subparsers.add_parser("render-graphic", help="Render kartu grafis lokal multi-rasio")
    p_graph.add_argument("--project", required=True)
    p_graph.add_argument("--template", default="stat", choices=["stat", "comparison", "chapter", "quote", "tier"])
    p_graph.add_argument("--title", default="")
    p_graph.add_argument("--subtitle", default="")
    p_graph.add_argument("--value", default="")
    p_graph.add_argument("--data-json", default="")
    p_graph.add_argument("--out", default=None)
    p_graph.add_argument("--aspect-ratio", default=None, choices=["16:9", "9:16", "1:1", "4:5"])
    p_graph.add_argument("--video", action="store_true")
    p_graph.add_argument("--duration", type=float, default=3.0)

    # ── TOPIC HISTORY ──
    p_topic = subparsers.add_parser("topic-history", help="Riwayat topik non-blokir sebagai konteks variasi")
    p_topic_sub = p_topic.add_subparsers(dest="topic_action")
    p_t_list = p_topic_sub.add_parser("list")
    p_t_list.add_argument("--status", default=None, choices=["draft", "exported", "published", "deleted"])
    p_t_rec = p_topic_sub.add_parser("record")
    p_t_rec.add_argument("--project", required=True)
    p_t_rec.add_argument("--topic", required=True)
    p_t_rec.add_argument("--angle", default="")
    p_t_rec.add_argument("--format-scale", default="short", choices=["short", "long"])
    p_t_rec.add_argument("--hook-type", default="")
    p_t_rec.add_argument("--payoff-type", default="")
    p_t_rec.add_argument("--status", default="draft", choices=["draft", "exported", "published"])
    p_t_set = p_topic_sub.add_parser("set-status")
    p_t_set.add_argument("--project", default=None)
    p_t_set.add_argument("--topic", default=None)
    p_t_set.add_argument("--status", required=True, choices=["draft", "exported", "published", "deleted"])
    p_topic_sub.add_parser("sync")
    p_t_ctx = p_topic_sub.add_parser("context")
    p_t_ctx.add_argument("--topic", required=True)

    # ── MEMORY ──
    p_mem = subparsers.add_parser("memory", help="Cross-Session Project & Global Memory")
    p_mem_sub = p_mem.add_subparsers(dest="action")
    p_mlist = p_mem_sub.add_parser("list")
    p_mlist.add_argument("--project", default=None)
    p_madd = p_mem_sub.add_parser("add")
    p_madd.add_argument("--project", default=None)
    p_madd.add_argument("--note", required=True)
    p_madd.add_argument("--category", default="decision", choices=["decision", "style", "rejected", "fact"])
    p_mrem = p_mem_sub.add_parser("remove")
    p_mrem.add_argument("--project", default=None)
    p_mrem.add_argument("--id", required=True)

    args = parser.parse_args()

    if args.subcommand == "project":
        if args.action == "list":
            print(json.dumps(cmd_project_list(), indent=2))
        elif args.action == "create":
            print(json.dumps(cmd_project_create(args.name, args.ratio, args.channel), indent=2))
        elif args.action == "delete":
            print(json.dumps(cmd_project_delete(args.name), indent=2))
        elif args.action == "status":
            print(json.dumps(cmd_project_status(args.name), indent=2))

    elif args.subcommand == "channel":
        if args.action == "list":
            print(json.dumps(cmd_channel_list(), indent=2))
        elif args.action == "create":
            print(json.dumps(cmd_channel_create(args.name, args.dna, args.aspect_ratio), indent=2))
        elif args.action == "link":
            print(json.dumps(cmd_channel_link(args.project, args.channel), indent=2))

    elif args.subcommand == "template":
        if args.action == "list":
            print(json.dumps(cmd_template_list(), indent=2))
        elif args.action == "apply":
            print(json.dumps(cmd_template_apply(args.project, args.template), indent=2))

    elif args.subcommand == "script":
        if args.action == "set":
            print(json.dumps(cmd_script_set(args.project, args.text, args.file), indent=2))
        elif args.action == "split-scenes":
            print(json.dumps(cmd_script_split_scenes(args.project), indent=2))

    elif args.subcommand == "scene":
        if args.action == "list":
            pj = read_project_json(args.project)
            print(json.dumps(pj.get("scenes", []), indent=2))
        elif args.action == "set-visual":
            print(json.dumps(cmd_scene_set_visual(args.project, args.index, args.file), indent=2))
        elif args.action == "set-prompt":
            print(json.dumps(cmd_scene_set_prompt(args.project, args.index, args.prompt), indent=2))

    elif args.subcommand == "voice":
        if args.action == "generate":
            print(json.dumps(cmd_voice_generate(args.project, args.voice, args.text), indent=2))
        elif args.action == "master":
            print(json.dumps(cmd_voice_master(args.project, args.preset), indent=2))

    elif args.subcommand == "audio":
        if args.action == "set-bgm":
            print(json.dumps(cmd_audio_set_bgm(args.project, args.file, args.volume), indent=2))

    elif args.subcommand == "media":
        if args.action == "assign":
            print(json.dumps(cmd_media_assign(args.project, args.scene, args.url, args.start, args.end), indent=2))

    elif args.subcommand == "render":
        if args.action == "start":
            print(json.dumps(cmd_render_start(args.project, args.safe_zone), indent=2))

    elif args.subcommand == "publish":
        if args.action == "set":
            print(json.dumps(cmd_publish_set(args.project, args.title, args.description, args.tags), indent=2))

    elif args.subcommand == "save-reference-dna":
        priority = None
        if args.sources_priority:
            priority = [s.strip() for s in args.sources_priority.split(",") if s.strip()]
        print(json.dumps(cmd_save_reference_dna(
            args.project, args.json_file, args.format_scale, args.aspect_ratio, args.resolution,
            args.visual_continuity, args.layout_pattern, args.audio_modality, args.pacing_style,
            args.avg_scene_sec, (True if args.chapter_structure else None), priority,
            args.visual_style_anchor, args.mechanics_summary, args.user_overrides,
        ), indent=2, ensure_ascii=False))

    elif args.subcommand == "resolve-assets":
        print(json.dumps(cmd_resolve_assets(args.project, args.force, args.scene), indent=2, ensure_ascii=False))

    elif args.subcommand == "generate-media":
        print(json.dumps(cmd_generate_media(
            args.project, args.prompt, args.scene, args.seed, args.aspect_ratio, args.out,
        ), indent=2, ensure_ascii=False))

    elif args.subcommand == "render-graphic":
        print(json.dumps(cmd_render_graphic(
            args.project, args.template, args.title, args.subtitle, args.value,
            args.out, args.aspect_ratio, args.data_json, args.video, args.duration,
        ), indent=2, ensure_ascii=False))

    elif args.subcommand == "topic-history":
        action = getattr(args, "topic_action", None)
        if not action:
            p_topic.print_help(sys.stderr)
            sys.exit(1)
        print(json.dumps(cmd_topic_history(
            action,
            getattr(args, "project", None),
            getattr(args, "topic", None),
            getattr(args, "angle", ""),
            getattr(args, "format_scale", "short"),
            getattr(args, "hook_type", ""),
            getattr(args, "payoff_type", ""),
            getattr(args, "status", None),
        ), indent=2, ensure_ascii=False))

    elif args.subcommand == "memory":
        if args.action == "list":
            print(json.dumps(cmd_memory_list(args.project), indent=2))
        elif args.action == "add":
            print(json.dumps(cmd_memory_add(args.note, args.project, args.category), indent=2))
        elif args.action == "remove":
            print(json.dumps(cmd_memory_remove(args.id, args.project), indent=2))

    else:
        parser.print_help(sys.stderr)
        sys.exit(1)

if __name__ == "__main__":
    main()
