# BLUEPRINT DNA KONTEN: HOK INSIGHTS
*Hasil Sintesis Analisis Batch 5 Sampel Video Channel "Ariya Eka" (@ariyaekaa)*

---

## 1. METODOLOGI ANALISIS BATCH

Ekstraksi dilakukan secara menyeluruh terhadap 5 sampel video dengan topik saling kontras:
1. `ref_01`: Popularitas Hero ("Hero Paling Gak Laku Di Honor Of Kings", 53s)
2. `ref_02`: Desain & Lore Skin ("Skin Limbo Terbaru Terinspirasi Dari Kaneki Kah?", 48s)
3. `ref_03`: Mekanik Gameplay Unik ("3 Mekanik Terunik Hero Honor Of Kings", 56s)
4. `ref_04`: Sejarah & Esports Trivia ("Faker Pernah Main Honor Of Kings", 58s)
5. `ref_05`: Tutorial Teknis Settingan ("3 Settingan Buat Ningkatin Gameplay Kalian", 60s)

---

## 2. IRISAN DNA TETAP (INVARIANT — KONSISTENSI $\ge 60\%$)

Pola-pola berikut terbukti selalu hadir pada $\ge 60\%$ (bahkan sebagian besar 100%) sampel dan wajib dikunci sebagai standar format:

### A. Anatomi Hook Pembuka (00:00 - 00:03.5) — *Konsistensi: 100%*
- **Visual Hook:** Menampilkan subjek vertikal utama (model 3D hero lobby, splash art full, atau potret tokoh esports) dengan zoom perlahan.
- **Header Title 3 Baris:** Judul besar bertumpuk di tengah-kiri layar dengan stroke hitam tebal:
  - *Baris 1:* Kata pengantar kuning (`#FFEE00`) atau merah (`#FF2222`), misal: `HERO PALING`, `TIGA MEKANIK`, `GOAT GAME PC`.
  - *Baris 2:* Kata kunci utama font paling besar/punchy, misal: `GAK LAKU`, `UNIK HERO`, `FAKER`.
  - *Baris 3:* Konteks game putih (`#FFFFFF`), misal: `HONOR OF KINGS`, `MAIN HOK`.
- **Elemen Penunjuk:** Panah melengkung merah tebal (`red curved arrow`) yang mengarah ke titik fokus visual hero/objek.
- **Pola Kalimat Hook:** 1 kalimat padat langsung ke inti masalah/kontroversi/pertanyaan tanpa basa-basi intro (durasi hook rata-rata 3.7 detik).
- **Initial Cut:** Transisi adegan pertama terjadi tepat di detik 2.9s – 4.2s saat kalimat hook selesai.

### B. Komposisi Kanvas & Layout Gameplay — *Konsistensi: 100%*
- **Format Kanvas:** 9:16 (1080x1920 / 720x1280).
- **Blur-Fill Gameplay 16:9:** Klip gameplay horizontal ditempatkan di tengah layar (lebar pas 100%), sementara ruang kosong di atas dan bawah diisi oleh rekaman gameplay yang sama yang di-scale melebar dan diberi efek *Gaussian Blur* tebal.
- **Secondary B-Roll Layout:** Untuk adegan artikel/berita menggunakan latar putih tekstur kertas, dan untuk foto arsip/sejarah menggunakan latar belakang *Dark Grid* (kisi-kisi kotak gelap).

### C. Tipografi & Subtitle — *Konsistensi: 100%*
- **Font Family:** Sans-Serif Modern Ekstra Tebal (identik dengan `Montserrat-ExtraBold` / `Poppins-ExtraBold` dari `assets/fonts/manifest.json`).
- **Posisi:** Zona aman bawah (*Lower Third*, $Y \approx 80\%$) agar tidak tertutup deskripsi/tombol like TikTok/Reels.
- **Ritme:** Frasa pendek super cepat (2–4 kata per tayang, rata-rata 3 kata), berganti mengikuti artikulasi vokal.
- **Warna:** Huruf kapital putih (`#FFFFFF`), outline hitam solid tebal (`Outline=2.0`), bayangan lembut.

