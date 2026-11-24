---
name: hok_insights-director
description: Sutradara otomatis produksi konten untuk workspace hok_insights. Mengunci DNA gaya, struktur shot, tipografi, dan parameter engine/build.py.
---

# HOK INSIGHTS CONTENT DIRECTOR (SKILL DNA)

Skill spesialis untuk memproduksi video pendek Shorts/Reels/TikTok di workspace `hok_insights`. Skill ini telah mengunci seluruh parameter visual, ritme pacing, dan tipografi hasil bedah batch 5 video referensi kreator Ariya Eka.

---

## 1. BATASAN NICHE & HUKUM GAYA
- **Topik Utama:** Fakta hero, lore skin/kolaborasi, dan mekanik gameplay unik Honor of Kings.
- **Audiens:** Gamer kasual hingga kompetitif di Indonesia.
- **Gaya Narasi:** Kasual, bersahabat, cepat & padat (~170 WPM, *zero dead air*).
- **Standar Resolusi Produksi:** Full HD 1080p (`1080x1920`, 30fps).
- **Standar Unduh Bahan Produksi:** Wajib 1080p (`--max-height 1080`). Resolusi 480p hanya untuk riset awal/bedah `dna-extractor`.
- **Larangan Keras:**
  - Dilarang membuka video dengan sapaan klise (*"Halo teman-teman"*).
  - Dilarang membiarkan batas hitam (*black bars*) kosong pada gameplay 16:9.
  - Dilarang menampilkan teks lebih dari 4 kata per kemunculan di subtitle.

---

## 2. CETAKAN STRUKTUR VIDEO (DETIK-PER-DETIK)

```
00:00 - 00:03.5 (HOOK RETENSI)
  ├─ Visual: Subjek vertikal (Hero 3D lobby / Splash art / Potret tokoh) zoom halus
  ├─ Header 3 Baris: Kuning (#FFEE00) + Putih (#FFFFFF) + Merah (#FF2222)
  ├─ Elemen: Panah melengkung merah penunjuk subjek
  └─ Audio: 1 kalimat pemicu rasa penasaran / pertanyaan kontroversial

00:03.5 - 00:45.0 (PENGEMBANGAN / BODY INTI)
  ├─ Format Poin: Listicle 3 Poin (Point 1 -> Point 2 -> Point 3) atau Analisis 2 Sisi
  ├─ Visual: Klip gameplay 16:9 di tengah dengan efek blur-fill cermin di atas/bawah
  ├─ Pacing: Pergantian shot setiap 2.5 - 4.0 detik (fast jump cut)
  ├─ B-Roll & Meme: Sisipan meme reaksi (Wojak/Patrick/Monyet) selama 1.0 - 1.5 detik
  └─ Subtitle: Lower-third (Y=80%), 2-4 kata per muncul, ALL CAPS, putih stroke hitam tebal

00:45.0 - 00:55.0 (PAYOFF & OUTRO INTERAKTIF)
  ├─ Visual: Gameplay penutup atau meme reaksi
  ├─ Narasi: Pertanyaan balik pemancing komentar ("Gimana menurut kalian?")
  └─ Elemen: Pop-up meme kucing jempol
```

---

## 3. PARAMETER KONKRET ENGINE/BUILD.PY (TEMPLATE PLAN.JSON RESMI)

Saat menyusun `plan.json` untuk proyek di `hok_insights`, gunakan struktur kontrak aktual yang divalidasi oleh `engine/build.py`:

```json
{
  "output": {
    "out_dir": "/sdcard/ContentStudio/out/hok_insights/<nama_proyek>",
    "filename": "output.mp4",
    "resolution": "1080p",
    "aspect": "9:16",
    "fps": 30,
    "quality": "balanced",
    "audio_strategy": "viral_sync",
    "dual_audio": true
  },
  "visual_polish": {
    "color_preset": "vibrant_gameplay",
    "vignette": 0.08,
    "video_fade_in": 0.2,
    "video_fade_out": 0.3
  },
  "segments": [
    {
      "source": "workspaces/hok_insights/inbox/gameplay_01.mp4",
      "start": 0.0,
      "end": 3.5,
      "framing": "blur_fill",
      "bg_dim": 0.15
    }
  ],
  "overlays": [
    {
      "src": "workspaces/hok_insights/assets/arrow_red.png",
      "start": 0.0,
      "end": 3.0,
      "x": 0.65,
      "y": 0.35,
      "width": 0.25,
      "fade": 0.2
    }
  ],
  "audio_tracks": {
    "clip_audio_volume": 0.15,
    "auto_ducking": true,
    "voiceover": {
      "source": "workspaces/hok_insights/inbox/voiceover.mp3",
      "volume_db": -1.5,
      "start": 0.0
    },
    "bgm": {
      "source": "workspaces/hok_insights/audio_viral/bgm_beat.mp3",
      "volume_db": -16.0,
      "offset": 0.0,
      "loop": true,
      "fade_in": 0.5,
      "fade_out": 1.0
    },
    "sfx": []
  },
  "texts": [
    {
      "text": "HERO PALING UNIK\\nDI HONOR OF KINGS\\nWAJIB COBA!",
      "animation": "header_hook",
      "font": "Montserrat-ExtraBold",
      "size": 0.045,
      "x": 0.5,
      "y": 0.35,
      "start": 0.0,
      "end": 3.5,
      "line_colors": ["#FFEE00", "#FFFFFF", "#FF2222"]
    },
    {
      "text": "MEKANIK PASIF",
      "animation": "chapter_card",
      "font": "Montserrat-ExtraBold",
      "size": 0.05,
      "x": 0.5,
      "y": 0.5,
      "start": 3.5,
      "end": 4.8,
      "color": "#FFFFFF"
    },
    {
      "text": "SKILL DUA BISA\\nSTUN AREA",
      "animation": "pop_punch",
      "font": "Montserrat-ExtraBold",
      "size": 0.04,
      "x": 0.5,
      "y": 0.8,
      "start": 4.8,
      "end": 6.5,
      "color": "#FFFFFF"
    }
  ]
}
```

---

## 4. FORMULA KALIMAT HOOK & CTA TERBUKTI

- **Formula Hook Kontroversi:**
  *"Siapakah hero paling [KATA SIFAT EKSTRIM] di Honor of Kings?..."*
- **Formula Hook Crossover / Anime:**
  *"Skin terbaru [NAMA HERO] ini beneran terinspirasi dari [TOKOH ANIME/BUDAYA] kah?..."*
- **Formula Hook Listicle Mekanik:**
  *"[JUMLAH] mekanik hero Honor of Kings yang gak bakal kalian temuin di game lain..."*
- **Formula Hook Tokoh / Sejarah:**
  *"Siapa sangka, [NAMA FIGUR PRO/POPULER] ternyata pernah main Honor of Kings?..."*
- **Formula Outro:**
  *"Dan itu dia... Kalau menurut kalian, kenapa [PERTANYAAN]? Tulis di kolom komentar!"*

---

## 5. CHECKLIST KONTRAK SEBELUM ACC (AGENTS.MD)

Sebelum mengajukan tabel kontrak edit kepada pengguna:
- [ ] 2-Pass Inspect (`probe`, `signals`, `energy`, `sheet`) telah dijalankan pada media sumber.
- [ ] Contact sheet telah diperiksa via `view_file` (bebas black bars, framing pas).
- [ ] Subtitle tidak menutupi area kritis gameplay (terkunci di lower-third).
- [ ] Durasi total video berada di rentang 45–60 detik.
- [ ] `python3 engine/build.py --validate plan.json` lolos 0 error.
