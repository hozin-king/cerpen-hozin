package com.hozinking.cerpen

import android.util.Log

/**
 * Jembatan JNI ke lib native `contentguard` — penyedia URL/endpoint
 * server Cerpen (Fase 2). Semua URL/endpoint yang dulu literal string
 * di Kotlin (DEX) kini disediakan Rust (template ter-XOR di `.so`).
 *
 * Transport HTTP tetap Kotlin (`HttpURLConnection`); yang pindah hanya
 * PENYEDIAAN URL. Perilaku URL SAMA PERSIS (urutan query, encoding
 * argumen tetap di Kotlin).
 *
 * Fail-closed seperti [ContentGuard]: bila lib tidak ada / gagal
 * dimuat (mis. perangkat non-arm64 — `.so` Rust hanya arm64-v8a),
 * fungsi mengembalikan "" (atau null untuk [imageAssetPath]) dan
 * pemanggil jaringan sudah menangani itu sebagai gagal (null),
 * tanpa crash. JANGAN jadikan "" sebagai URL — pemanggil harus
 * memeriksa kosong bila membangun request manual.
 */
object Endpoints {
    private const val TAG = "Endpoints"

    private var loaded = false
    private var loadFailed = false
    private var warned = false

    private fun loadOnce() {
        if (!loaded && !loadFailed) {
            try {
                System.loadLibrary("contentguard")
                loaded = true
            } catch (e: Throwable) {
                loadFailed = true
                Log.w(TAG, "lib contentguard gagal dimuat: ${e.message}")
            }
        }
    }

    /** Panggil native pengembali String; "" bila lib/JNI gagal. */
    private fun str(call: () -> String): String {
        loadOnce()
        if (!loaded) return ""
        return try {
            call()
        } catch (e: Throwable) {
            warnOnce(e)
            ""
        }
    }

    /** Panggil native pengembali String?; null bila lib/JNI gagal. */
    private fun strOrNull(call: () -> String?): String? {
        loadOnce()
        if (!loaded) return null
        return try {
            call()
        } catch (e: Throwable) {
            warnOnce(e)
            null
        }
    }

    private fun warnOnce(e: Throwable) {
        if (!warned) {
            warned = true
            Log.w(TAG, "Endpoints JNI gagal: ${e.message}")
        }
    }

    // ---------- host ----------

    fun primaryHost(): String = str { primaryHostNative() }
    fun fallbackHost(): String = str { fallbackHostNative() }

    // ---------- API konten ----------

    fun contentManifestUrl(hash: String): String = str { contentManifestUrlNative(hash) }
    fun contentAllUrl(hash: String): String = str { contentAllUrlNative(hash) }

    // ---------- config / versi / rilis ----------

    fun remoteConfigUrl(): String = str { remoteConfigUrlNative() }
    fun versionCheckUrl(): String = str { versionCheckUrlNative() }
    fun releasesPageUrl(): String = str { releasesPageUrlNative() }

    // ---------- tautan berbagi ----------

    fun shareCreateUrl(): String = str { shareCreateUrlNative() }
    fun shareResolveUrl(encodedToken: String): String =
        str { shareResolveUrlNative(encodedToken) }

    // ---------- gambar CMS + fallback GitHub ----------

    fun cmsImageUrl(assetPath: String, thumb: Boolean): String =
        str { cmsImageUrlNative(assetPath, thumb) }

    fun githubManifestUrl(): String = str { githubManifestUrlNative() }
    fun githubFileUrl(file: String): String = str { githubFileUrlNative(file) }
    fun githubAssetUrl(assetPath: String): String = str { githubAssetUrlNative(assetPath) }

    /** URL gambar CMS -> assetPath (tanpa ".enc"); null bila bukan URL CMS. */
    fun imageAssetPath(url: String): String? = strOrNull { imageAssetPathNative(url) }

    // ---------- koin ----------

    fun coinsMeUrl(deviceId: String): String = str { coinsMeUrlNative(deviceId) }
    fun coinsPurchaseUrl(): String = str { coinsPurchaseUrlNative() }
    fun coinsRedeemUrl(): String = str { coinsRedeemUrlNative() }
    fun coinsReadingUrl(): String = str { coinsReadingUrlNative() }
    fun coinsLeaderboardUrl(period: String): String =
        str { coinsLeaderboardUrlNative(period) }

