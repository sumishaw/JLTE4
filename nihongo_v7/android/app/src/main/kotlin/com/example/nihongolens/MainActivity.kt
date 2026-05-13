package com.example.nihongolens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    companion object {
        var instance: MainActivity? = null
    }

    private val CHANNEL = "overlay_channel"
    private var methodChannel: MethodChannel? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        instance = this

        methodChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger, CHANNEL)

        methodChannel?.setMethodCallHandler { call, result ->
            when (call.method) {

                "hasOverlayPermission" ->
                    result.success(Settings.canDrawOverlays(this))

                "requestOverlayPermission" -> {
                    if (!Settings.canDrawOverlays(this)) {
                        startActivity(Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")))
                        result.success(false)
                    } else {
                        result.success(true)
                    }
                }

                "checkAccessibilityEnabled" ->
                    result.success(isAccessibilityEnabled())

                "openAccessibilitySettings" -> {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    result.success(true)
                }

                "startOverlay" -> {
                    val i = Intent(this, OverlayService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        startForegroundService(i) else startService(i)
                    result.success(true)
                }

                "stopOverlay" -> {
                    stopService(Intent(this, OverlayService::class.java))
                    result.success(true)
                }

                "setTargetLanguage" -> {
                    val lang = call.argument<String>("language") ?: "english"
                    CaptionAccessibilityService.targetLanguage = lang
                    result.success(true)
                }

                "getLatestTranslation" -> {
                    result.success(mapOf(
                        "original" to CaptionAccessibilityService.latestOriginal,
                        "english"  to CaptionAccessibilityService.latestEnglish,
                        "hindi"    to CaptionAccessibilityService.latestHindi
                    ))
                }

                else -> result.notImplemented()
            }
        }
    }

    // Called by CaptionAccessibilityService to push translations to Flutter UI instantly
    fun onTranslation(original: String, english: String, hindi: String) {
        runOnUiThread {
            methodChannel?.invokeMethod("onTranslation", mapOf(
                "original" to original,
                "english"  to english,
                "hindi"    to hindi
            ))
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        return try {
            val service = "$packageName/${CaptionAccessibilityService::class.java.canonicalName}"
            val enabled = Settings.Secure.getString(
                contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            enabled?.contains(service) == true
        } catch (_: Exception) { false }
    }

    override fun onResume() {
        super.onResume()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
