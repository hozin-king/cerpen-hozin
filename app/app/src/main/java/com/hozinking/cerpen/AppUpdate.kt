package com.hozinking.cerpen

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Unduh + instal APK update DI DALAM aplikasi (v1.1.5).
 *
 * Alur: unduh APK rilis ke cache/updates (progres dilaporkan lewat
 * callback) -> instal via FileProvider. APK TIDAK dienkripsi (bukan
 * konten), file APK lama dihapus tiap ada unduhan baru. Browser HANYA
 * fallback bila unduh/instal di dalam aplikasi gagal.
 */
object AppUpdate {

    private const val TAG = "AppUpdate"

    /** File APK unduhan untuk satu tag rilis. */
    fun apkFileFor(context: Context, tag: String): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        return File(dir, "CerpenHozin-$tag.apk")
    }

    /** Hapus file APK unduhan lama (semua tag kecuali yang diminta). */
    fun deleteOldApks(context: Context, keepTag: String? = null) {
        try {
            val dir = File(context.cacheDir, "updates")
            dir.listFiles()?.forEach { f ->
                if (f.name.endsWith(".apk") && (keepTag == null || f.name != "CerpenHozin-$keepTag.apk")) {
                    f.delete()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "hapus APK lama gagal", e)
        }
    }

    /**
     * Unduh APK dari [url] ke cache. [onProgress] dipanggil dari thread
     * background dengan persen 0-100 (-1 bila total tidak diketahui).
     * Kembalikan file bila lengkap, null bila gagal (file parsial dihapus).
     * Berhenti bila [isCancelled] true.
     *
     * Keamanan: URL awal HARUS HTTPS + host terpercaya, dan SETIAP
     * redirect diikuti manual (maks [ApkUpdateVerification.MAX_REDIRECTS])
     * dengan validasi yang sama — unduhan GitHub me-redirect dari
     * github.com ke host aset resminya. Redirect ke host/protokol lain
     * dibatalkan. DownloadManager tidak dipakai justru karena ia tidak
     * mengekspos URL akhir redirect.
     */
    fun downloadApk(
        context: Context,
        tag: String,
        url: String,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean
    ): File? {
        if (!ApkUpdateVerification.isTrustedUpdateUrl(url)) {
            Log.w(TAG, "URL update tidak dipercaya, unduhan dibatalkan")
            return null
        }
        val dest = apkFileFor(context, tag)
        deleteOldApks(context, keepTag = tag)
        // Semua kegagalan di bawah menghapus file parsial.
        fun fail(): File? {
            try {
                dest.delete()
            } catch (ignored: Exception) {
            }
            return null
        }
        var conn: HttpURLConnection? = null
        return try {
            var currentUrl = url
            var redirects = 0
            while (true) {
                val c = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    // Redirect diikuti manual supaya tiap Location divalidasi.
                    instanceFollowRedirects = false
                    connectTimeout = 10000
                    readTimeout = 30000
                    setRequestProperty("User-Agent", "CerpenHozin/1.1")
                }
                conn = c
                val code = c.responseCode
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    if (redirects >= ApkUpdateVerification.MAX_REDIRECTS) return fail()
                    val next = ApkUpdateVerification.resolveRedirect(
                        currentUrl, c.getHeaderField("Location")
                    )
                    c.disconnect()
                    conn = null
                    if (next == null) return fail()
                    currentUrl = next
                    redirects++
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) return fail()
                break
            }
            val total = conn!!.contentLengthLong
            var done = 0L
            var lastPercent = -1
            conn!!.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        // Batal di tengah unduhan: hapus file parsial
                        // (sebelumnya lolos — file setengah jadi bisa
                        // terpasang nanti lewat jalur APK cache).
                        if (isCancelled()) return fail()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = ((done * 100) / total).toInt().coerceIn(0, 100)
                            if (p != lastPercent) {
                                lastPercent = p
                                onProgress(p)
                            }
                        } else {
                            onProgress(-1)
                        }
                    }
                }
            }
            if (isCancelled()) return fail()
            if (total > 0 && done < total) return fail()
            if (dest.length() == 0L) return fail()
            dest
        } catch (e: Exception) {
            Log.w(TAG, "unduh APK gagal", e)
            fail()
        } finally {
            conn?.disconnect()
        }
    }

    /** Apakah aplikasi ini diizinkan instal dari sumber tak dikenal (API 26+). */
    fun canInstall(context: Context): Boolean {
        return context.packageManager.canRequestPackageInstalls()
    }

    /** Buka halaman Settings "instal aplikasi tak dikenal" untuk aplikasi ini. */
    fun openUnknownSourcesSettings(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "buka settings unknown sources gagal", e)
        }
    }

    /**
     * Picu instalasi APK yang sudah terunduh via FileProvider. Kembalikan
     * false bila tidak ada aplikasi installer yang bisa menangani intent
     * (pemanggil memakai fallback browser).
     */
    fun installApk(context: Context, apk: File): Boolean {
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apk
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "picu instalasi gagal", e)
            false
        }
    }

    // ============ Verifikasi APK sebelum instalasi ============

    /** Package + digest SHA-256 (hex lowercase) sertifikat penandatangan. */
    data class SignerInfo(
        val packageName: String?,
        val signerDigests: Set<String>,
        val signerHistoryDigests: Set<String>
    )

    private fun sha256Hex(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        return d.joinToString("") { "%02x".format(it) }
    }

    /** Signer aplikasi terpasang (current + riwayat sertifikat). Null bila tidak bisa dibaca. */
    fun installedSignerInfo(context: Context): SignerInfo? {
        return try {
            val pm = context.packageManager
            val pkg = context.packageName
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                val si = info.signingInfo ?: return null
                SignerInfo(
                    pkg,
                    si.apkContentsSigners?.map { sha256Hex(it.toByteArray()) }?.toSet().orEmpty(),
                    si.signingCertificateHistory?.map { sha256Hex(it.toByteArray()) }?.toSet().orEmpty()
                )
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
                val sigs = info.signatures?.map { sha256Hex(it.toByteArray()) }?.toSet().orEmpty()
                // API 26-27 tidak punya riwayat sertifikat — current = lineage.
                SignerInfo(pkg, sigs, sigs)
            }
        } catch (e: Exception) {
            Log.w(TAG, "baca signer terpasang gagal", e)
            null
        }
    }

    /** Package + signer satu file APK (tanpa instalasi). Null bila file bukan APK valid. */
    fun apkSignerInfo(context: Context, apk: File): SignerInfo? {
        return try {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageArchiveInfo(
                    apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES
                ) ?: return null
                val si = info.signingInfo ?: return null
                SignerInfo(
                    info.packageName,
                    si.apkContentsSigners?.map { sha256Hex(it.toByteArray()) }?.toSet().orEmpty(),
                    si.signingCertificateHistory?.map { sha256Hex(it.toByteArray()) }?.toSet().orEmpty()
                )
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageArchiveInfo(
                    apk.absolutePath, PackageManager.GET_SIGNATURES
                ) ?: return null
                val sigs = info.signatures?.map { sha256Hex(it.toByteArray()) }?.toSet().orEmpty()
                SignerInfo(info.packageName, sigs, sigs)
            }
        } catch (e: Exception) {
            Log.w(TAG, "baca signer APK gagal", e)
            null
        }
    }

    /** SHA-256 satu file (streaming). Null bila tidak bisa dibaca. */
    fun sha256OfFile(file: File): ByteArray? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            digest.digest()
        } catch (e: Exception) {
            Log.w(TAG, "hitung SHA-256 APK gagal", e)
            null
        }
    }

    /**
     * Verifikasi APK terunduh SEBELUM diberikan ke installer (dipanggil
     * dari thread background — membaca file + hash ±30 MB).
     *
     * Urutan: file bisa dibaca -> ukuran cocok (bila diketahui) -> digest
     * SHA-256 cocok (bila disediakan API; hilang = lewati, salah format
     * = gagal) -> package sama -> signer ada di lineage terpasang.
     * Gagal apa pun = [ApkUpdateVerification.Result.Failed]; pemanggil
     * HARUS menghapus file dan TIDAK memanggil installer. Digest yang
     * hilang/salah format TIDAK PERNAH membuat pemeriksaan signer dilewati.
     */
    fun verifyApk(
        context: Context,
        apk: File,
        expectedDigest: String?,
        expectedSize: Long
    ): ApkUpdateVerification.Result {
        if (!apk.exists() || apk.length() == 0L) {
            return ApkUpdateVerification.Result.Failed(ApkUpdateVerification.Reason.FileUnreadable)
        }
        if (!ApkUpdateVerification.sizeMatches(apk.length(), expectedSize)) {
            return ApkUpdateVerification.Result.Failed(ApkUpdateVerification.Reason.SizeMismatch)
        }
        val fileSha = sha256OfFile(apk)
            ?: return ApkUpdateVerification.Result.Failed(ApkUpdateVerification.Reason.FileUnreadable)
        if (!ApkUpdateVerification.verifyDigest(expectedDigest, fileSha)) {
            return ApkUpdateVerification.Result.Failed(ApkUpdateVerification.Reason.DigestMismatch)
        }
        val installed = installedSignerInfo(context)
            ?: return ApkUpdateVerification.Result.Failed(ApkUpdateVerification.Reason.SignerMismatch)
        val apkInfo = apkSignerInfo(context, apk)
            ?: return ApkUpdateVerification.Result.Failed(ApkUpdateVerification.Reason.SignerMismatch)
        if (apkInfo.packageName != installed.packageName) {
            return ApkUpdateVerification.Result.Failed(ApkUpdateVerification.Reason.PackageMismatch)
        }
        if (!ApkUpdateVerification.signerLineageMatches(
                apkInfo.signerDigests, apkInfo.signerHistoryDigests,
                installed.signerDigests, installed.signerHistoryDigests
            )
        ) {
            return ApkUpdateVerification.Result.Failed(ApkUpdateVerification.Reason.SignerMismatch)
        }
        return ApkUpdateVerification.Result.Ok
    }

    /**
     * Fallback: buka browser HANYA ke halaman Releases GitHub resmi
     * (URL tetap). URL arbitrer dari data update TIDAK PERNAH
     * diteruskan ke browser.
     */
    fun openInBrowser(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(UpdateCheck.RELEASES_PAGE_URL)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "buka browser gagal", e)
        }
    }
}