    fun coinsHistoryUrl(deviceId: String): String = str { coinsHistoryUrlNative(deviceId) }

    // ---------- auth ----------

    fun authReadingPrivacyUrl(): String = str { authReadingPrivacyUrlNative() }
    fun authRegisterUrl(): String = str { authRegisterUrlNative() }
    fun authVerifyUrl(): String = str { authVerifyUrlNative() }
    fun authResendUrl(): String = str { authResendUrlNative() }
    fun authLoginUrl(): String = str { authLoginUrlNative() }
    fun authLogoutUrl(): String = str { authLogoutUrlNative() }

    // ---------- sosial ----------

    fun socialViewUrl(): String = str { socialViewUrlNative() }
    fun socialVoteUrl(): String = str { socialVoteUrlNative() }
    fun socialReportUrl(): String = str { socialReportUrlNative() }
    fun socialStatsUrl(kind: String, ref: String, deviceId: String): String =
        str { socialStatsUrlNative(kind, ref, deviceId) }

    fun socialStatsBatchUrl(): String = str { socialStatsBatchUrlNative() }

    // ---------- bookmark ----------

    fun bookmarksUrl(): String = str { bookmarksUrlNative() }
    fun bookmarksDeleteUrl(kind: String, contentId: String): String =
        str { bookmarksDeleteUrlNative(kind, contentId) }

    // ---------- JNI (implementasi Rust di src/endpoints.rs) ----------

    @JvmStatic
    private external fun primaryHostNative(): String
    @JvmStatic
    private external fun fallbackHostNative(): String
    @JvmStatic
    private external fun contentManifestUrlNative(hash: String): String
    @JvmStatic
    private external fun contentAllUrlNative(hash: String): String
    @JvmStatic
    private external fun remoteConfigUrlNative(): String
    @JvmStatic
    private external fun versionCheckUrlNative(): String
    @JvmStatic
    private external fun releasesPageUrlNative(): String
    @JvmStatic
    private external fun shareCreateUrlNative(): String
    @JvmStatic
    private external fun shareResolveUrlNative(encodedToken: String): String
    @JvmStatic
    private external fun cmsImageUrlNative(assetPath: String, thumb: Boolean): String
    @JvmStatic
    private external fun githubManifestUrlNative(): String
    @JvmStatic
    private external fun githubFileUrlNative(file: String): String
    @JvmStatic
    private external fun githubAssetUrlNative(assetPath: String): String
    @JvmStatic
    private external fun imageAssetPathNative(url: String): String?
    @JvmStatic
    private external fun coinsMeUrlNative(deviceId: String): String
    @JvmStatic
    private external fun coinsPurchaseUrlNative(): String
    @JvmStatic
    private external fun coinsRedeemUrlNative(): String
    @JvmStatic
    private external fun coinsReadingUrlNative(): String
    @JvmStatic
    private external fun coinsLeaderboardUrlNative(period: String): String
    @JvmStatic
    private external fun coinsHistoryUrlNative(deviceId: String): String
    @JvmStatic
    private external fun authReadingPrivacyUrlNative(): String
    @JvmStatic
    private external fun authRegisterUrlNative(): String
    @JvmStatic
    private external fun authVerifyUrlNative(): String
    @JvmStatic
    private external fun authResendUrlNative(): String
    @JvmStatic
    private external fun authLoginUrlNative(): String
    @JvmStatic
    private external fun authLogoutUrlNative(): String
    @JvmStatic
    private external fun socialViewUrlNative(): String
    @JvmStatic
    private external fun socialVoteUrlNative(): String
    @JvmStatic
    private external fun socialReportUrlNative(): String
    @JvmStatic
    private external fun socialStatsUrlNative(kind: String, ref: String, deviceId: String): String
    @JvmStatic
    private external fun socialStatsBatchUrlNative(): String
    @JvmStatic
    private external fun bookmarksUrlNative(): String
    @JvmStatic
    private external fun bookmarksDeleteUrlNative(kind: String, contentId: String): String
}
