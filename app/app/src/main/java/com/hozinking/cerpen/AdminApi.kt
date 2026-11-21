package com.hozinking.cerpen

import android.content.Context
import org.json.JSONObject

/**
 * Klien paket admin/keamanan Cerpen — sisi aplikasi (branch cerpen-admin-batch).
 *
 * Tersambung ke KONTRAK SERVER cerpen-track (builder server selesai
 * 21:34, tes 1015 OK): register / heartbeat / status / session/end /
 * key/rotate. Semua URL dari Rust ([Endpoints.track*Url]) — tidak ada
 * string path di DEX. Redeem code TIDAK di sini — UI + alur tukar kode
 * sudah ada di AccountActivity/CoinApi.redeem (menangani reward_type
 * coins/vip/unlocks + label generik); server tinggal menerbitkan kode
 * admin lewat endpoint redeem yang sama.
 *
 * Header kunci: [API_KEY_HEADER] — dikirim di SEMUA panggilan admin DAN
 * di rute lama (koin/auth/sosial/bookmark) setelah register (lihat
 * CoinApi/SocialApi). Tanpa header, perilaku lama dipertahankan server.
 *
 * Perilaku kunci: plaintext "ck-…" HANYA sekali (register/rotate) —
 * simpan di [AdminKeyStore] (EncryptedSharedPreferences). Bila hilang,
 * rotate (body device_id + fingerprint). Kunci dicabut otomatis saat
 * ban. 401 (kunci tidak valid) -> rotate sekali lalu ulangi panggilan.
 *
 * Semua pemanggilan jaringan HARUS dari thread latar (bukan UI thread).
 * Fail-open: gagal jaringan = aplikasi jalan seperti biasa. Ban HANYA
 * dari jawaban server yang jelas atau ban tersimpan lokal.
 *
 * SATUAN WAKTU: modul cerpen-track memakai expires_at unix DETIK.
 * Respons ban rute LAMA (coins) bisa membawa expires_at milidetik
 * (bug D4 — sengaja belum diperbaiki server, menunggu keputusan user):
 * [normalizeExpiresSec] menangani keduanya secara defensif.
 */
object AdminApi {

    /** Nama header kunci API (bukan URL — aman sebagai literal Kotlin). */
    const val API_KEY_HEADER = "x-cerpen-api-key"

    /** Hasil generik pemanggilan admin. */
    sealed class Result {
        /** Endpoint belum dikonfigurasi / lib native tidak ada — inert. */
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

    data class AdminCommand(val id: String, val type: String) {
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
        val commands: List<AdminCommand>,
        val userId: String? = null,
        val accountType: String? = null,
        val keyId: String? = null,
        /** true bila register menjawab kunci sudah ada (plaintext tidak dikirim). */
        val keyExists: Boolean = false,
        /** God-mode admin (21 Nov 2026): flag + expiry detik dari server. */
        val admin: Boolean = false,
        val adminExpiresAtSec: Long = 0L
    )

    /**
     * Penyedia URL endpoint admin. Produksi: Rust (pola Fase 2 — URL
     * dari modul native, tidak polos di DEX).
     */
    interface UrlProvider {
        fun registerUrl(): String
        fun heartbeatUrl(): String
        fun statusUrl(): String
        fun sessionEndUrl(): String
        fun keyRotateUrl(): String
    }

    /** Produksi: URL dari Rust Endpoints ("" bila lib tidak ada — fail-closed). */
    object RustUrlProvider : UrlProvider {
        override fun registerUrl() = Endpoints.trackRegisterUrl()
        override fun heartbeatUrl() = Endpoints.trackHeartbeatUrl()
        override fun statusUrl() = Endpoints.trackStatusUrl()
        override fun sessionEndUrl() = Endpoints.trackSessionEndUrl()
        override fun keyRotateUrl() = Endpoints.trackKeyRotateUrl()
    }

    /** Di-override unit test; produksi selalu RustUrlProvider. */
    internal var urlProvider: UrlProvider = RustUrlProvider

    // ---------- parsing murni (unit-testable) ----------

    /**
     * Normalisasi expires_at ke DETIK: nilai > 1e12 dianggap milidetik
     * (bug D4 rute lama) lalu dibagi 1000; detik dibiarkan.
     */
    fun normalizeExpiresSec(value: Long): Long =
        if (value > 1_000_000_000_000L) value / 1000 else value

