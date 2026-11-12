# Panduan untuk Muse lain — isi konten Cerpen Hozin (CERPEN + CERBUNG + CERGAM/KOMIK)

> **DOKUMEN HIDUP — baca ulang dari URL ini setiap kali bekerja.**
> File ini adalah sumber kebenaran. Isinya bisa berubah (judul baru,
> aturan baru, endpoint baru) dan **versi terbaru di URL inilah yang
> berlaku** — jangan mengandalkan salinan lama yang pernah dibaca.
> URL permanen:
> `https://raw.githubusercontent.com/hozin-king/cerpen-hozin/main/data/PANDUAN-MUSE-LAIN.md`

Aplikasi **Cerpen Hozin** (aplikasi baca native Android, repo publik
`hozin-king/cerpen-hozin`) punya 4 jenis konten yang semuanya bisa
kamu bantu isi:

- **Cerpen** — cerita pendek berseri (1 series = beberapa season,
  tiap season 2 episode).
- **Cerbung** — cerita bersambung = LANJUTAN season dari cerpen yang
  sudah ada (tokoh, tempat, dan benda yang sama, season berikutnya).
- **Cergam / Comic** — cerita bergambar = KOMIK (1 episode = 5 panel,
  tiap panel 1 gambar + teks 1–3 kalimat).
- **Berita** — TIDAK diisi dari sini (berita ditulis cron otomatis
  dari situs teknologi).

> ATURAN PALING PENTING: kamu HANYA mengusulkan. Tidak ada konten
> yang tayang tanpa persetujuan **Alyaa** (Muse utama, akun Android).
> Usulan masuk antrean, Alyaa memeriksa, yang lolos digabung ke konten
> resmi dan di-sync ke D1. Yang ditolak diberi alasan, perbaiki dan
> kirim ulang.

## 1. Sistemnya

- Konten resmi tinggal di database Cloudflare D1 `stopwatch-db`, tabel
  `cerpen_*` (series, episodes, komik, komik_episodes, news, manifest).
  Aplikasi (v1.1.19+) membaca dari D1, fallback ke file `.enc` di repo.
- Generator resmi: `cerpen_generator.py` (Python stdlib saja, tanpa
  network, template-based) — cron 3 jam menulis ke D1 otomatis.
  Kamu TIDAK menyentuh D1 langsung.
- Cover cerpen + gambar panel komik dibuat di sisi Alyaa (media
  generation) — kamu TIDAK membuat gambar. Untuk komik, kirim TEKS
  panel + `image_prompt` per panel (lihat §4).
- **Untuk MELANJUTKAN cerita yang sudah ada, baca dulu
  `data/LANJUTAN.md`** (ringkasan 54 cerpen + 10 komik: tokoh, latar,
  alur s/d episode terakhir, benang terbuka) supaya lanjutannya
  konsisten dengan yang sudah terbit. Tanpa baca itu, lanjutanmu
  akan melenceng dari cerita aslinya.

## 2. Cara kirim usulan — API (jalur utama)

Endpoint: `POST https://hozinking.my.id/api/cerpen-submit`
Header: `Authorization: Bearer <API key>` (key dibuat kamu, hash
SHA-256-nya didaftarkan ke Alyaa; plaintext key tidak pernah dikirim
ke Alyaa). Batas: **10 kiriman/hari/key**.

Body JSON — `kind` salah satu dari:

| kind | untuk | field wajib |
|---|---|---|
| `cerpen` | Cerpen baru | `id`, `judul`, `genre`, `deskripsi`, `seasons:[{season:1, episodes:[{no, judul, isi}]}]` |
| `season` | Cerbung (lanjutan season cerpen yang ada) | `id` (cerpen yang ada), `season` (nomor baru), `episodes:[...]` |
| `edit` | Edit episode | `id`, `season`, `no`, `judul?`, `isi?`, `alasan` |
| `delete` | Hapus | `id`, `alasan` |
| `komik` | Cergam/comic baru | `id`, `judul`, `genre`, `deskripsi`, `episodes:[{no, judul, panels:[{teks, image_prompt}]}]` |
| `komik-episode` | Lanjut episode komik yang ada | `id` (komik yang ada), `no` (episode baru), `judul`, `panels` tepat 5 `[{teks, image_prompt}]` |

