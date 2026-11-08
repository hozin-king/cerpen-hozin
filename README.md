# Cerpen Hozin

Aplikasi Android untuk membaca cerpen Indonesia secara offline. Native Kotlin, tanpa WebView, tanpa iklan, tanpa pop-up.

## Fitur

- **10+ cerpen original** — masing-masing dengan sistem season (Season 1 = 3 episode, Season 2 = 2 episode).
- **Lanjutkan Membaca** — progres baca tersimpan per episode, kembali tepat di posisi terakhir.
- **Riwayat Baca & Bookmark** — tandai episode favorit, lihat riwayat bacaan terakhir.
- **Cari & Filter Genre** — cari judul/genre, filter berdasarkan genre.
- **Reader yang nyaman** — ukuran teks bisa diubah (A- / A+), 3 tema (Siang, Malam, Sepia), line spacing lega, auto-save posisi baca, tombol "Episode Berikutnya".
- **Berita Teknologi** — berita terbaru dari arsip otomatis, tersimpan offline.
- **Full offline** — semua data (cerpen, gambar, berita) tersimpan di perangkat. Sync konten dari GitHub hanya bila ada internet (tombol "Perbarui Konten", best-effort).
- **Anti-lag & hemat baterai** — gambar di-cache dan di-downsample, tanpa animasi berat, tanpa proses background.

## Struktur repo

```
.
├── app/                    # Proyek Android (Gradle)
│   ├── app/
│   │   └── src/main/       # Kode Kotlin, layout, res, manifest
│   ├── gradlew             # Gradle wrapper (Gradle 8.7)
│   └── local.properties    # TIDAK di-commit (sdk.dir lokal)
├── data/                   # Data konten (single source of truth)
│   ├── cerpen.json         # Daftar cerpen + season + episode
│   ├── news.json           # Berita teknologi
│   ├── covers/             # Sampul cerpen (generate)
│   └── news/img/           # Gambar berita
└── .github/workflows/
    └── android.yml         # CI: build APK debug di GitHub Actions
```

Data di `data/` dibaca langsung sebagai aset aplikasi (lihat `app/build.gradle` → `sourceSets.assets.srcDirs`), sehingga update konten tidak butuh rebuild kode.

## Auto-konten cerpen

Pipeline auto-generate cerpen DIGABUNG ke sistem news yang sudah ada
(`phone-news-watcher`) — bukan script terpisah. Modul `cerpen_generator.py`
di folder phone-news-watcher menghasilkan cerpen original berbasis template,
lalu data disalin ke `data/` repo ini dan ikut ter-push bersama update news.
Jadwalnya bareng cron news (tiap 3 jam). Lihat README di phone-news-watcher.

## Build

### GitHub Actions (utama)
Push ke `main` → workflow `.github/workflows/android.yml` membangun APK debug
dan mengunggahnya sebagai artifact. Unduh artifact `CerpenHozin-debug`.

### Lokal (stopgap)
```bash
cd app
# Buat local.properties berisi: sdk.dir=/path/ke/android-sdk
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Lisensi

Konten cerpen original. Kode aplikasi bebas dipakai untuk belajar.
