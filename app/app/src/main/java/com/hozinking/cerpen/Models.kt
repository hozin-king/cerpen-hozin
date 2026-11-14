package com.hozinking.cerpen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache
import android.widget.ImageView
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

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
    val seasons: List<Season>,
    /** Tanggal ditambahkan (ISO "yyyy-MM-dd"); kosong bila data lama belum menyimpannya. */
    val date: String = ""
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

/** Satu panel komik: gambar webtoon (tanpa teks di dalam gambar) + dialog/keterangan terpisah. */
data class KomikPanel(
    val image: String,
    val teks: String
)

data class KomikEpisode(
    val no: Int,
    val judul: String,
    val panels: List<KomikPanel>
)

data class Komik(
    val id: String,
    val judul: String,
    val genre: String,
    val deskripsi: String,
    val cover: String,
    val episodes: List<KomikEpisode>,
    /** Tanggal ditambahkan (ISO "yyyy-MM-dd"); kosong bila data lama belum menyimpannya. */
    val date: String = ""
) {
    val totalEpisodes: Int get() = episodes.size
    val totalPanels: Int get() = episodes.sumOf { it.panels.size }

    fun episode(no: Int): KomikEpisode? = episodes.firstOrNull { it.no == no }
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
    val details: String = "",
    /** URL asli gambar pertama (untuk unduh on-demand bila .enc tidak dibundel repo). */
    val imageUrls: List<String> = emptyList(),
    /** Waktu ditambahkan (ISO "yyyy-MM-dd'T'HH:mm:ss+07:00"); dipakai badge NEW 24 jam. */
    val addedAt: String = "",
    /** v1.1.31: id baris cerpen_news (ref API sosial); "" bila data lama tak membawanya. */
    val id: String = ""
)

/** Ubah nilai `details` mentah (String / JSONObject / JSONArray / null) jadi teks bacaan rapi.
 *  Latar: `JSONObject.optString("details")` pada JSONObject mengembalikan `toString()` mentah
 *  (dengan escape `\/`), sehingga isi artikel tampil sebagai JSON di layar. Formatter ini
 *  murni org.json — tanpa dependensi Android — supaya bisa di-unit-test di JVM. */
object NewsDetailsFormatter {

    private val SECTION_TITLES = mapOf(
        "prancis" to "Prancis",
        "malaysia" to "Malaysia",
        "jepang" to "Jepang",
        "indonesia" to "Indonesia",
        "video_sumber" to "Sumber Video",
        "bssn_advisory" to "Imbauan BSSN",
        "hirudo" to "Malware Hirudo",
        "klarifikasi_bank_indonesia" to "Klarifikasi: Bank Indonesia",
        "tips_aman" to "Tips Aman",
        "sumber_verifikasi" to "Sumber Verifikasi",
        "status_verifikasi" to "Status Verifikasi"
    )

    fun format(value: Any?): String = when (value) {
        null, JSONObject.NULL -> ""
        is String -> value
        is JSONObject -> buildString { formatObject(this, value) }
        is JSONArray -> buildString { formatList(this, value) }
        else -> value.toString()
    }

    /** Tiap key jadi judul seksi di baris sendiri, dipisah baris kosong. */
    private fun formatObject(sb: StringBuilder, obj: JSONObject) {
        var first = true
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val v = obj.opt(key)
            if (v == null || v == JSONObject.NULL) continue
            if (!first) sb.append("\n\n")
            first = false
            sb.append(sectionTitle(key)).append("\n")
            appendValue(sb, v)
        }
    }

    /** String = paragraf; dict bersarang = "label: isi" per baris; list = bullet. */
    private fun appendValue(sb: StringBuilder, value: Any?) {
        when (value) {
            is String -> sb.append(value)
            is JSONObject -> {
                var first = true
                val keys = value.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = value.opt(k)
                    if (v == null || v == JSONObject.NULL) continue
                    if (!first) sb.append("\n")
                    first = false
                    if (v is JSONObject || v is JSONArray) {
                        sb.append(humanize(k)).append(":\n")
                        appendValue(sb, v)
                    } else {
                        sb.append(humanize(k)).append(": ").append(v.toString())
                    }
                }
            }
            is JSONArray -> formatList(sb, value)
            JSONObject.NULL, null -> Unit
            else -> sb.append(value.toString())
        }
    }

    private fun formatList(sb: StringBuilder, arr: JSONArray) {
        var first = true
        for (i in 0 until arr.length()) {
            val v = arr.opt(i)
            if (v == null || v == JSONObject.NULL) continue
            if (!first) sb.append("\n")
            first = false
            sb.append("• ")
            appendValue(sb, v)
        }
    }

    fun sectionTitle(key: String): String = SECTION_TITLES[key] ?: humanize(key)

    private fun humanize(key: String): String =
        key.split("_").filter { it.isNotEmpty() }
            .joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
}