### D. Audio & Pacing Narasi — *Konsistensi: 100%*
- **Dual Audio:** Voiceover vokal dominan, jernih, dan renyah di foreground (-1.5 dBFS) + BGM musik elektronik/lofi-trap upbeat di background (-16 dBFS).
- **Tempo BGM:** 120 – 128 BPM.
- **Kecepatan Bicara (WPM):** Rata-rata **172 WPM** (rentang 155 – 186 WPM). Karakter narasi energik, artikulasi tegas, nol jeda mati (*dead air*).
- **Outro:** Ditutup dengan kalimat interaksi audiens (*"Gimana menurut kalian?"* atau *"Kalau menurut kalian, kenapa...?"*) dibarengi stiker meme kucing jempol.

### E. Sisipan Meme & B-Roll Pop-Up — *Konsistensi: 80%*
- Penempatan meme pop-up singkat (0.8 – 1.5 detik) di tengah penjelasan untuk mereset fokus audiens (misal: Patrick pura-pura tidak lihat, Wojak menangis, monyet mengacungkan jari, kucing jempol).
- Chapter card di tengah layar untuk format listicle (misal: *Heal Minion*, *Stun Tower*).

---

## 3. SLOT VARIABEL (VARIABLE PER TOPIK)

Elemen-elemen berikut berubah sesuai materi konten dan dilarang dikunci statis:
1. **Jenis Konten:** 
   - *Listicle 3 Poin* (Mekanik unik, Hero tercepat, Settingan rahasia).
   - *Bedah Fakta/Popularitas* (Kenapa hero X sepi peminat, Rekor kill pro player).
   - *Lore & Inspirasi Skin* (Asal-usul mitologi, kemiripan anime/kultur pop).
2. **Karakter Meme:** Gambar meme disesuaikan dengan nada kalimat (lucu, ironis, sedih, salut).
3. **Warna Aksen:** Warna aksen line 1 hook title menyesuaikan tema skin/hero (misal skin merah -> merah, gameplay -> kuning).

---

## 4. GAP FITUR engine/build.py UNTUK MENDUKUNG DNA INI 1:1

Berdasarkan bedah empiris di atas, berikut 4 gap fitur konkret di `engine/build.py` yang diperlukan agar ContentStudio dapat memproduksi video yang identik 100% dengan gaya Ariya Eka:

| No | Nama Gap Fitur | Deskripsi Kebutuhan Teknis di `build.py` | Dampak Jika Belum Ada |
|---|---|---|---|
| **1** | **Layout `blur-fill` 16:9-ke-9:16** | Mendukung opsi `"layout": "blur_fill"` pada klip video di `plan.json`. Filter FFmpeg: `[in]split=2[fg][bg];[bg]scale=W:H:force_original_aspect_ratio=increase,crop=W:H,boxblur=25:5[bg_blur];[fg]scale=W:-2:force_original_aspect_ratio=decrease[fg_scaled];[bg_blur][fg_scaled]overlay=0:(H-h)/2[out]` | Saat ini klip 16:9 dipotong tepi kiri-kanannya (*crop*) atau memiliki garis hitam pekat (*pad black*). |
| **2** | **Overlay Gambar / Meme / Stiker (`overlays[]`)** | Mendukung array `"overlays": [{"src": "path.png", "start": 15.0, "end": 16.5, "x": "center", "y": "center", "scale": 0.4}]` untuk menumpangkan meme reaksi, panah merah penunjuk, atau avatar hero secara presisi. | Belum bisa memunculkan meme Patrick/Wojak atau panah penunjuk hook. |
| **3** | **Header Hook Bertingkat 3 Warna** | Dukungan styling header multiline di `.ass` generator dengan parameter warna per baris (Kuning/Putih/Merah) yang terkunci di detik 0.0 s/d detik hook selesai. | Header hook harus dipecah manual sebagai teks subtitle biasa. |
| **4** | **Chapter Card / Point Title Center** | Preset animasi kartu judul poin di tengah layar yang muncul 1.2 detik saat transisi antar-poin listicle (font besar, background pill transparan tipis). | Judul poin hanya muncul di lower-third subtitle biasa. |
