# SpeedCam Assistant ProGuard Rules
# Keep WebView JavaScript interface methods
-keepclassmembers class com.speedcam.assistant.SpeedCamBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep GpsService
-keep class com.speedcam.assistant.GpsService