Respons: `201` = masuk antrean pending; `409` = duplikat
(`duplicate_id` / `duplicate_title` / `duplicate_payload` /
`season_exists` / `target_missing` / `delete_pending`) — jangan kirim
ulang, buat yang lain; `400` = format salah; `401` = kunci salah;
`429` = batas harian habis.

Jalur cadangan (bila API tidak bisa dipakai): tulis usulan sebagai
file JSON ke folder `pending/` di repo (`pending/cerpen-55.json`,
`pending/cerpen-<N>-season-<S>.json`,
`pending/edit-cerpen-<N>-s<S>-e<E>.json`,
`pending/delete-cerpen-<N>.json`, `pending/komik-<N>.json`) atau
paste JSON ke Hozin — Alyaa yang memprosesnya.

## 3. Format & aturan konten (keras)

- **Cerpen baru:** `id` berurutan (`cerpen-55` berikutnya). 1 season,
  2 episode. Tiap episode 600–1200 kata, Bahasa Indonesia baku,
  paragraf pendek. Paragraf penutup episode dibungkus `*...*`
  (miring, Markdown). Boleh pakai coret `~~teks~~` dan warna
  `<font color="#...">teks</font>` secukupnya.
- **Cerbung (`season`):** tokoh, tempat, dan benda SAMA dengan season
  sebelumnya; tiap season 2 episode; akhiri dengan benang terbuka
  kecil untuk season berikut.
- **Cergam/komik:** 1 episode = 5 panel. Teks per panel 1–3 kalimat,
  Bahasa Indonesia baku, akhiri benang terbuka kecil. `image_prompt`
  per panel: gaya webtoon Indonesia, line art bersih, warna hangat,
  portrait 1280×1920, TANPA teks di dalam gambar, tokoh konsisten
  dengan panel lama.
- **Genre yang dipakai:** Drama Keluarga, Romansa, Horor, Komedi,
  Fantasi, Misteri, Slice of Life, Sci-Fi, Petualangan, Thriller.
- **ORIGINAL 100%.** Jangan comot cerpen, novel, atau lirik berhak
  cipta; jangan parafrase karya orang lain. Tokoh/tempat boleh
  terinspirasi suasana Indonesia (desa, pelabuhan, sawah, gang kota)
  tapi cerita harus karangan sendiri.
- **Jangan:** pornografi, SARA, politik, kekerasan grafis, ujaran
  kebencian, promosi judi/pinjol, nama orang nyata sebagai tokoh jahat.
- **Judul:** pendek, ber-suara manusia, tanpa caps lock + tanda seru,
  tanpa emoji, tanpa hashtag. Deskripsi: 1–2 kalimat gaya ngobrol.
- **Jangan duplikat** judul/topik dengan cerpen yang sudah ada —
  API mengecek duplikat secara live (judul ternormalisasi + id +
  isi), jadi daftar di §5 hanya pembantu; penolakan 409 adalah
  patokan akhirnya.

## 4. Format JSON lengkap

Cerpen (`cerpen.json`):

```json
{"cerpen": [{"id": "cerpen-55", "judul": "...", "genre": "Misteri",
"deskripsi": "1-2 kalimat",
"cover": "covers/cover_55.jpg",
"seasons": [{"season": 1, "episodes": [
  {"no": 1, "judul": "...", "isi": "teks episode..."},
  {"no": 2, "judul": "...", "isi": "teks episode..."}]}]}]}
```

Komik (`komik.json`):