    /**
     * Parse jawaban status/heartbeat/register/rotate server.
     * Bentuk yang diterima (semua field opsional kecuali ok):
     * {ok:true, user_id:"", type:"guest"|"user", banned:bool,
     *  reason:"", expires_at:<detik>, api_key:"", key_id:"",
     *  key_exists:bool, admin:bool, admin_expires_at:<detik>,
     *  commands:[{id, kind:"kick"|"crash", ...}]}
     * expires_at detik (modul baru) — dinormalisasi defensif.
     */
    fun parseStatus(json: JSONObject?): StatusResult? {
        if (json == null) return null
        val banned = json.optBoolean("banned", false)
        val reason = json.optString("reason", "").trim()
        val expSec = normalizeExpiresSec(json.optLong("expires_at", 0))
        return StatusResult(
            banned = banned,
            reason = reason,
            expiresAtMs = if (expSec > 0) expSec * 1000 else 0L,
            apiKey = json.optString("api_key", "").ifEmpty { null },
            commands = parseCommands(json),
            userId = json.optString("user_id", "").ifEmpty { null },
            accountType = json.optString("type", "").ifEmpty { null },
            keyId = json.optString("key_id", "").ifEmpty { null },
            keyExists = json.optBoolean("key_exists", false),
            admin = json.optBoolean("admin", false),
            adminExpiresAtSec = normalizeExpiresSec(json.optLong("admin_expires_at", 0))
        )
    }

    /** Parse array "commands" — kind (kontrak baru) atau type (lama). */
    fun parseCommands(json: JSONObject?): List<AdminCommand> {
        val out = ArrayList<AdminCommand>()
        val arr = json?.optJSONArray("commands") ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val kind = o.optString("kind", "").ifEmpty { o.optString("type", "") }.trim()
            if (kind.isNotEmpty()) {
                out.add(AdminCommand(o.optString("id", ""), kind))
            }
        }
        return out
    }

    /** true bila ban masih berlaku pada [nowMs] (0 = permanen). */
    fun isBanActive(expiresAtMs: Long, nowMs: Long): Boolean =
        expiresAtMs == 0L || expiresAtMs > nowMs

    // ---------- jaringan ----------

    /**
     * Panggilan ke URL admin dengan header gerbang + kunci API bila ada.
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
                        conn.setRequestProperty(API_KEY_HEADER, apiKey)
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

    /** Ubah Resp jadi Result (403 banned -> Banned, 200 -> Ok). */
    private fun toResult(r: CoinApi.Resp): Result {
        if (r.isNetworkError) return Result.NetworkError
        if (r.status == 403) {
            val msg = CoinGate.bannedMessage(r.json)
            if (msg != null) {
                val expSec = normalizeExpiresSec(r.json?.optLong("expires_at", 0) ?: 0L)
                return Result.Banned(msg, if (expSec > 0) expSec * 1000 else 0L)
            }
            return Result.NetworkError
        }
        if (r.status == 401) return Result.NetworkError
        val st = parseStatus(r.json)
        if (st == null || r.status !in 200..299) return Result.NetworkError
        if (st.banned) return Result.Banned(st.reason, st.expiresAtMs)
        return Result.Ok(st)
    }

    /**
     * Jalankan [call]; bila 401 (kunci tidak valid) -> rotate sekali
     * lalu ulangi SATU kali. Rotate gagal / tetap 401 -> NetworkError.
     */
    private fun withRotateRetry(ctx: Context, call: () -> CoinApi.Resp): CoinApi.Resp {
        val first = call()
        if (first.status != 401) return first
        if (!rotateKey(ctx)) return first
        return call()
    }

    private fun deviceBody(ctx: Context): JSONObject =
        JSONObject().apply {
            put("device_id", CoinStore.deviceId(ctx))
            put("android_id", CoinStore.androidId(ctx))
        }

