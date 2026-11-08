# Enkripsi Semua Aset + Pull-to-Refresh + Footer Versi — Cerpen Hozin v1.2

Tanggal: 8 Nov 2026. Perintah user: (1) enkripsi SEMUA aset (temuan: `manifest.json`
+ gambar masih plaintext di APK, dan cache plaintext di internal storage);
(2) pull-to-refresh; (3) footer versi hardcode "v1.1". Semua dikerjakan di
working tree lokal — TIDAK push, TIDAK ubah workflow CI / signing.
Build lokal hanya verifikasi; APK TIDAK dikirim ke mana pun.

## 1. Enkripsi semua aset (AES-256-GCM, kunci sama `CERPEN_CONTENT_KEY`)

Skema sama persis dengan yang sudah ada (IV acak 12B + ciphertext + tag GCM
16B). Tidak ada secret/kunci baru. Detail lengkap di `CONTENT_PROTECTION.md`
(bagian v1.2).

### Konvensi penamaan `.enc`

| Aset plaintext (dulu) | Hasil terenkripsi (sekarang) | Entri manifest |
|---|---|---|
| `data/cerpen.json` | `data/cerpen.json.enc` | `"cerpen.json"` |
| `data/news.json` | `data/news.json.enc` | `"news.json"` |
| `data/manifest.json` | `data/manifest.json.enc` | — (tag GCM yang mengautentikasi) |
| `data/covers/<nama>.jpg` | `data/covers/<nama>.jpg.enc` | `"covers/<nama>.jpg"` |
| `data/news/img/<nama>` | `data/news/img/<nama>.enc` | `"news/img/<nama>"` |

Manifest berisi SHA-256 plaintext tiap aset + nama file `.enc`-nya.

### File yang diubah — pipeline (`~/workspace/phone-news-watcher/`)

- `content_crypto.py`: `read_manifest()` kini juga bisa membaca
  `manifest.json.enc` (dekripsi); baru: `encrypt_repo_file()` (enkripsi
  satu file repo + hapus plaintext), `encrypt_manifest()` (enkripsi
  manifest -> `.enc` + hapus plaintext), `remove_manifest_entries()`
  (hapus entri manifest file ter-prune), `repo_file_up_to_date()` (cek
  `.enc` sudah berisi plaintext yang sama -> hindari tulis ulang tiap
  run / cegah plaintext tertinggal).
- `sync_cerpen_to_repo.py`: enkripsi cover + prune cover tak dirujuk
  (berikut entrinya di manifest) + enkripsi manifest di akhir + validasi
  (dekripsi tiap `.enc` + SHA lawan manifest).
- `sync_news_to_repo.py`: enkripsi gambar berita + prune + enkripsi
  manifest di akhir + validasi yang sama.

### File yang diubah — aplikasi (`app/app/src/main/java/com/hozinking/cerpen/`)

- `ContentCrypto.kt`: baru `decryptVerifiedBytes()` (dekripsi + SHA, untuk
  gambar); `decryptVerified()` kini memakainya; `Log.w` dibungkus aman
  untuk unit test JVM (android stub melempar di JVM).
- `DataManager.kt`:
  - `loadVerifiedText()` TIDAK LAGI menulis `cerpen.json` / `news.json`
    plaintext ke internal storage — hasil dekripsi hanya di memori.
    Fallback "salinan terakhir yang baik" = file `.enc` itu sendiri.
  - Baru `loadManifest()` (dekripsi `manifest.json.enc` in-memory, cache
    memori, fallback plaintext legasi dicatat di log).
  - Baru `decryptImageBytes(assetPath)` (cover/berita -> byte array di
    memori, verifikasi SHA lawan manifest; tidak pernah tulis JPG ke disk).
  - `ensureCopied()` hanya menyalin `.enc` + menjalankan migrasi.
  - Baru `migratePlaintextAway()`: hapus `cerpen.json` / `news.json` /
    `manifest.json` / JPG plaintext di `filesDir/data/` SETELAH `.enc`-nya
    terverifikasi valid (dekripsi + SHA untuk JSON; tag GCM untuk
    gambar/manifest). Bila `.enc` belum valid, plaintext dibiarkan
    (fail-safe). Dijalankan tiap `ensureCopied` + tiap akhir sync.
  - `syncFromGithub()`: unduh `manifest.json.enc` (bukan `.json`),
    simpan `.enc` gambar (baru `syncOneImage()`), migrasi di akhir.
  - Dihapus: `cerpenFile()` / `newsFile()` (cache plaintext),
    `coverLocalFile()` / `newsImageLocalFile()` (diganti
    `decryptImageBytes()`), `fetchText()` (tak terpakai).
- `Models.kt`: `CoverCache` decode dari byte array
  (`BitmapFactory.decodeByteArray`); dihapus `loadCerpenFromAssets()` /
  `loadNewsFromAssets()` / `readAsset()` (pemuat plaintext mati, tak ada
  pemanggil) + import tak terpakai.
- `ContentCryptoTest.kt` (baru) + fixture di `src/test/resources/`:
  `manifest_fixture.json.enc`, `image_fixture.jpg.enc`,
  `test-only-key.b64` (kunci TEST sekali-pakai, random — BUKAN kunci
  konten). Fixture dibuat Python (AESGCM) -> membuktikan interop
  Python -> Kotlin.

### Cara kerja dekripsi (aplikasi)

`manifest.json.enc` -> dekripsi (kunci JNI `contentguard`) -> JSON ->
tiap aset `.enc` -> dekripsi -> verifikasi SHA-256 lawan manifest ->
pakai. JSON jadi String di memori; gambar jadi byte array -> Bitmap.
Gagal verifikasi -> toast "Konten rusak", file ditolak.

