package com.example.nihongolens

import android.app.*
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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

        @Volatile var latestOriginal = ""
        @Volatile var latestEnglish  = "Waiting for captions…"
        @Volatile var latestHindi    = ""

        fun updateText(original: String, english: String, hindi: String = "") {
            latestOriginal = original
            latestEnglish  = english
            latestHindi    = hindi
        }
    }

    private var windowManager: WindowManager? = null
    private var overlayView:   View?           = null
    private var originalTv:    TextView?        = null
    private var translatedTv:  TextView?        = null
    private val handler = Handler(Looper.getMainLooper())
    private var params:  WindowManager.LayoutParams? = null

    @Volatile private var running   = true
    @Volatile private var viewAdded = false
    private var lastDisplayedKey = ""

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

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

    // ── Overlay construction ──────────────────────────────────────────────────

    private fun buildOverlay() {
        try {
            // Card with dark background + red border (matches working app style)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(12))
                background = GradientDrawable().apply {
                    setColor(Color.argb(235, 0, 0, 0))
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(2), Color.argb(220, 255, 59, 59))
                }
            }

            // Header row: label + close button
            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            topRow.addView(TextView(this).apply {
                text = "🌐 Caption Lens"
                setTextColor(Color.argb(180, 255, 255, 255))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            topRow.addView(TextView(this).apply {
                text = "  ✕  "
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setOnClickListener { stopSelf() }
            })

            // Divider
            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                ).also { it.setMargins(0, dp(5), 0, dp(7)) }
                setBackgroundColor(Color.argb(150, 255, 59, 59))
            }

            // Original text row (dim, small — shows source language text)
            originalTv = TextView(this).apply {
                text = ""
                setTextColor(Color.argb(150, 180, 180, 255))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(0, 0, 0, dp(4)) }
            }

            // Translated text row (bright, large — English or Hindi)
            translatedTv = TextView(this).apply {
                text = "Waiting for captions…"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                typeface = Typeface.DEFAULT_BOLD
                setLineSpacing(0f, 1.3f)
            }

            card.addView(topRow)
            card.addView(divider)
            card.addView(originalTv)
            card.addView(translatedTv)
            overlayView = card

            val screenWidth = resources.displayMetrics.widthPixels
            params = WindowManager.LayoutParams(
                (screenWidth * 0.95).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = dp(80)
            }

            // Draggable
            var sx = 0f; var sy = 0f; var ix = 0; var iy = 0
            card.setOnTouchListener { _, ev ->
                val p = params ?: return@setOnTouchListener false
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = ev.rawX; sy = ev.rawY
                        ix = p.x;    iy = p.y
                    }
                    MotionEvent.ACTION_MOVE -> {
                        p.x = ix + (ev.rawX - sx).toInt()
                        p.y = iy - (ev.rawY - sy).toInt()
                        if (viewAdded) {
                            try { windowManager?.updateViewLayout(overlayView, p) }
                            catch (_: Exception) {}
                        }
                    }
                }
                true
            }

            windowManager?.addView(overlayView, params)
            viewAdded = true
        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "buildOverlay: ${e.message}")
        }
    }

    // ── Update loop ───────────────────────────────────────────────────────────

    private fun startUpdateLoop() {
        Thread {
            while (running) {
                try {
                    Thread.sleep(200)
                    val original   = latestOriginal
                    val english    = latestEnglish
                    val hindi      = latestHindi

                    // Show Hindi if available (user chose Hindi mode), else English
                    val translated = if (hindi.isNotBlank()) hindi else english
                    val key = "$original|$translated"

                    if (key == lastDisplayedKey) continue
                    if (translated.isBlank() || translated == "Waiting for captions…") continue
                    lastDisplayedKey = key

                    val showOriginal = original.isNotBlank() && original != translated

                    handler.post {
                        originalTv?.apply {
                            text       = if (showOriginal) original else ""
                            visibility = if (showOriginal) View.VISIBLE else View.GONE
                        }
                        translatedTv?.apply {
                            text = translated
                            startAnimation(AlphaAnimation(0.3f, 1f).apply {
                                duration  = 300
                                fillAfter = true
                            })
                        }
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt(); break
                } catch (_: Exception) {}
            }
        }.also { it.isDaemon = true; it.name = "overlay-update" }.start()
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(CHANNEL_ID, "Caption Lens Overlay",
                NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
                .also { getSystemService(NotificationManager::class.java)
                    .createNotificationChannel(it) }
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Caption Lens Active")
            .setContentText("Translating captions from any app")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setSilent(true)
            .build()
}
