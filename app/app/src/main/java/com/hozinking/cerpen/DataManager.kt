package com.hozinking.cerpen

import android.content.Context
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream

/**
 * Semua data (cerpen, berita, gambar) disimpan LOKAL di penyimpanan internal
 * sehingga aplikasi full offline.
 *
 * PROTEKSI KONTEN (v1.2, lihat CONTENT_PROTECTION.md):
 * - TIDAK ADA plaintext di assets maupun di internal storage. Yang
 *   di-bundle hanya file `.enc` (AES-256-GCM, IV 12B + ciphertext+tag):
 *   `cerpen.json.enc`, `news.json.enc`, `komik.json.enc`, `manifest.json.enc`,
 *   gambar cover dan berita (tiap file berekstensi .enc di bawah
 *   covers/ dan news/img/) serta gambar komik (komik/covers/ dan
 *   komik/panels/). Manifest berisi SHA-256 plaintext
 *   tiap aset — manifestnya sendiri terenkripsi (tag GCM yang
 *   mengautentikasinya).
 * - DataManager: baca manifest terenkripsi -> dekripsi .enc (kunci dari
 *   native lib `contentguard` via JNI) -> verifikasi SHA-256 -> baru
 *   dipakai. Hasil dekripsi HANYA ada di memori (RAM), tidak pernah
 *   ditulis ke disk.
 * - Bila verifikasi gagal: toast "Konten rusak" dan file itu ditolak;
 *   fallback-nya adalah file .enc itu sendiri (dekripsi ulang), bukan
 *   salinan plaintext.
 * - Fallback MASA TRANSISI: bila .enc belum ada, plaintext dari aset
 *   masih boleh dibaca (dicatat di log) — agar update bertahap dari
 *   v1.1 tidak membuat aplikasi kosong. Migrasi sekali jalan
 *   (migratePlaintextAway) menghapus plaintext lama di internal storage
 *   setelah .enc-nya terverifikasi valid.
 * - Sync GitHub-raw: manifest.json.enc + file .enc saja (bukan
 *   plaintext), tiap file diverifikasi SHA-256 setelah dekripsi.
 */
/**
 * Hasil satu kali sync/unduh: apakah ada data baru, berapa file gambar
 * baru yang diunduh, berapa yang sudah ada di perangkat, dan berapa
 * yang gagal (perlu coba lagi saat sinyal bagus).
 */
data class SyncResult(
    val changed: Boolean,
    val downloaded: Int,
    val alreadyHad: Int,
    val failed: Int
)

/** Progress sync: (selesai, total, label tahap). Total 0 = belum diketahui. */
typealias SyncProgress = (done: Int, total: Int, label: String) -> Unit

object DataManager {

    private const val TAG = "DataManager"
    private const val GITHUB_RAW_BASE =
        "https://raw.githubusercontent.com/hozin-king/cerpen-hozin/main/data"

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Toast "Konten rusak" cukup sekali per proses agar tidak spam. */
    private var corruptToastShown = false

    /** Manifest terdekripsi yang di-cache di memori; dibatalkan tiap sync. */
    private var manifestCache: JSONObject? = null

    /** ensureCopied hanya perlu jalan sekali per proses. */
    private var copiedOnce = false

    private fun dataDir(context: Context): File =
        File(context.filesDir, "data").apply { if (!exists()) mkdirs() }

    private fun coversDir(context: Context): File =
        File(dataDir(context), "covers").apply { if (!exists()) mkdirs() }

    private fun newsImgDir(context: Context): File =
        File(dataDir(context), "news/img").apply { if (!exists()) mkdirs() }

    private fun komikCoversDir(context: Context): File =
        File(dataDir(context), "komik/covers").apply { if (!exists()) mkdirs() }

    private fun komikPanelsDir(context: Context): File =
        File(dataDir(context), "komik/panels").apply { if (!exists()) mkdirs() }

    /** File .enc di internal storage (satu-satunya salinan di disk). */
    private fun cerpenEncFile(context: Context): File = File(dataDir(context), "cerpen.json.enc")
    private fun newsEncFile(context: Context): File = File(dataDir(context), "news.json.enc")
    private fun komikEncFile(context: Context): File = File(dataDir(context), "komik.json.enc")
    private fun manifestEncFile(context: Context): File = File(dataDir(context), "manifest.json.enc")

    /** Jalur plaintext LEGASI (v1.1) — hanya dipakai migrasi penghapusan. */
    private fun legacyPlaintextFiles(context: Context): List<File> {
        val out = ArrayList<File>()
        out += File(dataDir(context), "cerpen.json")
        out += File(dataDir(context), "news.json")
        out += File(dataDir(context), "komik.json")
        out += File(dataDir(context), "manifest.json")
        coversDir(context).listFiles()?.forEach { if (!it.name.endsWith(".enc")) out += it }
        newsImgDir(context).listFiles()?.forEach { if (!it.name.endsWith(".enc")) out += it }
        komikCoversDir(context).listFiles()?.forEach { if (!it.name.endsWith(".enc")) out += it }
        komikPanelsDir(context).listFiles()?.forEach { if (!it.name.endsWith(".enc")) out += it }
        return out
    }

