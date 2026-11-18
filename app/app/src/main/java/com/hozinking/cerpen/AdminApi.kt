package com.hozinking.cerpen

import android.content.Context
import org.json.JSONObject

/**
 * Klien paket admin/keamanan Cerpen — sisi aplikasi (branch cerpen-admin-batch).
 *
 * Isi paket: ID guest + heartbeat + tracking, ban real-time, perintah
 * admin (kick/crash), API key anti-bypass, deteksi emulator/root/
 * integritas (hanya lapor). Redeem code TIDAK di sini — UI + alur tukar
 * kode sudah ada di AccountActivity/CoinApi.redeem (menangani
 * reward_type coins/vip/unlocks + label generik); server tinggal
 * menerbitkan kode admin lewat endpoint redeem yang sama.
 *
 * KOORDINASI SERVER (kontrak belum ada saat branch ini dibuat):
 * URL endpoint admin disediakan lewat [UrlProvider]. Default-nya
 * [UnconfiguredUrlProvider] (semua "") — pemanggilan menjadi
 * [Result.NotConfigured] dan fitur admin INERT (aplikasi jalan seperti
 * biasa). Bila kontrak server tiba, tambahkan fungsi JNI di Endpoints
 * (pola Fase 2 — URL dari Rust, tidak polos di DEX) + satu
 * UrlProvider baru; JANGAN hardcode URL di Kotlin. Daftar endpoint
 * yang harus disediakan kontrak: register, heartbeat, status, redeem
 * (lihat [UrlProvider]).
 *
 * Pengecualian: [checkStatus] punya JALUR CADANGAN lewat API Cerpen
 * yang sudah ada — GET /api/coins/me (Endpoints.coinsMeUrl). Server
 * sudah menjawab 403 {error:'banned', reason, expires_at} untuk
 * perangkat yang di-ban di jalur itu (lihat CoinApi), jadi gate ban
 * saat buka aplikasi BEKERJA HARI INI tanpa menunggu kontrak baru.
 * Begitu endpoint status khusus ada, jalur itu yang dipakai.
 *
 * Semua pemanggilan jaringan HARUS dari thread latar (bukan UI thread).
 * Fail-open: gagal jaringan / belum terkonfigurasi = aplikasi jalan
 * seperti biasa (ban HANYA ditegakkan dari jawaban server yang jelas
 * atau dari ban yang sudah tersimpan lokal).
 */
object AdminApi {

    /** Hasil generik pemanggilan admin. */
    sealed class Result {
        /** Endpoint belum dikonfigurasi (kontrak server belum ada) — inert. */
        object NotConfigured : Result()

        /** Gagal jaringan — fail-open: aplikasi jalan seperti biasa. */
        object NetworkError : Result()

        /** Server menjawab: tidak di-ban (bawa perintah + kunci bila ada). */
        data class Ok(val status: StatusResult) : Result()

        /** Server menjawab: di-ban. */
        data class Banned(val reason: String, val expiresAtMs: Long) : Result()
    }

    /** Perintah admin dari server (lihat AdminGate untuk eksekusi). */
    enum class CommandAction { KICK, CRASH, IGNORE }

    data class AdminCommand(val type: String) {
        val action: CommandAction
            get() = when (type) {
                "kick" -> CommandAction.KICK
                "crash" -> CommandAction.CRASH
                else -> CommandAction.IGNORE
            }
    }

    data class StatusResult(
        val banned: Boolean,
        val reason: String,
        val expiresAtMs: Long,
        val apiKey: String?,
        val commands: List<AdminCommand>
    )

    /**
     * Penyedia URL endpoint admin. Produksi: Rust (menyusul kontrak
     * server — JANGAN hardcode URL di Kotlin).
     */
    interface UrlProvider {
        fun registerUrl(): String
        fun heartbeatUrl(): String
        fun statusUrl(): String
        fun redeemUrl(): String
    }

    /** Default: belum dikonfigurasi — semua panggilan jadi NotConfigured. */
    object UnconfiguredUrlProvider : UrlProvider {
        override fun registerUrl() = ""
        override fun heartbeatUrl() = ""
        override fun statusUrl() = ""
        override fun redeemUrl() = ""
    }

