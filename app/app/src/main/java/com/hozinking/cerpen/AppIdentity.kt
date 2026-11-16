package com.hozinking.cerpen

import android.content.Context
import java.net.HttpURLConnection

/**
 * Identitas aplikasi untuk gerbang versi server (v1.1.38).
 *
 * Server (CMS v87.6) menolak request tanpa identitas lengkap:
 * X-App-Version + X-App-Signer-Sha + X-Device-Id. Device-id adalah
 * UUID stabil per-install dari [CoinStore] (sudah dipakai koin/sosial
 * sebagai device_id di body); signer dibaca sekali per proses dari
 * PackageManager lalu di-cache (bukan per request).
 *
 * Dipakai CoinApi/SocialApi (punya Context) dan DataManager (gambar CMS).
 * CerpenApi tetap memakai setAppIdentity-nya sendiri (tanpa Context).
 */
object AppIdentity {
    @Volatile
    private var deviceIdCached: String? = null

    @Volatile
    private var signerCached: String? = null

    fun versionName(): String = BuildConfig.VERSION_NAME

    fun deviceId(ctx: Context): String {
        deviceIdCached?.let { return it }
        synchronized(this) {
            deviceIdCached?.let { return it }
            return CoinStore.deviceId(ctx).also { deviceIdCached = it }
        }
    }

    fun signerSha(ctx: Context): String? {
        signerCached?.let { return it }
        synchronized(this) {
            signerCached?.let { return it }
            return AppUpdate.installedSignerSha256(ctx)?.also { signerCached = it }
        }
    }

    /** Pasang ketiga header gerbang ke satu koneksi (no-op bila kosong). */
    fun addHeaders(ctx: Context, conn: HttpURLConnection) {
        conn.setRequestProperty("X-App-Version", versionName())
        signerSha(ctx)?.let { conn.setRequestProperty("X-App-Signer-Sha", it) }
        conn.setRequestProperty("X-Device-Id", deviceId(ctx))
    }
}
