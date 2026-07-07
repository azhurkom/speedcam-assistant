package com.speedlimit.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.*

class GpsService : Service(), LocationListener {

    private val scope = CoroutineScope(Job() + Dispatchers.IO)
    private lateinit var locationManager: LocationManager
    private var wakeLock: PowerManager.WakeLock? = null

    private var speedLimit = 90
    var currentSpeed = 0f

    private var prevLat = 0.0
    private var prevLng = 0.0
    private var prevTime = 0L
    private var hasPrev = false

    private var isSirenPlaying = false
    private var sirenTrack: AudioTrack? = null

    // Kalman filter
    private var kalmanX = 0f
    private var kalmanP = 0.5f
    private val KALMAN_Q = 0.5f
    private val KALMAN_R = 2.0f
    private val DRIFT_THRESHOLD = 2.5f

    // Exceed timer
    private var exceedCount = 0

    companion object {
        private const val CHANNEL_ID = "speedlimit_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "START"
        private const val ACTION_STOP = "STOP"
        private const val EXTRA_LIMIT = "limit"

        @Volatile var lastDisplaySpeed = 0f
            private set

        fun start(context: Context, limit: Int) {
            val intent = Intent(context, GpsService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_LIMIT, limit)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GpsService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                speedLimit = intent.getIntExtra(EXTRA_LIMIT, 90)
                startForeground(NOTIFICATION_ID, buildNotification())
                acquireWakeLock()
                startLocationUpdates()
                scope.launch {
                    while (isActive) {
                        delay(1000)
                        tickExceed()
                        updateNotification()
                    }
                }
            }
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopLocationUpdates()
        releaseWakeLock()
        stopSiren()
        scope.cancel()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SpeedLimit:GPS")
        wakeLock?.acquire(10 * 60 * 60 * 1000L) // 10 hours
    }

    private fun releaseWakeLock() {
        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    // ─── GPS via LocationManager (direct GPS_PROVIDER) ─────────────────────
    private fun startLocationUpdates() {
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000,  // minTime (ms)
                0f,    // minDistance (m)
                this   // LocationListener
            )
        } catch (_: SecurityException) {}
    }

    private fun stopLocationUpdates() {
        locationManager.removeUpdates(this)
    }

    override fun onLocationChanged(location: Location) {
        val lat = location.latitude
        val lng = location.longitude
        val time = System.currentTimeMillis()
        val gpsSpeedMs = location.speed

        var rawSpeed = 0f

        // Method 1: Use hardware speed if available
        if (gpsSpeedMs >= 0f && gpsSpeedMs < 83.33f) { // < 300 km/h
            rawSpeed = gpsSpeedMs * 3.6f
        }

        // Method 2: Fallback to Haversine if hardware speed is 0
        if (rawSpeed < 2f && hasPrev) {
            val dt = (time - prevTime) / 1000.0
            if (dt in 0.5..10.0) {
                val dist = haversine(prevLat, prevLng, lat, lng)
                if (dist > 0.5) { // ignore micro-movements
                    rawSpeed = ((dist / dt) * 3.6).toFloat()
                }
            }
        }

        // Kalman filter
        if (rawSpeed > 0f) {
            kalmanP += KALMAN_Q
            val k = kalmanP / (kalmanP + KALMAN_R)
            kalmanX += k * (rawSpeed - kalmanX)
            kalmanP *= (1f - k)
        } else if (hasPrev) {
            // No movement detected, decay Kalman to 0
            kalmanX *= 0.9f
        }

        currentSpeed = if (kalmanX < DRIFT_THRESHOLD) 0f else kalmanX
        lastDisplaySpeed = currentSpeed

        prevLat = lat
        prevLng = lng
        prevTime = time
        hasPrev = true
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    // Haversine
    private fun haversine(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2.0).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2.0).pow(2)
        return R * 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
    }

    // ─── Exceed timer ──────────────────────────────────────────────────────
    private fun tickExceed() {
        if (currentSpeed > speedLimit) {
            exceedCount++
            if (exceedCount >= 45 && !isSirenPlaying) {
                startSiren()
            }
        } else {
            exceedCount = 0
            if (isSirenPlaying) stopSiren()
        }
    }

    // ─── Siren via AudioTrack ──────────────────────────────────────────────
    private fun startSiren() {
        if (isSirenPlaying) return
        isSirenPlaying = true

        val sampleRate = 22050
        val bufferSize = sampleRate / 2

        sirenTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(bufferSize * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        scope.launch {
            try {
                sirenTrack?.play()
                val samples = ShortArray(bufferSize)
                var phase = 0.0
                while (isActive && isSirenPlaying) {
                    for (i in samples.indices) {
                        val t = i.toDouble() / sampleRate
                        val freq = 500.0 + 500.0 * sin(2.0 * PI * t / 0.5)
                        val value = sin(2.0 * PI * freq * t + phase) * 0.4
                        samples[i] = (value * Short.MAX_VALUE).toInt().toShort()
                    }
                    phase += 2.0 * PI * 500.0 * (bufferSize.toDouble() / sampleRate)
                    sirenTrack?.write(samples, 0, samples.size)
                }
            } catch (_: Exception) {}
        }
    }

    private fun stopSiren() {
        isSirenPlaying = false
        try {
            sirenTrack?.stop()
            sirenTrack?.release()
        } catch (_: Exception) {}
        sirenTrack = null
    }

    // ─── Notification ──────────────────────────────────────────────────────
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Speed Limit", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Speed limit monitor"
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Speed Limit")
            .setContentText("$speedLimit км/год")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        val text = if (isSirenPlaying) "⚠ $speedLimit км/год"
                   else "$speedLimit км/год · ${Math.round(currentSpeed)} км/год"
        nm.notify(NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Speed Limit")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setOngoing(true)
                .setSilent(true)
                .build()
        )
    }
}