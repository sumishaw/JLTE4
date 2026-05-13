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
 * KEY FIX: This service must listen to Android's Live Caption window
 * (package: com.google.android.as / Android System Intelligence), NOT
 * to media app packages like YouTube.
 *
 * When YouTube is playing, Android Live Caption creates its own floating
 * overlay window owned by "com.google.android.as". That window contains
 * TextViews with viewId "com.google.android.as:id/caption_text" (or similar).
 *
 * The old code filtered FOR youtube/netflix packages, which meant it was
 * reading YouTube's own CC subtitle nodes — not the Live Caption overlay.
 * This fix inverts the logic: listen to com.google.android.as events only.
 */
class CaptionAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var latestTranslatedText = "Waiting for captions…"
        @Volatile var targetLanguage       = "hindi"   // "english" or "hindi"

        // Live Caption runs inside Android System Intelligence
        private val LIVE_CAPTION_PACKAGES = setOf(
            "com.google.android.as",           // Android System Intelligence (primary)
            "com.google.android.accessibility.captions", // some OEM variants
            "com.samsung.android.bixby.service" // Samsung Live Transcribe fallback
        )
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
                                  AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED      or
                                  AccessibilityEvent.TYPE_WINDOWS_CHANGED
                feedbackType    = AccessibilityServiceInfo.FEEDBACK_GENERIC
                flags           = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS          or
                                  AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                notificationTimeout = 30
                // KEY FIX: do NOT set packageNames here — we filter manually below
                // so we can match multiple Live Caption package variants.
            }
            Log.d("CaptionService", "Accessibility service connected — watching Live Caption")
        } catch (e: Exception) {
            Log.e("CaptionService", "onServiceConnected error: ${e.message}")
        }
    }

    // ── event handler ─────────────────────────────────────────────────────────
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) return

            val pkg = event.packageName?.toString()?.lowercase() ?: return

            // KEY FIX: only process events from Live Caption / ASI packages.
            // Drop everything else (YouTube, Chrome, etc.) — those are the
            // app's own subtitle nodes, not Android's Live Caption overlay.
            val isLiveCaption = LIVE_CAPTION_PACKAGES.any { pkg.contains(it) }
            if (!isLiveCaption) return

            val root: AccessibilityNodeInfo = rootInActiveWindow ?: return
            try {
                val captions = LinkedHashSet<String>()
                collectLiveCaptionText(root, captions)
                if (captions.isEmpty()) return

                val combined = captions.toList().takeLast(2).joinToString("\n").trim()
                if (combined.isBlank() || combined == lastCaption) return

                lastCaption = combined
                pendingText = combined

                handler.removeCallbacks(debounceRunnable)
                handler.postDelayed(debounceRunnable, 40)
            } finally {
                @Suppress("DEPRECATION")
                root.recycle()
            }
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
                    TranslationManager.translate(text, sourceLang, "en") { engText ->
                        TranslationManager.translate(text, sourceLang, "hi") { hindiText ->
                            latestTranslatedText = hindiText
                            OverlayService.updateText(
                                original = text,
                                english  = engText,
                                hindi    = hindiText
                            )
                            OverlayService.latestEnglish  = hindiText
                            OverlayService.latestOriginal = engText
                            Log.d("CaptionService", "EN: $engText | HI: $hindiText")
                        }
                    }
                } else {
                    TranslationManager.translate(text, sourceLang, "en") { engText ->
                        latestTranslatedText          = engText
                        OverlayService.latestEnglish  = engText
                        OverlayService.latestOriginal = text
                        OverlayService.updateText(text, engText)
                        Log.d("CaptionService", "EN: $engText")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("CaptionService", "processCaption error: ${e.message}")
            latestTranslatedText          = text
            OverlayService.latestEnglish  = text
            OverlayService.latestOriginal = text
        }
    }

    // ── Live Caption node collector ───────────────────────────────────────────
    // Live Caption nodes are specifically identified by:
    //   1. viewId containing "caption_text" or "caption" (com.google.android.as)
    //   2. OR a TextView with content that looks like a caption line
    //      (short, no UI chrome keywords, no URLs)
    // We do NOT use a generic "any TextView" rule here because that would
    // accidentally pick up UI elements from other windows.
    private fun collectLiveCaptionText(
        node: AccessibilityNodeInfo?,
        out:  LinkedHashSet<String>
    ) {
        try {
            if (node == null) return
            val text   = node.text?.toString()?.trim() ?: ""
            val viewId = node.viewIdResourceName?.lowercase() ?: ""
            val cls    = node.className?.toString() ?: ""

            // Primary: Live Caption's dedicated caption node IDs
            val isCaptionNode =
                viewId.contains("caption_text") ||
                viewId.contains("caption_line") ||
                viewId.contains("transcript")   ||
                viewId.contains("live_caption")

            // Secondary: any short, clean TextView that looks like speech
            val looksLikeCaption =
                cls.contains("TextView") &&
                text.isNotBlank()        &&
                text.length in 2..300    &&
                !text.contains("http")   &&
                !text.contains("://")    &&
                !text.contains("Search", ignoreCase = true) &&
                !text.contains("Settings", ignoreCase = true)

            if ((isCaptionNode || looksLikeCaption) && text.isNotBlank()) {
                out.add(text)
            }

            for (i in 0 until node.childCount) {
                collectLiveCaptionText(node.getChild(i), out)
            }
        } catch (_: Exception) {}
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        TranslationManager.closeAll()
    }
}
