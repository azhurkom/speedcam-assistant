package com.speedcam.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

class MainActivity : AppCompatActivity() {

    companion object {
        const val WEBVIEW_URL = "https://speed.komhub.top"
        const val TAG = "SpeedCamAssistant"

        // Reference to the current activity for GpsService to use
        var currentActivity: MainActivity? = null
            private set
    }

    lateinit var webView: WebView

    // Permission launcher for location and notification permissions
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            loadWebViewUrl()
        } else {
            // Still load the WebView even if permissions denied
            loadWebViewUrl()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentActivity = this

        setContentView(createWebView())

        checkAndRequestPermissions()
    }

    private fun createWebView(): WebView {
        webView = WebView(this)

        // WebView settings
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false

        // Set dark theme if supported
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_AUTO)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                url?.let { view?.loadUrl(it) }
                return true
            }
        }

        webView.webChromeClient = WebChromeClient()

        // Register JavaScript interface
        webView.addJavascriptInterface(SpeedCamBridge(this), "SpeedCamBridge")

        return webView
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        // Location permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        // Notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            loadWebViewUrl()
        }
    }

    private fun loadWebViewUrl() {
        webView.loadUrl(WEBVIEW_URL)
    }

    /**
     * Called from GpsService to inject GPS coordinates into JavaScript.
     */
    fun injectGpsCoordinates(lat: Double, lng: Double, timestamp: Long, accuracy: Float, speed: Float) {
        runOnUiThread {
            try {
                val jsCode = buildString {
                    append("window.SpeedCamBridgeCallback(")
                    append(lat)
                    append(", ")
                    append(lng)
                    append(", ")
                    append(timestamp)
                    append(", ")
                    append(accuracy)
                    if (speed > 0f) {
                        append(", ")
                        append(speed)
                    }
                    append(")")
                }
                webView.evaluateJavascript(jsCode, null)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        currentActivity = null
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}