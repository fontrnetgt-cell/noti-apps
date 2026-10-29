package com.example.notic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Starts Notic by itself after the phone restarts (after the first unlock) or the app is updated.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        c.startForegroundService(Intent(c, CameraService::class.java))
    }
}
