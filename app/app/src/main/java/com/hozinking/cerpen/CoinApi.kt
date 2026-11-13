package com.hozinking.cerpen

import android.content.Context
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Klien API koin/akun CMS (v1.1.30).
 *
 * Pola jaringan mengikuti CerpenApi/RemoteConfig: HttpURLConnection +
 * Hosts.withFallback (primary hozinking.my.id, fallback workers.dev).
 * Semua pemanggilan network HARUS dari thread latar (bukan UI thread).
 *
 * Kontrak server (coins.js, CMS v87):
 * - sesi lewat header Cookie: cms_user=<session>
 * - device_id dikirim di query (GET) / body (POST)
 * - banned: HTTP 403 {error:'banned', reason, expires_at(detik)}
 * - saldo tidak cukup: HTTP 402 {error, balance, price}
 */
object CoinApi {

    data class Resp(val status: Int, val json: JSONObject?) {
        val ok: Boolean get() = status in 200..299 && json?.optBoolean("ok", true) == true
        val isNetworkError: Boolean get() = status == 0
    }

    data class MeResult(
        val balance: Int,
        val username: String?,
        val ownerKey: String?,
        val vipActive: Boolean,
        val vipExpiresAtMs: Long,
        val readingPrivate: Boolean,
        val unlocks: Set<String>,
        /** JSON mentah objek pricing (disimpan CoinStore untuk parse ulang). */
        val pricingJson: String?
    )

    data class SessionResult(
        val session: String,
        val username: String?,
        val ownerKey: String?
    )

    data class LeaderboardEntry(
        val rank: Int,
        val username: String,
        val seconds: Long,
        val minutes: Long
    )

    data class HistoryUnlock(
        val kind: String,
        val targetId: String,
        val season: Int,
        val no: Int,
        /** created_at mentah dari server (detik bila < 1e12, else ms). */
        val createdAtRaw: Long
    )

    data class HistoryReading(
        val kind: String,
        val targetId: String,
        val season: Int,
        val no: Int,
        val seconds: Long,
        val lastDay: String
    )

    data class HistoryResult(
        val unlocks: List<HistoryUnlock>,
        val reading: List<HistoryReading>
    )

    // ---------- parsing murni (unit-testable) ----------

