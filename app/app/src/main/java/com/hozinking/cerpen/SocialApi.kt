package com.hozinking.cerpen

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Klien API sosial CMS (v1.1.31): view + like/dislike + report.
 *
 * Pola jaringan mengikuti CoinApi: HttpURLConnection +
 * Hosts.withFallback (primary hozinking.my.id, fallback workers.dev),
 * sesi lewat header Cookie: cms_user=<session>, device_id di body/query.
 * Semua pemanggilan network HARUS dari thread latar (bukan UI thread).
 *
 * Kontrak server (cerpen-social):
 * - POST /api/cerpen-social/view   {kind, ref, device_id} -> {ok, views, likes, dislikes, my_vote}
 * - POST /api/cerpen-social/vote   {kind, ref, vote, device_id} -> {ok, likes, dislikes, my_vote}
 *   vote: 1 = like, -1 = dislike (wajib login, tanpa sesi -> 401 {error:"login_required"}), 0 = batal
 * - POST /api/cerpen-social/report {kind, ref, category, note?, device_id} -> 429 {error:"already_reported"}
 * - GET  /api/cerpen-social/stats?kind=&ref=&device_id= -> {ok, views, likes, dislikes, my_vote}
 * - POST /api/cerpen-social/stats-batch {device_id, items:[{kind, ref}]} (maks 50) -> {ok, stats: {"kind|ref": {...}}}
 * - kind: cerpen | komik | berita; ref cerpen "cerpen-01:1:2" (series:season:no),
 *   komik "komik-10:2" (komik:no), berita = id cerpen_news (fallback judul bila id tak ada).
 */
object SocialApi {

    data class Resp(val status: Int, val json: JSONObject?) {
        val ok: Boolean get() = status in 200..299 && json?.optBoolean("ok", true) == true
        val isLoginRequired: Boolean get() =
            status == 401 || json?.optString("error", "") == "login_required"
        val error: String get() = json?.optString("error", "") ?: ""
    }

    data class Stats(
        val views: Long,
        val likes: Long,
        val dislikes: Long,
        /** Vote perangkat/akun ini: 1 = like, -1 = dislike, 0 = belum vote. */
        val myVote: Int
    )

    /** Kunci peta stats-batch: "kind|ref". */
    fun key(kind: String, ref: String): String = "$kind|$ref"

    fun cerpenRef(id: String, season: Int, no: Int): String = "$id:$season:$no"
    fun komikRef(id: String, no: Int): String = "$id:$no"

    /** Berita: id D1 bila ada (lihat NewsArticle.id), judul sebagai fallback data lama. */
    fun newsRef(a: NewsArticle): String = a.id.ifBlank { a.title }

    fun parseStats(json: JSONObject?): Stats? {
        if (json == null) return null
        return Stats(
            views = json.optLong("views", 0),
            likes = json.optLong("likes", 0),
            dislikes = json.optLong("dislikes", 0),
            myVote = json.optInt("my_vote", 0)
        )
    }

    /** Angka ringkas ala Indonesia: 1234 -> "1,2 rb", 2.500.000 -> "2,5 jt". */
    fun formatCount(n: Long): String = when {
        n >= 1_000_000 -> trim1(n / 1_000_000.0) + " jt"
        n >= 1_000 -> trim1(n / 1_000.0) + " rb"
        else -> n.toString()
    }

    private fun trim1(v: Double): String {
        val s = String.format(java.util.Locale.US, "%.1f", v)
        return (if (s.endsWith(".0")) s.dropLast(2) else s).replace('.', ',')
    }

    // ---------- jaringan (disalin polanya dari CoinApi) ----------

