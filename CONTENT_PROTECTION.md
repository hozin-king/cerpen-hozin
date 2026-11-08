# Proteksi Konten — Cerpen Hozin v1.2 (8 Nov 2026)

Tujuan: teks cerpen & berita **tidak lagi plaintext di APK** (unzip APK lalu
baca JSON langsung tidak bisa). Ini menghentikan **penyalinan kasual**.
Bukan DRM: seorang reverser yang bertekad tetap bisa mengambil kunci dari
`.so` dan plaintext dari runtime — jujur, itu di luar jangkauan proteksi
ringan ini.

## v1.2 — Enkripsi SEMUA aset (8 Nov 2026)

Perintah user setelah membuka APK dan menemukan `manifest.json` + gambar
masih plaintext: enkripsi semuanya. Perubahan terhadap v1.1:

- **Manifest ikut terenkripsi**: `data/manifest.json` plaintext DIHAPUS,
  diganti `data/manifest.json.enc` (format blob sama: IV 12B +
  ciphertext+tag). Aplikasi mendekripsinya in-memory; tag GCM yang
  mengautentikasinya (tidak ada SHA untuk manifest itu sendiri).
- **Gambar ikut terenkripsi**: `data/covers/<nama>.jpg.enc` dan
  `data/news/img/<nama>.enc`, masing-masing dengan entri manifest berisi
  SHA-256 plaintext + nama file .enc (kunci `"covers/<nama>"` /
  `"news/img/<nama>"`).
- **Tidak ada lagi cache plaintext di internal storage**: v1.1 menyimpan
  hasil dekripsi sebagai `filesDir/data/cerpen.json` / `news.json` /
  `manifest.json` / JPG ("salinan terakhir yang baik"). v1.2 MENGHAPUS
  itu semua — hasil dekripsi HANYA ada di memori (RAM). Fallback-nya
  adalah file .enc itu sendiri (dekripsi ulang tiap kali dibutuhkan).
  Gambar didekripsi ke byte array lalu `BitmapFactory.decodeByteArray`
  (tidak pernah ditulis JPG ke disk).
- **Migrasi**: saat versi baru pertama jalan, `migratePlaintextAway`
  menghapus file plaintext legasi di internal storage SETELAH versi
  .enc-nya terverifikasi valid (dekripsi + SHA lawan manifest untuk
  JSON; tag GCM untuk gambar/manifest). Bila .enc belum valid, plaintext
  dibiarkan (fail-safe).
- **Sync GitHub-raw** ikut berubah: mengunduh `manifest.json.enc` (bukan
  `.json`), menyimpan .enc gambar, dan menjalankan migrasi setelah sync.

## v1.1.3 — Komik, gambar on-demand (8 Nov 2026)

- **Konten komik**: `data/komik.json.enc` + `data/komik/covers/` +
  `data/komik/panels/` (111 file `.enc`), entri manifest
  `"komik.json"`, `"komik/covers/<nama>"`, `"komik/panels/<nama>"`.
  Skema + kunci sama (AES-256-GCM, `CERPEN_CONTENT_KEY`).
- **Gambar komik TIDAK di-bundle ke APK** (on-demand via sync GitHub):
  `app/app/build.gradle` memakai
  `androidResources.ignoreAssetsPattern` dengan pola `"!komik-*"`
  (hanya `komik.json.enc` yang ter-bundle). Pola AGP
  (`PatternBasedFileFilter`) hanya mendukung SATU `*`, di AWAL atau
  AKHIR pola — `*` di tengah TIDAK cocok. Tanpa pengecualian ini APK
  membengkak 13 MB -> ~95 MB. Cerpen/berita tetap ter-bundle seperti
  sebelumnya.
- **Sync + reader**: `DataManager` ikut menyinkronkan komik
  (`komik.json.enc` + gambar) lewat jalur yang sama; reader
  mendekripsi panel in-memory; plaintext tidak pernah ditulis ke disk.

## v1.1.4 — Lapisan gzip pada .enc JSON (8 Nov 2026)

- **Yang di-gzip: hanya ketiga JSON** — `cerpen.json`, `news.json`,
  `komik.json`. Pipeline Python meng-gzip plaintext dulu
  (`gzip.compress(data, compresslevel=9, mtime=0)`) BARU dienkripsi
  AES-256-GCM. Urutan: JSON -> gzip -> AES-GCM -> `.enc`.
  **Gambar (covers, panels, news img) TIDAK di-gzip** (JPEG/PNG sudah
  terkompresi; gzip hanya membuang waktu + byte).
- **Manifest**: entri `sha256` untuk ketiga JSON adalah **SHA-256 dari
  byte TER-GZIP** (bukan dari JSON polos) — karena SHA diverifikasi
  terhadap hasil dekripsi, sebelum gunzip. Manifest itu sendiri
  (`manifest.json.enc`) TIDAK di-gzip.
