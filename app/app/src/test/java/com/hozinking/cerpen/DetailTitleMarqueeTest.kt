package com.hozinking.cerpen

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Uji judul detail marquee (v1.1.39): judul di halaman detail cerpen
 * dan komik (satu layout activity_detail.xml yang sama) harus satu
 * baris + berjalan (marquee), bukan dibungkus dua baris.
 *
 * Pola baca file sama dengan LottieLogoTest (working dir unit test =
 * modul app/app).
 */
class DetailTitleMarqueeTest {

    private fun layoutFile(): File {
        val candidates = listOf(
            "src/main/res/layout/activity_detail.xml",
            "app/src/main/res/layout/activity_detail.xml"
        )
        for (path in candidates) {
            val f = File(path)
            if (f.exists()) return f
        }
        fail("activity_detail.xml tidak ditemukan (dicoba: ${candidates.joinToString()}; " +
            "working dir unit test = ${File(".").absolutePath})")
        throw IllegalStateException("unreachable")
    }

    /** Blok <TextView ... /> untuk id detail_judul. */
    private fun titleBlock(): String {
        val text = layoutFile().readText(Charsets.UTF_8)
        val start = text.indexOf("@+id/detail_judul")
        assertTrue("detail_judul harus ada di layout", start >= 0)
        val tagStart = text.lastIndexOf("<TextView", start)
        val tagEnd = text.indexOf("/>", tagStart)
        assertTrue("blok detail_judul harus tertutup", tagStart >= 0 && tagEnd > tagStart)
        return text.substring(tagStart, tagEnd)
    }

    @Test
    fun `judul detail satu baris marquee berjalan selamanya`() {
        val block = titleBlock()
        assertTrue("harus singleLine", block.contains("android:singleLine=\"true\""))
        assertTrue("harus ellipsize marquee", block.contains("android:ellipsize=\"marquee\""))
        assertTrue(
            "harus marquee_forever",
            block.contains("android:marqueeRepeatLimit=\"marquee_forever\"")
        )
    }

    @Test
    fun `marquee diaktifkan dari kode di kedua halaman detail`() {
        // android:selected BUKAN atribut layout yang valid — isSelected
        // diset di bind() (bila tidak, marquee tidak pernah jalan).
        for (file in listOf("DetailActivity.kt", "KomikDetailActivity.kt")) {
            val candidates = listOf(
                "src/main/java/com/hozinking/cerpen/$file",
                "app/src/main/java/com/hozinking/cerpen/$file"
            )
            val srcFile = candidates.map { File(it) }.firstOrNull { it.exists() }
            if (srcFile == null) {
                fail("$file tidak ditemukan")
                throw IllegalStateException("unreachable")
            }
            assertTrue(
                "$file harus mengaktifkan marquee (judul.isSelected = true)",
                srcFile.readText(Charsets.UTF_8).contains("judul.isSelected = true")
            )
        }
    }

    @Test
    fun `judul detail lebar penuh kolom supaya marquee tahu batasnya`() {
        val block = titleBlock()
        assertTrue(
            "lebar harus match_parent (bukan wrap_content — marquee butuh batas)",
            block.contains("android:layout_width=\"match_parent\"")
        )
    }
}