    /** Salin aset awal (.enc saja) ke internal storage bila belum ada. */
    fun ensureCopied(context: Context) {
        if (copiedOnce) return
        try {
            copyAssetIfMissing(context, "manifest.json.enc", manifestEncFile(context))
            copyAssetIfMissing(context, "cerpen.json.enc", cerpenEncFile(context))
            copyAssetIfMissing(context, "news.json.enc", newsEncFile(context))
            copyAssetIfMissing(context, "komik.json.enc", komikEncFile(context))
            val covers = context.assets.list("covers") ?: emptyArray()
            for (name in covers) {
                if (!name.endsWith(".enc")) continue
                copyAssetIfMissing(context, "covers/$name", File(coversDir(context), name))
            }
            val newsImgs = context.assets.list("news/img") ?: emptyArray()
            for (name in newsImgs) {
                if (!name.endsWith(".enc")) continue
                copyAssetIfMissing(context, "news/img/$name", File(newsImgDir(context), name))
            }
            val komikCovers = context.assets.list("komik/covers") ?: emptyArray()
            for (name in komikCovers) {
                if (!name.endsWith(".enc")) continue
                copyAssetIfMissing(context, "komik/covers/$name", File(komikCoversDir(context), name))
            }
            val komikPanels = context.assets.list("komik/panels") ?: emptyArray()
            for (name in komikPanels) {
                if (!name.endsWith(".enc")) continue
                copyAssetIfMissing(context, "komik/panels/$name", File(komikPanelsDir(context), name))
            }
            migratePlaintextAway(context)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        copiedOnce = true
    }

    /**
     * MIGRASI v1.2: hapus file plaintext legasi (v1.1) dari internal
     * storage — cerpen.json / news.json / manifest.json / JPG — SETELAH
     * versi .enc-nya terbukti ada dan terdekripsi valid. Bila .enc belum
     * ada / tidak valid, plaintext dibiarkan (fail-safe: jangan hapus
     * satu-satunya salinan yang masih bisa dibaca).
     */
    private fun migratePlaintextAway(context: Context) {
        val key = ContentGuard.getKey()
        for (plain in legacyPlaintextFiles(context)) {
            if (!plain.exists() || plain.length() == 0L) continue
            var valid = false
            try {
                val encFile = File(plain.parentFile, plain.name + ".enc")
                if (encFile.exists() && encFile.length() > 0 && key != null) {
                    val enc = encFile.readBytes()
                    // Gambar/manifest: tag GCM cukup (dekripsi sukses = valid).
                    valid = ContentCrypto.decrypt(enc, key) != null
                    // JSON: verifikasi penuh (dekripsi + SHA lawan manifest).
                    if (valid && (plain.name == "cerpen.json" || plain.name == "news.json" || plain.name == "komik.json")) {
                        val entry = loadManifest(context)?.optJSONObject(plain.name)
                        val sha = entry?.optString("sha256", "")
                        valid = !sha.isNullOrEmpty() &&
                            ContentCrypto.decryptVerified(enc, key, sha) != null
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            if (valid) {
                Log.i(TAG, "migrasi: hapus plaintext legasi ${plain.name}")
                try {
                    plain.delete()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                Log.w(TAG, "migrasi: ${plain.name} dibiarkan (.enc belum valid)")
            }
        }
    }

    private fun copyAssetIfMissing(context: Context, asset: String, dest: File) {
        if (dest.exists() && dest.length() > 0) return
        try {
            dest.parentFile?.mkdirs()
            context.assets.open(asset).use { input ->
                FileOutputStream(dest).use { out -> input.copyTo(out) }
            }
        } catch (e: Exception) {
            // Aset tidak ada — bukan error fatal.
            e.printStackTrace()
        }
    }

    private fun readAssetBytes(context: Context, asset: String): ByteArray? {
        return try {
            context.assets.open(asset).use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
    }

    private fun readAssetText(context: Context, asset: String): String? {
        return try {
            context.assets.open(asset).use { it.bufferedReader(Charsets.UTF_8).readText() }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Muat manifest (terdekripsi) — dari cache internal .enc dulu, lalu
     * aset .enc; fallback plaintext legasi (dicatat di log). Hasil
     * di-cache di memori sampai sync menulis manifest baru.
     */
    private fun loadManifest(context: Context): JSONObject? {
        manifestCache?.let { return it }
        val key = ContentGuard.getKey()
        val parsed = parseManifestBytes(readManifestEncBytes(context), key)
            ?: parseManifestText(readManifestPlaintext(context))
        if (parsed != null) manifestCache = parsed
        return parsed
    }

    private fun readManifestEncBytes(context: Context): ByteArray? {
        val f = manifestEncFile(context)
        if (f.exists() && f.length() > 0) {
            try {
                return f.readBytes()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return readAssetBytes(context, "manifest.json.enc")
    }

    private fun readManifestPlaintext(context: Context): String? {
        val f = File(dataDir(context), "manifest.json")
        if (f.exists() && f.length() > 0) {
            try {
                return f.readText(Charsets.UTF_8)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return readAssetText(context, "manifest.json")?.also {
            Log.w(TAG, "manifest PLAINTEXT dari aset (masa transisi)")
        }
    }

    private fun parseManifestBytes(enc: ByteArray?, key: ByteArray?): JSONObject? {
        if (enc == null || key == null) {
            if (enc != null) Log.w(TAG, "manifest .enc ada tapi kunci native tidak tersedia")
            return null
        }
        val plain = ContentCrypto.decrypt(enc, key) ?: run {
            Log.w(TAG, "manifest .enc gagal didekripsi")
            return null
        }
        return try {
            JSONObject(String(plain, Charsets.UTF_8))
        } catch (e: Exception) {
            Log.w(TAG, "manifest .enc bukan JSON valid: ${e.message}")
            null
        }
    }

    private fun parseManifestText(text: String?): JSONObject? {
        if (text.isNullOrBlank()) return null
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            Log.w(TAG, "manifest plaintext rusak: ${e.message}")
            null
        }
    }

    private fun invalidateManifestCache() {
        manifestCache = null
    }

    /** Entri manifest untuk satu aset: Pair(sha256, nama file .enc). */
    private fun manifestEntry(context: Context, name: String): Pair<String, String>? {
        val manifest = loadManifest(context) ?: return null
        val entry = manifest.optJSONObject(name) ?: return null
        val sha = entry.optString("sha256", "")
        val file = entry.optString("file", "")
        if (sha.isEmpty() || file.isEmpty()) return null
        return sha to file
    }

    private fun readEncBytes(context: Context, encName: String): ByteArray? {
        val f = File(dataDir(context), encName)
        if (f.exists() && f.length() > 0) {
            try {
                return f.readBytes()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return readAssetBytes(context, encName)
    }

    private fun toastCorrupt(context: Context) {
        if (corruptToastShown) return
        corruptToastShown = true
        val appCtx = context.applicationContext
        mainHandler.post {
            Toast.makeText(appCtx, "Konten rusak — memakai salinan terakhir", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Muat teks JSON terverifikasi untuk [jsonName] ("cerpen.json" / "news.json").
     * Urutan: .enc + manifest terenkripsi + kunci native -> plaintext
     * masa transisi (aset, dicatat di log). Hasil dekripsi HANYA ada di
     * memori — tidak pernah ditulis ke disk. Tidak ada lagi "salinan
     * terakhir yang baik" berupa plaintext: fallback-nya adalah file
     * .enc itu sendiri (dekripsi ulang tiap kali dibutuhkan).
     */
    private fun loadVerifiedText(context: Context, jsonName: String): String? {
        ensureCopied(context)
        val entry = manifestEntry(context, jsonName)
        if (entry != null) {
            val (sha, file) = entry
            val enc = readEncBytes(context, file)
            if (enc != null) {
                val key = ContentGuard.getKey()
                if (key == null) {
                    Log.w(TAG, "$jsonName: kunci native tidak tersedia, coba fallback plaintext")
                } else {
                    val text = ContentCrypto.decryptVerified(enc, key, sha)
                    if (text != null) return text // hanya di memori
                    Log.w(TAG, "$jsonName: verifikasi .enc gagal (SHA/dekripsi)")
                    toastCorrupt(context)
                }
            } else {
                Log.w(TAG, "$jsonName: file .enc tidak ditemukan ($file)")
            }
        } else {
            Log.w(TAG, "$jsonName: entri manifest tidak ada")
        }
        // Fallback masa transisi: plaintext dari aset (v1.0/v1.1) — dicatat di log.
        val plain = readAssetText(context, jsonName)
        if (plain != null) {
            Log.w(TAG, "$jsonName: fallback PLAINTEXT dari aset (masa transisi)")
            return plain
        }
        return null
    }

    /** Baca cerpen: .enc terverifikasi, fallback plaintext transisi. */
    fun loadCerpen(context: Context): List<Cerpen> {
        val text = loadVerifiedText(context, "cerpen.json") ?: return emptyList()
        return try {
            ContentLoader.parseCerpen(text)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /** Baca berita: .enc terverifikasi, fallback plaintext transisi. */
    fun loadNews(context: Context): List<NewsArticle> {
        val text = loadVerifiedText(context, "news.json") ?: return emptyList()
        return try {
            ContentLoader.parseNews(text)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /** Baca komik: .enc terverifikasi, fallback plaintext transisi. Kosong bila file belum ada. */
    fun loadKomik(context: Context): List<Komik> {
        val text = loadVerifiedText(context, "komik.json") ?: return emptyList()
        return try {
            ContentLoader.parseKomik(text)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Dekripsi satu gambar (cover / berita / komik) jadi byte array di memori.
     * [assetPath] berbentuk "covers/cover_01.jpg", "news/img/xxx.jpg",
     * "komik/covers/xxx.jpg" atau "komik/panels/xxx.jpg".
     * Sumber: file .enc internal dulu, lalu aset .enc. Verifikasi SHA
     * lawan manifest bila entrinya ada (tag GCM selalu diperiksa).
     * Fallback plaintext masa transisi dicatat di log. TIDAK PERNAH
     * menulis hasil dekripsi ke disk.
     */
    fun decryptImageBytes(context: Context, assetPath: String): ByteArray? {
        ensureCopied(context)
        val enc = readImageEncBytes(context, "$assetPath.enc")
        if (enc != null) {
            val key = ContentGuard.getKey()
            if (key == null) {
                Log.w(TAG, "$assetPath: kunci native tidak tersedia")
            } else {
                val entry = manifestEntry(context, assetPath)
                val bytes = if (entry != null) {
                    ContentCrypto.decryptVerifiedBytes(enc, key, entry.first)
                } else {
                    Log.w(TAG, "$assetPath: tanpa entri manifest, verifikasi GCM saja")
                    ContentCrypto.decrypt(enc, key)
                }
                if (bytes != null) return bytes
                Log.w(TAG, "$assetPath: verifikasi .enc gagal (SHA/dekripsi)")
                toastCorrupt(context)
            }
        }
        // Fallback masa transisi: plaintext (internal / aset).
        // Jalur generik di bawah dataDir — berlaku untuk covers/,
        // news/img/, komik/covers/ dan komik/panels/.
        val plainFile = File(dataDir(context), assetPath)
        if (plainFile.exists() && plainFile.length() > 0) {
            Log.w(TAG, "$assetPath: fallback PLAINTEXT internal (masa transisi)")
            return try {
                plainFile.readBytes()
            } catch (e: Exception) {
                null
            }
        }
        return try {
            context.assets.open(assetPath).use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
    }

    private fun readImageEncBytes(context: Context, encPath: String): ByteArray? {
        val f = File(dataDir(context), encPath)
        if (f.exists() && f.length() > 0) {
            try {
                return f.readBytes()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return readAssetBytes(context, encPath)
    }

    private fun isOnline(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * GET satu URL, kembalikan body bila HTTP 200, null bila gagal.
     * v1.1.6: 1x ulangi bila gagal (jeda 500 ms) — sinyal HP sering
     * putus sesaat, dan manifest adalah gerbang seluruh sync.
     */
    private fun fetchBytes(url: String): ByteArray? {
        repeat(2) { attempt ->
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 20000
                    setRequestProperty("User-Agent", "CerpenHozin/1.1")
                }
                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    return conn.inputStream.readBytes()
                }
            } catch (e: Exception) {
                // Jatuh ke ulangi di bawah (kecuali percobaan terakhir).
            } finally {
                conn?.disconnect()
            }
            if (attempt == 0) {
                try {
                    Thread.sleep(500)
                } catch (ignored: InterruptedException) {
                }
            }
        }
        return null
    }

    /**
     * Sync best-effort dari GitHub raw: manifest.json.enc + file .enc
     * (BUKAN plaintext). Tiap file diverifikasi SHA-256 setelah dekripsi;
     * yang gagal ditolak dan salinan lama dipertahankan. Dijalankan di
     * thread background; callback di main thread dengan true bila ada
     * data baru. [onProgress] melaporkan progres (dipakai bilah progres
     * di MainActivity): label tahap dulu (total belum diketahui), lalu
     * hitungan gambar setelah daftarnya diketahui.
     */
    fun syncFromGithub(
        context: Context,
        onProgress: SyncProgress? = null,
        onDone: (Boolean) -> Unit
    ) {
        syncInternal(context, onProgress) { result -> onDone(result.changed) }
    }

    /** Preferensi sync D1: versi manifest terakhir yang berhasil dirakit. */
    private fun d1Prefs(context: Context) =
        context.getSharedPreferences("cerpen_d1", Context.MODE_PRIVATE)

    private const val KEY_D1_VERSION = "d1_version"

    /**
     * Manifest lokal saat ini hasil rakitan D1 (true) atau dari GitHub
     * (false). Penting: SHA manifest GitHub hanya cocok dengan file
     * .enc repo — menimpa manifest D1 dengannya membuat 3 JSON lokal
     * gagal verifikasi SHA (gzip buatan aplikasi beda byte dengan
     * pipeline), jadi fetchFreshManifest tidak boleh mengambil manifest
     * GitHub selama flag ini true.
     */
    private const val KEY_D1_MANIFEST_ACTIVE = "d1_manifest_active"

    /**
     * Sync konten — D1 dulu, GitHub bila D1 gagal (v1.1.19).
     * Tanda tangan sama dengan syncFromGithub supaya pemanggil tidak
     * berubah; syncFromGithub tetap ada sebagai jalur GitHub murni.
     */
    fun syncContent(
        context: Context,
        onProgress: SyncProgress? = null,
        onDone: (Boolean) -> Unit
    ) {
        Thread {
            val result = try {
                runD1Sync(context, onProgress) ?: runSync(context, onProgress)
            } catch (e: Exception) {
                e.printStackTrace()
                try {
                    runSync(context, onProgress)
                } catch (e2: Exception) {
                    e2.printStackTrace()
                    SyncResult(false, 0, 0, 0)
                }
            }
            mainHandler.post { onDone(result.changed) }
        }.start()
    }

    /**
     * Inti sync D1: manifest -> dump `all` -> rakit ulang 3 JSON .enc +
     * manifest lokal -> unduh gambar yang belum ada. Null bila ADA
     * SATU SAJA kegagalan (offline / kunci / versi / jumlah baris /
     * dekripsi / tulis disk) — pemanggil memakai jalur GitHub lama.
     *
     * Urutan komit: tulis .enc baru ke file .tmp -> unduh gambar ->
     * pindah .tmp ke tempatnya (semua berhasil dulu) -> tulis manifest
     * -> simpan versi. Versi BARU disimpan setelah manifest tertulis,
     * jadi sync yang mati di tengah jalan akan mengulang rebuild
     * berikutnya (bukan melewatinya).
     */
    private fun runD1Sync(context: Context, onProgress: SyncProgress?): SyncResult? {
        fun report(done: Int, total: Int, label: String) {
            onProgress?.let { cb -> mainHandler.post { cb(done, total, label) } }
        }
        if (!isOnline(context)) return null
        val key = ContentGuard.getKey()
        if (key == null) {
            Log.w(TAG, "sync D1 dibatalkan: kunci native tidak tersedia")
            return null
        }
        report(0, 0, "Memeriksa pembaruan…")
        val manifest = CerpenApi.fetchManifest(key) ?: return null
        if (manifest.version <= 0) return null
        val prefs = d1Prefs(context)
        val storedVersion = prefs.getInt(KEY_D1_VERSION, 0)
        val haveFiles = cerpenEncFile(context).exists() && newsEncFile(context).exists() &&
            komikEncFile(context).exists() && manifestEncFile(context).exists()
        // Lewati hanya bila versi sama DAN file lokal lolos verifikasi
        // lawan manifest saat ini (menutup jendela crash / manifest
        // GitHub yang menimpa di tengah jalan).
        if (manifest.version == storedVersion && haveFiles &&
            verifyLocalJsonFiles(context, key)
        ) {
            Log.i(TAG, "sync D1: sudah versi ${manifest.version}, lewati")
            return SyncResult(false, 0, 0, 0)
        }
        report(0, 0, "Mengunduh konten…")
        val dump = CerpenApi.fetchAll(key) ?: return null
        if (dump.optInt("version", 0) != manifest.version) {
            Log.w(TAG, "sync D1: versi dump != manifest")
            return null
        }
        val counts = dump.optJSONObject("counts")
        fun countOk(name: String, arr: JSONArray?): Boolean {
            if (counts == null || arr == null) return false
            return counts.optInt(name, -1) == arr.length()
        }
        if (!countOk("cerpen_series", dump.optJSONArray("series")) ||
            !countOk("cerpen_episodes", dump.optJSONArray("episodes")) ||
            !countOk("cerpen_komik", dump.optJSONArray("komik")) ||
            !countOk("cerpen_komik_episodes", dump.optJSONArray("komik_episodes")) ||
            !countOk("cerpen_news", dump.optJSONArray("news"))
        ) {
            Log.w(TAG, "sync D1: jumlah baris tidak cocok dengan counts manifest")
            return null
        }
        val oldCerpen = loadCerpen(context).associateBy { it.id }
        val oldKomik = loadKomik(context).associateBy { it.id }
        val decrypt: D1Decrypt = { b64 ->
            try {
                val raw = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                ContentCrypto.decrypt(raw, key)?.let { String(it, Charsets.UTF_8) }
            } catch (e: Exception) {
                null
            }
        }
        val built = D1ContentBuilder.buildAll(dump, decrypt) ?: return null
        val newTexts = mapOf(
            "cerpen.json" to built.cerpenJson,
            "news.json" to built.newsJson,
            "komik.json" to built.komikJson
        )
        // Tulis .enc baru ke .tmp dulu (manifest lokal menyusul di akhir).
        // JSON di-gzip dulu seperti pipeline (SHA manifest = SHA byte ter-gzip);
        // manifest sendiri TIDAK di-gzip (parseManifestBytes tidak meng-gunzip).
        val manifestJson = JSONObject()
        val tmpFiles = HashMap<String, File>()
        for ((jsonName, text) in newTexts) {
            val gz = gzip(text.toByteArray(Charsets.UTF_8)) ?: return null
            val enc = ContentCrypto.encrypt(gz, key) ?: return null
            val file = when (jsonName) {
                "cerpen.json" -> "cerpen.json.enc"
                "news.json" -> "news.json.enc"
                else -> "komik.json.enc"
            }
            val tmp = File(dataDir(context), "$file.tmp")
            try {
                tmp.writeBytes(enc)
            } catch (e: Exception) {
                e.printStackTrace()
                return null
            }
            tmpFiles[file] = tmp
            manifestJson.put(jsonName, JSONObject().apply {
                put("sha256", ContentCrypto.sha256Hex(gz))
                put("file", file)
            })
        }
        // Gambar yang belum ada: cover/panel dari URL .enc repo (verifikasi
        // GCM saja — tanpa SHA manifest), berita via on-demand seperti biasa.
        val imagePaths = ArrayList<String>()
        for ((jsonName, text) in newTexts) imagePaths += collectImagePaths(text, jsonName)
        val onDemand = HashMap<String, String>()
        try {
            for (a in ContentLoader.parseNews(built.newsJson)) {
                val paths = newsImagePaths(a)
                if (paths.isNotEmpty() && a.imageUrls.isNotEmpty()) {
                    onDemand[paths[0]] = a.imageUrls[0]
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        val distinct = imagePaths.distinct()
        val alreadyHad = distinct.count { isImageOffline(context, it) }
        var downloaded = 0
        var failed = 0
        val missing = distinct.filter { !isImageOffline(context, it) }
        for ((i, path) in missing.withIndex()) {
            report(i, missing.size, "Mengunduh gambar")
            var ok = false
            val url = built.imageUrls[path]
            if (url != null) {
                ok = syncOneImageFromUrl(context, path, url, key)
                if (ok) {
                    // Entri manifest untuk gambar ini (SHA plaintext hasil dekripsi).
                    try {
                        val plain = ContentCrypto.decrypt(
                            File(dataDir(context), "$path.enc").readBytes(), key
                        )
                        if (plain != null) {
                            manifestJson.put(path, JSONObject().apply {
                                put("sha256", ContentCrypto.sha256Hex(plain))
                                put("file", "$path.enc")
                            })
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            if (!ok) {
                val od = onDemand[path]
                if (od != null && downloadAndEncryptImage(context, path, od, key)) {
                    downloaded++
                } else {
                    failed++
                }
            } else {
                downloaded++
            }
        }
        if (missing.isNotEmpty()) report(missing.size, missing.size, "Gambar selesai")
        // Komit: pindah semua .tmp dulu (gagal satu = batal, tanpa tulis
        // manifest — sync berikutnya mengulang dari keadaan lama yang konsisten).
        for ((file, tmp) in tmpFiles) {
            val dest = File(dataDir(context), file)
            val moved = try {
                tmp.renameTo(dest) || (tmp.copyTo(dest, overwrite = true).let { tmp.delete(); true })
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
            if (!moved || !dest.exists() || dest.length() == 0L) return null
        }
        val manifestEnc = ContentCrypto.encrypt(
            manifestJson.toString().toByteArray(Charsets.UTF_8), key
        ) ?: return null
        try {
            manifestEncFile(context).writeBytes(manifestEnc)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
        invalidateManifestCache()
        prefs.edit()
            .putInt(KEY_D1_VERSION, manifest.version)
            .putBoolean(KEY_D1_MANIFEST_ACTIVE, true)
            .apply()
        val changed = diffAndMarkUpdates(context, oldCerpen, oldKomik, newTexts).let { diffChanged ->
            if ((oldCerpen.isEmpty() && oldKomik.isEmpty()) && newTexts.isNotEmpty()) {
                // Sync pertama (belum ada data lokal): konten baru saja tiba.
                true
            } else {
                diffChanged
            }
        }
        migratePlaintextAway(context)
        return SyncResult(changed, downloaded, alreadyHad, failed)
    }

    /** Verifikasi 3 JSON lokal lawan manifest saat ini (dekripsi + SHA). */
    private fun verifyLocalJsonFiles(context: Context, key: ByteArray): Boolean {
        for (jsonName in listOf("cerpen.json", "news.json", "komik.json")) {
            val entry = manifestEntry(context, jsonName) ?: return false
            val enc = readEncBytes(context, entry.second) ?: return false
            if (ContentCrypto.decryptVerified(enc, key, entry.first) == null) return false
        }
        return true
    }

    private fun gzip(bytes: ByteArray): ByteArray? {
        return try {
            ByteArrayOutputStream().use { bos ->
                GZIPOutputStream(bos).use { it.write(bytes) }
                bos.toByteArray()
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Unduh satu gambar .enc dari URL langsung (bukan dari manifest):
     * dipakai sync D1 — URL-nya dari cover_url / image_url D1.
     * Verifikasi GCM saja (tanpa SHA manifest); true bila baru tersimpan.
     */
    private fun syncOneImageFromUrl(
        context: Context,
        assetPath: String,
        url: String,
        key: ByteArray
    ): Boolean {
        val dest = File(dataDir(context), "$assetPath.enc")
        if (dest.exists() && dest.length() > 0) return false
        val enc = fetchBytes(url) ?: return false
        if (ContentCrypto.decrypt(enc, key) == null) {
            Log.w(TAG, "sync $assetPath: verifikasi GCM gagal, file ditolak")
            return false
        }
        return try {
            dest.parentFile?.mkdirs()
            dest.writeBytes(enc)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /** Satu thread background untuk sync; [onDone] di main thread. */
    private fun syncInternal(
        context: Context,
        onProgress: SyncProgress?,
        onDone: (SyncResult) -> Unit
    ) {
        Thread {
            val result = try {
                runSync(context, onProgress)
            } catch (e: Exception) {
                e.printStackTrace()
                SyncResult(false, 0, 0, 0)
            }
            mainHandler.post { onDone(result) }
        }.start()
    }

    // ============ UNDUH PER KONTEN (v1.1.5) ============
    // Satu item saja (bukan semua): cover cerpen, gambar satu berita,
    // atau cover + panel satu episode komik. Jalur unduhnya SAMA dengan
    // sync global (file .enc dari GitHub raw, verifikasi SHA-256,
    // lewati yang sudah ada) — hanya daftar jalurnya yang per item.
    // Gambar berita yang TIDAK dibundel repo (artikel lama) diambil
    // dari URL aslinya lalu dienkripsi di perangkat (AES-256-GCM,
    // format .enc yang sama) sebelum disimpan. Semua di thread latar;
    // UI hanya menerima progres + hasil.

    /** .enc gambar sudah ada di perangkat (bundel APK sudah disalin ke internal oleh ensureCopied). */
    fun isImageOffline(context: Context, assetPath: String): Boolean {
        val f = File(dataDir(context), "$assetPath.enc")
        return f.exists() && f.length() > 0
    }

    fun cerpenImagePaths(cerpen: Cerpen): List<String> =
        if (cerpen.cover.isNotEmpty()) listOf("covers/${cerpen.cover}") else emptyList()

    fun newsImagePaths(article: NewsArticle): List<String> =
        article.images.mapNotNull { raw ->
            val name = raw.substringAfterLast("/")
            if (name.isNotEmpty()) "news/img/$name" else null
        }

    fun komikImagePaths(komik: Komik): List<String> {
        val out = ArrayList<String>()
        if (komik.cover.isNotEmpty()) out += "komik/covers/${komik.cover}"
        for (e in komik.episodes) {
            for (p in e.panels) {
                if (p.image.isNotEmpty()) out += "komik/panels/${p.image}"
            }
        }
        return out
    }

    fun komikEpisodeImagePaths(komik: Komik, episode: KomikEpisode): List<String> {
        val out = ArrayList<String>()
        if (komik.cover.isNotEmpty()) out += "komik/covers/${komik.cover}"
        for (p in episode.panels) {
            if (p.image.isNotEmpty()) out += "komik/panels/${p.image}"
        }
        return out
    }

    fun isCerpenOffline(context: Context, cerpen: Cerpen): Boolean =
        cerpenImagePaths(cerpen).all { isImageOffline(context, it) }

    fun isNewsOffline(context: Context, article: NewsArticle): Boolean =
        newsImagePaths(article).all { isImageOffline(context, it) }

    fun isKomikOffline(context: Context, komik: Komik): Boolean =
        komikImagePaths(komik).all { isImageOffline(context, it) }

    fun isKomikEpisodeOffline(context: Context, episode: KomikEpisode): Boolean =
        episode.panels.all { p ->
            p.image.isEmpty() || isImageOffline(context, "komik/panels/${p.image}")
        }

    /** (panel sudah ada, total panel) satu komik — untuk badge "X/Y panel". */
    fun komikPanelOfflineCount(context: Context, komik: Komik): Pair<Int, Int> {
        var done = 0
        var total = 0
        for (e in komik.episodes) {
            for (p in e.panels) {
                if (p.image.isEmpty()) continue
                total++
                if (isImageOffline(context, "komik/panels/${p.image}")) done++
            }
        }
        return done to total
    }

    /** (panel sudah ada, total panel) satu episode komik. */
    fun komikEpisodePanelOfflineCount(context: Context, episode: KomikEpisode): Pair<Int, Int> {
        var done = 0
        var total = 0
        for (p in episode.panels) {
            if (p.image.isEmpty()) continue
            total++
            if (isImageOffline(context, "komik/panels/${p.image}")) done++
        }
        return done to total
    }

    fun downloadCerpen(
        context: Context,
        cerpen: Cerpen,
        onProgress: SyncProgress? = null,
        onDone: (SyncResult) -> Unit
    ) = downloadItem(context, cerpenImagePaths(cerpen), emptyMap(), onProgress, onDone)

    fun downloadKomik(
        context: Context,
        komik: Komik,
        onProgress: SyncProgress? = null,
        onDone: (SyncResult) -> Unit
    ) = downloadItem(context, komikImagePaths(komik), emptyMap(), onProgress, onDone)

    fun downloadKomikEpisode(
        context: Context,
        komik: Komik,
        episode: KomikEpisode,
        onProgress: SyncProgress? = null,
        onDone: (SyncResult) -> Unit
    ) = downloadItem(context, komikEpisodeImagePaths(komik, episode), emptyMap(), onProgress, onDone)

    /**
     * Unduh gambar satu berita. `imageUrls[0]` (URL asli gambar
     * pertama) dipakai sebagai fallback bila .enc-nya tidak ada di
     * repo — gambar ke-2/3 tidak punya URL asli di data, jadi hanya
     * bisa dari repo (artikel terbaru yang dibundel).
     */
    fun downloadNewsItem(
        context: Context,
        article: NewsArticle,
        onProgress: SyncProgress? = null,
        onDone: (SyncResult) -> Unit
    ) {
        val paths = newsImagePaths(article)
        val onDemand = HashMap<String, String>()
        if (paths.isNotEmpty() && article.imageUrls.isNotEmpty()) {
            onDemand[paths[0]] = article.imageUrls[0]
        }
        downloadItem(context, paths, onDemand, onProgress, onDone)
    }

    /** Mesin unduh per item: manifest segar dulu, lalu tiap jalur yang belum ada. */
    private fun downloadItem(
        context: Context,
        assetPaths: List<String>,
        onDemandUrls: Map<String, String>,
        onProgress: SyncProgress?,
        onDone: (SyncResult) -> Unit
    ) {
        Thread {
            val result = try {
                runItemDownload(context, assetPaths, onDemandUrls, onProgress)
            } catch (e: Exception) {
                e.printStackTrace()
                SyncResult(false, 0, 0, 0)
            }
            mainHandler.post { onDone(result) }
        }.start()
    }

    private fun runItemDownload(
        context: Context,
        assetPaths: List<String>,
        onDemandUrls: Map<String, String>,
        onProgress: SyncProgress?
    ): SyncResult {
        fun report(done: Int, total: Int, label: String) {
            onProgress?.let { cb -> mainHandler.post { cb(done, total, label) } }
        }
        val missing = assetPaths.filter { !isImageOffline(context, it) }
        val alreadyHad = assetPaths.size - missing.size
        if (missing.isEmpty()) return SyncResult(false, 0, alreadyHad, 0)
        if (!isOnline(context)) return SyncResult(false, 0, alreadyHad, missing.size)
        val key = ContentGuard.getKey()
        if (key == null) {
            Log.w(TAG, "unduh item dibatalkan: kunci native tidak tersedia")
            return SyncResult(false, 0, alreadyHad, missing.size)
        }
        val manifest = fetchFreshManifest(context, key) ?: loadManifest(context)
        var downloaded = 0
        var failed = 0
        for ((i, path) in missing.withIndex()) {
            report(i, missing.size, "Mengunduh gambar")
            var ok = false
            if (manifest != null) ok = syncOneImage(context, manifest, path, key)
            if (!ok) {
                val url = onDemandUrls[path]
                if (url != null) ok = downloadAndEncryptImage(context, path, url, key)
            }
            if (ok) downloaded++ else failed++
        }
        report(missing.size, missing.size, "Selesai")
        return SyncResult(downloaded > 0, downloaded, alreadyHad, failed)
    }

    /**
     * Ambil manifest.json.enc segar dari repo (kecil) supaya SHA
     * verifikasi selalu terbaru; simpan + batalkan cache. Null bila
     * offline / gagal — pemanggil memakai manifest cache.
     */
    private fun fetchFreshManifest(context: Context, key: ByteArray): JSONObject? {
        // Manifest D1 sedang aktif: jangan timpa dengan manifest GitHub —
        // SHA-nya untuk file .enc repo, tidak cocok dengan hasil rakitan D1.
        if (d1Prefs(context).getBoolean(KEY_D1_MANIFEST_ACTIVE, false)) {
            return loadManifest(context)
        }
        val enc = fetchBytes("$GITHUB_RAW_BASE/manifest.json.enc") ?: return null
        val text = ContentCrypto.decrypt(enc, key)?.let { String(it, Charsets.UTF_8) }
            ?: return null
        return try {
            val parsed = JSONObject(text)
            val dest = manifestEncFile(context)
            dest.parentFile?.mkdirs()
            dest.writeBytes(enc)
            invalidateManifestCache()
            parsed
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Unduh gambar dari URL asli, validasi dulu bahwa byte-nya memang
     * gambar yang bisa di-decode (URL kadang menunjuk halaman video,
     * bukan gambar), lalu enkripsi (AES-256-GCM, format .enc sama)
     * dan simpan. Tanpa verifikasi SHA (tidak ada entri manifest) —
     * tag GCM tetap mengautentikasi isi saat dibaca nanti.
     */
    private fun downloadAndEncryptImage(
        context: Context,
        assetPath: String,
        url: String,
        key: ByteArray
    ): Boolean {
        val bytes = fetchBytes(url) ?: return false
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) {
            Log.w(TAG, "unduh $assetPath: URL bukan gambar, ditolak")
            return false
        }
        val enc = ContentCrypto.encrypt(bytes, key) ?: return false
        return try {
            val dest = File(dataDir(context), "$assetPath.enc")
            dest.parentFile?.mkdirs()
            dest.writeBytes(enc)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /** Inti sync: manifest -> 3 JSON -> semua gambar yang belum ada. */
    private fun runSync(context: Context, onProgress: SyncProgress?): SyncResult {
        fun report(done: Int, total: Int, label: String) {
            onProgress?.let { cb -> mainHandler.post { cb(done, total, label) } }
        }
        if (!isOnline(context)) return SyncResult(false, 0, 0, 0)
        val key = ContentGuard.getKey()
        if (key == null) {
            Log.w(TAG, "sync dibatalkan: kunci native tidak tersedia")
            return SyncResult(false, 0, 0, 0)
        }
        // 1. Manifest dulu — tanpa manifest, tidak ada yang diverifikasi.
        // Manifest diunduh TERENKRIPSI (manifest.json.enc) dan disimpan
        // apa adanya; dekripsinya in-memory saat dibaca.
        report(0, 0, "Mengunduh daftar konten…")
        val manifestEnc = fetchBytes("$GITHUB_RAW_BASE/manifest.json.enc")
        if (manifestEnc == null || manifestEnc.isEmpty()) {
            Log.w(TAG, "sync dibatalkan: manifest.json.enc tidak terunduh")
            return SyncResult(false, 0, 0, 0)
        }
        val manifestText = ContentCrypto.decrypt(manifestEnc, key)
            ?.let { String(it, Charsets.UTF_8) }
        val manifest = try {
            if (manifestText.isNullOrBlank()) null else JSONObject(manifestText)
        } catch (e: Exception) {
            null
        }
        if (manifest == null) {
            Log.w(TAG, "sync dibatalkan: manifest.json.enc rusak / gagal dekripsi")
            return SyncResult(false, 0, 0, 0)
        }
        val encDest = manifestEncFile(context)
        encDest.parentFile?.mkdirs()
        encDest.writeBytes(manifestEnc)
        invalidateManifestCache()
        // Manifest GitHub menimpa manifest D1: tandai agar fetchFreshManifest
        // kembali memakai GitHub (SHA file .enc repo cocok lagi).
        d1Prefs(context).edit().putBoolean(KEY_D1_MANIFEST_ACTIVE, false).apply()

        // 2. Tiga JSON — unduh + verifikasi + simpan, lalu kumpulkan
        //    semua jalur gambar yang dirujuk isinya.
        // v1.1.7: potret daftar LAMA sebelum unduh — dipakai membandingkan
        // apa yang benar-benar bertambah (badge "diperbarui" + toast jujur).
        val oldCerpen = loadCerpen(context).associateBy { it.id }
        val oldKomik = loadKomik(context).associateBy { it.id }
        val newTexts = HashMap<String, String>()
        // v1.1.10: URL asli gambar pertama tiap berita — jalur unduh
        // on-demand (pola downloadNewsItem) untuk gambar berita yang
        // tidak punya entri manifest (hanya gambar pertama yang punya
        // URL asli; gambar ke-2/3 hanya dari repo).
        val onDemand = HashMap<String, String>()
        // v1.1.7: diisi diffAndMarkUpdates di akhir (dulu `= false` + true
        // di tiap fetch berhasil — itu yang bikin toast "Konten baru
        // diunduh" muncul walau kontennya sama).
        var changed: Boolean
        var downloaded = 0
        var failed = 0
        val imagePaths = ArrayList<String>()
        val jsonLabels = mapOf(
            "cerpen.json" to "Mengunduh daftar cerbung…",
            "news.json" to "Mengunduh daftar berita…",
            "komik.json" to "Mengunduh daftar komik…"
        )
        for (jsonName in jsonLabels.keys) {
            report(0, 0, jsonLabels[jsonName] ?: "Mengunduh $jsonName…")
            val text = fetchVerifiedJson(context, manifest, jsonName, key)
            if (text == null) {
                failed++
                continue
            }
            newTexts[jsonName] = text
            imagePaths += collectImagePaths(text, jsonName)
            if (jsonName == "news.json") {
                try {
                    for (a in ContentLoader.parseNews(text)) {
                        val paths = newsImagePaths(a)
                        if (paths.isNotEmpty() && a.imageUrls.isNotEmpty()) {
                            onDemand[paths[0]] = a.imageUrls[0]
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // 3. Semua gambar yang belum ada di perangkat — unduh satu per
        //    satu dengan progres determinan (total sudah diketahui).
        // v1.1.10: hanya jalur yang BISA diunduh yang masuk antrean
        // (punya entri manifest ATAU URL on-demand). Jalur tanpa
        // keduanya (kebanyakan gambar berita ke-2/3) dilewati diam-diam
        // — bukan gagal — supaya refresh berikutnya tidak mengulang
        // unduhan yang memang tidak pernah bisa selesai.
        fun isOffline(path: String): Boolean {
            val dest = File(dataDir(context), "$path.enc")
            return dest.exists() && dest.length() > 0
        }
        val distinct = imagePaths.distinct()
        val alreadyHad = distinct.count { isOffline(it) }
        val missing = distinct.filter { path ->
            !isOffline(path) &&
                (manifest.optJSONObject(path) != null || onDemand.containsKey(path))
        }
        for ((i, path) in missing.withIndex()) {
            report(i, missing.size, "Mengunduh gambar")
            if (syncOneImage(context, manifest, path, key)) {
                downloaded++
            } else {
                val url = onDemand[path]
                if (url != null && downloadAndEncryptImage(context, path, url, key)) {
                    downloaded++
                } else {
                    failed++
                }
            }
        }
        if (missing.isNotEmpty()) {
            report(missing.size, missing.size, "Gambar selesai")
        }
        // v1.1.7: bandingkan lama vs baru; yang bertambah dicatat untuk
        // badge "diperbarui". changed hanya true bila ADA yang bertambah
        // (dulu true di tiap sync yang berhasil, walau kontennya sama).
        changed = diffAndMarkUpdates(context, oldCerpen, oldKomik, newTexts)
        if ((oldCerpen.isEmpty() && oldKomik.isEmpty()) && newTexts.isNotEmpty()) {
            // Sync pertama (belum ada data lokal): konten baru saja tiba.
            changed = true
        }
        migratePlaintextAway(context)
        return SyncResult(changed, downloaded, alreadyHad, failed)
    }

    /**
     * v1.1.7: bandingkan daftar lama vs baru; item yang bertambah dicatat
     * ke UpdatedStore (badge "diperbarui"). Kembalikan true bila ada satu
     * pun penambahan. Daftar lama yang KOSONG (sync pertama) tidak menandai
     * apa-apa — konten bundel adalah baseline, bukan pembaruan.
     */
    private fun diffAndMarkUpdates(
        context: Context,
        oldCerpen: Map<String, Cerpen>,
        oldKomik: Map<String, Komik>,
        newTexts: Map<String, String>
    ): Boolean {
        var any = false
        newTexts["cerpen.json"]?.let { text ->
            if (oldCerpen.isNotEmpty()) {
                val parsed = try {
                    ContentLoader.parseCerpen(text)
                } catch (e: Exception) {
                    emptyList()
                }
                for (c in parsed) {
                    val old = oldCerpen[c.id]
                    val kind = when {
                        old == null -> UpdatedStore.Kind.NEW
                        c.seasons.size > old.seasons.size -> UpdatedStore.Kind.SEASON
                        c.totalEpisodes > old.totalEpisodes -> UpdatedStore.Kind.EPISODE
                        else -> null
                    }
                    if (kind != null) {
                        UpdatedStore.markCerpen(context, c.id, kind)
                        any = true
                    }
                }
            }
        }
        newTexts["komik.json"]?.let { text ->
            if (oldKomik.isNotEmpty()) {
                val parsed = try {
                    ContentLoader.parseKomik(text)
                } catch (e: Exception) {
                    emptyList()
                }
                for (k in parsed) {
                    val old = oldKomik[k.id]
                    val kind = when {
                        old == null -> UpdatedStore.Kind.NEW
                        k.totalEpisodes > old.totalEpisodes -> UpdatedStore.Kind.EPISODE
                        else -> null
                    }
                    if (kind != null) {
                        UpdatedStore.markKomik(context, k.id, kind)
                        any = true
                    }
                }
            }
        }
        return any
    }

    /**
     * Unduh + verifikasi satu file .enc JSON; simpan ke internal storage
     * dan kembalikan teks terdekripsinya (hanya di memori). Null bila
     * gagal diunduh atau verifikasi gagal.
     */
    private fun fetchVerifiedJson(
        context: Context,
        manifest: JSONObject,
        jsonName: String,
        key: ByteArray
    ): String? {
        val entry = manifest.optJSONObject(jsonName) ?: return null
        val sha = entry.optString("sha256", "")
        val file = entry.optString("file", "")
        if (sha.isEmpty() || file.isEmpty()) return null
        val enc = fetchBytes("$GITHUB_RAW_BASE/$file") ?: return null
        val text = ContentCrypto.decryptVerified(enc, key, sha)
        if (text == null) {
            Log.w(TAG, "sync $jsonName: verifikasi gagal, file ditolak")
            return null
        }
        return try {
            val encDest = File(dataDir(context), file)
            encDest.parentFile?.mkdirs()
            encDest.writeBytes(enc)
            // Hasil dekripsi HANYA di memori — tidak ditulis plaintext ke disk.
            text
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Kumpulkan jalur aset gambar yang dirujuk satu JSON terdekripsi:
     * cover cerpen ("covers/..."), cover + panel komik
     * ("komik/covers/...", "komik/panels/..."), gambar berita
     * ("news/img/...").
     */
    private fun collectImagePaths(text: String, jsonName: String): List<String> {
        val out = ArrayList<String>()
        try {
            when (jsonName) {
                "cerpen.json" -> {
                    for (c in ContentLoader.parseCerpen(text)) {
                        if (c.cover.isNotEmpty()) out += "covers/${c.cover}"
                    }
                }
                "komik.json" -> {
                    for (k in ContentLoader.parseKomik(text)) {
                        if (k.cover.isNotEmpty()) out += "komik/covers/${k.cover}"
                        for (e in k.episodes) {
                            for (p in e.panels) {
                                if (p.image.isNotEmpty()) out += "komik/panels/${p.image}"
                            }
                        }
                    }
                }
                else -> {
                    for (a in ContentLoader.parseNews(text)) {
                        for (img in a.images) {
                            val name = img.substringAfterLast("/")
                            if (name.isNotEmpty()) out += "news/img/$name"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return out
    }

    /**
     * Unduh + verifikasi satu gambar terenkripsi; true bila ada data baru
     * yang valid. Disimpan sebagai .enc di internal storage (tidak pernah
     * plaintext). Bila .enc sudah ada, dilewati.
     */
    private fun syncOneImage(
        context: Context,
        manifest: JSONObject,
        assetPath: String,
        key: ByteArray
    ): Boolean {
        val dest = File(dataDir(context), "$assetPath.enc")
        if (dest.exists() && dest.length() > 0) return false
        val entry = manifest.optJSONObject(assetPath) ?: return false
        val sha = entry.optString("sha256", "")
        if (sha.isEmpty()) return false
        val enc = fetchBytes("$GITHUB_RAW_BASE/$assetPath.enc") ?: return false
        if (ContentCrypto.decryptVerifiedBytes(enc, key, sha) == null) {
            Log.w(TAG, "sync $assetPath: verifikasi gagal, file ditolak")
            return false
        }
        return try {
            dest.parentFile?.mkdirs()
            dest.writeBytes(enc)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /** Validasi cepat: teks JSON mengandung kunci yang diharapkan. */
    @Suppress("unused")
    fun looksLikeCerpenJson(text: String): Boolean {
        return try {
            JSONObject(text).has("cerpen")
        } catch (e: Exception) {
            false
        }
    }
}
