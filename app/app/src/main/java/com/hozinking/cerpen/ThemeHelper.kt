package com.hozinking.cerpen

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View

/** Palet warna satu tema baca (dipakai semua layar, bukan hanya Reader). */
data class ThemePalette(
    val bg: Int,
    val ink: Int,
    val inkSoft: Int,
    val muted: Int,
    val titleInk: Int,
    val card: Int,
    val accent: Int,
    /** Warna teks di atas permukaan berisi accent (tombol/tab/chip terpilih). */
    val onAccent: Int,
    val cardBorder: Int
)

/**
 * Tema + ukuran font global. Sumber kebenaran tetap ProgressStore
 * (SharedPreferences "cerpen_progress"); helper ini hanya memetakan
 * nilai tersimpan ke warna/ukuran yang dipakai semua Activity.
 */
object ThemeHelper {

    fun palette(theme: Int): ThemePalette = when (theme) {
        ProgressStore.THEME_NIGHT -> ThemePalette(
            bg = Color.parseColor("#121110"),
            ink = Color.parseColor("#E8E4DE"),
            inkSoft = Color.parseColor("#CFC9C0"),
            muted = Color.parseColor("#A8A29A"),
            titleInk = Color.parseColor("#F5F1EA"),
            card = Color.parseColor("#1E1C1A"),
            accent = Color.parseColor("#2BB59C"),
            onAccent = Color.parseColor("#121110"),
            cardBorder = Color.parseColor("#3A352F")
        )
        ProgressStore.THEME_SEPIA -> ThemePalette(
            bg = Color.parseColor("#F4ECD8"),
            ink = Color.parseColor("#4A4238"),
            inkSoft = Color.parseColor("#4A4238"),
            muted = Color.parseColor("#6B5D4A"),
            titleInk = Color.parseColor("#2E2820"),
            card = Color.parseColor("#FBF6E9"),
            accent = Color.parseColor("#0F766E"),
            onAccent = Color.parseColor("#FFFFFF"),
            cardBorder = Color.parseColor("#E0D5BE")
        )
        else -> ThemePalette( // THEME_DAY — sama persis dengan colors.xml v1.0
            bg = Color.parseColor("#FAF7F2"),
            ink = Color.parseColor("#1C1B1A"),
            inkSoft = Color.parseColor("#3D3A37"),
            muted = Color.parseColor("#6B6560"),
            titleInk = Color.parseColor("#1C1B1A"),
            card = Color.parseColor("#FFFFFF"),
            accent = Color.parseColor("#0F766E"),
            onAccent = Color.parseColor("#FFFFFF"),
            cardBorder = Color.parseColor("#E5DFD6")
        )
    }

    /** Faktor skala font daftar/judul dari ukuran teks baca (17sp = 1.0). */
    fun fontScale(context: Context): Float =
        (ProgressStore.getTextSizeSp(context) / 17f).coerceIn(0.8f, 1.4f)

    fun applyRootBackground(view: View, theme: Int) {
        view.setBackgroundColor(palette(theme).bg)
    }

    /**
     * Background kartu mengikuti tema (isi + border + radius 12dp, sama
     * seperti card_bg.xml). Dipakai semua permukaan kartu — daftar cerpen /
     * berita / riwayat / episode, kolom cari, seksi Lanjutkan Membaca —
     * karena drawable XML statis (card_bg selalu putih, card_accent selalu
     * mint terang) sementara warna teks mengikuti palet: di tema Malam teks
     * terang di atas kartu terang jadi tak terbaca.
     */
    fun cardBackground(context: Context, palette: ThemePalette): GradientDrawable {
        val d = context.resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(palette.card)
            setStroke((1f * d).toInt(), palette.cardBorder)
            cornerRadius = 12f * d
        }
    }

    /**
     * Background bottom dock kaca (v1.1.8): warna kartu tembus pandang
     * + border + radius penuh. Tanpa blur live — kaca dicapai dari
     * translucency statis, jadi tidak ada biaya per-frame.
     */
    fun dockBackground(context: Context, palette: ThemePalette): GradientDrawable {
        val d = context.resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(translucent(palette.card, 0.66f))
            setStroke((1f * d).toInt(), palette.cardBorder)
            cornerRadius = 30f * d
        }
    }

    /** Background bubble Cari: lingkaran accent + garis tepi terang tipis. */
    fun bubbleBackground(context: Context, palette: ThemePalette): GradientDrawable {
        val d = context.resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(palette.accent)
            setStroke((1.5f * d).toInt(), Color.argb(110, 255, 255, 255))
        }
    }

    private fun translucent(color: Int, alpha: Float): Int {
        val a = (255 * alpha).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }
}
