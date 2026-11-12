package com.hozinking.cerpen

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Tautan berbagi + deep link (v1.1.20).
 *
 * Alur: tombol Bagikan di layar detail -> POST /api/share/create ->
 * URL `https://hozinking.my.id/s/<token>` dibuka lewat share sheet
 * Android. Penerima yang mengeklik tautan (dan sudah memasang
 * aplikasi) masuk lewat intent-filter MainActivity -> GET
 * /api/share/resolve?token=... -> buka layar yang tepat.
 *
 * Token terenkripsi di server — aplikasi TIDAK PERNAH mendekripsinya,
 * cukup meneruskan token apa adanya ke endpoint resolve. Fail-open:
 * resolve/create gagal (404 / tanpa internet) -> null, pemanggil
 * tetap di daftar konten biasa tanpa crash dan tanpa dialog error.
 * Fungsi parse/build murni org.json supaya bisa di-unit-test di JVM.
 */
object ShareLink {
    private const val TAG = "ShareLink"
    private const val API_BASE = "https://hozinking.my.id/api/share"
    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 15000

    data class ResolvedShare(
        val contentType: String,
        val contentId: String,
        val season: Int?,
        val episodeNo: Int?
    )

    /** "/s/<token>" -> token; null bila path bukan tautan berbagi. */
    fun extractTokenFromPath(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val parts = path.trim().split("/").filter { it.isNotEmpty() }
        if (parts.size != 2 || parts[0] != "s") return null
        return parts[1].takeIf { it.isNotBlank() }
    }

    /** Body POST /api/share/create. season/episode_no hanya bila ada. */
    fun buildCreateBody(
        contentType: String,
        contentId: String,
        season: Int?,
        episodeNo: Int?,
        device: String
    ): String {
        val o = JSONObject()
        o.put("content_type", contentType)
        o.put("content_id", contentId)
        if (season != null) o.put("season", season)
        if (episodeNo != null) o.put("episode_no", episodeNo)
        o.put("device", device)
        return o.toString()
    }

    /** Respons create: {"url": "https://hozinking.my.id/s/<token>"}. */
    fun parseCreateResponse(body: String): String? {
        return try {
            JSONObject(body).optString("url", "").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            // Murni (unit-testable di JVM): tanpa Log Android — null saja.
            null
        }
    }

    /** Respons resolve: {content_type, content_id, season?, episode_no?}. */
    fun parseResolve(body: String): ResolvedShare? {
        return try {
            val o = JSONObject(body)
            val type = o.optString("content_type", "")
            val id = o.optString("content_id", "")
            if (type.isBlank() || id.isBlank()) return null
            val season = o.optInt("season", -1).takeIf { it >= 0 }
            val episode = o.optInt("episode_no", -1).takeIf { it >= 0 }
            ResolvedShare(type, id, season, episode)
        } catch (e: Exception) {
            // Murni (unit-testable di JVM): tanpa Log Android — null saja.
            null
        }
    }

    /** Label perangkat singkat, tanpa data pribadi (model saja). */
    fun deviceLabel(): String =
        Build.MODEL?.trim()?.take(40)?.takeIf { it.isNotEmpty() } ?: "Android"

    /** POST create; kembalikan URL share, null bila gagal. */
    fun createShare(
        contentType: String,
        contentId: String,
        season: Int? = null,
        episodeNo: Int? = null
    ): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("$API_BASE/create").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "CerpenHozin/1.1")
                doOutput = true
            }
            val body = buildCreateBody(contentType, contentId, season, episodeNo, deviceLabel())
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != HttpURLConnection.HTTP_OK && conn.responseCode != HttpURLConnection.HTTP_CREATED) {
                Log.w(TAG, "create gagal: HTTP ${conn.responseCode}")
                return null
            }
            parseCreateResponse(conn.inputStream.readBytes().toString(Charsets.UTF_8))
        } catch (e: Exception) {
            Log.w(TAG, "create gagal: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** GET resolve; kembalikan isi tautan, null bila token tak valid / gagal. */
    fun resolveShare(token: String): ResolvedShare? {
        var conn: HttpURLConnection? = null
        return try {
            val url = "$API_BASE/resolve?token=" +
                URLEncoder.encode(token, Charsets.UTF_8.name())
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", "CerpenHozin/1.1")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "resolve gagal: HTTP ${conn.responseCode}")
                return null
            }
            parseResolve(conn.inputStream.readBytes().toString(Charsets.UTF_8))
        } catch (e: Exception) {
            Log.w(TAG, "resolve gagal: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    // ---- Share sheet (dipakai tombol Bagikan di layar detail) ----

    /** Cegah ketuk ganda saat create sedang berjalan. */
    @Volatile
    private var inFlight = false

    /**
     * Buat tautan di thread latar lalu buka share sheet Android dengan
     * URL-nya. Gagal -> toast singkat yang jujur (bukan klaim palsu).
     */
    fun shareContent(
        activity: Activity,
        contentType: String,
        contentId: String,
        season: Int? = null,
        episodeNo: Int? = null
    ) {
        if (inFlight) return
        inFlight = true
        Thread {
            val url = createShare(contentType, contentId, season, episodeNo)
            activity.runOnUiThread {
                inFlight = false
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (url == null) {
                    Toast.makeText(
                        activity,
                        "Gagal membuat tautan — periksa internet lalu coba lagi",
                        Toast.LENGTH_LONG
                    ).show()
                    return@runOnUiThread
                }
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, url)
                }
                activity.startActivity(Intent.createChooser(send, "Bagikan lewat"))
            }
        }.start()
    }
}
