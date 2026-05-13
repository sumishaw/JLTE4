package com.example.nihongolens

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.ConcurrentHashMap

class CaptionAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var latestOriginal = ""
        @Volatile var latestEnglish  = ""
        @Volatile var latestHindi    = ""
        @Volatile var targetLanguage = "english" // "english" or "hindi"
        var instance: CaptionAccessibilityService? = null

        // Supported source languages — detected automatically from caption text.
        // Maps MLKit language code → TranslateLanguage constant.
        val SUPPORTED_LANGUAGES = mapOf(
            "ja" to TranslateLanguage.JAPANESE,
            "zh" to TranslateLanguage.CHINESE,
            "ko" to TranslateLanguage.KOREAN,
            "fr" to TranslateLanguage.FRENCH,
            "de" to TranslateLanguage.GERMAN,
            "es" to TranslateLanguage.SPANISH,
            "tr" to TranslateLanguage.TURKISH,
            "en" to TranslateLanguage.ENGLISH
        )

        // Exact view IDs for Live Caption and major apps across devices/OEMs.
        // Strategy 1 (fastest): search by these IDs before doing a full tree scan.
        private val CAPTION_VIEW_IDS = listOf(
            "com.google.android.as:id/caption_text",           // Pixel Live Caption
            "com.google.android.as:id/text",
            "com.google.android.captioning:id/caption_text",   // AOSP captioning
            "com.samsung.android.bixby.agent:id/text_view",    // Samsung Live Caption
            "com.sec.android.accessibility.DigitalWellbeing:id/caption",
            "org.videolan.vlc:id/player_overlay_subtitles",    // VLC
            "org.videolan.vlc:id/subtitles",
            "com.google.android.youtube:id/caption_window_text",
            "com.google.android.youtube:id/subtitle_text",
            "com.google.android.youtube:id/player_caption_text",
            "com.android.chrome:id/caption_text",              // Chrome
            "org.mozilla.firefox:id/caption_text",             // Firefox
            "com.brave.browser:id/caption_text",               // Brave
            "com.microsoft.emmx:id/caption_text",              // Edge
            "com.netflix.mediaclient:id/subtitle_text",        // Netflix
            "com.amazon.avod.thirdpartyclient:id/subtitle_text" // Prime Video
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastCaptionText  = ""
    private var debounceRunnable: Runnable? = null

    // Cache translators by "srcLang_tgtLang" key to avoid rebuilding
    private val translatorCache = ConcurrentHashMap<String, Translator>()

    // MLKit language identifier
    private val langIdentifier = LanguageIdentification.getClient()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        // Widen scope: watch ALL packages, ALL window types, include hidden views.
        // packageNames = null is critical — Live Caption lives in com.google.android.as,
        // not in YouTube/VLC/Chrome. Without null here, those events are never delivered.
        serviceInfo = serviceInfo?.also { info ->
            info.eventTypes =
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED   or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.flags =
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS             or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            info.notificationTimeout = 50
            info.packageNames = null // Watch ALL packages
        }

        // Pre-download EN and HI models at startup so first translation is instant
        predownloadModels()
        Log.d("CaptionLens", "✅ Service connected — watching all windows, all apps")
    }

    // ── Model pre-download ────────────────────────────────────────────────────

    private fun predownloadModels() {
        // Always pre-download EN and HI since those are the two output languages
        listOf(TranslateLanguage.ENGLISH, TranslateLanguage.HINDI).forEach { tgt ->
            // Pre-download from the most common source language (Japanese)
            // Other models are downloaded on-demand when first detected
            getOrCreateTranslator(TranslateLanguage.JAPANESE, tgt)
                ?.downloadModelIfNeeded()
                ?.addOnSuccessListener { Log.d("CaptionLens", "Model JA→$tgt ready ✅") }
                ?.addOnFailureListener { Log.e("CaptionLens", "Model JA→$tgt failed: ${it.message}") }
        }
    }

    // ── Translator cache ──────────────────────────────────────────────────────

    private fun getOrCreateTranslator(src: String, tgt: String): Translator? {
        if (src == tgt) return null
        val key = "${src}_${tgt}"
        return translatorCache.getOrPut(key) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(src)
                    .setTargetLanguage(tgt)
                    .build()
            )
        }
    }

    // ── Event handler ─────────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // STRATEGY A — scan ALL open windows.
        // This is what catches Android Live Caption: it runs as a floating overlay
        // window owned by com.google.android.as, separate from the active app window.
        // rootInActiveWindow alone will NEVER see it.
        val allWindows = windows
        if (!allWindows.isNullOrEmpty()) {
            for (window in allWindows) {
                val root = window.root ?: continue
                val text = extractCaptions(root)
                root.recycle()
                if (text.isNotBlank()) {
                    scheduleTranslate(text)
                    return
                }
            }
        }

        // STRATEGY B — fallback: scan just the active window root
        val root = rootInActiveWindow ?: return
        val text = extractCaptions(root)
        root.recycle()
        if (text.isNotBlank()) scheduleTranslate(text)
    }

    // ── Caption extraction ────────────────────────────────────────────────────

    private fun extractCaptions(root: AccessibilityNodeInfo): String {
        val results = mutableListOf<String>()

        // Strategy 1: search by known caption view IDs (fastest, most precise)
        for (id in CAPTION_VIEW_IDS) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            for (node in nodes) {
                val t = node.text?.toString()?.trim() ?: ""
                if (t.isNotEmpty()) results.add(t)
                node.recycle()
            }
            if (results.isNotEmpty()) break
        }

        // Strategy 2: deep tree scan for any text that looks like a caption
        // (any language — detection happens later in the translation pipeline)
        if (results.isEmpty()) {
            val sb = StringBuilder()
            deepScan(root, sb)
            sb.toString().lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && looksLikeCaption(it) }
                .forEach { results.add(it) }
        }

        return results
            .filter { looksLikeCaption(it) }
            .distinct()
            .joinToString(" ")
            .trim()
    }

    private fun deepScan(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (node == null) return
        node.text?.toString()?.let { if (it.isNotBlank()) sb.append(it).append("\n") }
        node.contentDescription?.toString()?.let {
            if (it.isNotBlank()) sb.append(it).append("\n")
        }
        for (i in 0 until node.childCount) {
            deepScan(node.getChild(i), sb)
        }
    }

    // Checks if a string looks like real caption content.
    // Filters out UI chrome (buttons, titles, URLs) while keeping
    // any human language — Japanese, Chinese, Korean, French, German,
    // Spanish, Turkish, English, etc.
    private fun looksLikeCaption(text: String): Boolean {
        if (text.length < 2 || text.length > 300) return false
        if (text.contains("http", ignoreCase = true)) return false
        if (text.contains("://")) return false
        // Reject obvious UI strings
        val uiWords = listOf("subscribe", "search", "settings", "menu",
            "comments", "loading", "buffering", "skip")
        if (uiWords.any { text.contains(it, ignoreCase = true) }) return false
        // Accept: contains CJK / Hangul / Hiragana / Katakana / Latin letters
        return text.any { c ->
            c.isLetter() // accepts any Unicode letter — all languages pass this
        }
    }

    // ── Debounced translation ─────────────────────────────────────────────────

    private fun scheduleTranslate(text: String) {
        if (text == lastCaptionText) return
        debounceRunnable?.let { handler.removeCallbacks(it) }
        debounceRunnable = Runnable {
            if (text != lastCaptionText) {
                lastCaptionText  = text
                latestOriginal   = text
                detectAndTranslate(text)
            }
        }
        handler.postDelayed(debounceRunnable!!, 350)
    }

    // ── Language detection + translation ─────────────────────────────────────

    private fun detectAndTranslate(text: String) {
        langIdentifier.identifyLanguage(text)
            .addOnSuccessListener { langCode ->
                val detectedCode = if (langCode == "und" || langCode.isBlank()) "ja" else langCode
                Log.d("CaptionLens", "Detected lang: $detectedCode | text: $text")

                val srcLang = SUPPORTED_LANGUAGES[detectedCode]
                    ?: SUPPORTED_LANGUAGES["ja"]!! // fallback to Japanese if unsupported

                val wantHindi = targetLanguage == "hindi"

                if (srcLang == TranslateLanguage.ENGLISH) {
                    // Source is already English — use directly, still translate to Hindi if needed
                    latestEnglish = text
                    if (wantHindi) {
                        translateWith(
                            text, TranslateLanguage.ENGLISH, TranslateLanguage.HINDI
                        ) { hindi ->
                            latestHindi = hindi
                            OverlayService.updateText(text, text, hindi)
                            MainActivity.instance?.onTranslation(text, text, hindi)
                            Log.d("CaptionLens", "EN→HI: $hindi")
                        }
                    } else {
                        latestHindi = ""
                        OverlayService.updateText(text, text, "")
                        MainActivity.instance?.onTranslation(text, text, "")
                    }
                } else {
                    // Translate to English first (always)
                    translateWith(text, srcLang, TranslateLanguage.ENGLISH) { english ->
                        latestEnglish = english
                        Log.d("CaptionLens", "$detectedCode→EN: $english")

                        if (wantHindi) {
                            // Translate original → Hindi directly (better than EN→HI)
                            translateWith(text, srcLang, TranslateLanguage.HINDI) { hindi ->
                                latestHindi = hindi
                                OverlayService.updateText(text, english, hindi)
                                MainActivity.instance?.onTranslation(text, english, hindi)
                                Log.d("CaptionLens", "$detectedCode→HI: $hindi")
                            }
                        } else {
                            latestHindi = ""
                            OverlayService.updateText(text, english, "")
                            MainActivity.instance?.onTranslation(text, english, "")
                        }
                    }
                }
            }
            .addOnFailureListener {
                Log.e("CaptionLens", "Language detection failed: ${it.message}")
                // Fallback: try translating as Japanese→English
                translateWith(text, TranslateLanguage.JAPANESE, TranslateLanguage.ENGLISH) { english ->
                    latestEnglish = english
                    OverlayService.updateText(text, english, "")
                    MainActivity.instance?.onTranslation(text, english, "")
                }
            }
    }

    private fun translateWith(
        text: String,
        src: String,
        tgt: String,
        onDone: (String) -> Unit
    ) {
        val translator = getOrCreateTranslator(src, tgt) ?: run {
            onDone(text); return
        }
        translator.downloadModelIfNeeded()
            .addOnSuccessListener {
                translator.translate(text)
                    .addOnSuccessListener { result -> onDone(result.ifBlank { text }) }
                    .addOnFailureListener { onDone(text) }
            }
            .addOnFailureListener { onDone(text) }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        debounceRunnable?.let { handler.removeCallbacks(it) }
        translatorCache.values.forEach { runCatching { it.close() } }
        translatorCache.clear()
        langIdentifier.close()
        super.onDestroy()
    }
}