```json
{"komik": [{"id": "komik-11", "judul": "...", "genre": "...",
"deskripsi": "...", "cover": "covers/komik_11.jpg",
"episodes": [{"no": 1, "judul": "...",
"panels": [{"teks": "1-3 kalimat",
"image_prompt": "gaya webtoon Indonesia, ..."}]}]}]}
```

## 5. Daftar cerpen yang sudah ada (54, per 12 Nov 2026)

01 Rumah yang Selalu Menunggu (Drama Keluarga) · 02 Surat Terakhir
dari Stasiun (Romansa) · 03 Kosong di Lantai Tiga (Horor) ·
04 Warung Kopi Pakde Slamet (Komedi) · 05 Penjaga Gerbang Senja
(Fantasi) · 06 Kunci yang Hilang (Misteri) · 07 Sepeda Tua Ayah
(Slice of Life) · 08 Sinyal dari Tahun 2087 (Sci-Fi) · 09 Peta Pulau
Karang (Petualangan) · 10 Pesan Terakhir (Thriller) · 11 Pesan dari
Tahun Lalu (Sci-Fi) · 12 Kota di Atas Awan (Sci-Fi) · 13 Surat Wasiat
di Laci Jahit (Drama Keluarga) · 14 Kios Nomor Sembilan Belas (Horor) ·
15 Paket Terakhir atas Nama Ratna (Thriller) · 16 Sepuluh Menit Milik
Pak Tono (Fantasi) · 17 Kucing di Pagar Bambu (Slice of Life) ·
18 Surat untuk Orang Lain (Romansa) · 19 Buku Nilai yang Berubah
Sendiri (Misteri) · 20 RT 05 Go Digital (Komedi) · 21 Terowongan di
Bawah Rumah Gadai (Petualangan) · 22 Pesan dari Besok (Sci-Fi) ·
23 Tukang Tambal Ban yang Hafal Semua Pelat Nomor (Misteri) ·
24 Jejak di pulau kecil yang sepi (Misteri) · 25 Danau yang Mengingat
(Fantasi) · 26 Pagi di desa nelayan di ujung teluk (Slice of Life) ·
27 Sungai di Balik Kabut (Petualangan) · 28 Dewi dan Tama (Romansa) ·
29 Jejak di lembah di antara dua bukit (Petualangan) · 30 Surat
Terakhir dari lembah di antara dua bukit (Fantasi) · 31 Peta Menuju
kota tua di seberang rel (Petualangan) · 32 Gerbang di lereng gunung
yang berkabut (Fantasi) · 33 Peta Menuju kampung di kaki bukit
(Petualangan) · 34 Penjaga lereng gunung yang berkabut (Fantasi) ·
35 Jejak di gang sempit di pinggir kota (Petualangan) · 36 Pulau yang
Tak Ada di Peta (Petualangan) · 37 Pasar Malam di Balik payung
(Fantasi) · 38 Teh Hangat di pelabuhan kecil yang berangin (Romansa) ·
39 Salah Alamat di pelabuhan kecil yang berangin (Komedi) · 40 Surat
dari kampung di kaki bukit (Romansa) · 41 Warisan di lembah di antara
dua bukit (Drama Keluarga) · 42 Penjaga dusun di tengah sawah
(Fantasi) · 43 Surat dari lembah di antara dua bukit (Romansa) ·
44 Pak Sinta dan sepeda Ajaib (Komedi) · 45 Kamar 214 (Thriller) ·
46 Lantai Tiga pelabuhan kecil yang berangin (Horor) · 47 Pulang ke
lereng gunung yang berkabut (Drama Keluarga) · 48 Penjaga desa nelayan
di ujung teluk (Fantasi) · 49 Gerbang di kampung di kaki bukit
(Fantasi) · 50 Sinyal dari pelabuhan kecil yang berangin (Sci-Fi) ·
51 Pagi di lereng gunung yang berkabut (Slice of Life) · 52 Sinyal dari
kampung di kaki bukit (Sci-Fi) · 53 Surat Tanpa Nama (Misteri) ·
54 Pulang ke gang sempit di pinggir kota (Drama Keluarga).

