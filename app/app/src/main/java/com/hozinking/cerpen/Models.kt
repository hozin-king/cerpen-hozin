package com.hozinking.cerpen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

data class Episode(
    val no: Int,
    val judul: String,
    val isi: String
)

data class Season(
    val season: Int,
    val episodes: List<Episode>
) {
    fun episode(no: Int): Episode? = episodes.firstOrNull { it.no == no }
}

data class Cerpen(
    val id: String,
    val judul: String,
    val genre: String,
    val deskripsi: String,
    val cover: String,
    val seasons: List<Season>
) {
    val totalEpisodes: Int get() = seasons.sumOf { it.episodes.size }

    fun episode(season: Int, no: Int): Episode? =
        seasons.firstOrNull { it.season == season }?.episode(no)

    /** Episode berikutnya setelah (season, no), atau null bila sudah habis. */
    fun nextEpisode(season: Int, no: Int): Pair<Int, Episode>? {
        val s = seasons.firstOrNull { it.season == season } ?: return null
        val idx = s.episodes.indexOfFirst { it.no == no }
        if (idx >= 0 && idx + 1 < s.episodes.size) {
            return season to s.episodes[idx + 1]
        }
        val nextSeason = seasons.firstOrNull { it.season == season + 1 } ?: return null
        val first = nextSeason.episodes.firstOrNull() ?: return null
        return (season + 1) to first
    }
}

data class NewsArticle(
    val title: String,
    val summary: String,
    val source: String,
    val date: String,
    val image: String?,
    /** Semua gambar artikel ("images", maks 3 dari scraper); fallback ke `image`. */
    val images: List<String> = emptyList(),
    /** Isi lengkap artikel; kosong bila data lama (pakai `summary`). */
    val details: String = ""
)

/** Parser cerpen.json + news.json (org.json, tanpa dependensi). */
object ContentLoader {

