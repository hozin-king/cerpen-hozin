package com.hozinking.cerpen

import android.animation.ValueAnimator
import android.text.Spanned
import android.view.animation.LinearInterpolator
import android.widget.TextView

/**
 * v1.1.18: pengatur denyut halus untuk kata berwarna di sebuah TextView.
 *
 * Satu animator per TextView; hanya dibuat bila teksnya mengandung
 * PulseColorSpan (kata <font color>). Tanpa kata berwarna, tidak ada
 * animator — tidak ada biaya apa pun.
 * Denyut: alpha 0.45 (redup) -> 1.0 (penuh), bolak-balik tiap 2,2 detik.
 * WAJIB dihentikan (stop) saat Activity dijeda/dihancurkan supaya tidak
 * jalan terus di latar dan tidak boros baterai.
 */
object PulseText {

    fun start(textView: TextView): ValueAnimator? {
        val text = textView.text as? Spanned ?: return null
        val spans = text.getSpans(0, text.length, PulseColorSpan::class.java)
        if (spans.isEmpty()) return null
        val animator = ValueAnimator.ofFloat(0.45f, 1f).apply {
            duration = 2200
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                for (span in spans) span.alphaFactor = f
                textView.invalidate()
            }
        }
        animator.start()
        return animator
    }

    fun stop(animator: ValueAnimator?) {
        animator?.cancel()
    }
}
