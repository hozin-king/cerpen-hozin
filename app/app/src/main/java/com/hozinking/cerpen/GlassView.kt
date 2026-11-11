package com.hozinking.cerpen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.ViewTreeObserver
import kotlin.math.max

/**
 * Latar kaca HIDUP untuk dock (v1.1.12).
 *
 * Cara kerja: view ini menangkap piksel konten di baliknya (backdropView,
 * biasanya ScrollView utama) ke bitmap kecil, lalu menggambar bitmap itu
 * diperbesar dengan filter bilinear — downscale + upscale = blur lembut.
 * Di atasnya digambar tint gradien (sheen atas + isi tembus ~40%) dan
 * garis tepi, jadi ikon/teks di atas kaca tetap tajam.
 *
 * Kenapa bukan View.setRenderEffect(blur): RenderEffect mem-blur ISI
 * view itu sendiri, BUKAN latar di baliknya (terverifikasi dari dok
 * AOSP + riset: backdrop blur asli baru ada di API 37.2 via
 * setBackdropRenderEffect). Memasang blur effect pada pill/bubble
 * hanya akan mem-blur ikon dock — jadi tidak dipakai di sini.
 *
 * Tangkap ulang dipicu saat scroll berhenti (debounce 150 ms), saat
 * konten berubah (MainActivity memanggil refresh()), dan saat posisi /
 * ukuran view berubah. v1.1.15: tangkap ulang JUGA selama scroll aktif,
 * di-throttle tiap ~90 ms (bukan tiap event mentah, supaya tidak jank)
 * — jadi latar di balik dock ikut bergerak saat scroll, bukan diam lalu
 * lompat setelah berhenti. Gagal tangkap = tampilan statis (tint +
 * stroke saja), tidak pernah hitam/crash.
 */
class GlassView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val SHAPE_PILL = 0
        const val SHAPE_OVAL = 1
        /** Skala tangkapan: kecil = blur lembut saat diperbesar. */
        private const val CAPTURE_SCALE = 0.25f
        /** Jeda setelah scroll berhenti sebelum tangkap ulang. */
        private const val SETTLE_MS = 150L
        /** Throttle tangkap selama scroll aktif (v1.1.15): maks ~11x/detik. */
        private const val LIVE_MS = 90L
    }

    var shape = SHAPE_PILL
    /** View yang jadi latar (konten di balik kaca) — diisi MainActivity. */
    var backdropView: View? = null

    private var palette: ThemePalette? = null
    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable { capture() }
    /** Tangkap terjadwal di batas interval throttle (v1.1.15). */
    private val liveRunnable = Runnable {
        capture()
        lastCaptureMs = SystemClock.uptimeMillis()
    }
    private var lastCaptureMs = 0L
    private val scrollListener = ViewTreeObserver.OnScrollChangedListener { onScrollChanged() }
    private var scrollObserver: ViewTreeObserver? = null
    private var backdropBitmap: Bitmap? = null
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(102, 255, 255, 255)
    }
    private val clipPath = Path()
    private val shapeRect = RectF()

    init {
        setWillNotDraw(false)
    }

    /** Pasang palet + bentuk; dipanggil tiap ganti tema. */
    fun setGlass(palette: ThemePalette, shape: Int) {
        this.palette = palette
        this.shape = shape
        rebuildPaints()
        invalidate()
    }

    /** Minta tangkap ulang (debounce) — dipakai MainActivity saat konten berubah. */
    fun scheduleRefresh() {
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, SETTLE_MS)
    }

    /**
     * Scroll berubah (v1.1.15): tangkap ulang juga SELAMA scroll, tapi
     * di-throttle tiap LIVE_MS — cukup lama sejak tangkapan terakhir
     * -> tangkap sekarang; kalau belum -> jadwalkan satu tangkapan di
     * batas interval berikutnya (bukan tiap event mentah). Tangkap akhir
     * saat scroll berhenti tetap via scheduleRefresh() (debounce).
     */
    private fun onScrollChanged() {
        val now = SystemClock.uptimeMillis()
        if (now - lastCaptureMs >= LIVE_MS) {
            handler.removeCallbacks(liveRunnable)
            capture()
            lastCaptureMs = now
        } else {
            handler.removeCallbacks(liveRunnable)
            handler.postDelayed(liveRunnable, LIVE_MS - (now - lastCaptureMs))
        }
        scheduleRefresh()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val observer = viewTreeObserver
        if (observer.isAlive) {
            observer.addOnScrollChangedListener(scrollListener)
            scrollObserver = observer
        }
        post { capture() }
    }

    override fun onDetachedFromWindow() {
        scrollObserver?.let { if (it.isAlive) it.removeOnScrollChangedListener(scrollListener) }
        scrollObserver = null
        handler.removeCallbacks(refreshRunnable)
        handler.removeCallbacks(liveRunnable)
        backdropBitmap?.recycle()
        backdropBitmap = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildClip()
        rebuildPaints()
        scheduleRefresh()
    }

    private fun rebuildClip() {
        shapeRect.set(0f, 0f, width.toFloat(), height.toFloat())
        clipPath.reset()
        if (shape == SHAPE_OVAL) {
            clipPath.addOval(shapeRect, Path.Direction.CW)
        } else {
            val r = height / 2f
            clipPath.addRoundRect(shapeRect, r, r, Path.Direction.CW)
        }
    }

    private fun rebuildPaints() {
        val d = resources.displayMetrics.density
        strokePaint.strokeWidth = 1f * d
        val p = palette ?: return
        if (height > 0) {
            val colors = ThemeHelper.glassTintColors(p)
            tintPaint.shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                colors, floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP
            )
        }
    }

    /** Tangkap latar di balik view ini ke bitmap kecil. */
    fun capture() {
        val target = backdropView ?: return
        if (width <= 0 || height <= 0 || !target.isLaidOut) return
        try {
            val bw = max(1, (width * CAPTURE_SCALE).toInt())
            val bh = max(1, (height * CAPTURE_SCALE).toInt())
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val loc = IntArray(2)
            getLocationInWindow(loc)
            val tloc = IntArray(2)
            target.getLocationInWindow(tloc)
            c.save()
            c.scale(CAPTURE_SCALE, CAPTURE_SCALE)
            c.translate((tloc[0] - loc[0]).toFloat(), (tloc[1] - loc[1]).toFloat())
            target.draw(c)
            c.restore()
            backdropBitmap?.recycle()
            backdropBitmap = bmp
            invalidate()
        } catch (e: Exception) {
            // Tangkap gagal: pertahankan bitmap lama / tampilan statis.
        } catch (e: OutOfMemoryError) {
            backdropBitmap?.recycle()
            backdropBitmap = null
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (palette == null || tintPaint.shader == null) return
        val bmp = backdropBitmap
        if (bmp != null && !bmp.isRecycled) {
            canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawBitmap(bmp, null, Rect(0, 0, width, height), bitmapPaint)
            canvas.restore()
        }
        // Tint + sheen di atas blur (atau sendiri bila belum ada tangkapan).
        canvas.drawPath(clipPath, tintPaint)
        canvas.drawPath(clipPath, strokePaint)
    }
}
