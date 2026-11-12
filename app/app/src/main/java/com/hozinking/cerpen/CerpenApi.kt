package com.hozinking.cerpen

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Klien API konten Cerpen dari D1 via Worker CMS (v1.1.19).
 *
 * Dulu konten diambil dari file .enc di repo GitHub; kini dari
 * `GET https://hozinking.my.id/api/c/<hash>/cerpen/...` dengan path
 * yang muter tiap jam (hash = 12 hex pertama HMAC-SHA256(kunci-jalur,
 * "YYYY-MM-DD-HH" WIB)). Kunci-jalur diturunkan dari kunci konten yang
 * sama (SHA-256(kunci || "cerpen-api-v1")) — TIDAK PERNAH ditulis ke
 * disk / log, hanya di memori. Path muter ini KAMUFLASE: perlindungan
 * beneran tetap enkripsi AES-256-GCM isi episode/panel.
 *
 * Fail-closed: jaringan gagal / HTTP bukan 200 / JSON rusak -> null,
 * pemanggil (DataManager.syncContent) jatuh ke jalur GitHub lama.
 * Semua helper kripto murni javax.crypto/java.time supaya bisa
 * di-unit-test di JVM (tanpa dependensi Android).
 */
object CerpenApi {
    private const val TAG = "CerpenApi"

    const val BASE_URL = "https://hozinking.my.id/api/c/"
    private const val PATH_KEY_LABEL = "cerpen-api-v1"
    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 60000

    data class D1Manifest(
        val version: Int,
        val updatedAt: String,
        val counts: Map<String, Int>
    )

    /** Kunci-jalur = SHA-256(kunci-konten || "cerpen-api-v1"). */
    fun derivePathKey(contentKey: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(contentKey)
        md.update(PATH_KEY_LABEL.toByteArray(Charsets.UTF_8))
        return md.digest()
    }

    /** "YYYY-MM-DD-HH" WIB dari epoch millis + offset jam (toleransi ±1). */
    fun wibHourString(epochMillis: Long, offsetHours: Long = 0): String {
        val z = java.time.Instant.ofEpochMilli(epochMillis)
            .atZone(java.time.ZoneId.of("Asia/Jakarta"))
            .plusHours(offsetHours)
        return "${z.year}-${z.monthValue.toString().padStart(2, '0')}-" +
            "${z.dayOfMonth.toString().padStart(2, '0')}-${z.hour.toString().padStart(2, '0')}"
    }

    /** 12 hex pertama HMAC-SHA256(kunci-jalur, jam WIB). */
    fun hourHash(pathKey: ByteArray, hourString: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pathKey, "HmacSHA256"))
        val sig = mac.doFinal(hourString.toByteArray(Charsets.UTF_8))
        return sig.joinToString("") { "%02x".format(it.toInt() and 0xFF) }.take(12)
    }

    /** Hash jam berjalan (Worker menerima ±1 jam, jadi jam ini cukup). */
    fun currentHash(pathKey: ByteArray): String =
        hourHash(pathKey, wibHourString(System.currentTimeMillis()))

    /**
     * GET satu URL, kembalikan body bila HTTP 200, null bila gagal.
     * 1x ulangi bila gagal (jeda 500 ms) — pola yang sama dengan
     * DataManager.fetchBytes; read timeout lebih longgar karena dump
     * `all` ~3 MB. v1.1.22: ulangan memakai host fallback workers.dev
     * HANYA bila percobaan pertama gagal koneksi (IOException);
     * HTTP error (server menjawab) tetap mengulang host yang sama.
     */
    private fun fetchBytes(url: String): ByteArray? {
        var connectionFailed = false
        repeat(2) { attempt ->
            val tryUrl = if (attempt == 1 && connectionFailed) Hosts.fallbackUrl(url) else url
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(tryUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("User-Agent", "CerpenHozin/1.1")
                }
                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    return conn.inputStream.readBytes()
                }
            } catch (e: java.io.IOException) {
                Log.w(TAG, "fetch gagal ($tryUrl): ${e.message}")
                connectionFailed = true
            } catch (e: Exception) {
                Log.w(TAG, "fetch gagal ($tryUrl): ${e.message}")
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

    /** Manifest D1 (kecil): versi + counts. Null bila gagal. */
    fun fetchManifest(contentKey: ByteArray): D1Manifest? {
        val pathKey = derivePathKey(contentKey)
        val body = fetchBytes("$BASE_URL${currentHash(pathKey)}/cerpen/manifest") ?: return null
        return try {
            val o = JSONObject(String(body, Charsets.UTF_8))
            val counts = HashMap<String, Int>()
            val c = o.optJSONObject("counts")
            if (c != null) {
                val keys = c.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    counts[k] = c.optInt(k, 0)
                }
            }
            D1Manifest(o.optInt("version", 0), o.optString("updated_at", ""), counts)
        } catch (e: Exception) {
            Log.w(TAG, "manifest D1 bukan JSON valid: ${e.message}")
            null
        }
    }

    /** Dump penuh (series + episodes + komik + komik_episodes + news). Null bila gagal. */
    fun fetchAll(contentKey: ByteArray): JSONObject? {
        val pathKey = derivePathKey(contentKey)
        val body = fetchBytes("$BASE_URL${currentHash(pathKey)}/cerpen/all") ?: return null
        return try {
            JSONObject(String(body, Charsets.UTF_8))
        } catch (e: Exception) {
            Log.w(TAG, "dump D1 bukan JSON valid: ${e.message}")
            null
        }
    }
}
