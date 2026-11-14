package com.hozinking.cerpen

import android.animation.ValueAnimator
import android.widget.TextView
import java.util.WeakHashMap

/**
 * Animasi angka rolling digit ala odometer (v1.1.32) — tanpa library
 * animasi berat, cukup ValueAnimator + translateY + alpha.
 *
 * Arah: [direction] +1 = angka NAIK (teks lama meluncur ke atas,
 * teks baru masuk dari bawah); -1 = angka TURUN (kebalikannya);
 * 0 = set langsung tanpa animasi. Animasi HANYA jalan saat nilai
 * benar-benar berubah — pemanggil membandingkan nilai lama vs baru
 * dan mengirim 0 bila sama, jadi tidak ada animasi/angka palsu.
 * Animator lama di view yang sama dibatalkan dulu (penting untuk
 * RecyclerView: holder yang di-recycle tidak membawa animasi sisa).
 */
object RollingText {

    private val animators = WeakHashMap<TextView, ValueAnimator>()

    fun set(tv: TextView, text: String, direction: Int) {
        cancel(tv)
        if (direction == 0 || tv.text.toString() == text || tv.height == 0) {
            tv.translationY = 0f
            tv.alpha = 1f
            tv.text = text
            return
        }
        val h = tv.height.toFloat()
        val dir = if (direction > 0) 1f else -1f
        var swapped = false
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            addUpdateListener { a ->
                val p = (a.animatedValue as Float).coerceIn(0f, 1f)
                if (p < 0.5f) {
                    val q = p * 2f
                    tv.translationY = -dir * h * q
                    tv.alpha = 1f - q
                } else {
                    if (!swapped) {
                        tv.text = text
                        swapped = true
                    }
                    val q = (p - 0.5f) * 2f
                    tv.translationY = dir * h * (1f - q)
                    tv.alpha = q
                }
            }
        }
        animators[tv] = anim
        anim.start()
    }

    /** Batalkan animasi di [tv] + kembalikan posisi/alpha normal. */
    fun cancel(tv: TextView) {
        animators.remove(tv)?.cancel()
        tv.translationY = 0f
        tv.alpha = 1f
    }
}
