package com.example.nihongolens

import android.util.Log
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.ConcurrentHashMap

object TranslationManager {

    private val translators =
        ConcurrentHashMap<String, Translator>()

    fun translate(
        text: String,
        sourceLang: String,
        targetLang: String,
        onTranslated: (String) -> Unit
    ) {
        // FIX 1: "und" (undetermined) language returned by ML Kit when detection
        //         fails.  The old code passed null to TranslatorOptions which threw
        //         an IllegalArgumentException and silently dropped the caption.
        //         Fall back to English source so the user still sees something.
        val resolvedSource = if (sourceLang == "und" || sourceLang.isBlank()) "en" else sourceLang

        val source = mapLanguage(resolvedSource)
        val target = mapLanguage(targetLang)

        if (source == null || target == null) {
            onTranslated(text)
            return
        }

        // FIX 2: Skip translation when source == target (avoids a needless
        //         network/model round-trip and the MLKit "same language" crash).
        if (source == target) {
            onTranslated(text)
            return
        }

        val key = "${source}_${target}"

        val translator =
            translators.getOrPut(key) {

                val options =
                    TranslatorOptions.Builder()
                        .setSourceLanguage(source)
                        .setTargetLanguage(target)
                        .build()

                Translation.getClient(options)
            }

        translator.downloadModelIfNeeded()
            .addOnSuccessListener {

                translator.translate(text)
                    .addOnSuccessListener { translated ->

                        // FIX 3: MLKit can return an empty string on rare occasions;
                        //         fall back to original text instead of showing blank.
                        onTranslated(translated.ifBlank { text })
                    }
                    .addOnFailureListener {

                        Log.e(
                            "TranslationManager",
                            "Translate failed: ${it.message}"
                        )

                        onTranslated(text)
                    }
            }
            .addOnFailureListener {

                Log.e(
                    "TranslationManager",
                    "Model download failed: ${it.message}"
                )

                onTranslated(text)
            }
    }

    /**
     * FIX 4: Call this when the service is destroyed to avoid a Translator
     * resource leak (each Translator holds a native handle).
     */
    fun closeAll() {
        translators.values.forEach { runCatching { it.close() } }
        translators.clear()
    }

    private fun mapLanguage(
        code: String
    ): String? {

        return when (code.lowercase()) {

            "ja" -> TranslateLanguage.JAPANESE

            "ko" -> TranslateLanguage.KOREAN

            "zh" -> TranslateLanguage.CHINESE

            "en" -> TranslateLanguage.ENGLISH

            "fr" -> TranslateLanguage.FRENCH

            "es" -> TranslateLanguage.SPANISH

            "de" -> TranslateLanguage.GERMAN

            "tr" -> TranslateLanguage.TURKISH

            "it" -> TranslateLanguage.ITALIAN

            "hi" -> TranslateLanguage.HINDI

            // FIX 5: pt (Portuguese) and ar (Arabic) were missing; add common ones.
            "pt" -> TranslateLanguage.PORTUGUESE

            "ar" -> TranslateLanguage.ARABIC

            else -> null
        }
    }
}