## 2. Pull-to-refresh (v1.2)

- `activity_main.xml`: root `ScrollView` dibungkus
  `SwipeRefreshLayout` (`@+id/swipe_refresh`) — satu tempat, berlaku
  untuk tab Cerpen dan Berita.
- `MainActivity.kt`: `swipeRefresh.setOnRefreshListener { syncNow() }` —
  jalur sync yang SAMA dengan tombol "Perbarui Konten"
  (`DataManager.syncFromGithub`, tidak ada jalur sync kedua). Indikator
  loading tampil selama sync (`isRefreshing`); toast hasil tetap yang
  lama ("Konten diperbarui" / "Sudah terbaru / tidak ada internet").
  Guard sync ganda (tombol + tarik bersamaan). Warna indikator ikut
  tema (`palette.accent`). Tombol "Perbarui Konten" TETAP ada.
- `app/build.gradle`: tambah dependensi
  `androidx.swiperefreshlayout:swiperefreshlayout:1.1.0` (wajib untuk
  SwipeRefreshLayout) + `buildFeatures.buildConfig = true` (wajib untuk
  `BuildConfig.VERSION_NAME`, lihat §3). Dua baris ini satu-satunya
  perubahan build.gradle; signing/workflow TIDAK diubah.

## 3. Footer versi (v1.2)

- `activity_main.xml`: teks footer jadi "Cerpen Hozin · semua konten
  tersimpan di perangkat" (angka versi dihapus dari layout);
  `MainActivity.applyThemeAndFont()` mengisi
  `"Cerpen Hozin v${BuildConfig.VERSION_NAME} · ..."`.
- `activity_settings.xml`: teks versi jadi "Cerpen Hozin";
  `SettingsActivity.refresh()` mengisi
  `"Cerpen Hozin v${BuildConfig.VERSION_NAME}"`.
- Verifikasi: `BuildConfig.VERSION_NAME = "1.1.1"` (generated) =
  `versionName = "1.1.1"` di `app/build.gradle`. Tidak ada lagi "v1.1"
  hardcode di teks layout (sisa hanya komentar kode).

## Hasil validasi (8 Nov 2026, build lokal)

- `data/` repo: **0 file plaintext**; **38/38 `.enc` dekripsi + SHA cocok**
  (cerpen.json.enc 22 cerpen / 170 episode, news.json.enc 10 artikel,
  manifest.json.enc, 22 cover, 14 gambar berita). Cover 12–22 ikut
  terenkripsi; 4 file cover `media-generation-*` sementara ter-prune.
- Build lokal: **BUILD SUCCESSFUL**, unit test **11/11 lolos**
  (5 ContentCryptoTest baru + 6 NewsDetailsFormatterTest lama).
- APK lokal `app-debug.apk` 13.167.765 B: assets hanya 38 file `.enc`,
  **0 plaintext**; 38/38 `.enc` di dalam APK terdekripsi + SHA cocok
  (verifikasi independen dengan kunci yang sama).
- Insiden selama build (diperbaiki, didokumentasikan di sini):
  - Komentar KDoc `covers/*.enc` memicu "unclosed comment" Kotlin
    (nested `/*`) -> pola glob dihapus dari komentar.
  - Gradle test worker + Kotlin daemon gagal konek loopback 127.0.0.1
    (penyakit Java di VM ini) -> init script lokal baru
    `~/.gradle-cerpen/init.d/test-worker-ipv4.gradle` (preferIPv4Stack
    HANYA untuk test worker, bukan daemon — daemon tetap butuh IPv6
    untuk proxy) + `-Pkotlin.daemon.jvm.options=...` saat build.
  - Download dependensi baru gagal PKIX (CA egress di cacerts JDK 17
    kedaluwarsa/rotasi) -> CA saat ini di-import ulang ke cacerts JDK 17.
  - Bug sync cover: salinan plaintext yang disalin ulang tertinggal
    karena loop enkripsi melewati file yang `.enc`-nya sudah ada ->
    diperbaiki dengan `repo_file_up_to_date()` (banding SHA hasil
    dekripsi vs plaintext; hapus plaintext bila sudah cocok).
- Catatan: `.enc` JSON (`cerpen.json.enc` / `news.json.enc` /
  `manifest.json.enc`) ditulis ulang tiap run sync meski isi plaintext
  sama (IV baru) — perilaku bawaan v1.1, bukan dari perubahan ini;
  hanya byte `.enc` yang berubah, SHA plaintext di manifest tetap.

## Yang BELUM selesai / butuh tes perangkat

- **Tes di POCO (menunggu user/parent):** (a) folder
  `files/data/` di HP benar-benar hanya berisi `.enc` setelah sekali
  jalan + sync (migrasi menghapus plaintext lama) — tidak ada emulator
  di VM ini, jadi ini belum terbukti runtime; yang terbukti lokal: build
  hijau, APK hanya `.enc`, dan inspeksi kode (tidak ada lagi jalur tulis
  plaintext di `DataManager` — hanya tulis `.enc`); (b) gestur
  pull-to-refresh di kedua tab + footer menampilkan "v1.1.1";
  (c) cover cerpen 12–22 tampil.
- **Batasan jujur (seperti CONTENT_PROTECTION.md):** skema ini menutup
  ekstraksi kasual (unzip APK / intip storage), TAPI bukan anti-reverser
  absolut — kunci ada di native lib (ter-XOR), dump memori saat aplikasi
  jalan tetap bisa mengambil plaintext. Jangan klaim lebih.
- **Tidak dilakukan (di luar tugas):** push ke GitHub, ubah workflow CI /
  signing, ubah isi cerpen/berita, kirim APK.