/** Tanggal tampil + badge NEW. Murni java.time/org.json — tanpa
 *  dependensi Android — supaya bisa di-unit-test di JVM.
 *  Aturan NEW (v1.1.5): item ditambahkan dalam 24 jam terakhir,
 *  dihitung dari `addedAt` (ISO dengan offset). Bila `addedAt`
 *  kosong, fallback ke `date` ("yyyy-MM-dd") = NEW bila tanggalnya
 *  hari ini. Bila keduanya kosong -> bukan NEW. */
object ItemDates {

    private val MONTHS_ID = arrayOf(
        "Jan", "Feb", "Mar", "Apr", "Mei", "Jun",
        "Jul", "Agu", "Sep", "Okt", "Nov", "Des"
    )

    /** "2026-11-09" -> "9 Nov 2026"; input tak dikenal dikembalikan apa adanya. */
    fun format(dateIso: String): String {
        val parts = dateIso.trim().split("-")
        if (parts.size != 3) return dateIso
        val year = parts[0].toIntOrNull() ?: return dateIso
        val month = parts[1].toIntOrNull() ?: return dateIso
        val day = parts[2].toIntOrNull() ?: return dateIso
        if (month !in 1..12 || day !in 1..31) return dateIso
        return "$day ${MONTHS_ID[month - 1]} $year"
    }

    /** true bila `addedAt` (atau `date`) masih dalam 24 jam terakhir. */
    fun isNew(addedAt: String, date: String = ""): Boolean =
        isNew(addedAt, date, System.currentTimeMillis())

    /** Overload dengan jam eksplisit (untuk unit test). */
    fun isNew(addedAt: String, date: String, nowMillis: Long): Boolean {
        if (addedAt.isNotBlank()) {
            return try {
                val added = java.time.OffsetDateTime.parse(addedAt)
                    .toInstant().toEpochMilli()
                val age = nowMillis - added
                age in 0..(24L * 60 * 60 * 1000)
            } catch (e: Exception) {
                false
            }
        }
        if (date.isNotBlank()) {
            return try {
                val today = java.time.LocalDate.now()
                    .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
                date.trim() == today
            } catch (e: Exception) {
                false
            }
        }
        return false
    }

    /** Id dengan angka akhir terbesar ("cerpen-30" > "cerpen-9") — item terbaru di daftar. */
    fun newestId(ids: List<String>): String? =
        ids.maxByOrNull { trailingNumber(it) }

    /** Angka di akhir id ("cerpen-30" -> 30); 0 bila tidak ada angka. */
    fun trailingNumber(id: String): Int {
        val digits = id.takeLastWhile { it.isDigit() }
        return digits.toIntOrNull() ?: 0
    }
}

/** Penyebaran gambar di antara blok paragraf teks berita (v1.1.5).
 *  Data berita TIDAK menyimpan posisi gambar, jadi gambar disebar
 *  merata: gambar ke-i disisipkan setelah blok ke
 *  ((i+1) * (jumlahBlok+1)) / (jumlahGambar+1) - 1. Murni Kotlin — unit-testable. */
object NewsBodyLayout {

    /** Indeks blok (0-based) SETELAH blok mana tiap gambar disisipkan. */
    fun insertAfter(blockCount: Int, imageCount: Int): List<Int> {
        if (blockCount <= 0 || imageCount <= 0) return emptyList()
        return (0 until imageCount).map { i ->
            (((i + 1) * (blockCount + 1)) / (imageCount + 1) - 1).coerceIn(0, blockCount - 1)
        }
    }
}

/** Parser cerpen.json + news.json + komik.json (org.json, tanpa dependensi). */
object ContentLoader {

