#!/usr/bin/env python3
"""
ContentStudio Dynamic Skill Engine (engine/skills.py)
Mengelola skill modular berformat Markdown (YAML frontmatter + SOP):
- Progressive Disclosure: get_skill_index() hanya memuat name & description tipis.
- Lazy Load: load_skill(slug) membaca konten penuh saat agen membutuhkan.
- Hot Install: install_skill(source) mendukung instalasi dinamis dari GitHub/URL/ZIP.
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
import shutil
import zipfile
import tempfile
import urllib.request
import urllib.parse
from typing import Dict, Any, List, Optional, Tuple
from engine.runtime import get_root_dir

def get_skills_dir() -> str:
    """Membaca folder skills di root data persisten (/sdcard/ContentStudio/skills jika writable)."""
    sdcard_dir = "/sdcard/ContentStudio/skills"
    if os.path.exists("/sdcard/ContentStudio") and os.access("/sdcard/ContentStudio", os.W_OK):
        os.makedirs(sdcard_dir, exist_ok=True)
        return sdcard_dir
    local_dir = os.path.join(get_root_dir(), "skills")
    os.makedirs(local_dir, exist_ok=True)
    return local_dir

# ==============================================================================
# DEFAULT EMBEDDED SKILLS
# ==============================================================================
DEFAULT_SKILLS = {
    "short-form-producer": {
        "name": "Short-Form Video Producer",
        "description": "Ahli merancang struktur video vertikal 9:16 Full HD 1080p, hook 3 detik, pacing cepat 2.5-4s, tipografi kontras, dan komposisi blur_fill.",
        "content": """---
name: Short-Form Video Producer
description: Ahli merancang struktur video vertikal 9:16 Full HD 1080p, hook 3 detik, pacing cepat 2.5-4s, tipografi kontras, dan komposisi blur_fill.
---

# SHORT-FORM VIDEO PRODUCER SKILL

Pedoman operasional untuk memproduksi video pendek Shorts/Reels/TikTok di ContentStudio:

