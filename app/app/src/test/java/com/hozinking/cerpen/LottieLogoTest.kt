package com.hozinking.cerpen

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Uji JSON logo splash Lottie (v1.1.38): file harus ada, ringan
 * (<100 KB, tanpa gambar eksternal), struktur Lottie valid, dan
 * loop-nya mulus (keyframe terakhir == keyframe pertama — bila
 * tidak, animasi "melompat" tiap putaran).
 *
 * Catatan: ini uji STRUKTUR, bukan render — parser Lottie asli
 * (lottie-android) hanya jalan di perangkat/emulator. Bila JSON
 * rusak di runtime, SplashActivity menampilkan ikon buku statis.
 */
class LottieLogoTest {

    private fun logoFile(): File {
        val candidates = listOf(
            "src/main/res/raw/logo_splash.json",
            "app/src/main/res/raw/logo_splash.json"
        )
        for (path in candidates) {
            val f = File(path)
            if (f.exists()) return f
        }
        fail("logo_splash.json tidak ditemukan (dicoba: ${candidates.joinToString()}; " +
            "working dir unit test = ${File(".").absolutePath})")
        throw IllegalStateException("unreachable")
    }

    private fun logoJson(): JSONObject = JSONObject(logoFile().readText(Charsets.UTF_8))

    @Test
    fun `file ada dan ringan di bawah 100KB`() {
        val f = logoFile()
        assertTrue("file harus ada", f.exists())
        assertTrue("JSON harus < 100KB (aktual ${f.length()} B)", f.length() < 100 * 1024)
    }

    @Test
    fun `struktur Lottie valid 240x240 30fps 60 frame`() {
        val json = logoJson()
        assertTrue("versi Lottie 5.x", json.getString("v").startsWith("5."))
        assertEquals(30, json.getInt("fr"))
        assertEquals(0, json.getInt("ip"))
        assertEquals(60, json.getInt("op"))
        assertEquals(240, json.getInt("w"))
        assertEquals(240, json.getInt("h"))
        // Tanpa aset eksternal (tanpa gambar berat).
        assertEquals(0, json.getJSONArray("assets").length())
        val layers = json.getJSONArray("layers")
        assertTrue("minimal 1 layer", layers.length() >= 1)
        for (i in 0 until layers.length()) {
            val layer = layers.getJSONObject(i)
            // Hanya layer bentuk (ty 4) — tanpa layer gambar (ty 2).
            assertEquals("layer ${layer.optString("nm")} harus shape layer", 4, layer.getInt("ty"))
            assertEquals(0, layer.getInt("ip"))
            assertEquals(60, layer.getInt("op"))
            assertTrue("layer harus punya shapes", layer.getJSONArray("shapes").length() >= 1)
        }
    }

    @Test
    fun `loop mulus - keyframe terakhir sama dengan pertama`() {
        val json = logoJson()
        val layers = json.getJSONArray("layers")
        for (i in 0 until layers.length()) {
            val ks = layers.getJSONObject(i).getJSONObject("ks")
            for (prop in listOf("s", "p")) {
                if (!ks.has(prop)) continue
                val node = ks.getJSONObject(prop)
                if (!node.optBoolean("a", false)) continue
                val frames = node.getJSONArray("k")
                val first = frames.getJSONObject(0)
                val last = frames.getJSONObject(frames.length() - 1)
                assertEquals(
                    "layer ${layers.getJSONObject(i).optString("nm")} properti $prop: " +
                        "keyframe akhir harus sama dengan awal (loop mulus)",
                    first.getJSONArray("s").toString(),
                    last.getJSONArray("s").toString()
                )
            }
        }
    }
}
