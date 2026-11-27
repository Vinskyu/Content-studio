# KONSTITUSI CONTENTSTUDIO (AGENTS.md V3.4)

Pedoman hukum, arsitektur, dan instruksi eksekusi otonom mutlak bagi seluruh agen AI yang beroperasi di ContentStudio V3.4+.
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
  * `memory.json` (catatan keputusan, style, fact, dan aturan cross-session khusus proyek ini).
- File preferensi global disimpan di `.cache/user_preferences.json`.
- **Dilarang keras** membuat atau menawarkan file warisan lama (`niche.md`, `style.md`, `laws.md`). Preferensi akun dikelola melalui Channel DNA (`channels.json`), Living Templates (`patterns.json`), dan Memory (`memory.json`).

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

### E. Pencarian & Pengunduhan Media Mandiri + Auto-QA
- Cari media: `python3 engine/media_fetcher.py search --query "<keyword>" --source auto|wikimedia|ytdlp|pexels|pixabay --type video|photo --limit 6`
- Unduh & alokasikan langsung ke adegan (dengan auto-QA):
  `python3 engine/studio_cli.py media assign --project <nama> --scene <index> --url "<url>" [--start "00:00:10"] [--end "00:00:25"]`
- Inspeksi kualitas media (QA): `python3 engine/media_fetcher.py qa --file <path> [--expected-ratio 9:16|16:9]`

### F. Cross-Session Project Memory & User Preferences
- `python3 engine/studio_cli.py memory list [--project <nama>]`
- `python3 engine/studio_cli.py memory add [--project <nama>] --note "<catatan>" [--category decision|style|rejected|fact]`
- `python3 engine/studio_cli.py memory remove [--project <nama>] --id <id>`

### G. Render Final & Publish Kit
- `python3 engine/studio_cli.py render start --project <nama>`
- `python3 engine/studio_cli.py publish set --project <nama> --title "<judul>" --description "<deskripsi>" --tags "#tag1 #tag2"`

## 4. ZERO-FORM & LANGSUNG EKSEKUSI
- **Dilarang menginterogasi pengguna** dengan daftar pertanyaan panjang tentang format, resolusi, atau opsi teknis.
- Jika pengguna meminta membuat video atau proyek, langsung buat proyek via CLI (default rasio `9:16` 1080x1920 kecuali pengguna menyebut `16:9`).
- Tampilkan ringkasan status dalam bentuk tabel rapi dan padat di chat.

## 5. ALUR KERJA WAJIB & PROTOKOL INTERAKTIF V3.4
1. **CROSS-SESSION PERSISTENCE:**
   - Setiap kali user memberikan koreksi gaya, batasan durasi, atau penolakan elemen tertentu, Agent wajib memanggil `studio_cli.py memory add` agar tidak lupa di sesi obrolan berikutnya.
2. **ACTION PLAN CARD:**
   - Untuk aksi multi-langkah berat atau destruktif (misal: download massal, render final, timpa naskah total), Agent dapat mengeluarkan blok terstruktur:
     `[ACTION_PLAN] {"title":"Judul Rencana","steps":["Langkah 1","Langkah 2"],"approvePrompt":"Lanjutkan eksekusi rencana ini sekarang"}`
     sebelum mengeksekusi langsung.
3. **SMART NEXT-STEP SUGGESTIONS:**
   - Di akhir setiap jawaban, sertakan 2-3 rekomendasi langkah lanjutan relevan dalam format:
     `[SUGGESTIONS] ["Saran 1","Saran 2","Saran 3"]` (maks 4-5 kata per saran).
4. **INSPECT & QA:**
   - Analisis bahan atau cari media referensi via `media_fetcher.py search`, lalu pastikan kelayakan resolusi dan aspek rasio via `media_fetcher.py qa`.
5. **PLAN & ASSEMBLE:**
   - Susun naskah via `script set`, pecah adegan via `script split-scenes`, tetapkan media via `media assign`, dan hasilkan voiceover via `voice generate`.
6. **ACC (Persetujuan Pengguna):**
   - Sajikan tabel ringkasan shot list (adegan, visual, narasi, durasi). Mintalah konfirmasi pengguna sebelum memulai rendering final video 1080p.
7. **RENDER:**
   - Jalankan `render start --project <nama>` setelah ACC diperoleh. Output akhir video tersimpan di `/sdcard/ContentStudio/out/`.

## 6. PARAMETER 100% DINAMIS & UNIVERSAL
- Konten bersifat universal tanpa keterikatan pada niche tertentu. Resolusi, framing, tempo, dan gaya ditentukan secara adaptif.
