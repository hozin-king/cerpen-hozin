package com.hozinking.cerpen

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Deteksi emulator + root + integritas perangkat (paket admin).
 *
 * ATURAN KERAS: HANYA LAPOR, JANGAN BLOKIR. Aplikasi HARUS tetap jalan
 * normal di perangkat root (HP user sendiri root — KernelSU) dan di
 * perangkat yang gagal integritas. Hasil deteksi dikirim ke server
 * (register/heartbeat) sebagai SINYAL untuk panel admin; soal banned
 * diputuskan user manual dari panel. TIDAK ADA keputusan apa pun di
 * aplikasi berdasarkan hasil ini.
 *
 * - Root: file su di jalur umum + Build.TAGS test-keys + paket
 *   manajemen root terinstal (perlu <queries> di manifest, Android 11+).
 * - Emulator: properti Build (fingerprint/model/manufacturer/hardware/
 *   product) — fungsi murni [emulatorHintsFor] supaya bisa di-unit-test.
 * - Integritas lokal: status bootloader + verified boot lewat
 *   SystemProperties (reflection, gagal -> null = "tidak diketahui").
 *   Token Play Integrity diminta async; bila Play Services tidak ada
 *   / API belum disiapkan -> null ("tidak tersedia"), aplikasi lanjut
 *   normal. Verifikasi token dilakukan SERVER, bukan di sini.
 */
object DeviceSignals {
    private const val TAG = "DeviceSignals"

    data class Signals(
        val rooted: Boolean,
        val rootMethods: List<String>,
        val emulator: Boolean,
        val emulatorHints: List<String>,
        val bootloaderLocked: Boolean?,
        val verifiedBoot: String?
    )

    // Jalur su yang umum (root pasif — hanya cek keberadaan file).
    private val SU_PATHS = listOf(
        "/system/app/Superuser.apk",
        "/system/xbin/su",
        "/system/bin/su",
        "/sbin/su",
        "/system/sd/xbin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/data/local/su",
        "/su/bin/su",
        "/system/bin/failsafe/su"
    )

    // Paket manajemen root yang umum (dicek via PackageManager).
    private val ROOT_PACKAGES = listOf(
        "eu.chainfire.supersu",
        "com.noshufou.android.su",
        "com.koushikdutta.superuser",
        "com.thirdparty.superuser",
        "com.yellowes.su",
        "com.topjohnwu.magisk",
        "io.github.vvb2060.magisk",
        "me.weishu.kernelsu",
        "com.kingroot.kinguser",
        "com.kingo.root"
    )

    // ---------- fungsi murni (unit-testable) ----------

    /** Metode root yang terdeteksi dari jalur su + tags + paket terinstal. */
    fun rootMethodsFor(
        existingPaths: Set<String>,
        tags: String?,
        installedPackages: Set<String>
    ): List<String> {
        val out = ArrayList<String>()
        val foundSu = SU_PATHS.filter { it in existingPaths }
        if (foundSu.isNotEmpty()) out.add("su:" + foundSu.first())
        if (tags != null && tags.contains("test-keys")) out.add("test-keys")
        val foundPkg = ROOT_PACKAGES.filter { it in installedPackages }
        if (foundPkg.isNotEmpty()) out.add("pkg:" + foundPkg.first())
        return out
    }

    /** Petunjuk emulator dari properti Build (kosong = bukan emulator). */
    fun emulatorHintsFor(
        fingerprint: String,
        model: String,
        manufacturer: String,
        hardware: String,
        product: String
    ): List<String> {
        val out = ArrayList<String>()
        val fp = fingerprint.lowercase()
        val md = model.lowercase()
        val mf = manufacturer.lowercase()
        val hw = hardware.lowercase()
        val pr = product.lowercase()
        if (fp.startsWith("generic") || fp.contains("generic")) out.add("fingerprint:generic")
        if (md.contains("google_sdk") || md.contains("emulator") || md.contains("android sdk")) {
            out.add("model:$model")
        }
        if (mf.contains("genymotion")) out.add("manufacturer:genymotion")
        if (hw.contains("goldfish") || hw.contains("ranchu")) out.add("hardware:$hardware")
        if (pr == "sdk" || pr.contains("google_sdk") || pr.contains("sdk_x86") || pr == "sdk_gphone64_arm64") {
            out.add("product:$product")
        }
        return out
    }

