package com.hozinking.cerpen

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

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

    private fun fetchBytes(url: String): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 20000
                setRequestProperty("User-Agent", "CerpenHozin/1.1")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            conn.inputStream.readBytes()
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Sync best-effort dari GitHub raw: manifest.json.enc + file .enc
     * (BUKAN plaintext). Tiap file diverifikasi SHA-256 setelah dekripsi;
     * yang gagal ditolak dan salinan lama dipertahankan. Dijalankan di
     * thread background; callback di main thread dengan true bila ada
     * data baru.
     */
    fun syncFromGithub(context: Context, onDone: (Boolean) -> Unit) {
        Thread {
            var changed = false
            try {
                if (!isOnline(context)) {
                    mainHandler.post { onDone(false) }
                    return@Thread
                }
                val key = ContentGuard.getKey()
                if (key == null) {
                    Log.w(TAG, "sync dibatalkan: kunci native tidak tersedia")
                    mainHandler.post { onDone(false) }
                    return@Thread
                }
                // 1. Manifest dulu — tanpa manifest, tidak ada yang diverifikasi.
                // Manifest diunduh TERENKRIPSI (manifest.json.enc) dan disimpan
                // apa adanya; dekripsinya in-memory saat dibaca.
                val manifestEnc = fetchBytes("$GITHUB_RAW_BASE/manifest.json.enc")
                if (manifestEnc == null || manifestEnc.isEmpty()) {
                    Log.w(TAG, "sync dibatalkan: manifest.json.enc tidak terunduh")
                    mainHandler.post { onDone(false) }
                    return@Thread
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
                    mainHandler.post { onDone(false) }
                    return@Thread
                }
                val encDest = manifestEncFile(context)
                encDest.parentFile?.mkdirs()
                encDest.writeBytes(manifestEnc)
                invalidateManifestCache()

                // 2. cerpen.json.enc
                changed = syncOneEncrypted(context, manifest, "cerpen.json", key) || changed
                // 3. news.json.enc
                changed = syncOneEncrypted(context, manifest, "news.json", key) || changed
                // 4. komik.json.enc (+ cover & panel komik, di dalam syncOneEncrypted)
                changed = syncOneEncrypted(context, manifest, "komik.json", key) || changed
            } catch (e: Exception) {
                e.printStackTrace()
            }
            mainHandler.post { onDone(changed) }
        }.start()
    }

    /** Unduh + verifikasi satu file .enc; true bila ada data baru yang valid. */
    private fun syncOneEncrypted(
        context: Context,
        manifest: JSONObject,
        jsonName: String,
        key: ByteArray
    ): Boolean {
        val entry = manifest.optJSONObject(jsonName) ?: return false
        val sha = entry.optString("sha256", "")
        val file = entry.optString("file", "")
        if (sha.isEmpty() || file.isEmpty()) return false
        val enc = fetchBytes("$GITHUB_RAW_BASE/$file") ?: return false
        val text = ContentCrypto.decryptVerified(enc, key, sha)
        if (text == null) {
            Log.w(TAG, "sync $jsonName: verifikasi gagal, file ditolak")
            return false
        }
        return try {
            val encDest = File(dataDir(context), file)
            encDest.parentFile?.mkdirs()
            encDest.writeBytes(enc)
            // Hasil dekripsi HANYA di memori — tidak ditulis plaintext ke disk.
            if (jsonName == "cerpen.json") {
                val parsed = ContentLoader.parseCerpen(text)
                for (c in parsed) {
                    if (c.cover.isNotEmpty()) {
                        syncOneImage(context, manifest, "covers/${c.cover}", key)
                    }
                }
            } else if (jsonName == "komik.json") {
                val parsed = ContentLoader.parseKomik(text)
                for (k in parsed) {
                    if (k.cover.isNotEmpty()) {
                        syncOneImage(context, manifest, "komik/covers/${k.cover}", key)
                    }
                    for (e in k.episodes) {
                        for (p in e.panels) {
                            if (p.image.isNotEmpty()) {
                                syncOneImage(context, manifest, "komik/panels/${p.image}", key)
                            }
                        }
                    }
                }
            } else {
                val parsed = ContentLoader.parseNews(text)
                for (a in parsed) {
                    for (img in a.images) {
                        val name = img.substringAfterLast("/")
                        if (name.isNotEmpty()) {
                            syncOneImage(context, manifest, "news/img/$name", key)
                        }
                    }
                }
            }
            migratePlaintextAway(context)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
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
