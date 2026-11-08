package com.hozinking.cerpen

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Semua data (cerpen, berita, gambar) disimpan LOKAL di penyimpanan internal
 * sehingga aplikasi full offline. Data awal di-bundle sebagai aset, lalu
 * disalin ke internal storage saat pertama jalan. Sync dari GitHub raw hanya
 * terjadi bila ada internet (best-effort, diam-diam gagal bila offline).
 */
object DataManager {

    private const val GITHUB_RAW_BASE =
        "https://raw.githubusercontent.com/hozin-king/cerpen-hozin/main/data"

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun dataDir(context: Context): File =
        File(context.filesDir, "data").apply { if (!exists()) mkdirs() }

    private fun coversDir(context: Context): File =
        File(dataDir(context), "covers").apply { if (!exists()) mkdirs() }

    private fun newsImgDir(context: Context): File =
        File(dataDir(context), "news/img").apply { if (!exists()) mkdirs() }

    fun cerpenFile(context: Context): File = File(dataDir(context), "cerpen.json")
    fun newsFile(context: Context): File = File(dataDir(context), "news.json")

    /** Salin aset awal ke internal storage bila belum ada (sekali saja). */
    fun ensureCopied(context: Context) {
        try {
            copyAssetIfMissing(context, "cerpen.json", cerpenFile(context))
            copyAssetIfMissing(context, "news.json", newsFile(context))
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
            // Aset tidak ada (mis. berita tanpa gambar) — bukan error fatal.
            e.printStackTrace()
        }
    }

    /** Baca cerpen: utamakan internal storage, fallback ke aset. */
    fun loadCerpen(context: Context): List<Cerpen> {
        ensureCopied(context)
        val f = cerpenFile(context)
        if (f.exists() && f.length() > 0) {
            try {
                return ContentLoader.parseCerpen(f.readText(Charsets.UTF_8))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return ContentLoader.loadCerpenFromAssets(context)
    }

    /** Baca berita: utamakan internal storage, fallback ke aset. */
    fun loadNews(context: Context): List<NewsArticle> {
        ensureCopied(context)
        val f = newsFile(context)
        if (f.exists() && f.length() > 0) {
            try {
                return ContentLoader.parseNews(f.readText(Charsets.UTF_8))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return ContentLoader.loadNewsFromAssets(context)
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
                setRequestProperty("User-Agent", "CerpenHozin/1.0")
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
                setRequestProperty("User-Agent", "CerpenHozin/1.0")
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
     * Sync best-effort dari GitHub raw. Dijalankan di thread background.
     * Callback dipanggil di main thread dengan true bila ada data baru.
     */
    fun syncFromGithub(context: Context, onDone: (Boolean) -> Unit) {
        Thread {
            var changed = false
            try {
                if (!isOnline(context)) {
                    mainHandler.post { onDone(false) }
                    return@Thread
                }
                // 1. cerpen.json
                val cerpenText = fetchText("$GITHUB_RAW_BASE/cerpen.json")
                if (!cerpenText.isNullOrBlank()) {
                    try {
                        val parsed = ContentLoader.parseCerpen(cerpenText)
                        if (parsed.isNotEmpty()) {
                            cerpenFile(context).writeText(cerpenText, Charsets.UTF_8)
                            changed = true
                            // Unduh cover yang belum ada.
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
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                // 2. news.json
                val newsText = fetchText("$GITHUB_RAW_BASE/news.json")
                if (!newsText.isNullOrBlank()) {
                    try {
                        val parsed = ContentLoader.parseNews(newsText)
                        if (parsed.isNotEmpty()) {
                            newsFile(context).writeText(newsText, Charsets.UTF_8)
                            changed = true
                            for (a in parsed) {
                                val img = a.image ?: continue
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
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            mainHandler.post { onDone(changed) }
        }.start()
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
