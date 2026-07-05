package com.speedcam.assistant

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class GpsService : Service() {

    companion object {
        const val TAG = "SpeedCamGpsService"
        const val NOTIFICATION_ID = 1001
        const val UPDATE_INTERVAL_MS = 2000L
        const val FASTEST_INTERVAL_MS = 1000L

        fun start(context: Context) {
            val intent = Intent(context, GpsService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, GpsService::class.java)
            context.stopService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            for (location in locationResult.locations) {
                val lat = location.latitude
                val lng = location.longitude
                val timestamp = location.time
                val accuracy = location.accuracy
                val speed = location.speed // meters per second

                Log.d(TAG, "GPS: $lat, $lng | accuracy=$accuracy | speed=$speed")

                // Send to WebView via Activity
                MainActivity.currentActivity?.injectGpsCoordinates(
                    lat, lng, timestamp, accuracy, speed
                )
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            UPDATE_INTERVAL_MS
        ).apply {
            setMinUpdateIntervalMillis(FASTEST_INTERVAL_MS)
            setMaxUpdateDelayMillis(UPDATE_INTERVAL_MS)
        }.build()

        // Create notification channel and start foreground
        NotificationHelper.createNotificationChannel(this)
        val notification = NotificationHelper.buildNotification(this)
        startForeground(NOTIFICATION_ID, notification)

        Log.d(TAG, "GpsService created and started foreground")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            Log.d(TAG, "Location updates requested successfully")
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission not granted", e)
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request location updates", e)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "GpsService destroyed - stopping location updates")
        fusedLocationClient.removeLocationUpdates(locationCallback)
        serviceScope.cancel()
    }
}