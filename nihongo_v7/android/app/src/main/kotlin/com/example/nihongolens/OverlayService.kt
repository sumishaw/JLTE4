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

/**
 * OverlayService — Transparent floating caption overlay.
 *
 * Fixes applied:
 * 1. onDestroy() now checks viewAdded flag before removeView() to prevent
 *    "View not attached to window manager" IllegalArgumentException.
 * 2. Handler.post { buildOverlay() } could race with onDestroy(); guard with
 *    running flag inside the posted runnable.
 * 3. Touch listener: params!! force-unwrap replaced with safe-call + return
 *    to avoid NullPointerException when overlay is torn down during a drag.
 * 4. updateViewLayout wrapped in isAdded guard to prevent IllegalArgumentException.
 * 5. startUpdateLoop thread: catch InterruptedException properly and clear flag.
 */
class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID  = "nihongo_overlay"
        const val NOTIF_ID    = 1

        @Volatile var latestEnglish  = "Waiting for captions…"
        @Volatile var latestHindi    = ""
        @Volatile var latestOriginal = ""

        fun updateText(original: String, english: String, hindi: String = "") {
            latestOriginal = original
            latestEnglish  = english
            latestHindi    = hindi
        }
    }

    // ── layout refs ──────────────────────────────────────────────────────────
    private var windowManager: WindowManager? = null
    private var overlayView:   View?           = null
    private var line1Tv:       TextView?        = null
    private var line2Tv:       TextView?        = null
    private var params:        WindowManager.LayoutParams? = null

    private val handler  = Handler(Looper.getMainLooper())
    @Volatile private var running    = true
    // FIX 1: track whether the view was successfully added to WindowManager
    @Volatile private var viewAdded  = false

    // 2-line buffer state
    private val lineBuffer   = ArrayDeque<String>(4)
    private var displayedKey = ""
    private var lastRawText  = ""

    // ── dp helper ────────────────────────────────────────────────────────────
    private fun dp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    // ── lifecycle ────────────────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        // FIX 2: only build overlay if the service is still alive when the post fires
        handler.post {
            if (running) buildOverlay()
        }
        startUpdateLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        // FIX 1: only call removeView() if we actually added the view
        if (viewAdded) {
            try { windowManager?.removeView(overlayView) } catch (_: Exception) {}
            viewAdded = false
        }
        super.onDestroy()
    }

    // ── overlay construction ─────────────────────────────────────────────────
    private fun buildOverlay() {
        try {
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity     = Gravity.CENTER_HORIZONTAL
                setPadding(dp(12f).toInt(), dp(8f).toInt(), dp(12f).toInt(), dp(8f).toInt())
            }

            fun makeCaptionTv(textSizeSp: Float, alpha: Float) = TextView(this).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
                typeface   = Typeface.DEFAULT_BOLD
                setShadowLayer(dp(4f), 0f, dp(2f), Color.BLACK)
                this.alpha = alpha
                setLineSpacing(0f, 1.25f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, dp(3f).toInt()) }
            }

            line1Tv = makeCaptionTv(16f, 0.75f)
            line2Tv = makeCaptionTv(22f, 1.00f)

            root.addView(line1Tv)
            root.addView(line2Tv)
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
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE          or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL        or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN       or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSPARENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = dp(72f).toInt()
            }

            // FIX 3: use safe-calls instead of !! in the touch listener
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
                        // FIX 4: only call updateViewLayout when the view is attached
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
            viewAdded = true   // FIX 1: mark as added only after success

        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "buildOverlay error: ${e.message}")
        }
    }

    // ── ultra-fast 50 ms update loop ─────────────────────────────────────────
    private fun startUpdateLoop() {
        Thread {
            while (running) {
                try {
                    Thread.sleep(50)
                    val raw  = latestEnglish
                    val orig = latestOriginal

                    if (raw == lastRawText || raw.isBlank()) continue
                    lastRawText = raw

                    val incoming = raw.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
                    incoming.forEach { line ->
                        if (lineBuffer.isEmpty() || lineBuffer.last() != line)
                            lineBuffer.addLast(line)
                        if (lineBuffer.size > 4) lineBuffer.removeFirst()
                    }

                    val bufKey = lineBuffer.takeLast(2).joinToString("|")
                    if (bufKey == displayedKey) continue
                    displayedKey = bufKey

                    val showLines = lineBuffer.takeLast(2)
                    val l1 = if (showLines.size >= 2) showLines[showLines.size - 2] else ""
                    val l2 = showLines.last()

                    handler.post {
                        line1Tv?.apply {
                            text       = if (l1.isNotEmpty()) "↑ $l1" else ""
                            visibility = if (l1.isNotEmpty()) View.VISIBLE else View.GONE
                        }
                        line2Tv?.apply {
                            text = l2
                            startAnimation(AlphaAnimation(0.2f, 1f).apply {
                                duration  = 120
                                fillAfter = true
                            })
                        }
                    }
                // FIX 5: catch InterruptedException separately; re-interrupt the thread
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (_: Exception) {}
            }
        }.also { it.isDaemon = true; it.name = "overlay-update" }.start()
    }

    // ── notification helpers ──────────────────────────────────────────────────
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
            .setContentText("Transparent overlay running — translating captions")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setSilent(true)
            .build()
}