    /** Body register/heartbeat: identitas + sidik + sinyal (nama field kontrak). */
    private fun trackBody(ctx: Context): JSONObject {
        val signals = DeviceSignals.collect(ctx)
        return deviceBody(ctx).apply {
            put("fingerprint", DeviceSignals.fingerprint(ctx))
            put("device_model", android.os.Build.MODEL ?: "")
            put("android_version", android.os.Build.VERSION.RELEASE ?: "")
            put("app_version", BuildConfig.VERSION_NAME)
            val sig = DeviceSignals.toJson(signals)
            val keys = sig.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                put(k, sig.get(k))
            }
            DeviceSignals.integrityToken(ctx)?.let { put("integrity_token", it) }
        }
    }

    /**
     * Cek status banned (dipakai saat buka aplikasi — SEBELUM konten).
     * Jalur utama: endpoint status kontrak. Jalur cadangan (lib native
     * tidak ada — perangkat non-arm64): GET /api/coins/me yang sudah
     * ada — server menjawab 403 banned untuk perangkat yang di-ban.
     */
    fun checkStatus(ctx: Context): Result {
        val url = urlProvider.statusUrl()
        if (url.isBlank()) {
            // Jalur cadangan — API yang sudah ada, tanpa endpoint baru.
            val dev = java.net.URLEncoder.encode(CoinStore.deviceId(ctx), "UTF-8")
            val r = CoinApi.request(ctx, "GET", Endpoints.coinsMeUrl(dev))
            if (r.isNetworkError) return Result.NetworkError
            if (r.status == 403) {
                val msg = CoinGate.bannedMessage(r.json)
                if (msg != null) {
                    val expSec = normalizeExpiresSec(r.json?.optLong("expires_at", 0) ?: 0L)
                    return Result.Banned(msg, if (expSec > 0) expSec * 1000 else 0L)
                }
                return Result.NetworkError
            }
            if (r.status !in 200..299) return Result.NetworkError
            val st = parseStatus(r.json)
            if (st != null && st.banned) return Result.Banned(st.reason, st.expiresAtMs)
            return Result.Ok(st ?: StatusResult(false, "", 0L, null, emptyList()))
        }
        val r = withRotateRetry(ctx) { adminRequest(ctx, "GET", url, null) }
        return toResult(r)
    }

    /**
     * Daftarkan guest (sekali per instalasi). 201 = kunci baru (plaintext
     * — simpan lewat AdminKeyStore oleh pemanggil); 200 key_exists =
     * kunci aktif sudah ada (plaintext TIDAK dikirim lagi — pemanggil
     * harus rotate bila kunci lokal hilang). 403 = perangkat/fingerprint
     * dibanned. 400/429 = NetworkError (fail-open, coba lagi nanti).
     */
    fun register(ctx: Context): Result {
        val url = urlProvider.registerUrl()
        if (url.isBlank()) return Result.NotConfigured
        return toResult(adminRequest(ctx, "POST", url, trackBody(ctx)))
    }

    /**
     * Heartbeat: presence + sesi baca + detik baca + ack perintah yang
     * sudah dieksekusi. reading_seconds 0–120 (dibatasi pemanggil).
     * 401 -> rotate sekali lalu ulangi. 403 = banned (dicek tiap heartbeat).
     */
    fun heartbeat(
        ctx: Context,
        session: AdminGate.ReadingSession?,
        readingSeconds: Int,
        acked: List<String>
    ): Result {
        val url = urlProvider.heartbeatUrl()
        if (url.isBlank()) return Result.NotConfigured
        val body = trackBody(ctx).apply {
            if (session != null) {
                put("reading", JSONObject().apply {
                    put("kind", session.kind)
                    put("target_id", session.targetId)
                    put("season", session.season)
                    put("no", session.no)
                })
            }
            put("reading_seconds", readingSeconds.coerceIn(0, 120))
            if (acked.isNotEmpty()) {
                val arr = org.json.JSONArray()
                for (id in acked) arr.put(id)
                put("acked", arr)
            }
        }
        val r = withRotateRetry(ctx) { adminRequest(ctx, "POST", url, body) }
        return toResult(r)
    }

    /**
     * Tutup sesi baca di server (dipanggil saat reader ditutup).
     * Best-effort — true bila server menjawab 200.
     */
    fun endSession(ctx: Context, readingSeconds: Int): Boolean {
        val url = urlProvider.sessionEndUrl()
        if (url.isBlank()) return false
        val key = AdminKeyStore.apiKey(ctx)
        val body = JSONObject().apply {
            if (!key.isNullOrBlank()) put("api_key", key)
            put("reading_seconds", readingSeconds.coerceAtLeast(0))
        }
        return try {
            val r = adminRequest(ctx, "POST", url, body)
            r.status in 200..299
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Rotate kunci API: header kunci lama, ATAU (bila kunci hilang)
     * body device_id + fingerprint (harus cocok dengan yang tersimpan).
     * Kunci lama dicabut server. True bila kunci baru tersimpan.
     */
    fun rotateKey(ctx: Context): Boolean {
        val url = urlProvider.keyRotateUrl()
        if (url.isBlank()) return false
        val body = JSONObject().apply {
            put("device_id", CoinStore.deviceId(ctx))
            put("fingerprint", DeviceSignals.fingerprint(ctx))
        }
        val r = adminRequest(ctx, "POST", url, body)
        if (r.status !in 200..299) return false
        val st = parseStatus(r.json) ?: return false
        val key = st.apiKey ?: return false
        AdminKeyStore.saveApiKey(ctx, key)
        return true
    }
}
