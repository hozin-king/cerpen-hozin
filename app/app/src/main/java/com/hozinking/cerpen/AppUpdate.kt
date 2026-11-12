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
     * SHA-256 sertifikat penandatangan APK terpasang (v1.1.23, anti-bypass).
     * Null bila tidak bisa dibaca — pemanggil menganggap cocok (fail-open).
     */
    fun installedSignerSha256(context: Context): String? {
        return try {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            }
            val signers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                info.signatures
            }
            val first = signers?.firstOrNull() ?: return null
            val digest = MessageDigest.getInstance("SHA-256").digest(first.toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.w(TAG, "baca signer gagal", e)
            null
        }
    }

    /**
     * Hapus APK unduhan yang instalnya SUDAH terkonfirmasi sukses
     * (versi terpasang >= versi tag APK). Dipanggil tiap buka aplikasi —
     * instal sukses = proses aplikasi mati lalu dibuka lagi sebagai versi
     * baru. Gagal/batal instal = versi terpasang masih lama, file TETAP
     * ada untuk retry. Kembalikan jumlah file yang dihapus.
     */
    fun deleteInstalledApks(context: Context, installedVersion: String): Int {
        var deleted = 0
        try {
            val dir = File(context.cacheDir, "updates")
            dir.listFiles()?.forEach { f ->
                if (!f.name.endsWith(".apk")) return@forEach
                val tag = f.name.removePrefix("CerpenHozin-").removeSuffix(".apk")
                if (UpdateCheck.shouldDeleteDownloadedApk(installedVersion, tag) && f.delete()) {
                    deleted++
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "hapus APK terinstal gagal", e)
        }
        return deleted
    }

    /**
     * Unduh APK dari [url] ke cache. [onProgress] dipanggil dari thread
     * background dengan persen 0-100 (-1 bila total tidak diketahui).
     * Kembalikan file bila lengkap, null bila gagal (file parsial dihapus).
     * Berhenti bila [isCancelled] true.
     */
    fun downloadApk(
        context: Context,
        tag: String,
        url: String,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean
    ): File? {
        val dest = apkFileFor(context, tag)
        deleteOldApks(context, keepTag = tag)
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 30000
                setRequestProperty("User-Agent", "CerpenHozin/1.1")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val total = conn.contentLengthLong
            var done = 0L
            var lastPercent = -1
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        if (isCancelled()) return null
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
            if (isCancelled()) {
                dest.delete()
                return null
            }
            if (total > 0 && done < total) {
                dest.delete()
                return null
            }
            if (dest.length() == 0L) {
                dest.delete()
                return null
            }
            dest
        } catch (e: Exception) {
            Log.w(TAG, "unduh APK gagal", e)
            try {
                dest.delete()
            } catch (ignored: Exception) {
            }
            null
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

    /** Fallback: buka browser ke URL unduh APK (atau halaman rilis). */
    fun openInBrowser(context: Context, apkUrl: String?) {
        val url = apkUrl ?: UpdateCheck.RELEASES_PAGE_URL
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "buka browser gagal", e)
        }
    }
}
