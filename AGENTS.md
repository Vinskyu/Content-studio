# KONSTITUSI CONTENTSTUDIO (AGENTS.md)

Pedoman hukum dan arsitektur mutlak bagi seluruh agen AI yang beroperasi di ContentStudio V2.2+. Setiap agen yang aktif dalam sesi apa pun terikat penuh tanpa pengecualian pada 7 prinsip berikut:

---

## 1. ARSITEKTUR WORKSPACE UNIVERSAL (V2.2)
- Setiap direktori di `workspaces/<nama>/` adalah domain otonom dengan struktur standar:
  * `project.json` (spesifikasi proyek, adegan, durasi, rasio, framing, parameter audio).
  * `scripts/main.txt` (naskah narasi / voiceover).
  * `assets/` (footage, gambar, elemen visual lokal).
  * `audio/` (rekaman voiceover, file BGM, sound effect).
  * `renders/` (output render perantara & log).
- **Dilarang keras** membuat, membaca, atau menawarkan file warisan lama seperti `niche.md`, `style.md`, atau `laws.md`.
- Semua data preferensi akun dikelola dinamis melalui Channel DNA (`channels.json`) dan pola ritme universal (`patterns.json`).

## 2. ZERO-FORM & LANGSUNG EKSEKUSI
- **Dilarang keras menginterogasi pengguna** dengan daftar pertanyaan panjang tentang format, resolusi, platform, atau opsi teknis.
- Jika pengguna meminta membuat workspace atau proyek dan nama sudah disebut, langsung buat `project.json` (default rasio `9:16` 1080x1920 kecuali pengguna menyebut lain).
- Jika nama proyek belum disebut, buatkan nama default yang relevan (misal `proyek_01`) atau tanyakan tepat 1 hal singkat, lalu segera eksekusi. Beritahu pengguna bahwa parameter dapat diubah kapan saja.

## 3. ZERO-COPY & PENGHEMATAN STORAGE KETAT
- **Dilarang menyalin file media mentah besar** ke dalam direktori internal workspace atau project.
- Semua pemrosesan media wajib mereferensikan file sumber secara langsung via path absolut atau melalui symlink inbox (`/sdcard/ContentStudio/inbox` atau `./inbox/`).
- Sisa storage perangkat terbatas: seluruh artefak perantara dan file cache uji wajib segera dibersihkan seusai inspeksi atau render.

## 4. NOL EDIT MANUAL OLEH PENGGUNA (ZERO MANUAL USER EDIT)
- Pengguna berinteraksi secara murni menggunakan instruksi bahasa alami (natural language).
- Agen memikul tanggung jawab penuh dalam membaca, menulis, merestrukturisasi, dan memperbarui seluruh file konfigurasi dan `project.json`.
- Setiap perubahan, rencana potongan adegan, atau keputusan teknis wajib disajikan kepada pengguna dalam format **ringkasan tabel yang padat, jelas, dan mudah diverifikasi** di chat.

## 5. ALUR KERJA WAJIB: 2-PASS INSPECT ➔ PLAN ➔ ACC ➔ RENDER
1. **INSPECT (Mata & Telinga Agen):**
   - Jalankan `python3 engine/inspect.py probe` untuk membaca spesifikasi teknis media.
   - Jalankan `python3 engine/inspect.py signals` untuk mengekstrak beats, rentang hening, dan scene cut.
   - Jalankan `python3 engine/inspect.py energy` untuk menemukan bagian chorus/drop terbaik.
   - Jalankan `python3 engine/inspect.py sheet` (Pass 1 Coarse untuk gambaran makro; Pass 2 Fine untuk zoom detik krusial).
   - **Wajib panggil tool `view_file`** pada gambar Contact Sheet untuk menganalisis isi visual secara nyata sebelum membuat klaim adegan apa pun.
2. **PLAN (Penyusunan Rencana):**
   - Formulasikan rancangan adegan lengkap beserta tabel ringkas shot list dan sinkronisasi audio.
3. **ACC (Persetujuan Pengguna):**
   - **Dilarang keras merender video final** sebelum pengguna menyetujui (`ACC`) tabel rancangan.
4. **RENDER (Eksekusi Engine):**
   - Lakukan rendering final setelah ACC diperoleh. Output akhir disimpan langsung di `/sdcard/ContentStudio/out/`.

## 6. PARAMETER 100% DINAMIS & UNIVERSAL
- Resolusi (`480p`, `720p`, `1080p`, `1440p`, `4K`, atau `source`), rasio aspek (`16:9`, `9:16`, `1:1`, `4:5`, atau `source`), framerate (`fps`), serta volume audio ditentukan secara dinamis.
- Jangan pernah menawarkan niche, game, atau topik spesifik tertentu sebagai contoh default. Konten bersifat universal untuk berbagai tema video.

## 7. CHANNEL DNA & LIVING PATTERNS
- Channel DNA (`channels.json`) bersifat *self-evolving*: menyerap preferensi font, tempo, dan gaya visual dari revisi pengguna secara bertahap.
- Living Patterns (`patterns.json`) mengunci ritme dan durasi adegan tanpa mengikat topik konten, memungkinkan penggunaan kembali pola pacing yang sukses pada topik apa pun.
- Dilarang mengarang lirik atau transkrip audio jika tidak tersedia file transkrip valid. Gunakan `inspect.py transcribe` jika audio berupa suara bicara, atau minta teks langsung ke pengguna jika diperlukan.
