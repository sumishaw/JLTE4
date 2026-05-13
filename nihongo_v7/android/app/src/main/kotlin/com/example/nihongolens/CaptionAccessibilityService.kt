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
 * HOW ANDROID LIVE CAPTION WORKS
 * ──────────────────────────────
 * Android Live Caption is an OS-level feature that captures audio via the
 * system mixer — it works for ALL apps (YouTube, VLC, Chrome, any browser,
 * any media player, phone calls, etc.) without needing to know which app is
 * playing. The Live Caption UI is rendered by Android System Intelligence
 * (package: com.google.android.as) as a floating overlay window.
 *
 * WHAT WAS WRONG BEFORE
 * ─────────────────────
 * The old code filtered events by media app package names (youtube, vlc, …).
 * This was doubly wrong:
 *   1. Live Caption events come from com.google.android.as, NOT from the media
 *      app — so the filter dropped every real Live Caption event.
 *   2. The filter accidentally matched the media app's own CC/subtitle nodes
 *      instead of the Live Caption overlay, so it read YouTube's own subtitles
 *      rather than Android's speech-recognition captions.
 *
 * THE FIX
 * ───────
 * Listen only to com.google.android.as (and known OEM variants). This
 * automatically covers every app the user might play audio from — now and in
 * the future — with no hardcoded app list needed.
 *
 * TRANSLATION VARIABLE BUG
 * ────────────────────────
 * In Hindi mode the old code wrote hindiText to latestEnglish and vice-versa,
 * so the overlay showed the wrong language. Fixed by using updateText() cleanly.
 */
class CaptionAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var latestTranslatedText = "Waiting for captions…"
        @Volatile var targetLanguage       = "hindi" // "english" or "hindi"

        // Live Caption is rendered by Android System Intelligence on stock Android.
        // OEM variants (Samsung, Xiaomi, etc.) use their own packages listed below.
        // We match by substring so minor version-suffix changes don't break anything.
        private val LIVE_CAPTION_PACKAGES = listOf(
            "com.google.android.as",                       // Pixel / stock Android 10+
            "com.google.android.accessibility.captions",  // older AOSP builds
            "com.samsung.android.bixby.service",          // Samsung Live Transcribe
            "com.samsung.android.accessibility",           // Samsung alt
            "com.miui.voiceassist",                       // Xiaomi
            "com.huawei.accessibility"                    // Huawei
        )
    }

    private val handler          = Handler(Looper.getMainLooper())
    private var lastCaption      = ""
    private var pendingText      = ""

    private val debounceRunnable = Runnable {
        try { processCaption(pendingText) }
        catch (e: Exception) { Log.e("CaptionService", "debounce error: ${e.message}") }
    }

    // ── service setup ─────────────────────────────────────────────────────────
    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            serviceInfo = AccessibilityServiceInfo().apply {
                eventTypes =
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED       or
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED
                feedbackType        = AccessibilityServiceInfo.FEEDBACK_GENERIC
                flags               = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                                      AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                notificationTimeout = 30
                // packageNames intentionally NOT set — we filter manually below.
                // Setting packageNames would restrict events to only those packages
                // and would miss OEM Live Caption variants not in the list.
            }
            Log.d("CaptionService", "Connected — watching Android Live Caption (ASI)")
        } catch (e: Exception) {
            Log.e("CaptionService", "onServiceConnected: ${e.message}")
        }
    }

    // ── event handler ─────────────────────────────────────────────────────────
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) return
            val pkg = event.packageName?.toString() ?: return

            // Accept only events from the Live Caption / ASI overlay.
            // This covers audio from ANY source — YouTube, VLC, Chrome, Firefox,
            // MX Player, phone calls, Spotify, or any future app — because Live
            // Caption operates at the OS audio-mixer level, not per-app.
            val isLiveCaption = LIVE_CAPTION_PACKAGES.any { pkg.contains(it) }
            if (!isLiveCaption) return

            val root: AccessibilityNodeInfo = rootInActiveWindow ?: return
            try {
                val captions = LinkedHashSet<String>()
                collectLiveCaptionNodes(root, captions)
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
            Log.e("CaptionService", "onAccessibilityEvent: ${e.message}")
        }
    }

    // ── translation pipeline ──────────────────────────────────────────────────
    private fun processCaption(text: String) {
        try {
            LanguageDetector.detectLanguage(text) { sourceLang ->
                val wantHindi = targetLanguage.lowercase() == "hindi"

                if (wantHindi) {
                    // Translate to English first (fast), then Hindi.
                    // Both results go to OverlayService via the correct fields.
                    TranslationManager.translate(text, sourceLang, "en") { engText ->
                        TranslationManager.translate(text, sourceLang, "hi") { hindiText ->
                            // latestTranslatedText is what the Flutter preview box reads.
                            latestTranslatedText = hindiText
                            // updateText keeps latestEnglish and latestHindi correctly named.
                            OverlayService.updateText(
                                original = text,
                                english  = engText,
                                hindi    = hindiText
                            )
                            Log.d("CaptionService",
                                "[$sourceLang→EN] $engText | [$sourceLang→HI] $hindiText")
                        }
                    }
                } else {
                    TranslationManager.translate(text, sourceLang, "en") { engText ->
                        latestTranslatedText = engText
                        OverlayService.updateText(
                            original = text,
                            english  = engText,
                            hindi    = ""
                        )
                        Log.d("CaptionService", "[$sourceLang→EN] $engText")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("CaptionService", "processCaption: ${e.message}")
            latestTranslatedText = text
            OverlayService.updateText(original = text, english = text, hindi = "")
        }
    }

    // ── Live Caption node collector ───────────────────────────────────────────
    // ASI's Live Caption uses dedicated view IDs (caption_text, caption_line, etc.).
    // We match those first; fall back to any short, speech-like TextView as secondary.
    // We do NOT use generic rules that would match UI chrome from other windows.
    private fun collectLiveCaptionNodes(
        node: AccessibilityNodeInfo?,
        out:  LinkedHashSet<String>
    ) {
        try {
            if (node == null) return
            val text   = node.text?.toString()?.trim() ?: ""
            val viewId = node.viewIdResourceName?.lowercase() ?: ""
            val cls    = node.className?.toString() ?: ""

            val isCaptionById =
                viewId.contains("caption_text") ||
                viewId.contains("caption_line") ||
                viewId.contains("live_caption") ||
                viewId.contains("transcript")

            val looksLikeCaption =
                cls.contains("TextView") &&
                text.isNotBlank()        &&
                text.length in 2..300    &&
                !text.contains("http")   &&
                !text.contains("://")    &&
                !text.contains("Search",    ignoreCase = true) &&
                !text.contains("Settings",  ignoreCase = true) &&
                !text.contains("Subscribe", ignoreCase = true) &&
                !text.contains("Comments",  ignoreCase = true)

            if ((isCaptionById || looksLikeCaption) && text.isNotBlank()) {
                out.add(text)
            }

            for (i in 0 until node.childCount) {
                collectLiveCaptionNodes(node.getChild(i), out)
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
