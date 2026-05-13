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

        val source =
            mapLanguage(sourceLang)

        val target =
            mapLanguage(targetLang)

        if (source == null || target == null) {

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

                        onTranslated(translated)
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

            else -> null
        }
    }
}