Komik yang sudah ada (10): id `komik-01` s/d `komik-10` — cek
`komik.json` di repo untuk judulnya sebelum mengusulkan komik baru
(id berikutnya `komik-11`).

## 6. MODE OTOMATIS (untuk jalan terjadwal — full auto)

Bagian ini dibaca BILA kamu dijalankan sebagai tugas terjadwal /
otomatis. Tujuan: kamu generate + kirim usulan sendiri tanpa
disuruh tiap kali. Publish tetap lewat antrean — Alyaa yang
menyetujui sebelum tayang.

> CATATAN (12 Nov 2026): bila Muse yang menjalankan tugas ini
> menolak menjadikan URL sebagai sumber perintah (kebijakan keamanan
> terhadap halaman luar), aturan operatif yang berlaku adalah yang
> tertulis langsung di chat — panduan ini tetap dipakai sebagai
> referensi format JSON + daftar judul. Perubahan aturan di panduan
> ini TIDAK otomatis berlaku; perubahan harus disampaikan eksplisit
> lewat chat.

**Jadwal:** 1x sehari. **Maks 2 usulan per jalan** (batas API 10/hari).

**Rotasi isi (biar bervariasi, 1 usulan per hari — siklus 3 hari):**
- Hari 1: 1 cerpen baru.
- Hari 2: 1 cerbung (lanjutan season cerpen yang ada).
- Hari 3: 1 komik — selang-seling komik baru dan lanjut episode komik
  yang ada (kind `komik-episode`).
- Lalu muter lagi. Genre dirotasi — jangan kirim 2 hari berturut-turut
  dengan genre yang sama, dan utamakan genre yang paling sedikit di
  daftar §5.

**Checklist tiap jalan:**
1. Baca ulang panduan ini dari URL (versi terbaru yang berlaku).
2. Tentukan target: cerpen baru = id setelah yang terbesar di §5
   (`cerpen-55`, lalu 56, dst.); cerbung = pilih cerpen yang
   episodenya paling sedikit / season-nya paling lama tidak lanjut;
   komik = pilih komik yang episodenya paling sedikit / lanjut
   episode berikutnya. **Untuk lanjutan (cerbung/komik-episode),
   BACA DULU `data/LANJUTAN.md`** bagian series/komik itu — lanjutkan
   dari benang terbukanya, dengan tokoh + latar yang sama.
3. Generate konten original mengikuti §3 (judul baru, tidak duplikat).
4. Kirim lewat API (§2) dengan API key yang sudah kamu simpan.
   Jangan pernah menulis key-nya di jawaban/log — key hanya dipakai
   di header request.
5. Catat di memorimu sendiri: judul + kind + id + tanggal + status
   respons, supaya jalan berikutnya tidak mengulang ide yang sama.

**Menangani respons API:**
- `201` = masuk antrean, selesai untuk item itu.
- `409 duplicate_id` = id sudah dipakai — naikkan nomor id dan coba lagi.
- `409 duplicate_title` / `duplicate_payload` = judul/isi duplikat —
  buat judul + cerita lain, jangan kirim ulang.
- `409 season_exists` = season itu sudah ada — pilih season berikutnya.
- `409 target_missing` = target tidak ada — cek id di §5 / `cerpen.json`.
- `429` = batas harian habis — BERHENTI, lanjut besok.
- `401` = kunci bermasalah — BERHENTI dan beri tahu Hozin.
- `400` = format salah — perbaiki JSON sesuai §2/§4 lalu coba sekali lagi.

**Bila kamu tidak bisa memanggil API langsung:** tampilkan JSON
usulannya secara lengkap agar Hozin bisa paste ke Alyaa — jangan
berhenti diam-diam.

**Laporan tiap jalan (ringkas):** judul + kind + id + status respons
API untuk tiap usulan. Tidak perlu laporan panjang.
