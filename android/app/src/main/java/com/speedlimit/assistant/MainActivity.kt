package com.speedlimit.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private var currentLimit = 90
    private val limitButtons = mutableListOf<Button>()
    private lateinit var speedValueText: TextView
    private lateinit var limitValueText: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            val speed = GpsService.lastDisplaySpeed.roundToInt()
            speedValueText.text = if (speed < 0) "--" else "$speed"
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        speedValueText = findViewById(R.id.speedValue)
        limitValueText = findViewById(R.id.limitValue)

        for (i in 0..4) {
            val btnId = resources.getIdentifier("btn_${88 + i}", "id", packageName)
            val btn = findViewById<Button>(btnId)
            limitButtons.add(btn)
            btn.setOnClickListener {
                currentLimit = 88 + i
                limitValueText.text = "$currentLimit км/год"
                if (hasPermissions()) {
                    GpsService.stop(this)
                    GpsService.start(this, currentLimit)
                }
                updateButtons()
            }
        }

        updateButtons()
        checkPermissionsAndStart()
        handler.post(updateRunnable)
    }

    override fun onDestroy() {
        handler.removeCallbacks(updateRunnable)
        GpsService.stop(this)
        super.onDestroy()
    }

    private fun updateButtons() {
        limitButtons.forEach { btn ->
            val value = btn.text.toString().toIntOrNull() ?: return@forEach
            btn.isSelected = value == currentLimit
        }
    }

    private fun checkPermissionsAndStart() {
        if (hasPermissions()) {
            GpsService.start(this, currentLimit)
        } else {
            requestPermissions()
        }
    }

    private fun hasPermissions(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val notif = if (Build.VERSION.SDK_INT >= 33)
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
        else PackageManager.PERMISSION_GRANTED
        return fine == PackageManager.PERMISSION_GRANTED && notif == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 29) perms.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 100)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            GpsService.start(this, currentLimit)
        }
    }
}