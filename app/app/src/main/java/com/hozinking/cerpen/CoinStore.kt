package com.hozinking.cerpen

import android.content.Context
import android.provider.Settings
import java.util.UUID

/**
 * Penyimpanan lokal koin/akun (v1.1.30).
 *
 * SharedPreferences "cerpen_coin" — BUKAN encrypted prefs (belum ada
 * dependensi androidx.security di proyek; data di sini bukan rahasia:
 * saldo & unlock adalah CACHE, server tetap otoritas. Device ID adalah
 * identifier biasa, bukan secret. Bila nanti mau, pindah ke
 * EncryptedSharedPreferences tanpa ubah pemanggil.)
 *
 * Guest: device_id UUID dibuat sekali, bertahan walau aplikasi di-update.
 * HAPUS DATA aplikasi = device_id baru (celah yang didokumentasikan di
 * DESIGN.md; ANDROID_ID dipakai sebagai sidik sekunder di server).
 */
object CoinStore {
    private const val PREFS = "cerpen_coin"
    private const val K_DEVICE_ID = "device_id"
    private const val K_SESSION = "session"
    private const val K_USERNAME = "username"
    private const val K_OWNER_KEY = "owner_key"
    private const val K_BALANCE = "balance"
    private const val K_BALANCE_AT = "balance_at"
    private const val K_UNLOCKS = "unlocks"
    private const val K_PRICING_JSON = "pricing_json"
    private const val K_VIP_EXPIRES_AT = "vip_expires_at"
    private const val K_VERIFIED = "verified"
    private const val K_READING_PRIVATE = "reading_private"
    private const val K_KOMIK_READ = "komik_read"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** ID perangkat guest — UUID, dibuat sekali dan disimpan. */
    fun deviceId(ctx: Context): String {
        val p = prefs(ctx)
        var id = p.getString(K_DEVICE_ID, null)
        if (id.isNullOrBlank()) {
            id = UUID.randomUUID().toString()
            p.edit().putString(K_DEVICE_ID, id).apply()
        }
        return id
    }

    /** Sidik sekunder (ANDROID_ID); "" bila tidak tersedia. */
    fun androidId(ctx: Context): String = try {
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
    } catch (e: Exception) {
        ""
    }

    fun session(ctx: Context): String? = prefs(ctx).getString(K_SESSION, null)
    fun hasSession(ctx: Context): Boolean = !session(ctx).isNullOrBlank()
    fun username(ctx: Context): String? = prefs(ctx).getString(K_USERNAME, null)

    /** "user:u-..." dari /me; null = guest. */
    fun ownerKey(ctx: Context): String? = prefs(ctx).getString(K_OWNER_KEY, null)

    /** ID akun mentah ("u-..."), diturunkan dari owner_key. */
    fun userId(ctx: Context): String? {
        val k = ownerKey(ctx) ?: return null
        return k.removePrefix("user:")
    }

    fun snapshot(ctx: Context): CoinGate.CoinState {
        val p = prefs(ctx)
        val pricingJson = p.getString(K_PRICING_JSON, null)
        val pricing = try {
            CoinGate.parsePricing(
                if (pricingJson.isNullOrBlank()) null
                else org.json.JSONObject(pricingJson)
            )
        } catch (e: Exception) {
            CoinGate.Pricing.DEFAULT
        }
        return CoinGate.CoinState(
            balance = p.getInt(K_BALANCE, 0),
            unlocks = p.getStringSet(K_UNLOCKS, emptySet()) ?: emptySet(),
            vipExpiresAtMs = p.getLong(K_VIP_EXPIRES_AT, 0),
            pricing = pricing,
            hasSession = hasSession(ctx),
            username = username(ctx),
            userId = userId(ctx),
            verified = p.getBoolean(K_VERIFIED, false),
            readingPrivate = p.getBoolean(K_READING_PRIVATE, false),
            balanceUpdatedAtMs = p.getLong(K_BALANCE_AT, 0)
        )
    }

    /** Simpan hasil /me (dipanggil CoinApi.fetchMe setelah sukses). */
    fun saveMe(ctx: Context, me: CoinApi.MeResult) {
        val p = prefs(ctx)
        val ed = p.edit()
        ed.putInt(K_BALANCE, me.balance)
        ed.putLong(K_BALANCE_AT, System.currentTimeMillis())
        ed.putStringSet(K_UNLOCKS, me.unlocks)
        ed.putLong(K_VIP_EXPIRES_AT, if (me.vipActive) me.vipExpiresAtMs else 0L)
        ed.putBoolean(K_VERIFIED, me.verified)
        ed.putBoolean(K_READING_PRIVATE, me.readingPrivate)
        if (me.pricingJson != null) ed.putString(K_PRICING_JSON, me.pricingJson)
        if (me.username != null) ed.putString(K_USERNAME, me.username)
        if (me.ownerKey != null) ed.putString(K_OWNER_KEY, me.ownerKey)
        ed.apply()
    }

    /** Simpan sesi setelah login/verifikasi berhasil. */
    fun setSession(ctx: Context, session: String, username: String?, ownerKey: String?) {
        val ed = prefs(ctx).edit()
        ed.putString(K_SESSION, session)
        if (username != null) ed.putString(K_USERNAME, username)
        if (ownerKey != null) ed.putString(K_OWNER_KEY, ownerKey)
        ed.apply()
    }

    /** Keluar: hapus sesi + data akun; device_id + cache guest tetap. */
    fun clearSession(ctx: Context) {
        val ed = prefs(ctx).edit()
        ed.remove(K_SESSION)
        ed.remove(K_USERNAME)
        ed.remove(K_OWNER_KEY)
        ed.remove(K_VIP_EXPIRES_AT)
        ed.remove(K_VERIFIED)
        ed.putBoolean(K_READING_PRIVATE, false)
        ed.apply()
    }

    fun addUnlock(ctx: Context, key: String) {
        val p = prefs(ctx)
        val set = (p.getStringSet(K_UNLOCKS, emptySet()) ?: emptySet()).toMutableSet()
        set.add(key)
        p.edit().putStringSet(K_UNLOCKS, set).apply()
    }

    fun addUnlocks(ctx: Context, keys: Collection<String>) {
        val p = prefs(ctx)
        val set = (p.getStringSet(K_UNLOCKS, emptySet()) ?: emptySet()).toMutableSet()
        set.addAll(keys)
        p.edit().putStringSet(K_UNLOCKS, set).apply()
    }

    fun setBalance(ctx: Context, balance: Int) {
        prefs(ctx).edit()
            .putInt(K_BALANCE, balance)
            .putLong(K_BALANCE_AT, System.currentTimeMillis())
            .apply()
    }

    fun setReadingPrivate(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean(K_READING_PRIVATE, v).apply()
    }

    /** Episode komik yang sudah dibaca lokal (grandfathering, lihat CoinGate). */
    fun markKomikRead(ctx: Context, komikId: String, no: Int) {
        val p = prefs(ctx)
        val set = (p.getStringSet(K_KOMIK_READ, emptySet()) ?: emptySet()).toMutableSet()
        set.add("$komikId|$no")
        p.edit().putStringSet(K_KOMIK_READ, set).apply()
    }

    fun isKomikReadLocally(ctx: Context, komikId: String, no: Int): Boolean {
        val set = prefs(ctx).getStringSet(K_KOMIK_READ, emptySet()) ?: emptySet()
        return "$komikId|$no" in set
    }
}
