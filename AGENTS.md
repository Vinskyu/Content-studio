# KONSTITUSI CONTENTSTUDIO (AGENTS.md V3.3)

Pedoman hukum, arsitektur, dan instruksi eksekusi otonom mutlak bagi seluruh agen AI yang beroperasi di ContentStudio V3.3+.
Setiap agen yang aktif terikat penuh tanpa pengecualian pada prinsip dan tata cara operasi berikut:

---

## 1. OTONOMI PENUH (FULL AUTONOMOUS EXECUTION - ZERO MANUAL USER CLICK)
- **DILARANG KERAS menyuruh pengguna mengklik tombol atau mengedit konfigurasi secara manual di UI.**
- Agen memikul tanggung jawab penuh mengeksekusi alur produksi dari hulu ke hilir (pembuatan proyek, penyusunan naskah, pemecahan adegan, pencarian/pengunduhan media stock, alokasi visual, penjadwalan voiceover AI, background music, hingga rendering dan penyiapan publish kit) menggunakan **Master CLI (`engine/studio_cli.py`)** dan **Media Engine (`engine/media_fetcher.py`)**.
- Setiap perintah mutasi otomatis memancarkan marker `[UI_SYNC] <scope>:<target>` ke sistem agar layar antarmuka Android langsung tersinkronisasi secara real-time tanpa refresh manual.

## 2. ARSITEKTUR WORKSPACE & STRUKTUR FILE UNIVERSAL
- Setiap direktori di `workspaces/<nama>/` adalah domain otonom dengan struktur standar:
  * `project.json` (spesifikasi proyek, adegan, durasi, rasio, framing, status tahap, parameter audio).
  * `scripts/main.txt` (naskah narasi / voiceover).
  * `assets/` (footage, gambar, elemen visual lokal).
  * `audio/` (rekaman voiceover `vo.mp3`, file BGM, sound effect).
  * `renders/` (output render perantara & log).
- **Dilarang keras** membuat atau menawarkan file warisan lama (`niche.md`, `style.md`, `laws.md`). Preferensi akun dikelola melalui Channel DNA (`channels.json`) dan Living Templates (`patterns.json`).

## 3. MASTER CLI COMMAND CHEATSHEET (`python3 engine/studio_cli.py`)
Gunakan selalu perintah Python Master CLI untuk memanipulasi dapur produksi:

### A. Proyek & Workspace
- `python3 engine/studio_cli.py project list`
- `python3 engine/studio_cli.py project create --name <nama> --ratio 9:16|16:9 [--channel <id>]`
- `python3 engine/studio_cli.py project status --name <nama>`
- `python3 engine/studio_cli.py project delete --name <nama>`

### B. Channel DNA & Living Templates
- `python3 engine/studio_cli.py channel list`
- `python3 engine/studio_cli.py channel create --name <nama> --dna "<catatan_gaya>"`
- `python3 engine/studio_cli.py channel link --project <nama> --channel <id>`
- `python3 engine/studio_cli.py template list`
- `python3 engine/studio_cli.py template apply --project <nama> --template <id>`

### C. Naskah & Pemecahan Adegan (Script & Scenes)
- `python3 engine/studio_cli.py script set --project <nama> --text "<naskah_lengkap>"`
- `python3 engine/studio_cli.py script split-scenes --project <nama>`
- `python3 engine/studio_cli.py scene list --project <nama>`
- `python3 engine/studio_cli.py scene set-visual --project <nama> --index <0_based> --file <path_aset>`
- `python3 engine/studio_cli.py scene set-prompt --project <nama> --index <0_based> --prompt "<prompt_gambar>"`

### D. Voiceover AI & Audio Mastering
- `python3 engine/studio_cli.py voice generate --project <nama> [--voice id-ID-ArdiNeural|id-ID-GadisNeural]`
- `python3 engine/studio_cli.py voice master --project <nama> --preset podcast|cinematic|clean`
- `python3 engine/studio_cli.py audio set-bgm --project <nama> --file <path_bgm> [--volume 0.18]`

### E. Pencarian & Pengunduhan Media Mandiri
- Cari media: `python3 engine/media_fetcher.py search --query "<keyword>" --source auto|wikimedia|ytdlp|pexels|pixabay --type video|photo --limit 6`
- Unduh & alokasikan langsung ke adegan:
  `python3 engine/studio_cli.py media assign --project <nama> --scene <index> --url "<url>" [--start "00:00:10"] [--end "00:00:25"]`

### F. Render Final & Publish Kit
- `python3 engine/studio_cli.py render start --project <nama>`
- `python3 engine/studio_cli.py publish set --project <nama> --title "<judul>" --description "<deskripsi>" --tags "#tag1 #tag2"`

## 4. ZERO-FORM & LANGSUNG EKSEKUSI
- **Dilarang menginterogasi pengguna** dengan daftar pertanyaan panjang tentang format, resolusi, atau opsi teknis.
- Jika pengguna meminta membuat video atau proyek, langsung buat proyek via CLI (default rasio `9:16` 1080x1920 kecuali pengguna menyebut `16:9`).
- Tampilkan ringkasan status dalam bentuk tabel rapi dan padat di chat.

## 5. ALUR KERJA WAJIB: INSPECT ➔ PLAN ➔ ACC ➔ RENDER
1. **INSPECT & GATHER:**
   - Analisis bahan atau cari media referensi via `media_fetcher.py search`.
2. **PLAN & ASSEMBLE:**
   - Susun naskah via `script set`, pecah adegan via `script split-scenes`, tetapkan media via `media assign`, dan hasilkan voiceover via `voice generate`.
3. **ACC (Persetujuan Pengguna):**
   - Sajikan tabel ringkasan shot list (adegan, visual, narasi, durasi). Mintalah konfirmasi pengguna sebelum memulai rendering final video 1080p.
4. **RENDER:**
   - Jalankan `render start --project <nama>` setelah ACC diperoleh. Output akhir video tersimpan di `/sdcard/ContentStudio/out/`.

## 6. PARAMETER 100% DINAMIS & UNIVERSAL
- Konten bersifat universal tanpa keterikatan pada niche tertentu. Resolusi, framing, tempo, dan gaya ditentukan secara adaptif.
