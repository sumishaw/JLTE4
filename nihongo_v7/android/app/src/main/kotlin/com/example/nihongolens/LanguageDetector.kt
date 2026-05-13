package com.example.nihongolens

import com.google.mlkit.nl.languageid.LanguageIdentification

object LanguageDetector {

    private val identifier =
        LanguageIdentification.getClient()

    fun detectLanguage(
        text: String,
        onDetected: (String) -> Unit
    ) {

        if (text.isBlank()) {

            onDetected("und")

            return
        }

        identifier.identifyLanguage(text)
            .addOnSuccessListener { languageCode ->

                onDetected(languageCode ?: "und")
            }
            .addOnFailureListener {

                onDetected("und")
            }
    }
}
