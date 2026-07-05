package com.speedcam.assistant

import android.content.Context
import android.webkit.JavascriptInterface

/**
 * JavaScript interface registered as `SpeedCamBridge` in the WebView.
 * JS code can call window.SpeedCamBridge.startGps() and .stopGps().
 */
class SpeedCamBridge(private val context: Context) {

    @JavascriptInterface
    fun startGps() {
        GpsService.start(context)
    }

    @JavascriptInterface
    fun stopGps() {
        GpsService.stop(context)
    }
}