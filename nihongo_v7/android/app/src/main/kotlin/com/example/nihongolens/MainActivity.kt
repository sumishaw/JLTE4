package com.example.nihongolens

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.NonNull
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private val CHANNEL =
        "overlay_channel"

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(savedInstanceState)

        requestIgnoreBatteryOptimizations()
    }

    override fun configureFlutterEngine(
        @NonNull flutterEngine: FlutterEngine
    ) {

        super.configureFlutterEngine(
            flutterEngine
        )

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL
        ).setMethodCallHandler { call, result ->

            when (call.method) {

                "hasOverlayPermission" -> {

                    result.success(
                        Settings.canDrawOverlays(this)
                    )
                }

                "requestOverlayPermission" -> {

                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )

                    startActivity(intent)

                    result.success(true)
                }

                "checkAccessibilityEnabled" -> {

                    result.success(
                        isAccessibilityEnabled()
                    )
                }

                "openAccessibilitySettings" -> {

                    val intent =
                        Intent(
                            Settings.ACTION_ACCESSIBILITY_SETTINGS
                        )

                    startActivity(intent)

                    result.success(true)
                }

                "startOverlay" -> {

                    val intent =
                        Intent(
                            this,
                            OverlayService::class.java
                        )

                    if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ) {

                        startForegroundService(intent)

                    } else {

                        startService(intent)
                    }

                    result.success(true)
                }

                "stopOverlay" -> {

                    stopService(
                        Intent(
                            this,
                            OverlayService::class.java
                        )
                    )

                    result.success(true)
                }

                "getLatestTranslation" -> {

                    result.success(
                        CaptionAccessibilityService.latestTranslatedText
                    )
                }

                "setTargetLanguage" -> {

                    val language =
                        call.argument<String>(
                            "language"
                        ) ?: "english"

                    CaptionAccessibilityService.targetLanguage =
                        language

                    result.success(true)
                }

                else -> result.notImplemented()
            }
        }
    }

    private fun isAccessibilityEnabled(): Boolean {

        val expectedComponent =
            ComponentName(
                this,
                CaptionAccessibilityService::class.java
            )

        val enabled =
            Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

        return enabled.contains(
            expectedComponent.flattenToString()
        )
    }

    private fun requestIgnoreBatteryOptimizations() {

        try {

            val powerManager =
                getSystemService(
                    Context.POWER_SERVICE
                ) as PowerManager

            if (
                !powerManager.isIgnoringBatteryOptimizations(
                    packageName
                )
            ) {

                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )

                startActivity(intent)
            }

        } catch (_: Exception) {
        }
    }
}
