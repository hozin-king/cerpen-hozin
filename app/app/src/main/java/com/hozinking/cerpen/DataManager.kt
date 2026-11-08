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
 * PROTEKSI KONTEN (v1.1, lihat CONTENT_PROTECTION.md):
 * - Teks JSON TIDAK PERNAH dibawa sebagai plaintext di assets. Yang di-bundle
 *   hanya `cerpen.json.enc` / `news.json.enc` (AES-256-GCM, IV 12B +
 *   ciphertext+tag) + `manifest.json` plaintext berisi SHA-256 plaintext.
 * - DataManager: baca manifest -> dekripsi .enc (kunci dari native lib
 *   `contentguard` via JNI) -> verifikasi SHA-256 -> baru dipakai.
 * - Bila verifikasi gagal: toast "Konten rusak", file ditolak, dipakai
 *   salinan terakhir yang baik (plaintext cache internal) bila ada.
 * - Fallback MASA TRANSISI: bila .enc belum ada, plaintext cerpen.json /
 *   news.json masih boleh dibaca (dicatat di log) — agar update bertahap
 *   dari v1.0 tidak membuat aplikasi kosong.
 * - Plaintext hasil dekripsi disimpan HANYA di internal storage privat
 *   aplikasi (filesDir/data/<nama>.json), tidak pernah di storage eksternal.
 */
object DataManager {

    private const val TAG = "DataManager"
    private const val GITHUB_RAW_BASE =
        "https://raw.githubusercontent.com/hozin-king/cerpen-hozin/main/data"

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Toast "Konten rusak" cukup sekali per proses agar tidak spam. */
    private var corruptToastShown = false

    private fun dataDir(context: Context): File =
        File(context.filesDir, "data").apply { if (!exists()) mkdirs() }

    private fun coversDir(context: Context): File =
        File(dataDir(context), "covers").apply { if (!exists()) mkdirs() }

    private fun newsImgDir(context: Context): File =
        File(dataDir(context), "news/img").apply { if (!exists()) mkdirs() }

    /** Salinan plaintext terakhir yang terverifikasi baik (cache internal). */
    fun cerpenFile(context: Context): File = File(dataDir(context), "cerpen.json")
    fun newsFile(context: Context): File = File(dataDir(context), "news.json")

    private fun cerpenEncFile(context: Context): File = File(dataDir(context), "cerpen.json.enc")
    private fun newsEncFile(context: Context): File = File(dataDir(context), "news.json.enc")
    private fun manifestFile(context: Context): File = File(dataDir(context), "manifest.json")

