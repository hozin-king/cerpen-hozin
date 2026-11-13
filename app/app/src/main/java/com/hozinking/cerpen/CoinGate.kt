package com.hozinking.cerpen

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logika koin murni (v1.1.30) — tanpa dependensi Android supaya bisa
 * di-unit-test di JVM.
 *
 * Cerminan persis aturan server (coins.js):
 * - harga: override per series/komik, else default; episode GRATIS bila
 *   nomor episode <= free_episodes (default 1). Season TIDAK masuk
 *   aturan gratis — episode no. 1 di season mana pun gratis.
 * - unlock: per (kind, target_id, season, no), permanen per pemilik.
 * - VIP: selama aktif, semua episode terkunci gratis (server yang
 *   memutuskan; aplikasi hanya menampilkan dari cache /me).
 * - satuan waktu: VIP expires_at = milidetik; ban expires_at = DETIK.
 */
object CoinGate {

    data class PriceOverride(val price: Int, val freeEpisodes: Int)

    data class Pricing(
        val defaultPrice: Int,
        val freeEpisodesDefault: Int,
        val overrides: Map<String, PriceOverride>
    ) {
        companion object {
            val DEFAULT = Pricing(10, 1, emptyMap())
        }
    }

    /** Snapshot status koin aplikasi (dibangun CoinStore dari cache + /me). */
    data class CoinState(
        val balance: Int = 0,
        val unlocks: Set<String> = emptySet(),
        val vipExpiresAtMs: Long = 0L,
        val pricing: Pricing = Pricing.DEFAULT,
        val hasSession: Boolean = false,
        val username: String? = null,
        val userId: String? = null,
        val readingPrivate: Boolean = false,
        val balanceUpdatedAtMs: Long = 0L
    )

    data class Price(val price: Int, val free: Boolean)

    /** Kunci unlock lokal: "kind:targetId:season:no" (sama dengan server). */
    fun unlockKey(kind: String, targetId: String, season: Int, no: Int): String =
        "$kind:$targetId:$season:$no"

    /** Parse pricing dari /me atau /pricing. Rusak / null -> DEFAULT. */
    fun parsePricing(obj: JSONObject?): Pricing {
        if (obj == null) return Pricing.DEFAULT
        val defPrice = obj.optInt("default_price", 10).coerceAtLeast(0)
        val defFree = obj.optInt("free_episodes_default", 1).coerceAtLeast(0)
        val overrides = HashMap<String, PriceOverride>()
        val ov = obj.optJSONObject("overrides")
        if (ov != null) {
            val keys = ov.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val o = ov.optJSONObject(k) ?: continue
                overrides[k] = PriceOverride(
                    price = o.optInt("price", defPrice).coerceAtLeast(0),
                    freeEpisodes = o.optInt("free_episodes", defFree).coerceAtLeast(0)
                )
            }
        }
        return Pricing(defPrice, defFree, overrides)
    }

    /** Cerminan priceFor() server — HARUS sama persis. */
    fun priceFor(p: Pricing, kind: String, targetId: String, no: Int): Price {
        val ov = p.overrides["$kind:$targetId"]
        val price = ov?.price ?: p.defaultPrice
        val freeN = ov?.freeEpisodes ?: p.freeEpisodesDefault
        return Price(price, no <= freeN)
    }

    fun isVipActive(state: CoinState, nowMs: Long): Boolean =
        state.vipExpiresAtMs > 0 && state.vipExpiresAtMs > nowMs

    sealed class LockState {
        /** Episode gratis (nomor <= free_episodes). */
        object Free : LockState()

        /** Sudah dibuka (unlock cache / dibaca lokal sebelum v1.1.30). */
        object Unlocked : LockState()

        /** VIP aktif — gratis, tapi unlock dicatat server via /purchase. */
        object Vip : LockState()

        /** Terkunci — butuh beli sebesar [price] koin. */
        data class Locked(val price: Int) : LockState()
    }

    /**
     * Status satu episode dari cache lokal.
     * [locallyRead] = episode ini sudah dibaca di perangkat ini sebelum
     * sistem koin (riwayat lokal ProgressStore / komik lokal) — dihitung
     * sebagai sudah dimiliki, supaya konten yang dulu gratis tidak
     * tiba-tiba terkunci.
     */
    fun lockState(
        state: CoinState,
        kind: String,
        targetId: String,
        season: Int,
        no: Int,
        locallyRead: Boolean,
        nowMs: Long
    ): LockState {
        val key = unlockKey(kind, targetId, season, no)
        if (key in state.unlocks || locallyRead) return LockState.Unlocked
        val p = priceFor(state.pricing, kind, targetId, no)
        if (p.free) return LockState.Free
        if (isVipActive(state, nowMs)) return LockState.Vip
        return LockState.Locked(p.price)
    }

    /** "12 hari lagi" / "3 jam lagi" / "45 menit lagi", null bila tidak aktif. */
    fun vipRemainingText(expiresAtMs: Long, nowMs: Long): String? {
        val left = expiresAtMs - nowMs
        if (left <= 0) return null
        val days = left / (24L * 60 * 60 * 1000)
        if (days >= 1) return "$days hari lagi"
        val hours = left / (60L * 60 * 1000)
        if (hours >= 1) return "$hours jam lagi"
        val minutes = (left / (60L * 1000)).coerceAtLeast(1)
        return "$minutes menit lagi"
    }

    /** "baru saja" / "5 menit lalu" / "2 jam lalu" / "9 Nov 2026". */
    fun updatedAtText(updatedAtMs: Long, nowMs: Long): String {
        if (updatedAtMs <= 0) return "belum pernah"
        val age = nowMs - updatedAtMs
        if (age < 60_000) return "baru saja"
        val minutes = age / 60_000
        if (minutes < 60) return "$minutes menit lalu"
        val hours = minutes / 60
        if (hours < 24) return "$hours jam lalu"
        return try {
            SimpleDateFormat("d MMM yyyy", Locale("id", "ID")).format(Date(updatedAtMs))
        } catch (e: Exception) {
            "lama"
        }
    }

    /** ID pendek untuk header drawer: 8 karakter pertama. */
    fun shortId(id: String): String =
        if (id.length <= 12) id else id.take(8) + "…"

    /**
     * Pesan ban dari respons server ({error:'banned', reason, expires_at
     * detik}). Null bila bukan respons ban. expires_at detik -> ms.
     */
    fun bannedMessage(json: JSONObject?): String? {
        if (json == null || json.optString("error") != "banned") return null
        val reason = json.optString("reason", "").trim()
        val expSec = json.optLong("expires_at", 0)
        val until = if (expSec > 0) {
            try {
                " sampai " + SimpleDateFormat("d MMM yyyy HH:mm", Locale("id", "ID"))
                    .format(Date(expSec * 1000))
            } catch (e: Exception) {
                ""
            }
        } else {
            " (permanen)"
        }
        val base = "Akun ini dibanned"
        return if (reason.isNotEmpty()) "$base: $reason$until" else base + until
    }
}
