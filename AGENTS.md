# KONSTITUSI CONTENTSTUDIO (AGENTS.md)

Pedoman hukum dan arsitektur mutlak bagi seluruh agen AI yang beroperasi di ContentStudio. Setiap agen yang aktif dalam sesi apa pun terikat penuh tanpa pengecualian pada 7 klausul berikut:

---

## 1. ISOLASI WORKSPACE MUTLAK
- Setiap direktori di `workspaces/<nama>/` adalah domain otonom dan terisolasi total (`niche.md`, `laws.md`, `style.md`, `audio_viral/`, `audio_safe/`, `references/`, `projects/`).
- **Dilarang keras** membaca, mengimpor, membocorkan, atau mencampur konteks, gaya, maupun aset lintas workspace.
- Proyek baru harus selalu bernaung di bawah workspace aktif yang ditentukan.

## 2. ZERO-COPY & PENGHEMATAN STORAGE KETAT
- **Dilarang menyalin file media mentah besar** ke dalam direktori internal workspace atau project.
- Semua pemrosesan media wajib mereferensikan file sumber secara langsung via path absolut atau melalui symlink inbox (`/sdcard/ContentStudio/inbox` atau `./inbox/`).
- Sisa storage perangkat terbatas: seluruh artefak perantara dan file cache uji wajib segera dibersihkan seusai inspeksi/render.

## 3. NOL EDIT MANUAL OLEH PENGGUNA (ZERO MANUAL USER EDIT)
- Pengguna berinteraksi secara murni menggunakan instruksi bahasa alami (natural language).
- Agen memikul tanggung jawab penuh dalam membaca, menulis, merestrukturisasi, dan memperbarui seluruh file konfigurasi, `*.md`, dan `plan.json`.
- Setiap perubahan, rencana potongan adegan, atau keputusan teknis wajib disajikan kepada pengguna dalam format **ringkasan tabel yang padat, jelas, dan mudah diverifikasi** di chat.

## 4. ALUR KERJA WAJIB: 2-PASS INSPECT ➔ PLAN ➔ ACC ➔ RENDER
1. **INSPECT (Mata & Telinga Agen):**
   - Jalankan `python3 engine/inspect.py probe` untuk membaca spesifikasi teknis media.
   - Jalankan `python3 engine/inspect.py signals` untuk mengekstrak beats, rentang hening, dan scene cut.
   - Jalankan `python3 engine/inspect.py energy` untuk menemukan bagian chorus/drop terbaik (dilarang menebak reff secara acak).
   - Jalankan `python3 engine/inspect.py sheet` (Pass 1 Coarse untuk gambaran makro; Pass 2 Fine untuk zoom detik krusial).
   - **Wajib panggil tool `view_file`** pada gambar Contact Sheet untuk menganalisis isi visual secara nyata sebelum membuat klaim adegan apa pun.
2. **PLAN (Penyusunan Rencana):**
   - Formulasikan `plan.json` lengkap beserta tabel ringkas shot list dan sinkronisasi audio.
3. **ACC (Persetujuan Pengguna):**
   - **Dilarang keras merender video final** sebelum pengguna menyetujui (`ACC`) tabel rancangan `plan.json`.
4. **RENDER (Eksekusi Engine):**
   - Lakukan rendering final setelah ACC diperoleh. Output akhir disimpan langsung di `/sdcard/ContentStudio/out/`.

## 5. PARAMETER 100% DINAMIS
- Resolusi (`480p`, `720p`, `1080p`, `1440p`, `4K`, atau `source`), rasio aspek (`16:9`, `9:16`, `1:1`, `4:5`, atau `source`), framerate (`fps`), serta strategi audio (`viral_sync` vs `clean_monetize`) **tidak pernah dikunci secara statis** dalam kode.
- Seluruh parameter ditentukan secara dinamis berdasarkan instruksi pengguna, target platform, dan konfigurasi workspace.

## 6. ANTI-HALUSINASI LIRIK & AUDIO
- Dilarang keras mengarang lirik, transkrip, atau menebak bagian vokal/reff jika file `.lrc` atau transkrip valid tidak ditemukan.
- Sebelum memilih bagian lagu/audio, agen wajib menjalankan `inspect.py energy` (dilarang menebak reff secara acak).
- Untuk lagu non-LRCLIB/Suno atau rekaman suara bicara, gunakan `inspect.py transcribe` jika `GROQ_API_KEY` tersedia.
- Jika sumber teks tidak ada, wajib berhenti dan minta teks asli + timestamp dari pengguna, atau jalankan mode visual + beat-sync tanpa teks.

## 7. ARSITEKTUR SKILL (AKUN DIRECTOR)
- Setiap akun/niche memiliki Skill DNA spesialis di `.agents/skills/<akun>-director/SKILL.md` yang dibangun lewat `.agents/skills/dna-extractor/SKILL.md`.
- Saat memproduksi konten di suatu workspace, agen WAJIB memuat Skill `<akun>-director` milik workspace tersebut jika sudah ada agar gaya antar-video tetap seragam, konsisten, dan tidak keluar dari koridor niche.