    /** Salin aset awal ke internal storage bila belum ada (sekali saja). */
    fun ensureCopied(context: Context) {
        try {
            copyAssetIfMissing(context, "manifest.json", manifestFile(context))
            copyAssetIfMissing(context, "cerpen.json.enc", cerpenEncFile(context))
            copyAssetIfMissing(context, "news.json.enc", newsEncFile(context))
            val covers = context.assets.list("covers") ?: emptyArray()
            for (name in covers) {
                copyAssetIfMissing(context, "covers/$name", File(coversDir(context), name))
            }
            val newsImgs = context.assets.list("news/img") ?: emptyArray()
            for (name in newsImgs) {
                copyAssetIfMissing(context, "news/img/$name", File(newsImgDir(context), name))
            }
        } catch (e: Exception) {
            e.printStackTrace()
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

    /** Entri manifest untuk satu file JSON: Pair(sha256, nama file .enc). */
    private fun manifestEntry(context: Context, jsonName: String): Pair<String, String>? {
        val text = try {
            val f = manifestFile(context)
            if (f.exists() && f.length() > 0) f.readText(Charsets.UTF_8)
            else readAssetText(context, "manifest.json")
        } catch (e: Exception) {
            null
        } ?: return null
        return try {
            val entry = JSONObject(text).optJSONObject(jsonName) ?: return null
            val sha = entry.optString("sha256", "")
            val file = entry.optString("file", "")
            if (sha.isEmpty() || file.isEmpty()) null else sha to file
        } catch (e: Exception) {
            Log.w(TAG, "manifest rusak: ${e.message}")
            null
        }
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
     * Urutan: .enc + manifest + kunci native -> plaintext cache terakhir yang
     * baik -> plaintext masa transisi (aset/internal, dicatat di log).
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
                    if (text != null) {
                        // Simpan sebagai salinan terakhir yang baik (internal privat).
                        try {
                            val cache = if (jsonName == "cerpen.json") cerpenFile(context) else newsFile(context)
                            cache.writeText(text, Charsets.UTF_8)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        return text
                    }
                    Log.w(TAG, "$jsonName: verifikasi .enc gagal (SHA/dekripsi)")
                    toastCorrupt(context)
                }
            } else {
                Log.w(TAG, "$jsonName: file .enc tidak ditemukan ($file)")
            }
        } else {
            Log.w(TAG, "$jsonName: entri manifest tidak ada")
        }
        // Salinan terakhir yang baik (plaintext cache internal).
        try {
            val cache = if (jsonName == "cerpen.json") cerpenFile(context) else newsFile(context)
            if (cache.exists() && cache.length() > 0) {
                Log.i(TAG, "$jsonName: memakai salinan terakhir yang baik")
                return cache.readText(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // Fallback masa transisi: plaintext dari aset (v1.0) — dicatat di log.
        val plain = readAssetText(context, jsonName)
        if (plain != null) {
            Log.w(TAG, "$jsonName: fallback PLAINTEXT dari aset (masa transisi)")
            return plain
        }
        return null
    }

    /** Baca cerpen: .enc terverifikasi, fallback cache/plaintext transisi. */
    fun loadCerpen(context: Context): List<Cerpen> {
        val text = loadVerifiedText(context, "cerpen.json") ?: return emptyList()
        return try {
            ContentLoader.parseCerpen(text)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /** Baca berita: .enc terverifikasi, fallback cache/plaintext transisi. */
    fun loadNews(context: Context): List<NewsArticle> {
        val text = loadVerifiedText(context, "news.json") ?: return emptyList()
        return try {
            ContentLoader.parseNews(text)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /** File gambar sampul di internal storage (null bila belum ada). */
    fun coverLocalFile(context: Context, coverName: String): File? {
        val f = File(coversDir(context), coverName)
        return if (f.exists() && f.length() > 0) f else null
    }

    /** File gambar berita di internal storage (null bila belum ada). */
    fun newsImageLocalFile(context: Context, imageName: String): File? {
        // imageName berbentuk "img/xxx.jpg" atau "xxx.jpg"
        val name = imageName.substringAfterLast("/")
        val f = File(newsImgDir(context), name)
        return if (f.exists() && f.length() > 0) f else null
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

    private fun fetchText(url: String, timeoutMs: Int = 15000): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", "CerpenHozin/1.1")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
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
     * Sync best-effort dari GitHub raw: manifest + file .enc (BUKAN plaintext).
     * Tiap file diverifikasi SHA-256 setelah dekripsi; yang gagal ditolak dan
     * salinan lama dipertahankan. Dijalankan di thread background; callback di
     * main thread dengan true bila ada data baru.
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
                val manifestText = fetchText("$GITHUB_RAW_BASE/manifest.json")
                if (manifestText.isNullOrBlank()) {
                    Log.w(TAG, "sync dibatalkan: manifest.json tidak terunduh")
                    mainHandler.post { onDone(false) }
                    return@Thread
                }
                val manifest = try {
                    JSONObject(manifestText)
                } catch (e: Exception) {
                    Log.w(TAG, "sync dibatalkan: manifest.json rusak")
                    mainHandler.post { onDone(false) }
                    return@Thread
                }
                manifestFile(context).writeText(manifestText, Charsets.UTF_8)

                // 2. cerpen.json.enc
                changed = syncOneEncrypted(context, manifest, "cerpen.json", key) || changed
                // 3. news.json.enc
                changed = syncOneEncrypted(context, manifest, "news.json", key) || changed
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
            val cache = if (jsonName == "cerpen.json") cerpenFile(context) else newsFile(context)
            cache.writeText(text, Charsets.UTF_8)
            if (jsonName == "cerpen.json") {
                val parsed = ContentLoader.parseCerpen(text)
                for (c in parsed) {
                    if (c.cover.isNotEmpty() && coverLocalFile(context, c.cover) == null) {
                        val bytes = fetchBytes("$GITHUB_RAW_BASE/covers/${c.cover}")
                        if (bytes != null && bytes.isNotEmpty()) {
                            val dest = File(coversDir(context), c.cover)
                            dest.parentFile?.mkdirs()
                            dest.writeBytes(bytes)
                        }
                    }
                }
            } else {
                val parsed = ContentLoader.parseNews(text)
                for (a in parsed) {
                    for (img in a.images) {
                        val name = img.substringAfterLast("/")
                        if (newsImageLocalFile(context, name) == null) {
                            val bytes = fetchBytes("$GITHUB_RAW_BASE/news/img/$name")
                            if (bytes != null && bytes.isNotEmpty()) {
                                val dest = File(newsImgDir(context), name)
                                dest.parentFile?.mkdirs()
                                dest.writeBytes(bytes)
                            }
                        }
                    }
                }
            }
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
