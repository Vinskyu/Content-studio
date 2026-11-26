---
name: dna-extractor
description: Ekstrak DNA visual, pacing, tipografi, dan audio dari batch video channel referensi untuk menghasilkan skill akun spesialis (.agents/skills/<akun>-director/SKILL.md).
---

# SKILL: DNA EXTRACTOR & ACCOUNT COMPILER

Skill ini membedah batch video referensi dari akun/channel panutan secara ilmiah dan zero-storage bloat untuk mengekstrak "DNA Konten" murni (memisahkan pola format tetap dari elemen spesifik topik). Output akhirnya adalah cetakan skill produksi spesialis permanen di `.agents/skills/<akun>-director/SKILL.md`.

---

## 5 FASE EKSEKUSI OPERASIONAL

```
+-----------------------------------------------------------------------------------+
| 1. ISOLASI TARGET  -->  2. SELEKSI MULTI-TOPIK  -->  3. LOOP BEDAH (ZERO-BLOAT)   |
|                                                              |                    |
| 5. COMPILE SKILL DIRECT <--- 4. SINTESIS DNA (>= 60%) <------+                    |
+-----------------------------------------------------------------------------------+
```

---

### FASE 1: INPUT & ISOLASI TARGET

1. Terima input dari pengguna:
   - **URL Sumber:** 1 URL channel/playlist YouTube Shorts/Reels/TikTok (atau daftar 5–8 URL video individu).
   - **Nama Akun / Workspace Target:** Misal `project_01`, `channel_focus`.
2. Siapkan direktori kerja terisolasi secara mutlak:
   ```bash
   mkdir -p workspaces/<nama>/references/batch/
   ```
3. Pastikan tidak ada interaksi atau pembacaan lintas workspace.

---

### FASE 2: SELEKSI SAMPEL MULTI-TOPIK (0 MB STORAGE)

1. Tarik indeks metadata video terpopuler tanpa mengunduh video (0 MB storage bloat):
   ```bash
   python3 engine/fetch.py channel "<url>" --limit 15 --sort popular --out workspaces/<akun>/references/channel_index.json
   ```
2. Baca file `channel_index.json` dan pilih **5–8 video dengan topik/tema yang berbeda-beda**.
   - *Rasional Ilmiah:* Dengan memilih topik yang kontras (misal topik teknologi vs sejarah vs psikologi dari kreator yang sama), pola yang selalu berulang adalah **DNA Gaya Tetap**, sedangkan hal yang berubah adalah **Variabel Topik**.
3. Sajikan ringkasan tabel seleksi sampel kepada pengguna di chat untuk konfirmasi cepat sebelum proses unduh:

| No | ID Video | Durasi | Views | Topik / Sudut Pandang |
|---|---|---|---|---|
| #1 | `...` | 00:45 | 1.4M | Topik A |
| #2 | `...` | 00:52 | 980K | Topik B |
| ... | ... | ... | ... | ... |

---

### FASE 3: LOOP BEDAH BATCH (ZERO-BLOAT + CHECKPOINT RESUME)

Jalankan proses bedah untuk setiap video terpilih (`ref_01` s/d `ref_0N`):

```bash
# Loop per video
for ref_i in ref_01 ref_02 ref_03 ...
```

#### 1. Cek Checkpoint Resume:
- Periksa keberadaan file `workspaces/<akun>/references/batch/<ref_id>.json`.
- Jika sudah ada dan valid $\rightarrow$ **SKIP** (langsung lanjut ke video berikutnya, hemat kuota dan waktu jika terjadi resume).

#### 2. Unduh Hemat Resolusi Rendah:
- Unduh video di resolusi 480p ke folder cache sementara:
  ```bash
  python3 engine/fetch.py media "<url_video>" --max-height 480 --out .cache/dna_tmp --name <ref_id>
  ```

#### 3. Ekstraksi Sinyal & Visual Contact Sheet:
- Jalankan analisis multi-level engine:
  ```bash
  # Metadata teknis
  python3 engine/inspect.py probe .cache/dna_tmp/<ref_id>.mp4 --out .cache/dna_tmp/<ref_id>_probe.json

  # Peta ketukan beat, transisi cut, dan rentang hening
  python3 engine/inspect.py signals .cache/dna_tmp/<ref_id>.mp4 --out .cache/dna_tmp/<ref_id>_signals.json

  # Titik energi / chorus terbaik
  python3 engine/inspect.py energy .cache/dna_tmp/<ref_id>.mp4 --out .cache/dna_tmp/<ref_id>_energy.json

  # Visual Pass 1: Coarse (gambaran umum video)
  python3 engine/inspect.py sheet .cache/dna_tmp/<ref_id>.mp4 --out .cache/dna_tmp/<ref_id>_sheet_macro.jpg --interval 3.0 --cols 4

  # Visual Pass 2: Fine Zoom (fokus 0-4 detik pertama untuk membedah pola hook)
  python3 engine/inspect.py sheet .cache/dna_tmp/<ref_id>.mp4 --out .cache/dna_tmp/<ref_id>_sheet_hook.jpg --interval 0.5 --start 0 --end 4 --cols 4
  ```
- Jika audio mengandung dialog/bicara dan `GROQ_API_KEY` aktif:
  ```bash
  python3 engine/inspect.py transcribe .cache/dna_tmp/<ref_id>.mp4 --out .cache/dna_tmp/<ref_id>_transcript.json
  ```