- **Skema enkripsi TIDAK berubah**: format blob tetap IV 12B +
  ciphertext+tag GCM 16B, kunci sama, dekripsi sama.
- **Aplikasi** (`ContentCrypto.decryptVerified`, jalur teks saja):
  setelah dekripsi + verifikasi SHA, bila 2 byte awal = magic gzip
  (`0x1F 0x8B`) hasilnya di-gunzip (`GZIPInputStream`) baru jadi
  String; bila tidak ada magic, byte dipakai apa adanya. Magic ada
  tapi gunzip gagal -> null (fail-closed, konten ditolak).
  `decryptVerifiedBytes` (jalur gambar) TIDAK berubah — tanpa gunzip.
- **Backward-compatible**: `.enc` lama (JSON polos, tanpa gzip) tetap
  dibaca — deteksi hanya dari magic byte, bukan dari versi/manifest.
  Jadi v1.1.4 membaca konten v1.1.2/v1.1.3 maupun konten baru
  ter-gzip, tanpa migrasi. Sebaliknya, aplikasi LAMA (<= v1.1.3)
  yang membaca `.enc` JSON baru akan mendapat byte gzip mentah dan
  gagal parse JSON — jadi konten ter-gzip hanya boleh di-sync setelah
  v1.1.4 terbit.
- **Tidak ada plaintext ke disk**: gunzip terjadi in-memory, sama
  seperti dekripsi; hasil gunzip tidak pernah ditulis ke file.

| Item | Isi |
|---|---|
| Plaintext | `cerpen.json` / `news.json` / `komik.json` (JSON seperti v1.0); gambar JPG/PNG/WebP |
| Kompresi (v1.1.4, JSON saja) | gzip level 9, `mtime=0`, SEBELUM enkripsi; SHA manifest = SHA-256 byte ter-gzip; gambar TIDAK di-gzip |
| Enkripsi | AES-256-GCM, IV acak 12 byte per file |
| File terenkripsi | `data/cerpen.json.enc` / `data/news.json.enc` / `data/manifest.json.enc` / `data/covers/<nama>.enc` / `data/news/img/<nama>.enc` — biner: **IV 12B + ciphertext + tag GCM 16B** (tag menempel di akhir, bawaan AESGCM) |
| Manifest | `data/manifest.json.enc` (TERENKRIPSI sejak v1.2): `{"cerpen.json": {"sha256": "<hex sha256 dari plaintext>", "file": "cerpen.json.enc"}, "news.json": {...}, "covers/cover_01.jpg": {...}, "news/img/<nama>": {...}}` |
| Kunci | 32 byte, base64 satu baris |

## Alur kunci

1. **Kunci disimpan di luar repo**: `~/workspace/cerpen-content.key` (mode 600,
   44 karakter base64 = 32 byte). JANGAN PERNAH di-commit, disalin ke repo,
   atau ditampilkan di output/log/chat.
2. **Build lokal (verifikasi saja)**: `app/build.gradle` membaca kunci dari
   env `CERPEN_CONTENT_KEY` → properti Gradle → file `CERPEN_KEY_FILE` →
   `~/workspace/cerpen-content.key`. Kunci di-XOR dengan mask tetap 8 byte
   (`{0x3C, 0xA7, 0x19, 0xE2, 0x6B, 0xD4, 0x08, 0x5F}`), hasilnya (hex)
   masuk sebagai definisi compile `CONTENT_KEY_MASKED` / `CONTENT_KEY_MASK`.
   Tanpa kunci valid → build GAGAL dengan pesan jelas.
3. **Build GitHub Actions (resmi)**: kunci dari **GitHub Secret
   `CERPEN_CONTENT_KEY`** (harus dibuat dulu — tanpa secret ini CI gagal).
   Workflow: `.github/workflows/android.yml` (langkah "Build debug APK",
   `env: CERPEN_CONTENT_KEY: ${{ secrets.CERPEN_CONTENT_KEY }}`).
4. **Native lib `contentguard`** (`app/app/src/main/cpp/`): `contentguard.cpp`
   membalik XOR saat runtime dan mengembalikan kunci lewat JNI
   (`ContentGuard.fetchKey(): ByteArray?`). Kunci di `.so` tersimpan dalam
   bentuk ter-XOR (bukan string polos), lalu di-wipe dari stack setelah
   dipakai. Dekripsi AES/GCM dilakukan di Kotlin (`ContentCrypto.kt`,
   `javax.crypto`, tag 128 bit).
5. **Build wajib NDK 25.2.9519653** (`ndkVersion` di `app/app/build.gradle`;
   workflow memasang `ndk;25.2.9519653` via sdkmanager).

## Alur aplikasi (`DataManager.kt`, v1.2)

