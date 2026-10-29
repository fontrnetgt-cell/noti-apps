package com.example.notic

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper

// Invisible screen shown for ~1 second. Android only lets an app open the camera
// when the app is visible, so the service launches this when Noti asks for the stream.
class WakeActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        startForegroundService(Intent(this, CameraService::class.java).putExtra("camera", true))
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 1500)
    }
}