## 1. STRUKTUR RETENSI KONTEN
- **Detik 0.0 - 3.5 (Hook Utama):**
  * Teks Header 3 baris ALL CAPS (Kuning #FFEE00, Putih #FFFFFF, Merah #FF2222) di posisi Y=0.35.
  * Elemen visual terpusat tanpa jeda audio (zero dead air).
- **Detik 3.5 - 45.0 (Pengembangan Body):**
  * Pergantian shot setiap 2.5 - 4.5 detik.
  * Gameplay horizontal 16:9 wajib dibingkai dengan `framing: "blur_fill"` (anti black-bars).
  * Gambar statis wajib diberi `motion: "zoom_in"` atau `zoom_out` (Ken Burns).
  * Subtitle lower-third Y=0.80, maksimal 3-5 kata per kemunculan, outline hitam tebal.
- **Detik 45.0 - Akhir (Payoff & CTA):**
  * Pertanyaan interaktif penutup pemancing komentar audiens.

## 2. STANDAR TEKNIS PRODUKSI
- Resolusi: 1080p (1080x1920, 30fps).
- Dual Track Audio: VO -1.5 dB, BGM -16.0 dB dengan auto-ducking.
- Selalu uji rencana lewat tool `validate_plan` sebelum `prepare_render`.
"""
    },
    "media-hunter": {
        "name": "Media Hunter",
        "description": "Spesialis berburu dan mengunduh bahan footage klip gameplay, video referensi, meme reaksi, dan sound effect (SFX) yang relevan untuk produksi konten.",
        "content": """---
name: Media Hunter
description: Spesialis berburu dan mengunduh bahan footage klip gameplay, video referensi, meme reaksi, dan sound effect (SFX) yang relevan untuk produksi konten.
---

# MEDIA HUNTER SKILL

Pedoman berburu dan mengumpulkan bahan media untuk konten:

## 1. TIPE MEDIA & STRATEGI PENCARIAN
- **Klip Gameplay / B-Roll (`media_type="clip"`):**
  * Gunakan query spesifik topik (misal: "cinematic nature b-roll", "highlight sepak bola").
  * Unduh potongan durasi 5-8 detik max 1080p.
- **Meme Reaksi (`media_type="meme"`):**
  * Cari klip humor pendek (misal: "patrick bingung", "monyet ketawa", "cat vibing").
  * Disimpan otomatis ke direktori aset meme.
- **Efek Suara (`media_type="sfx"`):**
  * Efek pukulan, whoosh, ding, buzzer, atau transisi suara pendek.

## 2. PRIORITAS ASET LOKAL
- Sebelum mengunduh, periksa apakah aset yang dicari sudah ada di cache lokal menggunakan `list_assets`.
- Jika belum ada, gunakan tool `hunt_media(query, media_type, duration)` untuk mengunduh secara otomatis.
"""
    }
}

def ensure_default_skills() -> None:
    """Memastikan skill bawaan terinstal jika direktori skills masih kosong."""
    s_dir = get_skills_dir()
    for slug, data in DEFAULT_SKILLS.items():
        skill_folder = os.path.join(s_dir, slug)
        skill_file = os.path.join(skill_folder, "SKILL.md")
        if not os.path.exists(skill_file):
            os.makedirs(skill_folder, exist_ok=True)
            with open(skill_file, "w", encoding="utf-8") as f:
                f.write(data["content"].strip() + "\n")

    # Sinkronisasi skill tambahan dari .agents/skills/ jika ada
    legacy_dir = os.path.join(get_root_dir(), ".agents", "skills")
    if os.path.exists(legacy_dir):
        for item in os.listdir(legacy_dir):
            src_folder = os.path.join(legacy_dir, item)
            src_file = os.path.join(src_folder, "SKILL.md")
            dest_folder = os.path.join(s_dir, item)
            dest_file = os.path.join(dest_folder, "SKILL.md")
            if os.path.isdir(src_folder) and os.path.exists(src_file) and not os.path.exists(dest_file):
                try:
                    shutil.copytree(src_folder, dest_folder)
                except Exception:
                    pass

def parse_frontmatter(content: str) -> Tuple[Dict[str, str], str]:
    """Mengekstrak YAML frontmatter (name & description) dari file SKILL.md."""
    meta = {}
    body = content
    pattern = r"^---\s*\n([\s\S]*?)\n---\s*\n([\s\S]*)$"
    match = re.match(pattern, content.strip())
    if match:
        yaml_text = match.group(1)
        body = match.group(2).strip()
        for line in yaml_text.splitlines():
            line = line.strip()
            if not line or line.startswith("#") or ":" not in line:
                continue
            k, v = line.split(":", 1)
            meta[k.strip().lower()] = v.strip().strip("'\"")
    return meta, body

# ==============================================================================
# SKILL PUBLIC API
# ==============================================================================

def get_skill_index() -> List[Dict[str, str]]:
    """
    Progressive Disclosure: Mengembalikan daftar tipis {slug, name, description}
    untuk disuntikkan ke system prompt agar hemat token.
    """
    ensure_default_skills()
    s_dir = get_skills_dir()
    index_list = []

    for entry in sorted(os.listdir(s_dir)):
        folder_path = os.path.join(s_dir, entry)
        skill_file = os.path.join(folder_path, "SKILL.md")
        if os.path.isdir(folder_path) and os.path.exists(skill_file):
            try:
                with open(skill_file, "r", encoding="utf-8") as f:
                    content = f.read()
                meta, _ = parse_frontmatter(content)
                name = meta.get("name") or entry.replace("-", " ").title()
                desc = meta.get("description") or "Skill operasional ContentStudio."
                index_list.append({
                    "slug": entry,
                    "name": name,
                    "description": desc
                })
            except Exception:
                continue

    return index_list

def list_skills() -> List[Dict[str, Any]]:
    """Mengembalikan daftar lengkap seluruh skill yang terpasang."""
    ensure_default_skills()
    s_dir = get_skills_dir()
    skills = []

    for entry in sorted(os.listdir(s_dir)):
        folder_path = os.path.join(s_dir, entry)
        skill_file = os.path.join(folder_path, "SKILL.md")
        if os.path.isdir(folder_path) and os.path.exists(skill_file):
            try:
                with open(skill_file, "r", encoding="utf-8") as f:
                    content = f.read()
                meta, _ = parse_frontmatter(content)
                skills.append({
                    "slug": entry,
                    "name": meta.get("name") or entry.replace("-", " ").title(),
                    "description": meta.get("description") or "",
                    "path": os.path.relpath(skill_file, get_root_dir()),
                    "is_default": entry in DEFAULT_SKILLS
                })
            except Exception:
                continue

    return skills

def load_skill(name_or_slug: str) -> Dict[str, Any]:
    """Membaca isi penuh SKILL.md saat agen membutuhkannya secara on-demand."""
    ensure_default_skills()
    s_dir = get_skills_dir()
    slug = name_or_slug.strip().lower().replace(" ", "-")

    # Cek kecocokan langsung dengan slug
    skill_folder = os.path.join(s_dir, slug)
    skill_file = os.path.join(skill_folder, "SKILL.md")

    if not os.path.exists(skill_file):
        # Cari kecocokan parsial atau berdasarkan nama di frontmatter
        found_slug = None
        for entry in os.listdir(s_dir):
            f_path = os.path.join(s_dir, entry, "SKILL.md")
            if os.path.exists(f_path):
                if entry == slug or slug in entry:
                    found_slug = entry
                    skill_file = f_path
                    break
        if not found_slug:
            return {
                "status": "error",
                "message": f"Skill '{name_or_slug}' tidak ditemukan di {s_dir}."
            }
        slug = found_slug

    try:
        with open(skill_file, "r", encoding="utf-8") as f:
            content = f.read()
        meta, body = parse_frontmatter(content)
        return {
            "status": "ok",
            "slug": slug,
            "name": meta.get("name") or slug.replace("-", " ").title(),
            "description": meta.get("description") or "",
            "content": content
        }
    except Exception as e:
        return {
            "status": "error",
            "message": f"Gagal membaca skill '{slug}': {e}"
        }

def install_skill(source: str) -> Dict[str, Any]:
    """
    Menginstal skill baru secara dinamis:
    - URL raw Markdown SKILL.md
    - URL GitHub repository
    - File lokal .zip atau direktori lokal
    """
    s_dir = get_skills_dir()
    source_clean = source.strip()

    # 1. URL Web / GitHub
    if source_clean.startswith("http://") or source_clean.startswith("https://"):
        try:
            req = urllib.request.Request(
                source_clean,
                headers={"User-Agent": "ContentStudio-SkillInstaller/1.0"}
            )
            # Jika URL langsung mengarah ke file SKILL.md / .md
            if source_clean.endswith(".md") or "/raw/" in source_clean or "raw.githubusercontent.com" in source_clean:
                with urllib.request.urlopen(req, timeout=30) as resp:
                    text_content = resp.read().decode("utf-8")

                meta, _ = parse_frontmatter(text_content)
                name = meta.get("name")
                if name:
                    slug = re.sub(r"[^a-z0-9_-]", "", name.lower().replace(" ", "-"))
                else:
                    slug = os.path.basename(urllib.parse.urlparse(source_clean).path).replace(".md", "").lower()
                if not slug:
                    slug = f"skill-{int(os.times().elapsed)}"

                dest_folder = os.path.join(s_dir, slug)
                os.makedirs(dest_folder, exist_ok=True)
                dest_file = os.path.join(dest_folder, "SKILL.md")
                with open(dest_file, "w", encoding="utf-8") as f:
                    f.write(text_content)

                return {
                    "status": "ok",
                    "slug": slug,
                    "name": meta.get("name", slug),
                    "message": f"Skill '{slug}' berhasil diinstal dari URL."
                }

            # Jika URL GitHub Repo umum (misal https://github.com/user/repo)
            if "github.com" in source_clean and not source_clean.endswith(".zip"):
                parts = source_clean.rstrip("/").split("/")
                if len(parts) >= 5:
                    user, repo = parts[-2], parts[-1]
                    raw_try_url = f"https://raw.githubusercontent.com/{user}/{repo}/main/SKILL.md"
                    try:
                        return install_skill(raw_try_url)
                    except Exception:
                        raw_try_url2 = f"https://raw.githubusercontent.com/{user}/{repo}/master/SKILL.md"
                        return install_skill(raw_try_url2)

            # Jika file ZIP via HTTP
            with tempfile.NamedTemporaryFile(suffix=".zip", delete=False) as tmp_zip:
                tmp_zip_path = tmp_zip.name
            urllib.request.urlretrieve(source_clean, tmp_zip_path)
            res = _install_from_zip(tmp_zip_path, s_dir)
            if os.path.exists(tmp_zip_path):
                os.remove(tmp_zip_path)
            return res
        except Exception as e:
            return {"status": "error", "message": f"Gagal mengunduh skill dari {source_clean}: {e}"}

    # 2. File Lokal ZIP
    if source_clean.endswith(".zip") and os.path.isfile(source_clean):
        return _install_from_zip(source_clean, s_dir)

    # 3. Direktori Lokal yang berisi SKILL.md
    if os.path.isdir(source_clean):
        skill_f = os.path.join(source_clean, "SKILL.md")
        if not os.path.exists(skill_f):
            return {"status": "error", "message": "Direktori sumber tidak memiliki file SKILL.md"}
        slug = os.path.basename(os.path.abspath(source_clean)).lower()
        dest_folder = os.path.join(s_dir, slug)
        shutil.copytree(source_clean, dest_folder, dirs_exist_ok=True)
        return {
            "status": "ok",
            "slug": slug,
            "message": f"Skill '{slug}' berhasil diinstal dari folder lokal."
        }

    return {"status": "error", "message": f"Sumber skill tidak dikenali atau tidak valid: {source_clean}"}

def _install_from_zip(zip_path: str, target_base_dir: str) -> Dict[str, Any]:
    """Helper mengekstrak file ZIP yang berisi SKILL.md."""
    try:
        with tempfile.TemporaryDirectory() as extract_tmp:
            with zipfile.ZipFile(zip_path, "r") as zf:
                zf.extractall(extract_tmp)

            # Cari SKILL.md di folder hasil ekstrak
            skill_md_path = None
            for root, _, files in os.walk(extract_tmp):
                for f in files:
                    if f.lower() == "skill.md":
                        skill_md_path = os.path.join(root, f)
                        break
                if skill_md_path:
                    break

            if not skill_md_path:
                return {"status": "error", "message": "File ZIP tidak mengandung file SKILL.md"}

            source_skill_dir = os.path.dirname(skill_md_path)
            with open(skill_md_path, "r", encoding="utf-8") as f:
                meta, _ = parse_frontmatter(f.read())

            name = meta.get("name")
            if name:
                slug = re.sub(r"[^a-z0-9_-]", "", name.lower().replace(" ", "-"))
            else:
                slug = os.path.basename(source_skill_dir).lower()
                if not slug or slug == "tmp":
                    slug = "custom-skill"

            dest_folder = os.path.join(target_base_dir, slug)
            os.makedirs(dest_folder, exist_ok=True)

            # Salin seluruh file dari direktori skill
            for item in os.listdir(source_skill_dir):
                s_item = os.path.join(source_skill_dir, item)
                d_item = os.path.join(dest_folder, item)
                if os.path.isdir(s_item):
                    shutil.copytree(s_item, d_item, dirs_exist_ok=True)
                else:
                    shutil.copy2(s_item, d_item)

            return {
                "status": "ok",
                "slug": slug,
                "name": meta.get("name", slug),
                "message": f"Skill '{slug}' berhasil diekstrak dan diinstal."
            }
    except Exception as e:
        return {"status": "error", "message": f"Gagal mengekstrak ZIP skill: {e}"}

def delete_skill(name_or_slug: str) -> Dict[str, Any]:
    """Menghapus skill terpasang berdasarkan slug atau nama."""
    s_dir = get_skills_dir()
    slug = name_or_slug.strip().lower().replace(" ", "-")
    target_dir = os.path.join(s_dir, slug)

    if not os.path.exists(target_dir):
        # Coba cari folder terdekat
        found = None
        for entry in os.listdir(s_dir):
            if entry == slug or slug in entry:
                found = entry
                target_dir = os.path.join(s_dir, entry)
                break
        if not found:
            return {"status": "error", "message": f"Skill '{name_or_slug}' tidak ditemukan."}
        slug = found

    try:
        shutil.rmtree(target_dir)
        return {
            "status": "ok",
            "slug": slug,
            "message": f"Skill '{slug}' berhasil dihapus."
        }
    except Exception as e:
        return {"status": "error", "message": f"Gagal menghapus skill '{slug}': {e}"}

if __name__ == "__main__":
    ensure_default_skills()
    print("Daftar Skill Index (Progressive Disclosure):")
    print(json.dumps(get_skill_index(), indent=2))
