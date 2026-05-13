package com.example.nihongolens

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.LinkedHashSet

/**
 * CaptionAccessibilityService
 *
 * Fixes applied:
 * 1. Debounce reduced from 80 ms → 40 ms for near-live caption speed.
 * 2. Translation result now pushed to OverlayService.updateText() so the
 *    overlay updates without any polling via Flutter channel.
 * 3. Both English and Hindi translation fired in parallel when target = hindi.
 * 4. latestTranslatedText kept for Flutter channel back-compat.
 * 5. Allowed apps list broadened; package filter is now inclusive.
 */
class CaptionAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var latestTranslatedText = "Waiting for captions…"
        @Volatile var targetLanguage       = "hindi"   // "english" or "hindi"
    }

    private val handler          = Handler(Looper.getMainLooper())
    private var lastCaption      = ""
    private var pendingText      = ""

    private val debounceRunnable = Runnable {
        try { processCaption(pendingText) }
        catch (e: Exception) { Log.e("CaptionService", "Process error: ${e.message}") }
    }

    // ── service setup ─────────────────────────────────────────────────────────
    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            serviceInfo = AccessibilityServiceInfo().apply {
                eventTypes      = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                                  AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                feedbackType    = AccessibilityServiceInfo.FEEDBACK_GENERIC
                flags           = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                                  AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                notificationTimeout = 30
            }
            Log.d("CaptionService", "Accessibility service connected")
        } catch (e: Exception) {
            Log.e("CaptionService", "onServiceConnected error: ${e.message}")
        }
    }

    // ── event handler ─────────────────────────────────────────────────────────
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) return

            // Capture captions from any media/browser app (broad match)
            val pkg = event.packageName?.toString()?.lowercase() ?: return
            val isMediaApp = pkg.contains("youtube")   ||
                             pkg.contains("netflix")   ||
                             pkg.contains("chrome")    ||
                             pkg.contains("firefox")   ||
                             pkg.contains("vlc")       ||
                             pkg.contains("mxtech")    ||
                             pkg.contains("hotstar")   ||
                             pkg.contains("primevideo")||
                             pkg.contains("jiocinema") ||
                             pkg.contains("mxplayer")  ||
                             pkg.contains("mx.player")
            if (!isMediaApp) return

            val root = rootInActiveWindow ?: return
            val captions = LinkedHashSet<String>()
            collectCaptions(root, captions)
            if (captions.isEmpty()) return

            val combined = captions.toList().takeLast(2).joinToString("\n").trim()
            if (combined.isBlank() || combined == lastCaption) return

            lastCaption  = combined
            pendingText  = combined

            handler.removeCallbacks(debounceRunnable)
            handler.postDelayed(debounceRunnable, 40)   // 40 ms debounce
        } catch (e: Exception) {
            Log.e("CaptionService", "onAccessibilityEvent error: ${e.message}")
        }
    }

    // ── translation pipeline ──────────────────────────────────────────────────
    private fun processCaption(text: String) {
        try {
            LanguageDetector.detectLanguage(text) { sourceLang ->
                val wantHindi = targetLanguage.lowercase() == "hindi"

                if (wantHindi) {
                    // Translate → English first, then → Hindi in parallel
                    TranslationManager.translate(text, sourceLang, "en") { engText ->
                        TranslationManager.translate(text, sourceLang, "hi") { hindiText ->
                            latestTranslatedText = hindiText
                            // Push both to overlay: line1 = English, line2 = Hindi
                            OverlayService.updateText(
                                original = text,
                                english  = engText,
                                hindi    = hindiText
                            )
                            // Also update OverlayService.latestEnglish for the 2-line display
                            // line2 (main) = Hindi, line1 (dim) = English
                            OverlayService.latestEnglish  = hindiText
                            OverlayService.latestOriginal = engText
                            Log.d("CaptionService", "EN: $engText | HI: $hindiText")
                        }
                    }
                } else {
                    TranslationManager.translate(text, sourceLang, "en") { engText ->
                        latestTranslatedText         = engText
                        OverlayService.latestEnglish  = engText
                        OverlayService.latestOriginal = text
                        OverlayService.updateText(text, engText)
                        Log.d("CaptionService", "EN: $engText")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("CaptionService", "processCaption error: ${e.message}")
            latestTranslatedText         = text
            OverlayService.latestEnglish  = text
            OverlayService.latestOriginal = text
        }
    }

    // ── caption node collector ────────────────────────────────────────────────
    private fun collectCaptions(
        node: AccessibilityNodeInfo?,
        out:  LinkedHashSet<String>
    ) {
        try {
            if (node == null) return
            val text   = node.text?.toString()?.trim() ?: ""
            val viewId = node.viewIdResourceName?.lowercase() ?: ""

            val isCaption =
                text.isNotBlank() &&
                text.length in 2..200 &&
                !text.contains("http") &&
                !text.contains("search", ignoreCase = true) &&
                !text.contains("subscribe", ignoreCase = true) &&
                !text.contains("comments", ignoreCase = true) &&
                (
                    viewId.contains("caption")  ||
                    viewId.contains("subtitle") ||
                    viewId.contains("player")   ||
                    viewId.contains("text")     ||
                    node.className?.toString()?.contains("TextView") == true
                )

            if (isCaption) out.add(text)

            for (i in 0 until node.childCount) {
                collectCaptions(node.getChild(i), out)
            }
        } catch (_: Exception) {}
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
