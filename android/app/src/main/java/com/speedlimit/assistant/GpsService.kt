package com.speedlimit.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class GpsService : Service() {

    private val scope = CoroutineScope(Job() + Dispatchers.IO)
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { updateSpeed(it) }
        }
    }

    // Speed state
    private var speedLimit = 90
    private var currentSpeed = 0f
    private var exceedSeconds = 0f
    private var isSirenPlaying = false
    private var sirenTrack: AudioTrack? = null

    // Kalman filter (responsive)
    private var kalmanX = 0f
    private var kalmanP = 0.5f
    private val KALMAN_Q = 0.5f   // responsive to acceleration
    private val KALMAN_R = 2.0f   // trust GPS more
    private val DRIFT_THRESHOLD = 2.5f

    // Exceed timer
    private val EXCEED_THRESHOLD = 45  // seconds
    private var exceedCount = 0

    companion object {
        private const val CHANNEL_ID = "speedlimit_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "START"
        private const val ACTION_STOP = "STOP"
        private const val EXTRA_LIMIT = "limit"

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
        fusedLocationClient = FusedLocationProviderClient(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                speedLimit = intent.getIntExtra(EXTRA_LIMIT, 90)
                startForeground(NOTIFICATION_ID, buildNotification())
                startLocationUpdates()
                // Start exceed monitor
                scope.launch {
                    while (isActive) {
                        delay(1000) // every second
                        tickExceed()
                        updateNotification(isSirenPlaying)
                    }
                }
            }
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        stopLocationUpdates()
        stopSiren()
        super.onDestroy()
    }

    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setMinUpdateIntervalMillis(500)
            .build()
        try {
            fusedLocationClient.requestLocationUpdates(
                request, locationCallback, Looper.getMainLooper()
            )
        } catch (_: SecurityException) {}
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    // Kalman filter speed update
    private fun updateSpeed(location: Location) {
        val gpsSpeed = location.speed * 3.6f  // m/s → km/h

        // Kalman update
        kalmanP += KALMAN_Q
        val k = kalmanP / (kalmanP + KALMAN_R)
        kalmanX += k * (gpsSpeed - kalmanX)
        kalmanP *= (1f - k)

        currentSpeed = if (kalmanX < DRIFT_THRESHOLD) 0f else kalmanX
    }

    // Exceed timer tick
    private fun tickExceed() {
        if (currentSpeed > speedLimit) {
            exceedCount++
            if (exceedCount >= EXCEED_THRESHOLD && !isSirenPlaying) {
                startSiren()
            }
        } else {
            exceedCount = 0
            if (isSirenPlaying) stopSiren()
        }
    }

    // Siren via AudioTrack (programmatic sine wave sweep)
    private fun startSiren() {
        if (isSirenPlaying) return
        isSirenPlaying = true

        val sampleRate = 22050
        val bufferSize = sampleRate / 2  // 0.5 second buffer

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
            .setTransferMode(AudioTrack.MODE_CIRCULAR)
            .build()

        scope.launch {
            try {
                sirenTrack?.play()
                val samples = ShortArray(bufferSize)
                var phase = 0.0

                while (isActive && isSirenPlaying) {
                    // Sweep 500→1000→500 Hz over buffer
                    for (i in samples.indices) {
                        val t = i.toDouble() / sampleRate
                        val freq = 500.0 + 500.0 * Math.sin(2.0 * Math.PI * t / 0.5)  // sweep period 0.5s
                        val value = Math.sin(2.0 * Math.PI * freq * t + phase) * 0.4
                        samples[i] = (value * Short.MAX_VALUE).toInt().toShort()
                    }
                    phase += 2.0 * Math.PI * 500.0 * (bufferSize.toDouble() / sampleRate)
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

    // Notification
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Speed Limit", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Speed limit monitor is active"
                setSound(null, null)  // silent channel (siren is separate)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
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

    private fun updateNotification(sirenPlaying: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        val text = if (sirenPlaying) "⚠ ПЕРЕВИЩЕННЯ!" else "$speedLimit км/год"
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