    private fun doRequest(
        ctx: Context,
        method: String,
        urlStr: String,
        body: JSONObject?
    ): Resp {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "CerpenHozin/" + BuildConfig.VERSION_NAME)
            conn.setRequestProperty("Accept", "application/json")
            CoinStore.session(ctx)?.let { s ->
                if (s.isNotBlank()) conn.setRequestProperty("Cookie", "cms_user=$s")
            }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val json = try {
                if (text.isBlank()) null else JSONObject(text)
            } catch (e: Exception) {
                null
            }
            Resp(status, json)
        } catch (e: Exception) {
            // IOException -> withFallback yang melempar; kesalahan lain -> Resp 0.
            if (e is java.io.IOException) throw e
            Resp(0, null)
        } finally {
            try { conn?.disconnect() } catch (e: Exception) { /* abaikan */ }
        }
    }

    fun request(ctx: Context, method: String, path: String, body: JSONObject? = null): Resp {
        return try {
            Hosts.withFallback(Hosts.PRIMARY + path) { url ->
                doRequest(ctx, method, url, body)
            }
        } catch (e: Exception) {
            Resp(0, null)
        }
    }

    private fun deviceBody(ctx: Context): JSONObject =
        JSONObject().apply { put("device_id", CoinStore.deviceId(ctx)) }

    // ---------- endpoint ----------

    /** Catat satu view (unik per perangkat per hari — urusan server). Null bila gagal/offline. */
    fun recordView(ctx: Context, kind: String, ref: String): Stats? {
        val body = deviceBody(ctx).apply {
            put("kind", kind)
            put("ref", ref)
        }
        val r = request(ctx, "POST", "/api/cerpen-social/view", body)
        return if (r.status in 200..299) parseStats(r.json) else null
    }

    /** Vote: 1 = like, -1 = dislike, 0 = batal. Cek r.isLoginRequired untuk dislike tanpa sesi. */
    fun vote(ctx: Context, kind: String, ref: String, vote: Int): Resp {
        val body = deviceBody(ctx).apply {
            put("kind", kind)
            put("ref", ref)
            put("vote", vote)
        }
        return request(ctx, "POST", "/api/cerpen-social/vote", body)
    }

    /** Lapor konten (guest boleh). 429 / error "already_reported" bila sudah lapor hari ini. */
    fun sendReport(ctx: Context, kind: String, ref: String, category: String, note: String): Resp {
        val body = deviceBody(ctx).apply {
            put("kind", kind)
            put("ref", ref)
            put("category", category)
            if (note.isNotBlank()) put("note", note)
        }
        return request(ctx, "POST", "/api/cerpen-social/report", body)
    }

    /** Satu stats tanpa menambah view. Null bila gagal/offline. */
    fun fetchStats(ctx: Context, kind: String, ref: String): Stats? {
        val k = URLEncoder.encode(kind, "UTF-8")
        val rf = URLEncoder.encode(ref, "UTF-8")
        val dev = URLEncoder.encode(CoinStore.deviceId(ctx), "UTF-8")
        val r = request(ctx, "GET", "/api/cerpen-social/stats?kind=$k&ref=$rf&device_id=$dev")
        return if (r.status in 200..299) parseStats(r.json) else null
    }

    /**
     * Stats banyak item sekaligus (satu panggilan per daftar; di-chunk 50
     * sesuai batas server). Item tanpa stats di server TIDAK ada di peta —
     * pemanggil menyembunyikan angkanya, bukan menampilkan 0 palsu.
     */
    fun fetchStatsBatch(ctx: Context, items: List<Pair<String, String>>): Map<String, Stats> {
        val out = HashMap<String, Stats>()
        for (chunk in items.chunked(50)) {
            val arr = JSONArray()
            for ((kind, ref) in chunk) {
                arr.put(JSONObject().apply { put("kind", kind); put("ref", ref) })
            }
            val body = deviceBody(ctx).apply { put("items", arr) }
            val r = request(ctx, "POST", "/api/cerpen-social/stats-batch", body)
            val stats = r.json?.optJSONObject("stats") ?: continue
            val keys = stats.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                parseStats(stats.optJSONObject(k))?.let { out[k] = it }
            }
        }
        return out
    }
}
