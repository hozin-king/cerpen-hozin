package com.hozinking.cerpen

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cek update aplikasi (v1.1.5): bandingkan versi terpasang dengan rilis
 * GitHub terbaru. Sumber OTOMATIS — GitHub Releases API, tanpa file
 * versi manual dan tanpa hardcode URL APK per rilis.
 *
 * Dialog update ini HANYA untuk update KODE aplikasi. Konten
 * (cerpen/berita/komik) tetap di-sync dari repo GitHub oleh DataManager
 * dan bisa update tanpa update APK.
 *
 * Fail-open: semua kegagalan jaringan/parse mengembalikan null — pemanggil
 * diam saja, aplikasi tidak pernah diblokir oleh cek update.
 */
object UpdateCheck {

    const val REPO_OWNER = "hozin-king"
    const val REPO_NAME = "cerpen-hozin"
    const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
    const val RELEASES_URL =
        "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases?per_page=10"
    const val RELEASES_PAGE_URL =
        "https://github.com/$REPO_OWNER/$REPO_NAME/releases/latest"

    /** Tertinggal berapa rilis hingga dialog jadi PAKSA (tanpa bisa ditutup). */
    const val FORCE_AFTER_BEHIND = 3

    /** Info satu rilis dari API GitHub. */
    data class ReleaseInfo(
        val tag: String,
        val version: String,
        val apkUrl: String?,
        /** Ukuran aset APK (byte) dari API; 0 = tidak diketahui. */
        val apkSize: Long = 0,
        /** Digest aset dari API ("sha256:<64 hex>"); null = tidak disediakan. */
        val apkDigest: String? = null
    )

    /** Satu aset APK dari `assets[]`: URL + ukuran + digest (bila ada). */
    data class ApkAsset(val url: String, val size: Long, val digest: String?)

    /** Hasil keputusan cek update (logika murni, bisa dites tanpa Android). */
    sealed class Decision {
        /** Tidak ada versi baru / tidak bisa dicek — diam. */
        object None : Decision()

        /** Ada versi baru, tertinggal 1-2 rilis — dialog bisa ditutup. */
        data class Soft(val release: ReleaseInfo) : Decision()

        /** Tertinggal >= 3 rilis — dialog paksa, tanpa bisa di-bypass. */
        data class Force(val release: ReleaseInfo) : Decision()
    }

    /**
     * Parse "v1.2.3" / "1.2.3" jadi [1, 2, 3]. Null bila format tidak
     * valid (bukan angka semua) — tag aneh diabaikan, bukan crash.
     */
    fun parseVersion(tag: String): List<Int>? {
        val clean = tag.trim().removePrefix("v").removePrefix("V")
        if (clean.isEmpty()) return null
        val parts = clean.split(".")
        if (parts.size > 4) return null
        val nums = ArrayList<Int>(parts.size)
        for (p in parts) {
            val n = p.toIntOrNull() ?: return null
            if (n < 0) return null
            nums.add(n)
        }
        return nums
    }

    /**
     * Bandingkan dua versi semver: >0 bila a lebih baru, <0 bila a lebih
     * lama, 0 bila sama / salah satu tidak valid (tag invalid = bukan
     * update — fail-open).
     */
    fun compareVersions(a: String, b: String): Int {
        val va = parseVersion(a) ?: return 0
        val vb = parseVersion(b) ?: return 0
        val n = maxOf(va.size, vb.size)
        for (i in 0 until n) {
            val x = va.getOrElse(i) { 0 }
            val y = vb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    /** Hitung berapa tag di daftar yang lebih baru dari versi terpasang. */
    fun countNewerReleases(installed: String, tags: List<String>): Int {
        var n = 0
        for (t in tags) {
            if (compareVersions(t, installed) > 0) n++
        }
        return n
    }

    /**
     * Pilih aset APK dari `assets[]` satu rilis: utamakan .apk yang
     * namanya mengandung "CerpenHozin" (nama aset rilis CI), fallback
     * .apk pertama/terbesar. Null bila tidak ada aset .apk.
     */
    fun pickApkAssetInfo(assets: JSONArray?): ApkAsset? {
        if (assets == null) return null
        var fallback: ApkAsset? = null
        var fallbackSize = -1L
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name", "")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            val url = a.optString("browser_download_url", "")
            if (url.isEmpty()) continue
            val size = a.optLong("size", 0)
            val digest = a.optString("digest", "").ifEmpty { null }
            val asset = ApkAsset(url, size, digest)
            if (name.contains("CerpenHozin", ignoreCase = true)) {
                return asset
            }
            if (size > fallbackSize) {
                fallbackSize = size
                fallback = asset
            }
        }
        return fallback
    }

    /** Kompatibilitas: hanya URL unduh APK (lihat [pickApkAssetInfo]). */
    fun pickApkAsset(assets: JSONArray?): String? = pickApkAssetInfo(assets)?.url

    /** Parse JSON satu rilis (endpoint /releases/latest). Null bila tag invalid. */
    fun parseLatestRelease(jsonText: String): ReleaseInfo? {
        return try {
            val obj = JSONObject(jsonText)
            val tag = obj.optString("tag_name", "")
            if (parseVersion(tag) == null) return null
            val version = tag.trim().removePrefix("v").removePrefix("V")
            val asset = pickApkAssetInfo(obj.optJSONArray("assets"))
            ReleaseInfo(tag, version, asset?.url, asset?.size ?: 0, asset?.digest)
        } catch (e: Exception) {
            null
        }
    }

    /** Parse JSON daftar rilis (endpoint /releases) jadi daftar tag. */
    fun parseReleaseTags(jsonText: String): List<String> {
        return try {
            val arr = JSONArray(jsonText)
            val tags = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val tag = arr.optJSONObject(i)?.optString("tag_name", "") ?: ""
                if (tag.isNotEmpty()) tags.add(tag)
            }
            tags
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Keputusan update dari versi terpasang + rilis terbaru + daftar tag.
     * Bila daftar tag gagal diambil (kosong), asumsikan tertinggal 1 rilis
     * (soft) — jangan paksa update berdasarkan data yang tidak lengkap.
     */
    fun decide(installed: String, latest: ReleaseInfo?, tags: List<String>): Decision {
        if (latest == null) return Decision.None
        if (compareVersions(latest.version, installed) <= 0) return Decision.None
        val behind = if (tags.isEmpty()) 1 else countNewerReleases(installed, tags)
        return if (behind >= FORCE_AFTER_BEHIND) Decision.Force(latest)
        else Decision.Soft(latest)
    }

    // ============ Jaringan (java.net saja, pola sama dengan DataManager) ============

    /** GET satu URL, kembalikan body teks bila HTTP 200, null bila gagal. */
    fun fetchText(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 15000
                setRequestProperty("User-Agent", "CerpenHozin/1.1")
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Cek update lengkap: rilis terbaru + daftar rilis (untuk hitung
     * ketertinggalan). Dipanggil dari thread background. Null = tidak ada
     * update / tidak bisa dicek (fail-open).
     */
    fun check(installed: String): Decision {
        val latestJson = fetchText(LATEST_RELEASE_URL) ?: return Decision.None
        val latest = parseLatestRelease(latestJson) ?: return Decision.None
        if (compareVersions(latest.version, installed) <= 0) return Decision.None
        val tagsJson = fetchText(RELEASES_URL)
        val tags = if (tagsJson == null) emptyList() else parseReleaseTags(tagsJson)
        return decide(installed, latest, tags)
    }
}
