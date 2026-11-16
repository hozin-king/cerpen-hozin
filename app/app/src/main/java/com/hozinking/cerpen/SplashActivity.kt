package com.hozinking.cerpen

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.airbnb.lottie.LottieDrawable

/**
 * Splash v1.1.38 — logo buku Lottie (res/raw/logo_splash.json), loop
 * tak berujung, ringan (4,3 KB, tanpa gambar). Tampil ~1,5 detik lalu
 * ke MainActivity. Bila Lottie gagal dimuat (JSON rusak / library
 * gagal), ikon buku statis yang tampil — aplikasi tetap jalan.
 *
 * MainActivity melepas peran launcher; intent-filter App Links
 * (autoVerify) tetap di MainActivity supaya tautan berbagi /s/<token>
 * langsung membuka MainActivity, bukan splash.
 */
class SplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var navigated = false

    private val goMain = Runnable {
        if (navigated || isFinishing || isDestroyed) return@Runnable
        navigated = true
        val intent = Intent(this, MainActivity::class.java)
        // Teruskan intent asal (mis. App Link yang keliru membuka splash).
        this.intent?.let {
            if (it.action != null) intent.action = it.action
            intent.data = it.data
            intent.putExtras(it)
        }
        startActivity(intent)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        val palette = ThemeHelper.palette(ProgressStore.getTheme(this))
        findViewById<View>(R.id.splash_root).setBackgroundColor(palette.bg)
        val title = findViewById<android.widget.TextView>(R.id.splash_title)
        title.setTextColor(palette.titleInk)
        val fallback = findViewById<View>(R.id.splash_fallback)
        val lottie = findViewById<com.airbnb.lottie.LottieAnimationView>(R.id.splash_lottie)
        lottie.setAnimation(R.raw.logo_splash)
        lottie.repeatCount = LottieDrawable.INFINITE
        lottie.addLottieOnCompositionLoadedListener { composition ->
            if (composition == null) {
                lottie.visibility = View.GONE
                fallback.visibility = View.VISIBLE
            } else {
                lottie.playAnimation()
            }
        }
        lottie.setFailureListener {
            lottie.visibility = View.GONE
            fallback.visibility = View.VISIBLE
        }
        // Tombol kembali selama splash: batalkan navigasi, tutup saja.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handler.removeCallbacks(goMain)
                navigated = true
                finish()
            }
        })
        handler.postDelayed(goMain, 1500L)
    }

    override fun onDestroy() {
        handler.removeCallbacks(goMain)
        super.onDestroy()
    }
}
