package com.hozinking.cerpen

import android.content.Context

/**
 * State admin lokal (paket admin) — SharedPreferences biasa, BUKAN
 * rahasia: status ban terakhir yang diketahui, penanda registrasi,
 * heartbeat terakhir, token integritas. Kunci API TIDAK di sini
 * (lihat AdminKeyStore). Ban lokal hanya CACHE — server tetap
 * otoritas; ban kedaluwarsa diabaikan.
 */
object AdminStore {
    private const val PREFS = "cerpen_admin"
    private const val K_BAN_REASON = "ban_reason"
    private const val K_BAN_EXPIRES_AT_MS = "ban_expires_at_ms"
    private const val K_REGISTERED = "registered"
    private const val K_LAST_HEARTBEAT_MS = "last_heartbeat_ms"
    private const val K_INTEGRITY_TOKEN = "integrity_token"
    private const val K_INTEGRITY_TOKEN_AT_MS = "integrity_token_at_ms"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Ban terakhir dari server (null = tidak ada / sudah dicabut). */
    fun savedBan(ctx: Context): Pair<String, Long>? {
        val p = prefs(ctx)
        val reason = p.getString(K_BAN_REASON, null) ?: return null
        val exp = p.getLong(K_BAN_EXPIRES_AT_MS, 0)
        if (!AdminApi.isBanActive(exp, System.currentTimeMillis())) return null
        return reason to exp
    }

    fun saveBan(ctx: Context, reason: String, expiresAtMs: Long) {
        prefs(ctx).edit()
            .putString(K_BAN_REASON, reason)
            .putLong(K_BAN_EXPIRES_AT_MS, expiresAtMs)
            .apply()
    }

    fun clearBan(ctx: Context) {
        prefs(ctx).edit()
            .remove(K_BAN_REASON)
            .remove(K_BAN_EXPIRES_AT_MS)
            .apply()
    }

    fun isRegistered(ctx: Context): Boolean = prefs(ctx).getBoolean(K_REGISTERED, false)
    fun setRegistered(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean(K_REGISTERED, v).apply()
    }

    fun lastHeartbeatMs(ctx: Context): Long = prefs(ctx).getLong(K_LAST_HEARTBEAT_MS, 0)
    fun setLastHeartbeatMs(ctx: Context, ms: Long) {
        prefs(ctx).edit().putLong(K_LAST_HEARTBEAT_MS, ms).apply()
    }

    /** Token Play Integrity terakhir (segar < 50 menit) atau null. */
    fun integrityToken(ctx: Context): String? {
        val p = prefs(ctx)
        val at = p.getLong(K_INTEGRITY_TOKEN_AT_MS, 0)
        if (at == 0L || System.currentTimeMillis() - at > 50 * 60 * 1000L) return null
        return p.getString(K_INTEGRITY_TOKEN, null)?.ifEmpty { null }
    }

    fun saveIntegrityToken(ctx: Context, token: String) {
        prefs(ctx).edit()
            .putString(K_INTEGRITY_TOKEN, token)
            .putLong(K_INTEGRITY_TOKEN_AT_MS, System.currentTimeMillis())
            .apply()
    }
}
