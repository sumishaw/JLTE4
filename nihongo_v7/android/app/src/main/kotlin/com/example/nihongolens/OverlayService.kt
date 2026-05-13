package com.example.nihongolens

import android.app.*
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.*
import android.util.TypedValue
import android.view.*
import android.view.animation.AlphaAnimation
import android.widget.*
import androidx.core.app.NotificationCompat

class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "nihongo_overlay"
        const val NOTIF_ID   = 1

        @Volatile var latestEnglish  = "Waiting for captions…"
        @Volatile var latestHindi    = ""
        @Volatile var latestOriginal = ""

        fun updateText(original: String, english: String, hindi: String = "") {
            latestOriginal = original
            latestEnglish  = english
            latestHindi    = hindi
        }
    }

    private var windowManager: WindowManager?              = null
    private var overlayView:   View?                       = null
    private var originalTv:    TextView?                   = null  // dim top row: original text
    private var translatedTv:  TextView?                   = null  // bright bottom row: translation
    private var params:        WindowManager.LayoutParams? = null

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var running   = true
    @Volatile private var viewAdded = false

    private var lastDisplayedKey = ""

    private fun dp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    // ── lifecycle ─────────────────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        handler.post { if (running) buildOverlay() }
        startUpdateLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        if (viewAdded) {
            try { windowManager?.removeView(overlayView) } catch (_: Exception) {}
            viewAdded = false
        }
        super.onDestroy()
    }

    // ── overlay construction ──────────────────────────────────────────────────
    private fun buildOverlay() {
        try {
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity     = Gravity.CENTER_HORIZONTAL
                setPadding(dp(14f).toInt(), dp(8f).toInt(), dp(14f).toInt(), dp(8f).toInt())
            }

            fun makeTextView(sizeSp: Float, alphaVal: Float, color: Int) =
                TextView(this).apply {
                    setTextColor(color)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
                    typeface  = Typeface.DEFAULT_BOLD
                    setShadowLayer(dp(4f), 0f, dp(2f), Color.BLACK)
                    alpha     = alphaVal
                    setLineSpacing(0f, 1.2f)
                    gravity   = Gravity.CENTER_HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 0, 0, dp(4f).toInt()) }
                }

            originalTv   = makeTextView(13f, 0.50f, Color.WHITE)  // original speech, dim
            translatedTv = makeTextView(22f, 1.00f, Color.WHITE)  // translated, bright

            root.addView(originalTv)
            root.addView(translatedTv)
            overlayView = root

            val wType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

            val screenW = resources.displayMetrics.widthPixels
            params = WindowManager.LayoutParams(
                (screenW * 0.92).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
                wType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE    or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL  or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSPARENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = dp(80f).toInt()
            }

            var sx = 0f; var sy = 0f; var ix = 0; var iy = 0
            root.setOnTouchListener { _, ev ->
                val p = params ?: return@setOnTouchListener false
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = ev.rawX; sy = ev.rawY
                        ix = p.x;    iy = p.y
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        p.x = ix + (ev.rawX - sx).toInt()
                        p.y = iy - (ev.rawY - sy).toInt()
                        if (viewAdded) {
                            try { windowManager?.updateViewLayout(overlayView, p) }
                            catch (_: Exception) {}
                        }
                        true
                    }
                    else -> false
                }
            }

            windowManager?.addView(overlayView, params)
            viewAdded = true
        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "buildOverlay: ${e.message}")
        }
    }

    // ── update loop ───────────────────────────────────────────────────────────
    // Reads the correct translation field based on what was populated:
    // - Hindi mode  → latestHindi is non-blank → show Hindi in big row
    // - English mode → latestHindi is blank    → show English in big row
    // - Top dim row always shows the original caption text (any language)
    private fun startUpdateLoop() {
        Thread {
            while (running) {
                try {
                    Thread.sleep(50)

                    val original = latestOriginal
                    val english  = latestEnglish
                    val hindi    = latestHindi

                    // Prefer Hindi when available (user chose Hindi mode)
                    val translated = if (hindi.isNotBlank()) hindi else english

                    val key = "$original|$translated"
                    if (key == lastDisplayedKey) continue
                    if (translated.isBlank() || translated == "Waiting for captions…") continue
                    lastDisplayedKey = key

                    // Only show the original dim row when the original differs from
                    // the translation (i.e. it was actually translated, not passed through)
                    val showOriginal = original.isNotBlank() && original != translated

                    handler.post {
                        originalTv?.apply {
                            text       = if (showOriginal) original else ""
                            visibility = if (showOriginal) View.VISIBLE else View.GONE
                        }
                        translatedTv?.apply {
                            text = translated
                            startAnimation(AlphaAnimation(0.15f, 1f).apply {
                                duration  = 150
                                fillAfter = true
                            })
                        }
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (_: Exception) {}
            }
        }.also { it.isDaemon = true; it.name = "overlay-update" }.start()
    }

    // ── notification ──────────────────────────────────────────────────────────
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(CHANNEL_ID, "Caption Translator Overlay",
                NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
                .also { getSystemService(NotificationManager::class.java)
                    .createNotificationChannel(it) }
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Caption Translator Active")
            .setContentText("Works with any app — YouTube, VLC, Chrome, and more")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setSilent(true)
            .build()
}