    /** Di-override unit test; produksi selalu Unconfigured sampai kontrak tiba. */
    internal var urlProvider: UrlProvider = UnconfiguredUrlProvider

    // ---------- parsing murni (unit-testable) ----------

    /**
     * Parse jawaban status/heartbeat/register server.
     * Bentuk yang diterima (semua field opsional kecuali ok):
     * {ok:true, banned:bool, reason:"", expires_at:<detik|0=permanen>,
     *  api_key:"", commands:[{type:"kick"|"crash"}]}
     * expires_at detik (konvensi server yang sama seperti ban koin);
     * null bila tidak di-ban.
     */
    fun parseStatus(json: JSONObject?): StatusResult? {
        if (json == null) return null
        val banned = json.optBoolean("banned", false)
        val reason = json.optString("reason", "").trim()
        val expSec = json.optLong("expires_at", 0)
        return StatusResult(
            banned = banned,
            reason = reason,
            expiresAtMs = if (expSec > 0) expSec * 1000 else 0L,
            apiKey = json.optString("api_key", "").ifEmpty { null },
            commands = parseCommands(json)
        )
    }

    /** Parse array "commands" — tipe tak dikenal diabaikan (IGNORE). */
    fun parseCommands(json: JSONObject?): List<AdminCommand> {
        val out = ArrayList<AdminCommand>()
        val arr = json?.optJSONArray("commands") ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val t = o.optString("type", "").trim()
            if (t.isNotEmpty()) out.add(AdminCommand(t))
        }
        return out
    }

    /** true bila ban masih berlaku pada [nowMs] (0 = permanen). */
    fun isBanActive(expiresAtMs: Long, nowMs: Long): Boolean =
        expiresAtMs == 0L || expiresAtMs > nowMs

    // ---------- jaringan ----------

    /**
     * POST/GET ke URL admin dengan header gerbang + kunci API bila ada.
     * Mengikuti pola CoinApi.request (Hosts.withFallback, timeout sama).
     * Kunci API TIDAK PERNAH ditulis ke log.
     */
    private fun adminRequest(
        ctx: Context,
        method: String,
        urlStr: String,
        body: JSONObject?
    ): CoinApi.Resp {
        val apiKey = AdminKeyStore.apiKey(ctx)
        return try {
            Hosts.withFallback(urlStr) { url ->
                var conn: java.net.HttpURLConnection? = null
                try {
                    conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = method
                    conn.connectTimeout = 8000
                    conn.readTimeout = 15000
                    conn.setRequestProperty("User-Agent", "CerpenHozin/" + BuildConfig.VERSION_NAME)
                    conn.setRequestProperty("Accept", "application/json")
                    AppIdentity.addHeaders(ctx, conn)
                    CoinStore.session(ctx)?.let { s ->
                        if (s.isNotBlank()) conn.setRequestProperty("Cookie", "cms_user=$s")
                    }
                    if (!apiKey.isNullOrBlank()) {
                        conn.setRequestProperty("X-Api-Key", apiKey)
                    }
                    if (body != null) {
                        conn.doOutput = true
                        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        java.io.OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                            it.write(body.toString())
                        }
                    }
                    val status = conn.responseCode
                    val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                    val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                    val json = try {
                        if (text.isBlank()) null else JSONObject(text)
                    } catch (e: Exception) {
                        null
                    }
                    CoinApi.Resp(status, json)
                } finally {
                    try { conn?.disconnect() } catch (e: Exception) { /* abaikan */ }
                }
            }
        } catch (e: Exception) {
            CoinApi.Resp(0, null)
        }
    }

    /** Ubah Resp jadi Result (403 banned -> Banned, 200 -> Ok/NotConfigured). */
    private fun toResult(r: CoinApi.Resp): Result {
        if (r.isNetworkError) return Result.NetworkError
        if (r.status == 403) {
            val msg = CoinGate.bannedMessage(r.json)
            if (msg != null) {
                val expSec = r.json?.optLong("expires_at", 0) ?: 0L
                return Result.Banned(msg, if (expSec > 0) expSec * 1000 else 0L)
            }
        }
        val st = parseStatus(r.json)
        if (st == null || r.status !in 200..299) return Result.NetworkError
        if (st.banned) return Result.Banned(st.reason, st.expiresAtMs)
        return Result.Ok(st)
    }

    private fun deviceBody(ctx: Context): JSONObject =
        JSONObject().apply {
            put("device_id", CoinStore.deviceId(ctx))
            put("android_id", CoinStore.androidId(ctx))
            val uid = CoinStore.userId(ctx)
            if (uid != null) put("user_id", uid)
        }

    /**
     * Cek status banned. Jalur utama: endpoint status khusus (bila
     * kontrak sudah ada). Jalur cadangan: GET /api/coins/me yang sudah
     * ada — server menjawab 403 banned untuk perangkat yang di-ban.
     * Offline / belum terkonfigurasi -> NetworkError / NotConfigured
     * (fail-open).
     */
    fun checkStatus(ctx: Context): Result {
        val url = urlProvider.statusUrl()
        if (url.isNotBlank()) {
            return toResult(adminRequest(ctx, "GET", url, null))
        }
        // Jalur cadangan — API yang sudah ada, tanpa endpoint baru.
        val dev = java.net.URLEncoder.encode(CoinStore.deviceId(ctx), "UTF-8")
        val r = CoinApi.request(ctx, "GET", Endpoints.coinsMeUrl(dev))
        if (r.isNetworkError) return Result.NetworkError
        if (r.status == 403) {
            val msg = CoinGate.bannedMessage(r.json)
            if (msg != null) {
                val expSec = r.json?.optLong("expires_at", 0) ?: 0L
                return Result.Banned(msg, if (expSec > 0) expSec * 1000 else 0L)
            }
            return Result.NetworkError
        }
        if (r.status !in 200..299) return Result.NetworkError
        // /me tidak (belum) membawa field banned — lolos.
        val st = parseStatus(r.json)
        if (st != null && st.banned) return Result.Banned(st.reason, st.expiresAtMs)
        return Result.Ok(st ?: StatusResult(false, "", 0L, null, emptyList()))
    }

    /**
     * Daftarkan guest (sekali per instalasi) + kirim sidik perangkat
     * dan sinyal deteksi. Jawaban boleh membawa api_key (disimpan
     * AdminKeyStore oleh pemanggil) dan perintah admin.
     * Belum terkonfigurasi -> NotConfigured (guest sudah dikenal server
     * lewat device_id di tiap panggilan CoinApi — tidak ada yang rusak).
     */
    fun register(ctx: Context): Result {
        val url = urlProvider.registerUrl()
        if (url.isBlank()) return Result.NotConfigured
        val signals = DeviceSignals.collect(ctx)
        val body = deviceBody(ctx).apply {
            put("device_model", android.os.Build.MODEL ?: "")
            put("device_manufacturer", android.os.Build.MANUFACTURER ?: "")
            put("android_version", android.os.Build.VERSION.RELEASE ?: "")
            put("app_version", BuildConfig.VERSION_NAME)
            put("signals", DeviceSignals.toJson(signals))
            DeviceSignals.integrityToken(ctx)?.let { put("integrity_token", it) }
        }
        return toResult(adminRequest(ctx, "POST", url, body))
    }

    /**
     * Heartbeat: presence + sesi baca saat ini + sinyal deteksi ringkas.
     * Belum terkonfigurasi -> NotConfigured; pemanggil (AdminGate)
     * memakai checkStatus sebagai pengganti (poll ban ringan).
     */
    fun heartbeat(ctx: Context, session: AdminGate.ReadingSession?): Result {
        val url = urlProvider.heartbeatUrl()
        if (url.isBlank()) return Result.NotConfigured
        val body = deviceBody(ctx).apply {
            if (session != null) {
                put("reading", JSONObject().apply {
                    put("kind", session.kind)
                    put("target_id", session.targetId)
                    put("season", session.season)
                    put("no", session.no)
                    put("started_at", session.startedAtMs / 1000)
                })
            }
            put("signals", DeviceSignals.toJson(DeviceSignals.collect(ctx)))
            DeviceSignals.integrityToken(ctx)?.let { put("integrity_token", it) }
        }
        return toResult(adminRequest(ctx, "POST", url, body))
    }
}
