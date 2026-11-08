# Proteksi Konten — Cerpen Hozin v1.1

Tujuan: teks cerpen & berita **tidak lagi plaintext di APK** (unzip APK lalu
baca JSON langsung tidak bisa). Ini menghentikan **penyalinan kasual**.
Bukan DRM: seorang reverser yang bertekad tetap bisa mengambil kunci dari
`.so` dan plaintext dari runtime — jujur, itu di luar jangkauan proteksi
ringan ini.

## Format (disepakati dengan pipeline, JANGAN ubah sepihak)

| Item | Isi |
|---|---|
| Plaintext | `cerpen.json` / `news.json` (JSON seperti v1.0) |
| Enkripsi | AES-256-GCM, IV acak 12 byte per file |
| File terenkripsi | `data/cerpen.json.enc` / `data/news.json.enc` — biner: **IV 12B + ciphertext + tag GCM 16B** (tag menempel di akhir, bawaan AESGCM) |
| Manifest | `data/manifest.json` (PLAINTEXT): `{"cerpen.json": {"sha256": "<hex sha256 dari plaintext>", "file": "cerpen.json.enc"}, "news.json": {...}}` |
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

## Alur aplikasi (`DataManager.kt`)

1. Muat `data/manifest.json` (plaintext) dari assets.
2. Untuk tiap entri: baca `.enc`, dekripsi dengan kunci dari JNI,
   verifikasi `SHA-256(plaintext) == manifest.sha256`.
3. **Tidak cocok / dekripsi gagal** → toast "Konten rusak" (sekali per
   proses) dan file itu **ditolak**; dipakai salinan terakhir yang baik
   (`filesDir/data/*.json`) bila ada.
4. Plaintext hasil dekripsi disimpan **HANYA** di penyimpanan internal
   privat aplikasi (`filesDir/data/`), tidak pernah di storage eksternal.
5. **Fallback masa transisi**: bila `.enc` belum ada, plaintext
   `cerpen.json`/`news.json` boleh dibaca bila ada — tapi dicatat di log.
   (Sejak v1.1, `data/` repo hanya berisi `.enc` + manifest.)
6. **Sync GitHub-raw** (`https://raw.githubusercontent.com/hozin-king/cerpen-hozin/main/data`):
   mengambil `manifest.json` + `.enc` saja (bukan plaintext), memverifikasi
   SHA tiap file, lalu mendekripsi. Cover/gambar tetap diambil terpisah
   seperti biasa.

## Alur pipeline (sisi cron, `~/workspace/phone-news-watcher/`)

- **Kanonis: `content_crypto.py`** — `load_key()`, `encrypt_bytes()` /
  `decrypt_bytes()`, `encrypt_to_repo()` (tulis atomik tmp + `os.replace`),
  `update_manifest()`. Dipakai `sync_cerpen_to_repo.py` dan
  `sync_news_to_repo.py`.
- Tiap sync: plaintext kanonis → enkripsi di memori → tulis `.enc` +
  update `manifest.json` (SHA plaintext) → hapus plaintext dari `data/`.
- Bila enkripsi gagal: `.enc` + manifest lama dibiarkan utuh (fail-closed);
  run berikutnya mencoba lagi. JANGAN PERNAH menulis `.enc` setengah/corrupt.
- Verifikasi silang (8 Nov 2026): kedua `.enc` di `data/` didekripsi dengan
  `content_crypto.decrypt_bytes` dan SHA cocok dengan manifest.

## Batasan — dibaca jujur

- Menghentikan: unzip APK + baca JSON, salin file `data/` dari repo.
- TIDAK menghentikan: reverser yang membongkar `.so` (kunci ter-XOR ada di
  sana), dump memori saat aplikasi jalan, atau root yang membaca
  `filesDir/data/` (plaintext hasil dekripsi ada di sana).
- **Plaintext v1.0 tetap ada di RIWAYAT git** (commit `57d4332d`) — enkripsi
  melindungi mulai v1.1; menghapus dari riwayat butuh rewrite history
  (tidak dilakukan di v1.1).
- Untuk menghapus plaintext dari repo GitHub saat ini: hapus file
  `data/cerpen.json` + `data/news.json` via antarmuka web GitHub
  (MCP `push-files` tidak bisa menghapus file) — push v1.1 hanya
  menambah/memperbarui file.