#### 4. Analisis Multimodal (Mata Agen):
- Panggil tool `view_file` pada contact sheet `_sheet_macro.jpg` dan `_sheet_hook.jpg` (atau gunakan `python3 engine/router.py call --task vision --prompt "..." --image <sheet.jpg> --checkpoint <path> --pace 4`).
- Ekstrak 7 dimensi metrik sesuai skema `.agents/skills/dna-extractor/references/dna_schema.json`:
  1. **Hook 0–3 detik:** Karakter visual, teks pembuka, pemicu retensi/penasaran (*curiosity trigger*), titik potong pertama (*initial cut*).
  2. **Pacing:** `avg_shot_sec`, jumlah potongan, gaya potong (*hard cut* vs transisi *xfade*).
  3. **Visual Framing:** Rasio aspek, framing kamera, *safe zone*, intensitas `dim` (0.0–1.0), `vignette`, nuansa warna `color_preset`.
  4. **Tipografi:** Keluarga font (pilihan dari `assets/fonts/manifest.json`), ukuran, posisi Y, preset animasi `.ass` (`cinematic_fade`, `slide_up`, `karaoke_word`, `pop_punch`, `clean_minimal`), batas karakter per baris (`max_chars_per_line`).
  5. **Audio:** Struktur (voiceover + BGM / beat-sync lirik), BPM tempo, dinamika drop.
  6. **Elemen Spesifik Topik:** Catat elemen yang hanya berlaku untuk materi/topik video tersebut agar tidak tercampur ke dalam DNA akun.

#### 5. Simpan Checkpoint & Bersihkan Penyimpanan (Patuhi Batas HP):
- Simpan data terstruktur ke `workspaces/<akun>/references/batch/<ref_id>.json`.
- **WAJIB SEGERA HAPUS** file video mentah `.cache/dna_tmp/<ref_id>.*` dan gambar sheet sementara:
  ```bash
  rm -rf .cache/dna_tmp/<ref_id>*
  ```

---

### FASE 4: SINTESIS IRISAN DNA (INVARIANT VS VARIABLE)

1. Baca seluruh file JSON `ref_01.json` s/d `ref_0N.json` di `workspaces/<akun>/references/batch/`.
2. **Kaidah Konsistensi Mayoritas (>= 60%):**
   - Suatu pola hanya diklasifikasikan sebagai **DNA Tetap (Invariant)** jika muncul secara konsisten pada minimal **60% dari seluruh sampel video yang dibedah**.
   - Contoh: Jika 6 dari 7 video menggunakan font sans tebal dengan animasi `pop_punch` di zona bawah dan transisi *hard cut* rata-rata 2.1 detik, maka parameter tersebut **dikunci sebagai DNA Tetap**.
   - Pola yang bervariasi mengikuti subjek (warna objek, kata benda khusus, topik) dikelompokkan ke dalam **Slot Variabel**.
3. Tulis laporan analitis komprehensif ke `workspaces/<akun>/references/blueprint.md`.
4. Perbarui profil DNA channel di `channels.json` dan `workspaces/<akun>/project.json`:
   - Pola ritme dan pacing (durasi rata-rata scene, beat role).
   - Parameter visual (font, animasi subtitle .ass, palet visual).
   - Blueprint struktur adegan standar.

---

### FASE 5: COMPILE SKILL PERMANEN AKUN (`.agents/skills/<akun>-director/SKILL.md`)

Generate file skill spesialis akun di `.agents/skills/<akun>-director/SKILL.md` dengan template struktur berikut:

```markdown
---
name: <akun>-director
description: Sutradara otomatis produksi konten untuk workspace <akun>. Mengunci DNA gaya, struktur shot, tipografi, dan parameter engine/build.py.
---

# <AKUN> CONTENT DIRECTOR

## 1. BATASAN AUDIENS & KARAKTER KONTEN
- Audiens & Nada: ...
- Larangan Keras: ...

## 2. CETAKAN STRUKTUR VIDEO (DETIK-PER-DETIK)
- Detik 00:00 - 00:03 (HOOK): Visual ..., Teks pembuka ..., Trigger ...
- Detik 00:03 - 00:xx (PENGEMBANGAN / BODY): Ritme potong avg ... detik, transisi ...
- Detik xx:xx - END (PAYOFF / SEAMLESS LOOP): CTA / punchline / loop beat ...

## 3. PARAMETER KONKRET ENGINE/BUILD.PY
- Rasio: 9:16 (1080x1920 atau 720x1280)
- Font: "<Font dari assets/fonts/manifest.json>" (ukuran: ..., max_chars: ...)
- Animasi Teks: "<cinematic_fade | slide_up | karaoke_word | pop_punch | clean_minimal>"
- Visual Polish: dim: ..., vignette: ..., color_preset: "..."
- Pacing Target: avg shot ... detik
- Audio Strategy: dual_audio: <true|false>, bgm_volume: 0.15

## 4. CHECKLIST KONTRAK SEBELUM RENDER (AGENTS.MD)
- [ ] 2-Pass Inspect telah dijalankan (probe, signals, energy, sheet).
- [ ] Contact sheet telah diperiksa via view_file.
- [ ] plan.json divalidasi dengan python3 engine/build.py --validate.
- [ ] Tabel kontrak edit disajikan dan telah di-ACC oleh pengguna.
```

---

## CONTOH PENGGUNAAN SKILL INI

```bash
# Saat pengguna meminta: "Bedah channel @stoic_quotes dan jadikan acuan gaya workspace stoik_id"
1. Jalankan Fase 1 & 2 untuk mengambil daftar video terpopuler.
2. Tampilkan pilihan video ke pengguna.
3. Jalankan loop Fase 3 dengan pembersihan storage ketat per iterasi.
4. Hitung irisan DNA di Fase 4 (>= 60%).
5. Tulis .agents/skills/stoik_id-director/SKILL.md di Fase 5.
```