    fun parseMe(json: JSONObject?): MeResult? {
        if (json == null) return null
        val unlocks = HashSet<String>()
        val arr = json.optJSONArray("unlocks")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val u = arr.optJSONObject(i) ?: continue
                val kind = u.optString("kind", "")
                val target = u.optString("target_id", "")
                if (kind.isEmpty() || target.isEmpty()) continue
                unlocks.add(CoinGate.unlockKey(kind, target, u.optInt("season", 1), u.optInt("no", 0)))
            }
        }
        val vip = json.optJSONObject("vip")
        return MeResult(
            balance = json.optInt("balance", 0),
            username = json.optString("username", "").ifEmpty { null },
            ownerKey = json.optString("owner_key", "").ifEmpty { null },
            vipActive = vip?.optBoolean("active", false) ?: false,
            // VIP expires_at server = milidetik (vipStatus vs Date.now()).
            vipExpiresAtMs = vip?.optLong("expires_at", 0) ?: 0L,
            readingPrivate = json.optBoolean("reading_private", false),
            unlocks = unlocks,
            pricingJson = json.optJSONObject("pricing")?.toString()
        )
    }

    fun parseSession(json: JSONObject?): SessionResult? {
        if (json == null) return null
        val s = json.optString("session", "")
        if (s.isEmpty()) return null
        return SessionResult(
            session = s,
            username = json.optString("username", "").ifEmpty { null },
            ownerKey = json.optString("owner_key", "").ifEmpty { null }
        )
    }

    fun parseLeaderboard(json: JSONObject?): List<LeaderboardEntry> {
        val out = ArrayList<LeaderboardEntry>()
        val arr = json?.optJSONArray("leaderboard") ?: return out
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            out.add(
                LeaderboardEntry(
                    rank = e.optInt("rank", i + 1),
                    username = e.optString("username", "?"),
                    seconds = e.optLong("seconds", 0),
                    minutes = e.optLong("minutes", 0)
                )
            )
        }
        return out
    }

    fun parseHistory(json: JSONObject?): HistoryResult? {
        if (json == null) return null
        val unlocks = ArrayList<HistoryUnlock>()
        val ua = json.optJSONArray("unlocks")
        if (ua != null) {
            for (i in 0 until ua.length()) {
                val u = ua.optJSONObject(i) ?: continue
                unlocks.add(
                    HistoryUnlock(
                        kind = u.optString("kind", ""),
                        targetId = u.optString("target_id", ""),
                        season = u.optInt("season", 1),
                        no = u.optInt("no", 0),
                        createdAtRaw = u.optLong("created_at", 0)
                    )
                )
            }
        }
        val reading = ArrayList<HistoryReading>()
        val ra = json.optJSONArray("reading")
        if (ra != null) {
            for (i in 0 until ra.length()) {
                val r = ra.optJSONObject(i) ?: continue
                reading.add(
                    HistoryReading(
                        kind = r.optString("kind", ""),
                        targetId = r.optString("target_id", ""),
                        season = r.optInt("season", 1),
                        no = r.optInt("no", 0),
                        seconds = r.optLong("seconds", 0),
                        lastDay = r.optString("last_day", "")
                    )
                )
            }
        }
        return HistoryResult(unlocks, reading)
    }

    /** Pesan error singkat dari respons server (bukan banned). */
    fun errorMessage(json: JSONObject?, fallback: String): String {
        val e = json?.optString("error", "")?.trim() ?: ""
        return if (e.isNotEmpty() && e != "banned") e else fallback
    }

    // ---------- jaringan ----------

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
        JSONObject().apply {
            put("device_id", CoinStore.deviceId(ctx))
            put("android_id", CoinStore.androidId(ctx))
        }

    // ---------- endpoint ----------

    /** GET /me + simpan ke CoinStore. Null bila gagal / offline. */
    fun fetchMe(ctx: Context): MeResult? {
        val dev = URLEncoder.encode(CoinStore.deviceId(ctx), "UTF-8")
        val r = request(ctx, "GET", "/api/cerpen-coins/me?device_id=$dev")
        val me = parseMe(r.json)
        if (me != null && r.status in 200..299) {
            CoinStore.saveMe(ctx, me)
        }
        return me
    }

    fun purchase(ctx: Context, kind: String, targetId: String, season: Int, no: Int): Resp {
        val body = deviceBody(ctx).apply {
            put("kind", kind)
            put("target_id", targetId)
            put("season", season)
            put("no", no)
        }
        return request(ctx, "POST", "/api/cerpen-coins/purchase", body)
    }

    fun redeem(ctx: Context, code: String): Resp {
        val body = deviceBody(ctx).apply { put("code", code.trim()) }
        return request(ctx, "POST", "/api/cerpen-coins/redeem", body)
    }

    fun reportReading(
        ctx: Context,
        kind: String,
        targetId: String,
        season: Int,
        no: Int,
        seconds: Int,
        maxScroll: Int
    ): Resp {
        val body = deviceBody(ctx).apply {
            put("kind", kind)
            put("target_id", targetId)
            put("season", season)
            put("no", no)
            put("seconds", seconds)
            put("max_scroll", maxScroll)
        }
        return request(ctx, "POST", "/api/cerpen-coins/reading", body)
    }

    fun fetchLeaderboard(ctx: Context, period: String): List<LeaderboardEntry>? {
        val p = if (period == "all") "all" else "weekly"
        val r = request(ctx, "GET", "/api/cerpen-coins/leaderboard?period=$p")
        return if (r.status in 200..299) parseLeaderboard(r.json) else null
    }

    fun fetchHistory(ctx: Context): HistoryResult? {
        val dev = URLEncoder.encode(CoinStore.deviceId(ctx), "UTF-8")
        val r = request(ctx, "GET", "/api/cerpen-coins/history?device_id=$dev")
        return if (r.status in 200..299) parseHistory(r.json) else null
    }

    fun setReadingPrivacy(ctx: Context, private: Boolean): Resp {
        val body = JSONObject().apply { put("private", if (private) 1 else 0) }
        return request(ctx, "POST", "/api/cerpen-auth/reading-privacy", body)
    }

    // ---------- auth ----------

    fun register(ctx: Context, username: String, password: String, email: String): Resp {
        val body = deviceBody(ctx).apply {
            put("username", username)
            put("password", password)
            put("email", email)
        }
        return request(ctx, "POST", "/api/cerpen-auth/register", body)
    }

    fun verify(ctx: Context, email: String, code: String): Resp {
        val body = deviceBody(ctx).apply {
            put("email", email)
            put("code", code.trim())
        }
        return request(ctx, "POST", "/api/cerpen-auth/verify", body)
    }

    fun resend(ctx: Context, email: String): Resp {
        val body = JSONObject().apply { put("email", email) }
        return request(ctx, "POST", "/api/cerpen-auth/resend", body)
    }

    fun login(ctx: Context, username: String, password: String): Resp {
        val body = deviceBody(ctx).apply {
            put("username", username)
            put("password", password)
        }
        return request(ctx, "POST", "/api/cerpen-auth/login", body)
    }

    fun logout(ctx: Context): Resp {
        return request(ctx, "POST", "/api/cerpen-auth/logout", JSONObject())
    }
}