    fun parseCerpen(text: String): List<Cerpen> {
        val out = ArrayList<Cerpen>()
        try {
            val root = JSONObject(text)
            val arr = root.optJSONArray("cerpen") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val seasons = ArrayList<Season>()
                val sArr = o.optJSONArray("seasons") ?: JSONArray()
                for (j in 0 until sArr.length()) {
                    val s = sArr.getJSONObject(j)
                    val eps = ArrayList<Episode>()
                    val eArr = s.optJSONArray("episodes") ?: JSONArray()
                    for (k in 0 until eArr.length()) {
                        val e = eArr.getJSONObject(k)
                        eps.add(
                            Episode(
                                no = e.optInt("no", k + 1),
                                judul = e.optString("judul", "Episode ${k + 1}"),
                                isi = e.optString("isi", "")
                            )
                        )
                    }
                    seasons.add(Season(season = s.optInt("season", j + 1), episodes = eps))
                }
                out.add(
                    Cerpen(
                        id = o.optString("id", "cerpen-$i"),
                        judul = o.optString("judul", "Tanpa Judul"),
                        genre = o.optString("genre", ""),
                        deskripsi = o.optString("deskripsi", ""),
                        cover = o.optString("cover", ""),
                        seasons = seasons
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return out
    }

    fun parseNews(text: String): List<NewsArticle> {
        val out = ArrayList<NewsArticle>()
        try {
            val root = JSONObject(text)
            val arr = root.optJSONArray("articles") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val single = if (o.isNull("image")) null else o.optString("image")
                val imgs = ArrayList<String>()
                val imgArr = o.optJSONArray("images")
                if (imgArr != null) {
                    for (j in 0 until imgArr.length()) {
                        val s = imgArr.optString(j, "")
                        if (s.isNotEmpty()) imgs.add(s)
                    }
                }
                // Fallback data lama: hanya 1 gambar di "image".
                if (imgs.isEmpty() && !single.isNullOrEmpty()) imgs.add(single)
                out.add(
                    NewsArticle(
                        title = o.optString("title", ""),
                        summary = o.optString("summary", ""),
                        source = o.optString("source", ""),
                        date = o.optString("date", ""),
                        image = single,
                        images = imgs,
                        details = o.optString("details", "")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return out
    }

    fun loadCerpenFromAssets(context: Context): List<Cerpen> {
        val text = readAsset(context, "cerpen.json") ?: return emptyList()
        return parseCerpen(text)
    }

    fun loadNewsFromAssets(context: Context): List<NewsArticle> {
        val text = readAsset(context, "news.json") ?: return emptyList()
        return parseNews(text)
    }

    private fun readAsset(context: Context, name: String): String? {
        return try {
            context.assets.open(name).use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

/**
 * Cache bitmap sampul/berita dengan downsampling agar anti-lag.
 * Sumber: penyimpanan internal dulu (hasil sync / salinan aset), fallback ke aset.
 */
object CoverCache {
    private val cache: LruCache<String, Bitmap> =
        LruCache((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt())

    /**
     * assetPath berbentuk "covers/cover_01.jpg" atau "news/img/xxx.jpg".
     */
    fun get(context: Context, assetPath: String, reqWidth: Int): Bitmap? {
        val key = "$assetPath@$reqWidth"
        cache.get(key)?.let { return it }
        val bmp = decodeSampled(context, assetPath, reqWidth) ?: return null
        cache.put(key, bmp)
        return bmp
    }

    private fun decodeSampled(context: Context, assetPath: String, reqWidth: Int): Bitmap? {
        // 1. Coba file lokal (internal storage) dulu.
        val local: File? = when {
            assetPath.startsWith("covers/") ->
                DataManager.coverLocalFile(context, assetPath.removePrefix("covers/"))
            assetPath.startsWith("news/") ->
                DataManager.newsImageLocalFile(context, assetPath.removePrefix("news/"))
            else -> null
        }
        if (local != null) {
            decodeFile(local, reqWidth)?.let { return it }
        }
        // 2. Fallback ke aset.
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open(assetPath).use { BitmapFactory.decodeStream(it, null, opts) }
            var sample = 1
            val w = opts.outWidth
            while (w / (sample * 2) >= reqWidth && sample < 8) sample *= 2
            val opts2 = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            context.assets.open(assetPath).use { BitmapFactory.decodeStream(it, null, opts2) }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun decodeFile(file: File, reqWidth: Int): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            var sample = 1
            val w = opts.outWidth
            while (w / (sample * 2) >= reqWidth && sample < 8) sample *= 2
            val opts2 = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeFile(file.absolutePath, opts2)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

/** Progres baca di SharedPreferences. */
object ProgressStore {
    private const val PREFS = "cerpen_progress"
    private const val KEY_LAST_CERPEN = "last_cerpen"
    private const val KEY_LAST_SEASON = "last_season"
    private const val KEY_LAST_EPISODE = "last_episode"
    private const val KEY_LAST_PERCENT = "last_percent"
    private const val KEY_TEXT_SIZE = "text_size_sp"
    private const val KEY_THEME = "reader_theme"
    private const val KEY_BOOKMARKS = "bookmarks"
    private const val KEY_HISTORY = "history"

    /** Tema baca: 0 = Siang (kertas), 1 = Malam, 2 = Sepia. */
    const val THEME_DAY = 0
    const val THEME_NIGHT = 1
    const val THEME_SEPIA = 2

    data class LastRead(val cerpenId: String, val season: Int, val episode: Int, val percent: Int)
    data class Bookmark(val cerpenId: String, val season: Int, val episode: Int)
    data class HistoryEntry(val cerpenId: String, val season: Int, val episode: Int, val time: Long)

    fun saveLast(context: Context, cerpenId: String, season: Int, episode: Int, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST_CERPEN, cerpenId)
            .putInt(KEY_LAST_SEASON, season)
            .putInt(KEY_LAST_EPISODE, episode)
            .putInt(KEY_LAST_PERCENT, percent)
            .apply()
    }

    fun getLast(context: Context): LastRead? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = p.getString(KEY_LAST_CERPEN, null) ?: return null
        return LastRead(
            id,
            p.getInt(KEY_LAST_SEASON, 1),
            p.getInt(KEY_LAST_EPISODE, 1),
            p.getInt(KEY_LAST_PERCENT, 0)
        )
    }

    fun markDone(context: Context, cerpenId: String, season: Int, episode: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("done_${cerpenId}_${season}_$episode", true)
            .apply()
    }

    fun isDone(context: Context, cerpenId: String, season: Int, episode: Int): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("done_${cerpenId}_${season}_$episode", false)

    fun getTextSizeSp(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY_TEXT_SIZE, 17f)

    fun setTextSizeSp(context: Context, sp: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_TEXT_SIZE, sp).apply()
    }

    fun getTheme(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_THEME, THEME_DAY)

    fun setTheme(context: Context, theme: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_THEME, theme).apply()
    }

    // ---- Bookmark per episode ----
    private fun bookmarkKey(cerpenId: String, season: Int, episode: Int) =
        "$cerpenId|$season|$episode"

    fun isBookmarked(context: Context, cerpenId: String, season: Int, episode: Int): Boolean {
        val set = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_BOOKMARKS, emptySet()) ?: emptySet()
        return set.contains(bookmarkKey(cerpenId, season, episode))
    }

    /** Toggle bookmark; mengembalikan status baru (true = ditandai). */
    fun toggleBookmark(context: Context, cerpenId: String, season: Int, episode: Int): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = HashSet(prefs.getStringSet(KEY_BOOKMARKS, emptySet()) ?: emptySet())
        val key = bookmarkKey(cerpenId, season, episode)
        val now = if (set.contains(key)) {
            set.remove(key); false
        } else {
            set.add(key); true
        }
        prefs.edit().putStringSet(KEY_BOOKMARKS, set).apply()
        return now
    }

    fun getBookmarks(context: Context): List<Bookmark> {
        val set = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_BOOKMARKS, emptySet()) ?: emptySet()
        return set.mapNotNull { raw ->
            val parts = raw.split("|")
            if (parts.size == 3) {
                Bookmark(parts[0], parts[1].toIntOrNull() ?: 1, parts[2].toIntOrNull() ?: 1)
            } else null
        }
    }

    // ---- Riwayat baca (maks 10, terbaru dulu) ----
    fun addHistory(context: Context, cerpenId: String, season: Int, episode: Int) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_HISTORY, "") ?: ""
        val entries = raw.split("\n").filter { it.isNotBlank() }.toMutableList()
        val key = "$cerpenId|$season|$episode"
        entries.removeAll { it.startsWith("$key|") }
        entries.add(0, "$key|${System.currentTimeMillis()}")
        val trimmed = entries.take(10)
        prefs.edit().putString(KEY_HISTORY, trimmed.joinToString("\n")).apply()
    }

    fun getHistory(context: Context): List<HistoryEntry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HISTORY, "") ?: ""
        return raw.split("\n").filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size == 4) {
                HistoryEntry(
                    parts[0],
                    parts[1].toIntOrNull() ?: 1,
                    parts[2].toIntOrNull() ?: 1,
                    parts[3].toLongOrNull() ?: 0L
                )
            } else null
        }
    }
}