1. Muat `data/manifest.json.enc` dari internal storage / aset, dekripsi
   in-memory dengan kunci JNI (fallback plaintext legasi dicatat di log).
2. Untuk tiap entri: baca `.enc`, dekripsi dengan kunci dari JNI,
   verifikasi `SHA-256(plaintext) == manifest.sha256`.
3. **Tidak cocok / dekripsi gagal** → toast "Konten rusak" (sekali per
   proses) dan file itu **ditolak**; fallback-nya file .enc itu sendiri
   (dekripsi ulang), bukan salinan plaintext.
4. Hasil dekripsi TIDAK PERNAH ditulis ke disk — hanya di memori.
   Gambar: `decryptImageBytes()` -> byte array -> `BitmapFactory`.
5. **Fallback masa transisi**: bila `.enc` belum ada, plaintext dari
   aset masih boleh dibaca bila ada — tapi dicatat di log. (Sejak v1.2,
   `data/` repo hanya berisi `.enc`.)
6. **Sync GitHub-raw** (`https://raw.githubusercontent.com/hozin-king/cerpen-hozin/main/data`):
   mengambil `manifest.json.enc` + `.enc` saja (bukan plaintext),
   memverifikasi SHA tiap file, lalu mendekripsi in-memory. Gambar
   diunduh sebagai `.enc` dan disimpan sebagai `.enc`.
7. **Migrasi** (`migratePlaintextAway`, jalan di `ensureCopied` + akhir
   sync): hapus `cerpen.json` / `news.json` / `manifest.json` /
   JPG plaintext di `filesDir/data/` setelah .enc-nya terverifikasi.

## Alur pipeline (sisi cron, `~/workspace/phone-news-watcher/`)

- **Kanonis: `content_crypto.py`** — `load_key()`, `encrypt_bytes()` /
  `decrypt_bytes()`, `encrypt_to_repo()` (tulis atomik tmp + `os.replace`),
  `update_manifest()`, `encrypt_repo_file()` (enkripsi satu file repo +
  hapus plaintext-nya), `encrypt_manifest()` (enkripsi manifest ->
  `manifest.json.enc` + hapus plaintext). `read_manifest()` membaca
  plaintext (transisi) atau mendekripsi `manifest.json.enc`.
  Dipakai `sync_cerpen_to_repo.py` dan `sync_news_to_repo.py`.
- Tiap sync: plaintext kanonis → enkripsi di memori → tulis `.enc` +
  update manifest → hapus plaintext dari `data/` → **di akhir,
  enkripsi manifest** (`manifest.json.enc`, plaintext manifest dihapus).
  Gambar (`covers/`, `news/img/`) mengikuti jalur yang sama (v1.2).
- Bila enkripsi gagal: `.enc` + manifest lama dibiarkan utuh (fail-closed);
  run berikutnya mencoba lagi. JANGAN PERNAH menulis `.enc` setengah/corrupt.
- Verifikasi silang (8 Nov 2026): semua 31 `.enc` di `data/` didekripsi
  dengan `content_crypto.decrypt_bytes` dan SHA cocok dengan manifest
  (termasuk manifest yang didekripsi dari `manifest.json.enc`).

## Batasan — dibaca jujur

- Menghentikan: unzip APK + baca JSON/gambar, salin file `data/` dari repo,
  intip `filesDir/data/` di perangkat (sejak v1.2 isinya hanya `.enc`).
- TIDAK menghentikan: reverser yang membongkar `.so` (kunci ter-XOR ada di
  sana), dump memori saat aplikasi jalan, atau screenshot / baca layar.
- **Plaintext v1.0 tetap ada di RIWAYAT git** (commit `57d4332d`) — enkripsi
  melindungi mulai v1.1; menghapus dari riwayat butuh rewrite history
  (tidak dilakukan di v1.1). Plaintext v1.1 (`manifest.json` + JPG di
  `data/`) juga ada di riwayat git sampai commit sebelum v1.2 — yang
  dihapus v1.2 hanya salinan di working tree / APK / perangkat.
- Untuk menghapus plaintext dari repo GitHub saat ini: hapus file
  `data/cerpen.json` + `data/news.json` via antarmuka web GitHub
  (MCP `push-files` tidak bisa menghapus file) — push v1.1 hanya
  menambah/memperbarui file.
- **Verifikasi runtime `files/data/` belum dilakukan di perangkat**:
  tidak ada emulator di VM ini. Yang terbukti lokal: build hijau +
  11/11 unit test, APK hanya berisi `.enc` (31/31 terdekripsi + SHA
  cocok), dan inspeksi kode — tidak ada lagi jalur tulis plaintext di
  `DataManager` (hanya tulis `.enc`). Bukti bahwa folder
  `files/data/` di HP bersih setelah sekali jalan + sync masih menunggu
  tes di POCO.
