package com.hozinking.cerpen

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cek update aplikasi (v1.1.29): bandingkan versi terpasang dengan versi
 * terbaru dari CMS Worker (endpoint /api/cerpen-version) — BUKAN lagi
 * GitHub Releases API, supaya repo boleh privat kapan pun tanpa merusak
 * cek update. Sumber OTOMATIS — tanpa file versi manual dan tanpa
 * hardcode URL APK per rilis.
 *
 * Dialog update ini HANYA untuk update KODE aplikasi. Konten
 * (cerpen/berita/komik) tetap di-sync dari D1/CMS oleh DataManager
 * dan bisa update tanpa update APK.
 *
 * Fail-open: semua kegagalan jaringan/parse mengembalikan null — pemanggil
 * diam saja, aplikasi tidak pernah diblokir oleh cek update.
 */
object UpdateCheck {

    const val REPO_OWNER = "hozin-king"
    const val REPO_NAME = "cerpen-hozin"
    /** v1.1.29: versi terbaru dari CMS Worker (fail-open bila 404). */
    const val LATEST_RELEASE_URL =
        "https://hozinking.my.id/api/cerpen-version"
    const val RELEASES_PAGE_URL =
        "https://github.com/$REPO_OWNER/$REPO_NAME/releases/latest"

    /** Tertinggal berapa rilis hingga dialog jadi PAKSA (tanpa bisa ditutup). */
    const val FORCE_AFTER_BEHIND = 3

    /**
     * SHA-256 sertifikat penandatangan rilis RESMI (CN=Hozin King).
     * Diambil dari APK rilis resmi v1.1.22 lewat apksigner — BUKAN secret,
     * nilainya publik (juga tercantum di assetlinks.json untuk App Links).
     * Dipakai anti-bypass v1.1.23: string versi bisa diedit/dipalsu, tapi
     * signer tidak bisa dipalsu tanpa kunci rilis.
     */
    const val OFFICIAL_SIGNER_SHA256 =
        "75ba028f4f9b401b3f414a2b9e63617fa35b503c692c5f6c188e3724a7a01c1d"

    /** Info satu rilis dari API GitHub. */
    data class ReleaseInfo(val tag: String, val version: String, val apkUrl: String?)

    /** Hasil keputusan cek update (logika murni, bisa dites tanpa Android). */
    sealed class Decision {
        /** Tidak ada versi baru / tidak bisa dicek — diam. */
        object None : Decision()

        /** Ada versi baru, tertinggal 1-2 rilis — dialog bisa ditutup. */
        data class Soft(val release: ReleaseInfo) : Decision()

        /** Tertinggal >= 3 rilis — dialog paksa, tanpa bisa di-bypass. */
        data class Force(val release: ReleaseInfo) : Decision()

        /**
         * v1.1.23: SHA signer terpasang TIDAK cocok dengan rilis resmi —
         * dialog paksa (tanpa NANTI) meski string versi mengklaim terbaru.
         */
        data class SignerMismatch(val release: ReleaseInfo) : Decision()
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
     * Parse JSON endpoint CMS /api/cerpen-version:
     * {"tag":"v1.1.28","version":"1.1.28","apk_url":"...","published_at":"..."}.
     * Null bila tag/version invalid (fail-open). apk_url boleh kosong.
     */
    fun parseLatestRelease(jsonText: String): ReleaseInfo? {
        return try {
            val obj = JSONObject(jsonText)
            val tag = obj.optString("tag", "")
            val version = obj.optString("version", "").ifEmpty {
                tag.trim().removePrefix("v").removePrefix("V")
            }
            if (parseVersion(tag) == null || parseVersion(version) == null) return null
            val apkUrl = obj.optString("apk_url", "").ifEmpty { null }
            ReleaseInfo(tag, version, apkUrl)
        } catch (e: Exception) {
            null
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

    /** Normalisasi fingerprint: buang ':'/spasi/dll, huruf kecil semua. */
    fun normalizeFingerprint(hex: String): String {
        val out = StringBuilder(hex.length)
        for (c in hex) {
            if (c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F') out.append(c.lowercaseChar())
        }
        return out.toString()
    }

    /** Cocokkan SHA-256 signer terpasang dengan rilis resmi. */
    fun signerMatches(installedSha256: String): Boolean {
        val norm = normalizeFingerprint(installedSha256)
        return norm.isNotEmpty() && norm == OFFICIAL_SIGNER_SHA256
    }

    /**
     * Haruskah APK unduhan untuk [tag] dihapus? Ya bila versi terpasang
     * sudah sama/lebih baru (instal sukses terkonfirmasi) — file tidak
     * lagi dibutuhkan. Tag invalid = jangan hapus (fail-safe).
     */
    fun shouldDeleteDownloadedApk(installed: String, tag: String): Boolean {
        val tagVersion = tag.trim().removePrefix("v").removePrefix("V")
        if (parseVersion(tagVersion) == null) return false
        return compareVersions(installed, tagVersion) >= 0
    }

    // ============ Jaringan (java.net saja, pola sama dengan DataManager) ============

    /**
     * GET satu URL, kembalikan body teks bila HTTP 200, null bila server
     * menjawab HTTP error (mis. 404). LEMPAR IOException bila koneksi
     * gagal — syarat Hosts.withFallback (coba host fallback sekali).
     */
    fun fetchText(url: String): String? {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 15000
                setRequestProperty("User-Agent", "CerpenHozin/1.1")
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            return conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(e)
        } finally {
            conn?.disconnect()
        }
    }

}