    fun parseKomik(text: String): List<Komik> {
        val out = ArrayList<Komik>()
        try {
            val root = JSONObject(text)
            val arr = root.optJSONArray("komik") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val eps = ArrayList<KomikEpisode>()
                val eArr = o.optJSONArray("episodes") ?: JSONArray()
                for (j in 0 until eArr.length()) {
                    val e = eArr.getJSONObject(j)
                    val panels = ArrayList<KomikPanel>()
                    val pArr = e.optJSONArray("panels") ?: JSONArray()
                    for (k in 0 until pArr.length()) {
                        val p = pArr.getJSONObject(k)
                        panels.add(
                            KomikPanel(
                                image = p.optString("image", ""),
                                teks = p.optString("teks", "")
                            )
                        )
                    }
                    eps.add(
                        KomikEpisode(
                            no = e.optInt("no", j + 1),
                            judul = e.optString("judul", "Episode ${j + 1}"),
                            panels = panels
                        )
                    )
                }
                out.add(
                    Komik(
                        id = o.optString("id", "komik-$i"),
                        judul = o.optString("judul", "Tanpa Judul"),
                        genre = o.optString("genre", ""),
                        deskripsi = o.optString("deskripsi", ""),
                        cover = o.optString("cover", ""),
                        episodes = eps,
                        date = o.optString("date", "")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return out
    }

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
                        seasons = seasons,
                        date = o.optString("date", "")
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
                val imgUrls = ArrayList<String>()
                val urlArr = o.optJSONArray("image_urls")
                if (urlArr != null) {
                    for (j in 0 until urlArr.length()) {
                        val s = urlArr.optString(j, "")
                        if (s.isNotEmpty()) imgUrls.add(s)
                    }
                }
                out.add(
                    NewsArticle(
                        title = o.optString("title", ""),
                        summary = o.optString("summary", ""),
                        source = o.optString("source", ""),
                        date = o.optString("date", ""),
                        image = single,
                        images = imgs,
                        details = NewsDetailsFormatter.format(o.opt("details")),
                        imageUrls = imgUrls,
                        addedAt = o.optString("added_at", ""),
                        id = o.optString("id", "")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return out
    }

}

/**
 * Cache bitmap sampul/berita dengan downsampling agar anti-lag.
 * Sumber: byte hasil dekripsi in-memory (DataManager.decryptImageBytes)
 * — tidak pernah ada file JPG plaintext di disk.
 */
object CoverCache {
    private val cache: LruCache<String, Bitmap> =
        LruCache((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt())

    /** Dekode gambar maksimal 3 thread latar sekaligus — daftar tetap
     *  bisa di-scroll mulus walau banyak gambar antre. UI thread tidak
     *  pernah mendekripsi/mendekode. */
    private val loader = Executors.newFixedThreadPool(3)

    /**
     * Muat gambar ke ImageView secara asinkron (v1.1.5, lazy render):
     * placeholder langsung tampil, dekripsi+dekode di thread latar.
     * Tag view = assetPath; bila holder sudah di-recycle untuk item
     * lain (tag berubah), hasil dibuang — tidak ada gambar nyasar.
     * v1.1.25: GIF (cover/panel animasi) diputar via AnimatedImageDrawable
     * (ImageDecoder, API 28+); di bawah API 28 tampil frame pertama.
     * v1.1.29 progressive: bila full belum ada di lokal, tampilkan
     * THUMBNAIL CMS dulu (kecil, cepat), lalu unduh FULL di latar dan
     * ganti otomatis begitu full siap. Tiap tahap hanya tampil setelah
     * byte lengkap + dekripsi GCM lolos — tidak pernah tampil gambar
     * rusak/setengah diunduh. Bila thumb juga belum ada (404, gambar
     * lama tanpa thumb), perilaku lama berlaku: placeholder sampai
     * full siap. Full yang terunduh disimpan .enc lokal oleh
     * DataManager.fetchCmsImageBytes, jadi tampil berikutnya lokal.
     */
    fun loadInto(context: Context, assetPath: String, reqWidth: Int, view: ImageView, placeholderRes: Int) {
        stopAnimated(view)
        view.setImageResource(placeholderRes)
        view.tag = assetPath
        loader.execute {
            // Jalur lama: lokal dulu (offline, cepat, GIF tetap animasi).
            val d = getDrawable(context, assetPath, reqWidth)
            if (d != null) {
                if (view.tag != assetPath) return@execute
                view.post {
                    if (view.tag == assetPath) {
                        view.setImageDrawable(d)
                        (d as? AnimatedImageDrawable)?.start()
                    }
                }
                return@execute
            }
            try {
                val thumbBytes = DataManager.fetchCmsImageBytes(context, assetPath, thumb = true)
                if (thumbBytes != null && view.tag == assetPath) {
                    val thumbBmp = decodeBytes(thumbBytes, reqWidth)
                    if (thumbBmp != null) {
                        view.post {
                            if (view.tag == assetPath) {
                                stopAnimated(view)
                                view.setImageDrawable(BitmapDrawable(context.resources, thumbBmp))
                            }
                        }
                    }
                }
                if (view.tag != assetPath) return@execute
                val fullBytes = DataManager.fetchCmsImageBytes(context, assetPath, thumb = false)
                if (fullBytes == null) return@execute
                // Full sudah tersimpan .enc lokal -> tampil lewat jalur
                // lokal biasa (cache + GIF animasi).
                val full = getDrawable(context, assetPath, reqWidth)
                if (full != null && view.tag == assetPath) {
                    view.post {
                        if (view.tag == assetPath) {
                            view.setImageDrawable(full)
                            (full as? AnimatedImageDrawable)?.start()
                        }
                    }
                }
            } catch (e: Exception) {
                // Fail-open: placeholder tetap tampil.
            }
        }
    }

    /** Hentikan animasi GIF yang sedang berjalan di view ini (bila ada). */
    private fun stopAnimated(view: ImageView) {
        (view.drawable as? AnimatedImageDrawable)?.stop()
    }

    /** Batalkan muatan tertunda untuk view yang di-recycle. */
    fun clear(view: ImageView, placeholderRes: Int) {
        view.tag = null
        stopAnimated(view)
        view.setImageResource(placeholderRes)
    }

    /**
     * GIF dikenali dari magic "GIF8" (GIF87a/GIF89a). Fungsi murni —
     * tidak menyentuh disk/jaringan.
     */
    fun isGif(bytes: ByteArray): Boolean {
        return bytes.size >= 4 &&
            bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == '8'.code.toByte()
    }

    /**
     * Muat gambar sebagai Drawable: GIF -> AnimatedImageDrawable yang
     * beranimasi (API 28+; di bawah itu jatuh ke Bitmap frame pertama),
     * gambar lain -> BitmapDrawable dari cache Bitmap yang sudah ada.
     * Keputusan GIF diambil dari ekstensi ".gif" (prosedur cover/panel
     * animasi memakai nama .gif) supaya gambar statis tidak kena
     * dekripsi ganda; isi byte tetap diperiksa magic "GIF8" sebelum
     * di-decode sebagai animasi. Drawable GIF TIDAK di-cache (satu
     * drawable tidak boleh dipakai dua view sekaligus) — dekode diulang
     * tiap bind di thread latar.
     * assetPath berbentuk "covers/cover_01.jpg" atau "news/img/xxx.jpg".
     */
    fun getDrawable(context: Context, assetPath: String, reqWidth: Int): Drawable? {
        if (!assetPath.endsWith(".gif", ignoreCase = true)) {
            val bmp = get(context, assetPath, reqWidth) ?: return null
            return BitmapDrawable(context.resources, bmp)
        }
        val bytes = DataManager.decryptImageBytes(context, assetPath) ?: return null
        if (isGif(bytes)) {
            decodeGifDrawable(bytes)?.let { return it }
        }
        // Bukan GIF beneran / gagal decode -> frame pertama statis.
        val bmp = get(context, assetPath, reqWidth) ?: return null
        return BitmapDrawable(context.resources, bmp)
    }

    /** Dekode GIF menjadi AnimatedImageDrawable (API 28+ saja). */
    private fun decodeGifDrawable(bytes: ByteArray): AnimatedImageDrawable? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return try {
            val src = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
            ImageDecoder.decodeDrawable(src) as? AnimatedImageDrawable
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

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

    /**
     * Gambar selalu didekripsi in-memory (DataManager.decryptImageBytes)
     * lalu di-decode dari byte array — tidak pernah ada file JPG
     * plaintext di disk.
     */
    private fun decodeSampled(context: Context, assetPath: String, reqWidth: Int): Bitmap? {
        val bytes = DataManager.decryptImageBytes(context, assetPath) ?: return null
        return decodeBytes(bytes, reqWidth)
    }

    /**
     * Dekode byte gambar (sudah terdekripsi) jadi Bitmap ter-downsample
     * ke [reqWidth]. Dipakai jalur progressive (thumb/full dari CMS)
     * maupun decodeSampled. Null bila byte bukan gambar valid — tidak
     * pernah mengembalikan bitmap setengah/rusak.
     */
    fun decodeBytes(bytes: ByteArray, reqWidth: Int): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            var sample = 1
            val w = opts.outWidth
            while (w / (sample * 2) >= reqWidth && sample < 8) sample *= 2
            val opts2 = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts2)
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

    // ---- Progres baca komik (v1.1.13): satu posisi terakhir per komik ----
    // Kunci: "last_komik_<id>" = "episode|panel" (panel 0-based, sama
    // pola penyimpanan dengan progres cerbung di atas).
    data class LastKomikRead(val komikId: String, val episode: Int, val panel: Int)

    fun saveLastKomik(context: Context, komikId: String, episode: Int, panel: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("last_komik_$komikId", "$episode|$panel")
            .apply()
    }

    fun getLastKomik(context: Context, komikId: String): LastKomikRead? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("last_komik_$komikId", null) ?: return null
        val parts = raw.split("|")
        if (parts.size != 2) return null
        val episode = parts[0].toIntOrNull() ?: return null
        val panel = parts[1].toIntOrNull() ?: return null
        return LastKomikRead(komikId, episode, panel)
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