    /** "locked" -> true, "unlocked" -> false, lainnya/null -> null. */
    fun parseBootLocked(value: String?): Boolean? = when (value?.trim()?.lowercase()) {
        "locked" -> true
        "unlocked" -> false
        else -> null
    }

    /** Sinyal -> JSON ringkas untuk body register/heartbeat. */
    fun toJson(s: Signals): JSONObject = JSONObject().apply {
        put("rooted", s.rooted)
        put("root_methods", JSONArray(s.rootMethods))
        put("emulator", s.emulator)
        put("emulator_hints", JSONArray(s.emulatorHints))
        if (s.bootloaderLocked == null) put("bootloader_locked", JSONObject.NULL)
        else put("bootloader_locked", s.bootloaderLocked)
        put("verified_boot", s.verifiedBoot ?: JSONObject.NULL)
    }

    // ---------- kolektor Android ----------

    private fun systemProperty(name: String): String? = try {
        val clazz = Class.forName("android.os.SystemProperties")
        val m = clazz.getMethod("get", String::class.java)
        (m.invoke(null, name) as? String)?.ifEmpty { null }
    } catch (e: Exception) {
        null
    }

    /** Kumpulkan semua sinyal (ringan, tanpa network). */
    fun collect(ctx: Context): Signals {
        val existing = SU_PATHS.filter { File(it).exists() }.toSet()
        val installed = HashSet<String>()
        val pm = ctx.applicationContext.packageManager
        for (pkg in ROOT_PACKAGES) {
            try {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
                installed.add(pkg)
            } catch (e: PackageManager.NameNotFoundException) {
                // Tidak terinstal — normal.
            } catch (e: Exception) {
                // Android 11+ tanpa <queries> melempar di sini — abaikan.
            }
        }
        val methods = rootMethodsFor(existing, Build.TAGS, installed)
        val hints = emulatorHintsFor(
            Build.FINGERPRINT ?: "",
            Build.MODEL ?: "",
            Build.MANUFACTURER ?: "",
            Build.HARDWARE ?: "",
            Build.PRODUCT ?: ""
        )
        return Signals(
            rooted = methods.isNotEmpty(),
            rootMethods = methods,
            emulator = hints.isNotEmpty(),
            emulatorHints = hints,
            bootloaderLocked = parseBootLocked(systemProperty("ro.boot.flash.locked")),
            verifiedBoot = systemProperty("ro.boot.verifiedbootstate")
        )
    }

    /** Token Play Integrity terakhir yang masih segar (null bila belum ada). */
    fun integrityToken(ctx: Context): String? = AdminStore.integrityToken(ctx)

    /**
     * Minta token Play Integrity (async, tidak memblokir). Nonce bebas
     * (dipakai device_id guest). Gagal / tidak tersedia -> onResult(null).
     * Token yang didapat disimpan (AdminStore) untuk heartbeat/register
     * berikutnya; verifikasi dilakukan server.
     */
    fun requestIntegrityToken(ctx: Context, nonce: String, onResult: (String?) -> Unit) {
        try {
            val manager = IntegrityManagerFactory.create(ctx.applicationContext)
            val request = IntegrityTokenRequest.builder().setNonce(nonce).build()
            manager.requestIntegrityToken(request)
                .addOnSuccessListener { resp ->
                    val token = resp.token().ifEmpty { null }
                    if (token != null) AdminStore.saveIntegrityToken(ctx, token)
                    onResult(token)
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Play Integrity gagal: ${e.message}")
                    onResult(null)
                }
        } catch (e: Exception) {
            Log.w(TAG, "Play Integrity tidak tersedia: ${e.message}")
            onResult(null)
        }
    }
}
